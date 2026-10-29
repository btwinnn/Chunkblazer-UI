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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Where to get each Obtain and Equip task's item, read from task_sources.json (built from
 * the obtain/equip sources sheet): the source NPC, object or activity, drop rate or cost,
 * the unique part for made items, a short where/how note, and the real level needed.
 *
 * UI only: it adds lines under an expanded task in the task window, outlines the source
 * by name, and adds its level to the "(Lvl ...)" note. Task data, completion and the
 * server never read it.
 */
@Slf4j
public final class TaskSources
{
	private static final String RESOURCE = "task_sources.json";
	private static final Pattern LEVEL = Pattern.compile("(\\d+)\\s+([A-Za-z]+)");

	/** One task's entry. Every field is optional. */
	public static final class Source
	{
		public String type;
		public String source;
		public String part;
		public String how;
		public String rate;
		public String cost;
		public String level;
		/**
		 * In-game names to outline instead of the source: the shopkeeper for a shop, each
		 * revenant for "Revenants", Pyramid Plunder's urns.
		 */
		public List<String> outline;
		/** NPC ids to outline as well, for NPCs whose name is shared or changes (e.g. Floria, Dusuri). */
		public List<Integer> npcIds;
	}

	private static volatile Map<String, Source> sources = Collections.emptyMap();
	private static volatile Map<String, Map<Skill, Integer>> levels = Collections.emptyMap();

	private TaskSources()
	{
	}

	/** Reads task_sources.json. Missing or broken data just means no source info. */
	public static void load(Gson gson)
	{
		Map<String, Source> loaded = null;
		try (InputStream in = TaskSources.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				Type type = new TypeToken<Map<String, Source>>()
				{
				}.getType();
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
				{
					loaded = gson.fromJson(reader, type);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", RESOURCE, e);
		}
		sources = loaded == null ? Collections.emptyMap() : loaded;

		Map<String, Map<Skill, Integer>> parsed = new java.util.HashMap<>();
		for (Map.Entry<String, Source> entry : sources.entrySet())
		{
			Map<Skill, Integer> needs = parseLevels(entry.getValue().level);
			if (!needs.isEmpty())
			{
				parsed.put(entry.getKey(), needs);
			}
		}
		levels = parsed;
	}

	public static Source get(NuzlockeTask task)
	{
		return task == null || task.getTaskId() == null ? null : sources.get(task.getTaskId());
	}

	// Whole numbers of four or more digits, not already grouped.
	private static final Pattern BIG_NUMBER = Pattern.compile("(?<![\\d,.])(\\d{4,})(?![\\d,]|\\.\\d)");

	/**
	 * Numbers written the game's way: "1/10000" as "1/10,000", "200625 coins" as "200,625 coins".
	 * Years ("May 2024") are left alone.
	 */
	public static String tidy(String text)
	{
		if (text == null)
		{
			return null;
		}
		Matcher m = BIG_NUMBER.matcher(text);
		StringBuffer out = new StringBuffer();
		while (m.find())
		{
			String digits = m.group(1);
			boolean year = digits.length() == 4 && digits.compareTo("1990") >= 0 && digits.compareTo("2099") <= 0
				&& (m.start() == 0 || text.charAt(m.start() - 1) != '/');
			m.appendReplacement(out, year ? digits : String.format("%,d", Long.parseLong(digits)));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** The levels the sheet says a task needs, like {Hunter=91}; empty if none. */
	public static Map<Skill, Integer> levels(NuzlockeTask task)
	{
		return task == null || task.getTaskId() == null ? Collections.emptyMap()
			: levels.getOrDefault(task.getTaskId(), Collections.emptyMap());
	}

	/** "75 Defence, 70 Magic" to {Defence=75, Magic=70}. Words that aren't skills ("combat") are skipped. */
	public static Map<Skill, Integer> parseLevels(String text)
	{
		Map<Skill, Integer> needs = new EnumMap<>(Skill.class);
		if (text == null)
		{
			return needs;
		}
		Matcher m = LEVEL.matcher(text);
		while (m.find())
		{
			Skill skill = TaskTargetExtras.categorySkill(m.group(2));
			if (skill != null)
			{
				needs.merge(skill, Integer.parseInt(m.group(1)), Math::max);
			}
		}
		return needs;
	}

	/**
	 * The lines shown under an expanded task: "Source: Imp (drop)",
	 * "Part: ...", the where/how note, then "Drop rate: 1/16" and "Cost: ...". Empty if the task has no entry.
	 */
	public static List<String> lines(NuzlockeTask task)
	{
		return lines(task, null);
	}

	/**
	 * The same, leaving out the Source line when it says nothing new: it's the chunk's own
	 * name ({@code chunk}, shown on the task already) or the where/how note names it.
	 */
	public static List<String> lines(NuzlockeTask task, String chunk)
	{
		List<String> lines = new ArrayList<>();
		Source s = get(task);
		if (s == null)
		{
			return lines;
		}
		if (s.source != null && !s.source.equalsIgnoreCase(chunk)
			&& (s.how == null || !s.how.toLowerCase().contains(s.source.toLowerCase())))
		{
			lines.add("Source: " + s.source);
		}
		if (s.part != null)
		{
			lines.add("Part: " + s.part);
		}
		if (s.how != null)
		{
			lines.add(s.how);
		}
		// The drop rate and cost always last, so they sit at the bottom of every task.
		if (s.rate != null)
		{
			lines.add("Drop rate: " + s.rate);
		}
		if (s.cost != null)
		{
			lines.add("Cost: " + s.cost);
		}
		return lines;
	}

	/** NPC ids to outline for a task's source (empty if none). */
	public static List<Integer> npcIds(NuzlockeTask task)
	{
		Source s = get(task);
		return s == null || s.npcIds == null ? Collections.emptyList() : s.npcIds;
	}

	/** The in-game names that show where a task's item comes from: the listed outline names, or its source. */
	public static List<String> outlineNames(NuzlockeTask task)
	{
		Source s = get(task);
		if (s == null)
		{
			return Collections.emptyList();
		}
		return s.outline != null && !s.outline.isEmpty() ? s.outline
			: s.source != null ? Collections.singletonList(s.source) : Collections.emptyList();
	}
}
