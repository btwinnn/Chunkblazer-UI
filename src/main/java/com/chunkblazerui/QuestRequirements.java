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
package com.chunkblazerui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;

/**
 * What each quest needs before you can do it in a chunk account: the chunks its steps
 * happen in, skill levels, quests to finish first, quest points and combat level. UI
 * only: it reads quest_requirements.json and the player's own state, and is used by the
 * task window's "Quests (ready now)" filter and the requirement list under a quest.
 *
 * Data: chunks and requirements are derived from the Quest Helper plugin's step
 * coordinates and requirements (https://github.com/Zoinkwiz/quest-helper, BSD 2-Clause,
 * Copyright (c) 2020, Zoinkwiz). Into the Tombs and The Frozen Door, which Quest Helper
 * doesn't cover, were filled in by hand. Optional "maybe" chunks are supported by the
 * format but not currently used.
 */
@Slf4j
@Singleton
public class QuestRequirements
{
	private static final String RESOURCE = "quest_requirements.json";
	public static final Color MET = new Color(110, 210, 110);
	public static final Color NOT_MET = new Color(255, 90, 90);
	// A locked chunk next to one you own (or a charter/free chunk): it can be unlocked now.
	public static final Color NEXT_TO_YOURS = new Color(255, 200, 60);
	static final Color COST = new Color(255, 170, 60);
	static final Color UNSURE = new Color(150, 145, 135);
	public static final Color TEXT = new Color(200, 195, 180);

	/** One quest's needs, as stored in quest_requirements.json. */
	static final class Need
	{
		List<Integer> chunks;
		List<Integer> maybe;
		Map<String, Integer> skills;
		List<String> quests;
		Integer questPoints;
		Integer combat;
	}

	/** Space above each section header, in pixels. */
	public static final int SECTION_GAP = 5;
	/** How far items sit in from their section header, in pixels. */
	static final int ITEM_INDENT = 14;

	/**
	 * One line under an expanded task: plain text, a section header (click to open or
	 * close it), or an item inside a section. A chunk item carries its region (click to
	 * see it on the map); a quest item carries the quest's name (click to jump to it).
	 */
	public static final class Line
	{
		public final String text;
		public final Color color;
		public final int region;
		public final String quest;
		public final String toggle;
		public final boolean header;
		public final boolean open;
		public final int indent;
		public final int gap;
		/** An OSRS Wiki page this line opens when clicked (an Obtain or Equip task's item), or null. */
		public final String wiki;

		public Line(String text, Color color)
		{
			this(text, color, -1, null, null, false, false, 0, 0, null);
		}

		private Line(String text, Color color, int region, String quest, String toggle, boolean header, boolean open,
			int indent, int gap, String wiki)
		{
			this.wiki = wiki;
			this.text = text;
			this.color = color;
			this.region = region;
			this.quest = quest;
			this.toggle = toggle;
			this.header = header;
			this.open = open;
			this.indent = indent;
			this.gap = gap;
		}

		public static Line header(String text, Color color, String toggle, boolean open)
		{
			return new Line(text, color, -1, null, toggle, true, open, 0, SECTION_GAP, null);
		}

		public static Line item(String text, Color color, int region, String quest)
		{
			return new Line(text, color, region, quest, null, false, false, ITEM_INDENT, 0, null);
		}

		/** Plain text with space above it, like the cost line. */
		public static Line spaced(String text, Color color)
		{
			return new Line(text, color, -1, null, null, false, false, 0, SECTION_GAP, null);
		}

		/** The same line with other text, for wrapping. Only the first part keeps the space above. */
		public Line withText(String newText, boolean first)
		{
			return new Line(newText, color, region, quest, toggle, header, open, indent, first ? gap : 0, wiki);
		}

		/** A link to an item's OSRS Wiki page. */
		public static Line wikiLink(String text, Color color, String url, int gap)
		{
			return new Line(text, color, -1, null, null, false, false, 0, gap, url);
		}

		public boolean clickable()
		{
			return region > 0 || quest != null || toggle != null || wiki != null;
		}
	}

	private final Client client;
	private final ChunkBlazerCore plugin;
	private final Gson gson;

	private volatile Map<String, Need> needs = Collections.emptyMap();
	// Prerequisite quest states, refreshed a few per tick (getState runs a client script).
	private final Map<Quest, QuestState> states = new ConcurrentHashMap<>();
	private List<Quest> watched = Collections.emptyList();
	private int nextToCheck;
	// Sections the player opened or closed ("taskId:chunks" -> open). Unset ones are closed.
	private final Map<String, Boolean> sectionOpen = new ConcurrentHashMap<>();
	private final Map<String, Boolean> sectionDefault = new ConcurrentHashMap<>();

	@Inject
	QuestRequirements(Client client, ChunkBlazerCore plugin, Gson gson)
	{
		this.client = client;
		this.plugin = plugin;
		this.gson = gson;
	}

	/** Reads quest_requirements.json. Missing or broken data just means no quest info. */
	public void load()
	{
		try (InputStream in = QuestRequirements.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} not found", RESOURCE);
				return;
			}
			Type type = new TypeToken<Map<String, Need>>()
			{
			}.getType();
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				Map<String, Need> loaded = gson.fromJson(reader, type);
				needs = loaded == null ? Collections.emptyMap() : loaded;
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", RESOURCE, e);
			needs = Collections.emptyMap();
		}

		Set<Quest> prereqs = new LinkedHashSet<>();
		for (Need need : needs.values())
		{
			if (need.quests != null)
			{
				for (String name : need.quests)
				{
					Quest quest = quest(name);
					if (quest != null)
					{
						prereqs.add(quest);
					}
				}
			}
		}
		watched = new ArrayList<>(prereqs);
		nextToCheck = 0;
	}

	/** Forget quest states, e.g. after logging out (the next account may differ). */
	public void reset()
	{
		states.clear();
		nextToCheck = 0;
	}

	/**
	 * Checks the state of up to {@code budget} prerequisite quests, taking turns so the
	 * whole list is covered every few ticks. Client thread, logged in only.
	 */
	public void refreshStates(int budget)
	{
		List<Quest> list = watched;
		for (int i = 0; i < budget && !list.isEmpty(); i++)
		{
			nextToCheck = nextToCheck % list.size();
			Quest quest = list.get(nextToCheck++);
			try
			{
				states.put(quest, quest.getState(client));
			}
			catch (RuntimeException e)
			{
			}
		}
	}

	/** True for quest tasks (they get the requirement list, with or without data). */
	/**
	 * A "Complete <quest>" task. Other tasks can complete on a quest too (Defeat Damis, Obtain
	 * a Black Candle: QUEST_CHECK, but category Combat/Obtain); those are normal tasks, tracked
	 * and listed like any other, not quests.
	 */
	public static boolean isQuestTask(NuzlockeTask task)
	{
		return task != null && "QUEST_CHECK".equalsIgnoreCase(task.getCompletionType())
			&& ("Quest".equalsIgnoreCase(task.getCategory())
			|| (task.getTaskId() != null && task.getTaskId().startsWith("quest_")));
	}

	/** The needs of a quest task, or null if it isn't a quest task we have data for. */
	Need needFor(NuzlockeTask task)
	{
		if (!isQuestTask(task))
		{
			return null;
		}
		TaskConstraints c = task.getConstraints();
		return c == null || c.getQuest() == null ? null : needs.get(c.getQuest());
	}

	/**
	 * True if every chunk, level, prerequisite quest and point total is met. Tasks that
	 * aren't quests always pass; a quest with no data never does, since we can't tell.
	 */
	public boolean isReady(NuzlockeTask task)
	{
		if (!isQuestTask(task))
		{
			return true;
		}
		Need need = needFor(task);
		if (need == null)
		{
			return false;
		}
		if (need.chunks != null)
		{
			for (int region : need.chunks)
			{
				if (!reachable(region))
				{
					return false;
				}
			}
		}
		if (need.skills != null)
		{
			for (Map.Entry<String, Integer> e : need.skills.entrySet())
			{
				Skill skill = skill(e.getKey());
				if (skill != null && client.getRealSkillLevel(skill) < e.getValue())
				{
					return false;
				}
			}
		}
		if (need.quests != null)
		{
			for (String name : need.quests)
			{
				Quest quest = quest(name);
				if (quest != null && states.get(quest) != QuestState.FINISHED)
				{
					return false;
				}
			}
		}
		if (need.questPoints != null && questPoints() < need.questPoints)
		{
			return false;
		}
		return need.combat == null || combatLevel() >= need.combat;
	}

	/**
	 * The locked chunks a quest still needs: its own steps' chunks and those of every
	 * unfinished quest before it. Empty for tasks that aren't quests or have no data.
	 */
	public Set<Integer> lockedChunksFor(NuzlockeTask task)
	{
		Set<Integer> out = new LinkedHashSet<>();
		Need need = needFor(task);
		if (need == null)
		{
			return out;
		}
		if (need.chunks != null)
		{
			for (int region : need.chunks)
			{
				if (!reachable(region))
				{
					out.add(region);
				}
			}
		}
		Map<String, Integer> earlier = new LinkedHashMap<>();
		collectEarlierChunks(need, new HashSet<>(), Collections.emptyMap(), earlier, r -> Integer.toString(r));
		out.addAll(earlier.values());
		return out;
	}

	/**
	 * A free or charter chunk: it unlocks the moment you walk in, for nothing, so a path
	 * can go straight through it even while it's locked.
	 */
	public boolean freeToEnter(int region)
	{
		return plugin.isCharterRegion(region) || plugin.isFreeUnlockableRegion(region);
	}

	/** What unlocking a chunk costs, counted in chunks: free and charter chunks are 0, the rest 1. */
	public int unlockWeight(int region)
	{
		if (plugin.isCharterRegion(region) || plugin.isFreeUnlockableRegion(region))
		{
			return 0;
		}
		return plugin.isBossRegion(region) || plugin.getRegionUnlockCost(region) != 0 ? 1 : 0;
	}

	/** Open one section of a quest's requirement list (e.g. "taskId:chunks"). */
	public void openSection(String key)
	{
		sectionOpen.put(key, true);
	}

	/** Open or close one section of a quest's requirement list. */
	public void toggleSection(String key)
	{
		sectionOpen.put(key, !sectionOpen.getOrDefault(key, sectionDefault.getOrDefault(key, false)));
	}

	private boolean isOpen(String key, boolean openByDefault)
	{
		sectionDefault.put(key, openByDefault);
		return sectionOpen.getOrDefault(key, openByDefault);
	}

	/**
	 * The same, with {@code extra} locked chunks (the ones to walk there, which the UI's
	 * chunk calculator works out) added at the top of the Chunks list, which then opens by itself.
	 */
	public List<Line> lines(NuzlockeTask task, IntFunction<String> chunkName, List<Integer> extra)
	{
		List<Line> lines = new ArrayList<>();
		if (!isQuestTask(task))
		{
			return lines;
		}
		Need need = needFor(task);
		if (need == null)
		{
			lines.add(new Line("No requirement data for this quest yet", UNSURE));
			return lines;
		}
		String id = task.getTaskId();
		lines.add(isReady(task) ? new Line("Ready to start", MET) : new Line("Not ready yet", NOT_MET));
		Set<Integer> toUnlock = new LinkedHashSet<>();

		// Chunks the quest's own steps are in.
		Map<String, Integer> own = chunks(need.chunks, chunkName);
		Map<String, Integer> walk = chunks(extra, chunkName);
		walk.keySet().removeAll(own.keySet());
		if (!own.isEmpty() || !walk.isEmpty())
		{
			List<Line> missing = new ArrayList<>();
			List<Line> have = new ArrayList<>();
			for (Map.Entry<String, Integer> e : own.entrySet())
			{
				boolean ok = reachable(e.getValue());
				(ok ? have : missing).add(Line.item(e.getKey(), chunkColor(e.getValue()), e.getValue(), null));
				if (!ok)
				{
					toUnlock.add(e.getValue());
				}
			}
			// The calculator's extra chunks go first, at the top of the list.
			List<Line> first = new ArrayList<>();
			for (Map.Entry<String, Integer> e : walk.entrySet())
			{
				first.add(Line.item(e.getKey(), chunkColor(e.getValue()), e.getValue(), null));
				toUnlock.add(e.getValue());
			}
			missing.addAll(0, first);
			addSection(lines, id + ":chunks", "Chunks", have.size(), own.size() + walk.size(), missing, have,
				!walk.isEmpty());
		}

		// Locked chunks needed by quests that must be finished first. Closed to start with.
		Map<String, Integer> earlier = new LinkedHashMap<>();
		collectEarlierChunks(need, new HashSet<>(), own, earlier, chunkName);
		if (!earlier.isEmpty())
		{
			toUnlock.addAll(earlier.values());
			String key = id + ":earlier";
			boolean open = isOpen(key, false);
			lines.add(Line.header("Chunks for earlier quests (" + earlier.size() + ")", NOT_MET, key, open));
			if (open)
			{
				for (Map.Entry<String, Integer> e : earlier.entrySet())
				{
					lines.add(Line.item(e.getKey(), chunkColor(e.getValue()), e.getValue(), null));
				}
			}
		}

		if (need.skills != null && !need.skills.isEmpty())
		{
			List<Line> missing = new ArrayList<>();
			List<Line> have = new ArrayList<>();
			for (Map.Entry<String, Integer> e : need.skills.entrySet())
			{
				Skill skill = skill(e.getKey());
				if (skill == null)
				{
					continue;
				}
				int level = client.getRealSkillLevel(skill);
				if (level >= e.getValue())
				{
					have.add(Line.item(e.getValue() + " " + skill.getName(), MET, -1, null));
				}
				else
				{
					missing.add(Line.item(e.getValue() + " " + skill.getName() + " (you: " + level + ")", NOT_MET, -1, null));
				}
			}
			addSection(lines, id + ":skills", "Skills", have.size(), have.size() + missing.size(), missing, have);
		}

		if (need.quests != null && !need.quests.isEmpty())
		{
			List<Line> missing = new ArrayList<>();
			List<Line> have = new ArrayList<>();
			for (String name : need.quests)
			{
				Quest quest = quest(name);
				if (quest == null)
				{
					continue;
				}
				QuestState state = states.get(quest);
				if (state == QuestState.FINISHED)
				{
					have.add(Line.item(quest.getName(), MET, -1, name));
				}
				else
				{
					missing.add(Line.item(quest.getName() + (state == null ? " (checking)" : ""),
						state == null ? UNSURE : NOT_MET, -1, name));
				}
			}
			addSection(lines, id + ":quests", "Quests", have.size(), have.size() + missing.size(), missing, have);
		}

		if (need.questPoints != null)
		{
			int have = questPoints();
			lines.add(Line.spaced("Quest points " + Math.min(have, need.questPoints) + "/" + need.questPoints,
				have >= need.questPoints ? MET : NOT_MET));
		}
		if (need.combat != null)
		{
			int have = combatLevel();
			lines.add(Line.spaced("Combat level " + need.combat + " (you: " + have + ")",
				have >= need.combat ? MET : NOT_MET));
		}

		Line cost = costLine(toUnlock);
		if (cost != null)
		{
			lines.add(cost);
		}
		if (need.chunks != null && !need.chunks.isEmpty())
		{
			lines.add(Line.spaced("Chunks are from Quest Helper: an estimate of the essential ones.", UNSURE));
		}
		return lines;
	}

	/** A section header ("Skills 1/2", green when complete) and, if open, its items. */
	private void addSection(List<Line> lines, String key, String label, int have, int total,
		List<Line> missing, List<Line> met)
	{
		addSection(lines, key, label, have, total, missing, met, false);
	}

	private void addSection(List<Line> lines, String key, String label, int have, int total,
		List<Line> missing, List<Line> met, boolean openByDefault)
	{
		if (total == 0)
		{
			return;
		}
		boolean open = isOpen(key, openByDefault);
		lines.add(Line.header(label + " " + have + "/" + total, have >= total ? MET : NOT_MET, key, open));
		if (open)
		{
			lines.addAll(missing);
			lines.addAll(met);
		}
	}

	/** Green if you have it, yellow if it's next to yours (unlockable now), red if not. */
	public Color chunkColor(int region)
	{
		if (reachable(region))
		{
			return MET;
		}
		return plugin.isCharterRegion(region) || plugin.isFreeUnlockableRegion(region) || neighbours().contains(region)
			? NEXT_TO_YOURS : NOT_MET;
	}

	// The chunks next to yours, worked out at most once a game tick: it walks every unlocked
	// chunk, and the requirement list asks for each of its chunks every frame it's drawn.
	private Set<Integer> neighbours = Collections.emptySet();
	private long neighboursAt;

	private Set<Integer> neighbours()
	{
		long now = System.currentTimeMillis();
		if (now - neighboursAt > 600)
		{
			neighbours = plugin.getNeighborRegionIds();
			neighboursAt = now;
		}
		return neighbours;
	}

	/** Chunk name to its first listed region, one entry per chunk (multi-region chunks share a name). */
	private static Map<String, Integer> chunks(List<Integer> regions, IntFunction<String> chunkName)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		if (regions != null)
		{
			for (int region : regions)
			{
				String name = chunkName.apply(region);
				out.putIfAbsent(name == null || name.isEmpty() ? "Region " + region : name, region);
			}
		}
		return out;
	}

	/** Locked chunks of every unfinished prerequisite quest, recursively, minus the quest's own. */
	private void collectEarlierChunks(Need need, Set<String> visited, Map<String, Integer> own,
		Map<String, Integer> out, IntFunction<String> chunkName)
	{
		if (need.quests == null)
		{
			return;
		}
		for (String name : need.quests)
		{
			Quest quest = quest(name);
			Need prereq = needs.get(name);
			if (quest == null || prereq == null || states.get(quest) == QuestState.FINISHED || !visited.add(name))
			{
				continue;
			}
			for (Map.Entry<String, Integer> e : chunks(prereq.chunks, chunkName).entrySet())
			{
				if (!reachable(e.getValue()) && !own.containsKey(e.getKey()))
				{
					out.putIfAbsent(e.getKey(), e.getValue());
				}
			}
			collectEarlierChunks(prereq, visited, own, out, chunkName);
		}
	}

	/**
	 * "Unlock cost: 14 points for 6 chunks": points for the paid ones at your current
	 * price, plus boss tokens. Free chunks cost nothing, so aren't mentioned; null when
	 * there's nothing to pay.
	 */
	public Line costLine(Set<Integer> regions)
	{
		if (regions.isEmpty())
		{
			return null;
		}
		int paid = 0;
		int boss = 0;
		for (int region : regions)
		{
			if (plugin.isBossRegion(region))
			{
				boss++;
			}
			else if (plugin.getRegionUnlockCost(region) != 0)
			{
				paid++;
			}
		}
		// Each paid unlock raises the price of the next, so add them up from your current rank.
		if (paid == 0 && boss == 0)
		{
			return null;
		}
		int rank = plugin.countPayableUnlockedChunks();
		int points = 0;
		for (int k = 1; k <= paid; k++)
		{
			points += plugin.curveUnlockCost(rank + k);
		}
		StringBuilder text = new StringBuilder("Unlock cost: ").append(points).append(points == 1 ? " pt" : " pts")
			.append(" (").append(paid).append(paid == 1 ? " chunk)" : " chunks)");
		if (boss > 0)
		{
			text.append(", ").append(boss).append(boss == 1 ? " boss token" : " boss tokens");
		}
		return Line.spaced(text.toString(), COST);
	}

	/**
	 * Unlocked, or not a lockable chunk at all (dungeons, and the odd region that isn't
	 * part of the chunk grid), so it never holds a quest back.
	 */
	public boolean reachable(int region)
	{
		return !plugin.isKnownRegion(region) || plugin.isRegionUnlocked(region);
	}

	private int questPoints()
	{
		return client.getVarpValue(VarPlayerID.QP);
	}

	private int combatLevel()
	{
		Player player = client.getLocalPlayer();
		return player == null ? 0 : player.getCombatLevel();
	}

	private static Quest quest(String name)
	{
		try
		{
			return Quest.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static Skill skill(String name)
	{
		try
		{
			return Skill.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
