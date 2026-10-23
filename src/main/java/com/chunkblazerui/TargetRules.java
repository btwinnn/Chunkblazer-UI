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

import com.chunkblazerui.TaskTargetHighlighter.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Outline rules built from ChunkBlazer's task data: where a task's item comes from
 * (TaskSources) and the extras' patches, stalls, STASH units and named NPCs (TaskTargetExtras).
 */
final class TargetRules
{
	private TargetRules()
	{
	}

	/** Name rules outlining where a task's item comes from: its source, or the listed outline names. */
	static List<Rule> sourceRules(NuzlockeTask task)
	{
		List<Rule> rules = new ArrayList<>();
		for (String name : TaskSources.outlineNames(task))
		{
			String target = TaskTargetExtras.letters(name);
			if (!target.isEmpty())
			{
				rules.add(new Rule("source:" + target, n -> TaskTargetExtras.letters(n).equals(target)));
			}
		}
		return rules;
	}

	/** Object name rules for this task: patches, stalls, STASH units. */
	static List<Rule> extraObjectRules(NuzlockeTask task)
	{
		String type = TaskTargetExtras.type(task);
		String name = TaskTargetExtras.lower(task.getName());

		if (type.equals("FARMING") && name.startsWith("plant"))
		{
			String crop = name + " " + TaskTargetExtras.firstItem(task);
			for (List<String> entry : TaskTargetExtras.patches())
			{
				if (entry.size() == 2 && crop.contains(entry.get(0)))
				{
					String patch = entry.get(1);
					return Collections.singletonList(new Rule("extra:patch:" + patch, n -> n.startsWith(patch)));
				}
			}
			return Collections.emptyList();
		}

		if (type.equals("THIEVING") && name.startsWith("steal from a") && name.endsWith("stall"))
		{
			String stall = TaskTargetExtras.letters(name.replaceFirst("^steal from an? ", ""));
			return Collections.singletonList(new Rule("extra:stall:" + stall,
				n -> TaskTargetExtras.letters(n).equals(stall), "steal-from", "steal from"));
		}

		// Silver crafting is done at a furnace, like jewellery (same rule as the plugin's).
		if (type.equals("CRAFTING") && TaskTargetExtras.containsAny(name, "tiara", "unstrung symbol", "unstrung emblem", "silver sickle"))
		{
			return Collections.singletonList(new Rule("station:smelt", n -> true, "smelt"));
		}

		if (type.equals("CONSTRUCTION") && name.contains("stash unit"))
		{
			for (String tier : Arrays.asList("beginner", "easy", "medium", "hard", "elite", "master"))
			{
				if (name.contains(tier))
				{
					return Collections.singletonList(new Rule("extra:stash:" + tier,
						n -> n.contains("stash unit") && n.contains(tier)));
				}
			}
		}
		return Collections.emptyList();
	}

	/** NPC name rule for this task: pickpocketing, hunter creatures, kills with no ids. */
	static Rule extraNpcRule(NuzlockeTask task)
	{
		String type = TaskTargetExtras.type(task);
		String name = TaskTargetExtras.lower(task.getName());

		if (type.equals("THIEVING") && name.matches("^pickpocket an? .*"))
		{
			String target = TaskTargetExtras.npcName(name.replaceFirst("^pickpocket an? ", ""));
			return new Rule("extra:pickpocket:" + target, n -> TaskTargetExtras.letters(n).equals(target), "pickpocket");
		}
		if (type.equals("HUNTER") && name.matches("^catch an? .*"))
		{
			String target = TaskTargetExtras.npcName(name.replaceFirst("^catch an? ", "").replaceFirst(" in a butterfly jar$", ""));
			return new Rule("extra:hunt:" + target, n -> TaskTargetExtras.letters(n).equals(target));
		}
		String named = TaskTargetExtras.namedNpc(task.getTaskId());
		if (named != null)
		{
			String target = TaskTargetExtras.letters(named);
			return new Rule("extra:named:" + target, n -> TaskTargetExtras.letters(n).equals(target));
		}

		// Imp Catcher beads drop from imps (any spawn, so matched by name).
		if (type.equals("OBTAIN") && name.matches("^obtain an? (black|yellow|white|red) bead$"))
		{
			return new Rule("extra:kill:imp", n -> TaskTargetExtras.letters(n).equals("imp"), "attack");
		}
		if ((type.equals("NPC_KILL") || type.equals("SLAYER")) && name.matches("^defeat an? .*"))
		{
			String target = TaskTargetExtras.npcName(name.replaceFirst("^defeat an? ", "").replaceFirst(" on task$", ""));
			return new Rule("extra:kill:" + target, n -> TaskTargetExtras.letters(n).equals(target), "attack");
		}
		return null;
	}
}
