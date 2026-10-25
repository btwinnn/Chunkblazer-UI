/*
 * Copyright (c) 2026, Vani-Lab
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazerui.pathing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/**
 * Ways to get somewhere other than walking: stairs, ladders, doors, agility shortcuts,
 * boats, charter ships, gliders, minecarts, quetzals, spirit trees, fairy rings and the
 * like. Teleports aren't included. Each one has its requirements (levels, finished
 * quests, varbits, items), checked against the player with {@link #usable}.
 *
 * <p>The transport tables and item groups are from the Shortest Path plugin
 * (https://github.com/Skretzo/shortest-path, BSD 2-Clause).
 */
@Slf4j
public final class Transports
{
	private static final String[] FILES = {
		"transports", "agility_shortcuts", "boats", "canoes", "charter_ships", "gnome_gliders",
		"hot_air_balloons", "magic_carpets", "magic_mushtrees", "minecarts", "quetzals", "ships",
		"spirit_trees", "fairy_rings"};

	/** One way across: from one tile to another, taking {@code cost} ticks, if every requirement is met. */
	public static final class Link
	{
		public final int from;
		public final int to;
		public final int cost;
		final List<Requirement> requirements;
		/** Where it goes ("Port Sarim"), or "" if the table doesn't say. */
		public final String place;
		/** What to click ("Charter Trader Crewmember"), and what it costs ("2,500 coins") if anything. */
		public final String how;
		/** {@link #UP}, {@link #DOWN}, {@link #DOOR}, {@link #TRAVEL} or {@link #OTHER}. */
		public final int kind;

		Link(int from, int to, int cost, List<Requirement> requirements, String place, String how)
		{
			this.from = from;
			this.to = to;
			this.cost = cost;
			this.requirements = requirements;
			this.place = place;
			this.how = how;
			this.kind = kind(how, from, to, place);
		}
	}

	/** Up stairs or a ladder. */
	public static final int UP = 1;
	/** Down stairs, a ladder or a hole. */
	public static final int DOWN = 2;
	/** Through a door, gate, gangplank or cave mouth. */
	public static final int DOOR = 3;
	/** Somewhere far: a charter, glider, spirit tree. */
	public static final int TRAVEL = 4;
	/** Anything else (an agility shortcut). */
	public static final int OTHER = 0;

	private static final String[] THROUGH = {"open", "enter", "exit", "leave", "go-through", "pass", "walk-through",
		"squeeze-through", "crawl-through", "exit-through", "quick-pass"};
	private static final String[] DOORS = {"door", "gate", "gangplank", "doorway", "entrance", "curtain", "archway"};

	/** What kind of step a transport is, from what you click and where it goes. */
	private static int kind(String how, int from, int to, String place)
	{
		String text = how.toLowerCase();
		String option = text.split(" ", 2)[0];
		for (String word : DOORS)
		{
			if (text.contains(word))
			{
				return DOOR;
			}
		}
		for (String word : THROUGH)
		{
			if (option.equals(word))
			{
				return DOOR;
			}
		}
		if (PathTile.z(to) != PathTile.z(from))
		{
			return PathTile.z(to) > PathTile.z(from) ? UP : DOWN;
		}
		if (option.startsWith("climb-up") || option.startsWith("walk-up") || option.equals("ascend"))
		{
			return UP;
		}
		if (option.startsWith("climb-down") || option.startsWith("walk-down") || option.equals("descend")
			|| option.startsWith("jump-down"))
		{
			return DOWN;
		}
		return place.isEmpty() ? OTHER : TRAVEL;
	}

	/** What one row of a table asks for. Rows with the same text share one. */
	static final class Requirement
	{
		String skills;
		String quests;
		String items;
		String varbits;
		String varps;
		boolean fairyRing;
	}

	private final List<Link> links = new ArrayList<>();
	private final Map<String, int[]> itemGroups = new HashMap<>();

	private Transports()
	{
	}

	static Transports load() throws IOException
	{
		Transports transports = new Transports();
		transports.loadItemGroups();
		for (String file : FILES)
		{
			transports.loadFile(file);
		}
		return transports;
	}

	private static BufferedReader open(String name) throws IOException
	{
		InputStream in = Transports.class.getResourceAsStream(name);
		if (in == null)
		{
			throw new IOException(name + " not found");
		}
		return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
	}

	private void loadItemGroups() throws IOException
	{
		try (BufferedReader reader = open("item-variations.tsv"))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.startsWith("#") || line.trim().isEmpty())
				{
					continue;
				}
				String[] cells = line.split("\t", -1);
				if (cells.length < 2 || cells[1].isEmpty())
				{
					continue;
				}
				itemGroups.put(cells[0].trim().toUpperCase(),
					Arrays.stream(cells[1].split(",")).map(String::trim).filter(s -> !s.isEmpty())
						.mapToInt(Integer::parseInt).toArray());
			}
		}
	}

	private void loadFile(String file) throws IOException
	{
		boolean fairy = file.equals("fairy_rings");
		// Rows with no destination are one stop on a network (fairy rings, spirit trees):
		// each goes to every other stop in the same table.
		List<Integer> network = new ArrayList<>();
		List<Requirement> networkNeeds = new ArrayList<>();
		List<String> networkPlaces = new ArrayList<>();
		List<String> networkHows = new ArrayList<>();
		// Tables head each group of rows with its place ("# Tree Gnome Village").
		String section = "";
		Map<String, Requirement> shared = new HashMap<>();
		try (BufferedReader reader = open("transports/" + file + ".tsv"))
		{
			String header = reader.readLine();
			if (header == null)
			{
				return;
			}
			String[] columns = header.replaceFirst("^#\\s*", "").split("\t", -1);
			Map<String, Integer> col = new HashMap<>();
			for (int i = 0; i < columns.length; i++)
			{
				col.put(columns[i].trim(), i);
			}
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.startsWith("#"))
				{
					section = line.replaceFirst("^#\\s*", "").trim();
					continue;
				}
				if (line.trim().isEmpty())
				{
					continue;
				}
				String[] cells = line.split("\t", -1);
				int from = point(cell(cells, col, "Origin"));
				if (from < 0)
				{
					continue;
				}
				Requirement need = new Requirement();
				need.skills = cell(cells, col, "Skills");
				need.quests = cell(cells, col, "Quests");
				need.items = cell(cells, col, "Items");
				need.varbits = cell(cells, col, "Varbits");
				need.varps = cell(cells, col, "VarPlayers");
				need.fairyRing = fairy;
				String key = need.skills + "\t" + need.quests + "\t" + need.items + "\t" + need.varbits + "\t" + need.varps;
				Requirement existing = shared.putIfAbsent(key, need);
				if (existing != null)
				{
					need = existing;
				}
				int cost = 1;
				try
				{
					String duration = cell(cells, col, "Duration");
					cost = duration.isEmpty() ? 1 : Math.max(1, Integer.parseInt(duration));
				}
				catch (NumberFormatException e)
				{
				}
				int to = point(cell(cells, col, "Destination"));
				String how = how(cell(cells, col, "menuOption menuTarget objectID"), need.items);
				if (to < 0)
				{
					network.add(from);
					networkNeeds.add(need);
					networkPlaces.add(section);
					networkHows.add(how);
					continue;
				}
				links.add(new Link(from, to, cost, Collections.singletonList(need), cell(cells, col, "Display info"), how));
			}
		}
		for (int i = 0; i < network.size(); i++)
		{
			for (int j = 0; j < network.size(); j++)
			{
				if (i != j && !network.get(i).equals(network.get(j)))
				{
					links.add(new Link(network.get(i), network.get(j), 5,
						Arrays.asList(networkNeeds.get(i), networkNeeds.get(j)), networkPlaces.get(j), networkHows.get(i)));
				}
			}
		}
	}

	private static final java.util.regex.Pattern COINS = java.util.regex.Pattern.compile("(?:^|[&|])\\s*COINS\\s*=\\s*(\\d+)");

	/** "Charter Trader Crewmember 1330" and "COINS=2500" to "Charter Trader Crewmember, 2,500 coins". */
	private static String how(String menu, String items)
	{
		String text = menu.replaceFirst("\\s+-?\\d+$", "").trim().intern();
		java.util.regex.Matcher coins = COINS.matcher(items);
		if (coins.find())
		{
			text = text + ", " + String.format("%,d", Integer.parseInt(coins.group(1))) + " coins";
		}
		return text;
	}

	private static String cell(String[] cells, Map<String, Integer> col, String name)
	{
		Integer i = col.get(name);
		return i == null || i >= cells.length ? "" : cells[i].trim();
	}

	/** "x y plane" to a packed point, or -1. */
	private static int point(String text)
	{
		String[] parts = text.trim().split("\\s+");
		if (parts.length != 3)
		{
			return -1;
		}
		try
		{
			return PathTile.pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	public List<Link> all()
	{
		return links;
	}

	/**
	 * The links this player can use right now, by the tile they start from. Client thread:
	 * quest states run a client script. Coins are assumed (anyone can bring some); other
	 * items have to be in the inventory or worn.
	 */
	public Map<Integer, List<Link>> usable(Client client)
	{
		Map<Requirement, Boolean> checked = new IdentityHashMap<>();
		Map<String, Boolean> quests = new HashMap<>();
		Map<Integer, Integer> carried = carried(client);
		Map<Integer, List<Link>> out = new HashMap<>();
		for (Link link : links)
		{
			boolean ok = true;
			for (Requirement need : link.requirements)
			{
				Boolean met = checked.get(need);
				if (met == null)
				{
					met = meets(client, need, quests, carried);
					checked.put(need, met);
				}
				ok &= met;
			}
			if (ok)
			{
				out.computeIfAbsent(link.from, k -> new ArrayList<>()).add(link);
			}
		}
		return out;
	}

	private boolean meets(Client client, Requirement need, Map<String, Boolean> quests, Map<Integer, Integer> carried)
	{
		try
		{
			if (need.fairyRing && client.getVarbitValue(VarbitID.FAIRY2_QUEENCURE_QUEST) <= 39)
			{
				return false;
			}
			return skillsMet(client, need.skills) && questsMet(client, need.quests, quests)
				&& varsMet(client, need.varbits, true) && varsMet(client, need.varps, false)
				&& itemsMet(need.items, carried);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private static boolean skillsMet(Client client, String text)
	{
		if (text.isEmpty())
		{
			return true;
		}
		for (String part : text.split(";"))
		{
			String[] bits = part.trim().split("\\s+", 2);
			if (bits.length != 2)
			{
				continue;
			}
			int level = Integer.parseInt(bits[0]);
			String name = bits[1].trim();
			int have;
			if (name.equalsIgnoreCase("Combat"))
			{
				have = client.getLocalPlayer() == null ? 0 : client.getLocalPlayer().getCombatLevel();
			}
			else if (name.equalsIgnoreCase("Total"))
			{
				have = client.getTotalLevel();
			}
			else
			{
				Skill skill = skill(name);
				if (skill == null)
				{
					return false;
				}
				have = client.getRealSkillLevel(skill);
			}
			if (have < level)
			{
				return false;
			}
		}
		return true;
	}

	private static Skill skill(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return skill;
			}
		}
		return null;
	}

	private static boolean questsMet(Client client, String text, Map<String, Boolean> cache)
	{
		if (text.isEmpty())
		{
			return true;
		}
		for (String name : text.split(";"))
		{
			String quest = name.trim();
			if (quest.isEmpty())
			{
				continue;
			}
			Boolean done = cache.get(quest);
			if (done == null)
			{
				done = false;
				for (Quest q : Quest.values())
				{
					if (q.getName().equalsIgnoreCase(quest))
					{
						done = q.getState(client) == QuestState.FINISHED;
						break;
					}
				}
				cache.put(quest, done);
			}
			if (!done)
			{
				return false;
			}
		}
		return true;
	}

	/** "id=value", "id>value", "id<value" or "id&bits" (all set), separated by ";". */
	private static boolean varsMet(Client client, String text, boolean varbit)
	{
		if (text.isEmpty())
		{
			return true;
		}
		for (String part : text.split(";"))
		{
			String term = part.trim();
			int at = indexOfOperator(term);
			if (at <= 0)
			{
				continue;
			}
			char op = term.charAt(at);
			if (op == '@')
			{
				continue; // a cooldown: can't tell, so let it through
			}
			int id = Integer.parseInt(term.substring(0, at));
			int want = Integer.parseInt(term.substring(at + 1));
			int have = varbit ? client.getVarbitValue(id) : client.getVarpValue(id);
			boolean ok = op == '=' ? have == want : op == '>' ? have > want : op == '<' ? have < want
				: (have & want) == want;
			if (!ok)
			{
				return false;
			}
		}
		return true;
	}

	private static int indexOfOperator(String term)
	{
		for (int i = 0; i < term.length(); i++)
		{
			if ("=<>&@".indexOf(term.charAt(i)) >= 0)
			{
				return i;
			}
		}
		return -1;
	}

	/** "A=1&B=1|C=1": every &-part needs one of its |-options. */
	private boolean itemsMet(String text, Map<Integer, Integer> carried)
	{
		if (text.isEmpty())
		{
			return true;
		}
		for (String all : text.replace(" ", "").toUpperCase().split("&"))
		{
			if (all.isEmpty())
			{
				continue;
			}
			boolean any = false;
			for (String option : all.split("\\|"))
			{
				String[] nameAndCount = option.split("=");
				if (nameAndCount.length != 2)
				{
					continue;
				}
				String name = nameAndCount[0];
				int count = Math.max(1, Integer.parseInt(nameAndCount[1]));
				if (name.equals("COINS"))
				{
					any = true;
				}
				else if (name.startsWith("UNLOCK_"))
				{
					continue;
				}
				else if (name.chars().allMatch(Character::isDigit))
				{
					any = carried.getOrDefault(Integer.parseInt(name), 0) >= count;
				}
				else
				{
					int[] ids = itemGroups.get(name);
					int have = 0;
					if (ids != null)
					{
						for (int id : ids)
						{
							have += carried.getOrDefault(id, 0);
						}
					}
					any = have >= count;
				}
				if (any)
				{
					break;
				}
			}
			if (!any)
			{
				return false;
			}
		}
		return true;
	}

	private static Map<Integer, Integer> carried(Client client)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer container = client.getItemContainer(id);
			if (container == null)
			{
				continue;
			}
			for (Item item : container.getItems())
			{
				if (item.getId() > 0)
				{
					out.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return out;
	}
}
