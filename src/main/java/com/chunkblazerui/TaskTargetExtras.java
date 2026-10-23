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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Skill;

/**
 * Extra knowledge of where tasks happen, for the UI only: outlines, the right-click
 * Tasks menu and auto-tracking. The task data and completion tracking never read
 * this, so nothing here changes how a task is completed.
 *
 * Three kinds of extra:
 *  - Name rules, for tasks whose target can be told from the task's own wording:
 *    farming (crop to patch), hunter creatures, stalls, pickpocketing, STASH units,
 *    Imp Catcher beads (imps), and the few kill tasks with no NPC ids.
 *  - Ids, for tasks whose target has a generic name (agility shortcuts and courses,
 *    cocktails, drops from monsters in the same chunk).
 *  - Inventory tools, for tasks done from the inventory: right-clicking a knife lists
 *    fletching tasks, a tinderbox firemaking, a pestle and mortar herblore, and so on
 *    (see matchesItem).
 *
 * Only used for tasks that have no NPC or object ids of their own.
 */
public final class TaskTargetExtras
{
	private TaskTargetExtras()
	{
	}

	/**
	 * The extras' data, read from the catalog sidecar task-authoring/_Target_Extras.json
	 * (see load). Empty until the catalog loads, and on any plugin build whose catalog
	 * lacks the file, in which case the outlines and menus just show fewer targets.
	 */
	private static final class Data
	{
		/** Task wording (letters only, lowercase) to the in-game name, where they differ. */
		Map<String, String> nameAliases = Collections.emptyMap();
		/** Crop (part of the task name or seed) to the patch it grows in. Checked in order. */
		List<List<String>> patches = Collections.emptyList();
		/** Task id to an NPC's name in game, for NPCs whose id varies or isn't known. */
		Map<String, String> namedNpcs = Collections.emptyMap();
		/** Task id to extra NPC ids: cocktail teachers, monsters in the chunk that drop the item. */
		Map<String, Set<Integer>> npcs = Collections.emptyMap();
		/** Task id to extra object ids: agility shortcut and course obstacles. */
		Map<String, Set<Integer>> objects = Collections.emptyMap();
		/** Task id to real level requirements the task's own data doesn't hold (gear, multi-skill tasks). */
		Map<String, Map<Skill, Integer>> requirements = Collections.emptyMap();
	}

	private static volatile Data data = new Data();

	/** Load the extras from the catalog sidecar's JSON; missing or unreadable leaves them empty. */
	public static void load(String json, Gson gson)
	{
		Data loaded = null;
		try
		{
			loaded = json == null ? null : gson.fromJson(json, Data.class);
		}
		catch (RuntimeException e)
		{
			// Unreadable sidecar: run without extras rather than fail the catalog load.
		}
		data = loaded != null ? loaded : new Data();
	}

	/**
	 * Every level a task needs: its own (category + level, "_Set" categories included)
	 * plus any listed in the extras' requirements (gear needing several skills, multi-skill tasks the
	 * data only half-holds, tasks with no level). The highest wins where both name a skill.
	 */
	public static Map<Skill, Integer> requirements(NuzlockeTask task)
	{
		Map<Skill, Integer> needs = new EnumMap<>(Skill.class);
		Skill own = categorySkill(task.getCategory());
		if (own != null && task.getLevelRequirement() > 1)
		{
			needs.put(own, task.getLevelRequirement());
		}
		Map<Skill, Integer> extra = task.getTaskId() == null ? null : data.requirements.get(task.getTaskId());
		if (extra != null)
		{
			for (Map.Entry<Skill, Integer> need : extra.entrySet())
			{
				needs.merge(need.getKey(), need.getValue(), Math::max);
			}
		}
		// Obtain/Equip levels from the sources sheet (task_sources.json).
		for (Map.Entry<Skill, Integer> need : TaskSources.levels(task).entrySet())
		{
			needs.merge(need.getKey(), need.getValue(), Math::max);
		}
		return needs;
	}

	/** The task's own level check, plus any real requirements it's missing. */
	public static boolean canDo(Client client, ChunkBlazerCore plugin, NuzlockeTask task)
	{
		return plugin.meetsLevelRequirement(task) && missingRequirement(client, task) == null;
	}

	/** "(20 Defence, 20 Ranged)" or "(Lvl 40)" for a task you lack the levels for; null if you have them. */
	public static String levelNote(Client client, ChunkBlazerCore plugin, NuzlockeTask task)
	{
		String missing = missingRequirement(client, task);
		return missing != null ? "(" + missing + ")"
			: plugin.meetsLevelRequirement(task) ? null : "(Lvl " + task.getLevelRequirement() + ")";
	}

	/** How far along the task is, 0 to 1. */
	public static double progress(NuzlockeTask task)
	{
		return Math.min(1.0, task.getCurrentProgress() / (double) Math.max(1, task.getTargetQuantity()));
	}

	/**
	 * Every requirement the player is missing, like "20 Defence, 20 Ranged", or null if
	 * they have them all.
	 */
	public static String missingRequirement(Client client, NuzlockeTask task)
	{
		StringBuilder missing = new StringBuilder();
		for (Map.Entry<Skill, Integer> need : requirements(task).entrySet())
		{
			if (client.getRealSkillLevel(need.getKey()) < need.getValue())
			{
				if (missing.length() > 0)
				{
					missing.append(", ");
				}
				missing.append(need.getValue()).append(' ').append(need.getKey().getName());
			}
		}
		return missing.length() == 0 ? null : missing.toString();
	}

	/**
	 * The skill a task category names: "Mining", "Runecrafting" (Runecraft), and "_Set"
	 * categories like "Herblore_Set" (Herblore). Null for Combat, Quest, Progression, etc.
	 */
	public static Skill categorySkill(String category)
	{
		if (category == null)
		{
			return null;
		}
		String name = category.trim().toUpperCase();
		if (name.endsWith("_SET"))
		{
			name = name.substring(0, name.length() - 4);
		}
		if (name.equals("RUNECRAFTING"))
		{
			name = "RUNECRAFT";
		}
		try
		{
			return Skill.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** Extra object ids for this task (empty if none). */
	public static Set<Integer> objectIds(NuzlockeTask task)
	{
		return task.getTaskId() == null ? Collections.emptySet()
			: data.objects.getOrDefault(task.getTaskId(), Collections.emptySet());
	}

	/** Extra NPC ids for this task (empty if none). */
	public static Set<Integer> npcIds(NuzlockeTask task)
	{
		return task.getTaskId() == null ? Collections.emptySet()
			: data.npcs.getOrDefault(task.getTaskId(), Collections.emptySet());
	}

	/**
	 * Whether right-clicking this inventory item should list the task. Tools stand in
	 * for skills done from the inventory; bones and ashes for their bury/scatter task.
	 * {@code item} is the item's name in lowercase.
	 */
	public static boolean matchesItem(NuzlockeTask task, String item)
	{
		String type = type(task);
		String name = lower(task.getName());
		switch (item)
		{
			case "knife":
				return type.equals("FLETCHING") || (type.equals("CRAFTING") && name.contains("dramen staff"));
			case "tinderbox":
				return type.equals("FIREMAKING");
			case "pestle and mortar":
				return type.equals("HERBLORE");
			case "needle":
				return type.equals("CRAFTING") && containsAny(name, "leather", "hide", "snakeskin", "coif",
					"xerician", "meat pouch");
			case "chisel":
				return type.equals("CRAFTING") && (name.matches("^cut an? .*") || name.contains("snelm"));
			case "glassblowing pipe":
				return type.equals("CRAFTING") && containsAny(name, "vial", "orb", "lantern", "lamp", "lens",
					"fishbowl", "beer glass");
			case "hammer":
				return type.equals("CRAFTING") && name.contains("broodoo");
			default:
				// Bones and ashes: the bury/scatter task that uses exactly this item.
				return type.equals("PRAYER") && !item.isEmpty() && item.equals(firstItem(task));
		}
	}

	public static boolean containsAny(String text, String... words)
	{
		for (String word : words)
		{
			if (text.contains(word))
			{
				return true;
			}
		}
		return false;
	}

	/** The in-game NPC name (letters only) a task's wording refers to. */
	public static String npcName(String wording)
	{
		String key = letters(wording);
		return data.nameAliases.getOrDefault(key, key);
	}

	public static String type(NuzlockeTask task)
	{
		return task.getCompletionType() == null ? "" : task.getCompletionType().toUpperCase();
	}

	public static String lower(String text)
	{
		return text == null ? "" : text.toLowerCase().trim();
	}

	/** Lowercase letters only: "Baker's Stall" and "baker's stall" both become "bakersstall". */
	public static String letters(String text)
	{
		return text == null ? "" : text.toLowerCase().replaceAll("[^a-z]", "");
	}

	public static String firstItem(NuzlockeTask task)
	{
		if (task.getRequiredItems() == null || task.getRequiredItems().isEmpty()
			|| task.getRequiredItems().get(0).getItem() == null)
		{
			return "";
		}
		return task.getRequiredItems().get(0).getItem().toLowerCase();
	}

	/** Crop to patch, in order (for the outline rules). */
	public static List<List<String>> patches()
	{
		return data.patches;
	}

	/** An NPC's in-game name for this task, where its id varies or isn't known; else null. */
	public static String namedNpc(String taskId)
	{
		return taskId == null ? null : data.namedNpcs.get(taskId);
	}
}
