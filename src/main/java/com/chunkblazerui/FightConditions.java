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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * For the tracked task's fight rules (nothing equipped, only a weapon, no prayer, no food,
 * no cannon, and boss challenges' gear, inventory, run and prayer rules): whether you meet
 * them right now, for the task box to say, and a chat message the moment one is broken
 * mid-fight, so you know that kill won't count before you finish it. The kill itself is
 * still judged by ChunkBlazer, which says when a boss challenge breaks itself.
 */
@Singleton
public class FightConditions
{
	// The equipment slots there are (some indices are skipped in the game).
	private static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};
	// A broken rule stays on the task box this long after the fight ends, in ticks.
	private static final int FAIL_SHOWN_TICKS = 10;

	@Inject
	private Client client;
	@Inject
	private ChunkBlazerCore plugin;
	@Inject
	private ChatMessageManager chat;

	private volatile String taskId;
	// What to change to meet the rules right now (empty when they're all met).
	private volatile List<String> problem = Collections.emptyList();
	// The rule broken during this fight, or null.
	private volatile String failed;
	private int fightStart = -1;
	private int fightEndedAt = -1;

	/** Whether the task has fight rules this checks (kill tasks and boss challenges). */
	public static boolean applies(NuzlockeTask task)
	{
		if (task != null && task.getChallenge() != null && task.getChallenge().any())
		{
			return true;
		}
		TaskConstraints c = task == null ? null : task.getConstraints();
		if (c == null || task.getTargetNpc() == null)
		{
			return false;
		}
		return Boolean.TRUE.equals(c.getNoEquipment()) || Boolean.TRUE.equals(c.getEquipNothing())
			|| notEmpty(c.getRequiredEquipmentIds()) || notEmpty(c.getAllowedEquipmentIds())
			|| notEmpty(c.getForbiddenEquipmentIds()) || notEmpty(c.getMustBeEmptySlots())
			|| notEmpty(c.getEquippableSlots()) || notEmpty(c.getProhibitedActiveVarbits())
			|| Boolean.TRUE.equals(c.getNoPrayer()) || Boolean.TRUE.equals(c.getNoFood());
	}

	/** Everything to change to meet {@code task}'s rules right now (empty when they're all met). */
	public List<String> problems(NuzlockeTask task)
	{
		return task != null && task.getTaskId() != null && task.getTaskId().equals(taskId) ? problem : Collections.emptyList();
	}

	/** The rule broken in this fight (or the one just ended), or null. */
	public String failed(NuzlockeTask task)
	{
		return task != null && task.getTaskId() != null && task.getTaskId().equals(taskId) ? failed : null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		NuzlockeTask task = plugin.getSelectedTask();
		if (!applies(task))
		{
			taskId = null;
			problem = Collections.emptyList();
			failed = null;
			fightStart = -1;
			return;
		}
		if (!task.getTaskId().equals(taskId))
		{
			taskId = task.getTaskId();
			failed = null;
			fightStart = -1;
		}
		int tick = client.getTickCount();
		if (task.getChallenge() != null && task.getChallenge().any())
		{
			// ChunkBlazer runs the attempt and says in chat when it breaks; this shows it.
			problem = check(task.getChallenge());
			String status = plugin.challengeStatus(task);
			boolean live = status != null && status.startsWith("1|");
			String why = status == null ? "" : status.substring(status.indexOf('|') + 1);
			if (live)
			{
				fightEndedAt = tick;
			}
			failed = !why.isEmpty() && (live || tick - fightEndedAt <= FAIL_SHOWN_TICKS) ? why : null;
			return;
		}
		problem = check(task.getConstraints());
		int start = plugin.getActiveFightStartTick(task);
		if (start >= 0)
		{
			if (start != fightStart)
			{
				// A new fight: a rule broken in the last one doesn't carry over.
				fightStart = start;
				failed = null;
			}
			if (!problem.isEmpty())
			{
				fail(task, problem.get(0));
			}
		}
		else if (fightStart >= 0)
		{
			fightStart = -1;
			fightEndedAt = tick;
		}
		else if (failed != null && tick - fightEndedAt > FAIL_SHOWN_TICKS)
		{
			failed = null;
		}
	}

	/** Eating mid-fight breaks a no-food rule straight away. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		NuzlockeTask task = plugin.getSelectedTask();
		if (fightStart < 0 || task == null || !task.getTaskId().equals(taskId) || task.getConstraints() == null
			|| !Boolean.TRUE.equals(task.getConstraints().getNoFood()))
		{
			return;
		}
		if ("eat".equals(Text.removeTags(event.getMenuOption()).toLowerCase()))
		{
			fail(task, "Ate food");
		}
	}

	private void fail(NuzlockeTask task, String why)
	{
		if (failed != null)
		{
			return;
		}
		failed = why;
		chat.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value("<col=ff9628>ChunkBlazer:</col> <col=ff4040>" + task.getName() + " won't count this fight: " + why + "</col>")
			.build());
	}

	/** Client thread: everything to change to meet the rules right now. */
	private List<String> check(TaskConstraints c)
	{
		Set<String> problems = new LinkedHashSet<>();
		List<Integer> worn = new ArrayList<>();
		int[] bySlot = new int[14];
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment != null)
		{
			Item[] items = equipment.getItems();
			for (int slot : SLOTS)
			{
				if (slot < items.length && items[slot] != null && items[slot].getId() > 0)
				{
					bySlot[slot] = items[slot].getId();
					worn.add(items[slot].getId());
				}
			}
		}
		if ((Boolean.TRUE.equals(c.getNoEquipment()) || Boolean.TRUE.equals(c.getEquipNothing())) && !worn.isEmpty())
		{
			for (int id : worn)
			{
				problems.add("Take off " + name(id));
			}
		}
		if (c.getRequiredEquipmentIds() != null)
		{
			for (int id : c.getRequiredEquipmentIds())
			{
				if (!worn.contains(id))
				{
					problems.add("Wear " + name(id));
				}
			}
		}
		for (int id : worn)
		{
			if (notEmpty(c.getAllowedEquipmentIds()) && !c.getAllowedEquipmentIds().contains(id)
				|| c.getForbiddenEquipmentIds() != null && c.getForbiddenEquipmentIds().contains(id))
			{
				problems.add("Take off " + name(id));
			}
		}
		for (int slot : SLOTS)
		{
			boolean mustEmpty = c.getMustBeEmptySlots() != null && c.getMustBeEmptySlots().contains(slot)
				|| notEmpty(c.getEquippableSlots()) && !c.getEquippableSlots().contains(slot);
			if (mustEmpty && bySlot[slot] > 0)
			{
				problems.add("Take off " + name(bySlot[slot]));
			}
		}
		if (c.getProhibitedActiveVarbits() != null)
		{
			for (TaskConstraints.VarbitConstraint v : c.getProhibitedActiveVarbits())
			{
				if (client.getVarbitValue(v.getVarbitId()) != v.getMustBeValue())
				{
					problems.add(v.getFailMessage() != null && !v.getFailMessage().isEmpty() ? v.getFailMessage() : "Not allowed right now");
				}
			}
		}
		if (Boolean.TRUE.equals(c.getNoPrayer()))
		{
			for (Prayer prayer : Prayer.values())
			{
				if (client.isPrayerActive(prayer))
				{
					problems.add("Turn your prayers off");
				}
			}
		}
		return new ArrayList<>(problems);
	}

	/** Client thread: everything to change to meet a boss challenge's rules right now. */
	private List<String> check(Challenge c)
	{
		Set<String> problems = new LinkedHashSet<>();
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		Item[] worn = equipment == null ? new Item[0] : equipment.getItems();
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Item[] held = inventory == null ? new Item[0] : inventory.getItems();
		int weapon = worn.length > 3 && worn[3] != null ? worn[3].getId() : -1;
		if (c.getRequiredEquippedIds() != null)
		{
			for (int id : c.getRequiredEquippedIds())
			{
				if (!has(worn, id))
				{
					problems.add("Wear " + name(id));
				}
			}
		}
		if (c.getRequiredEquippedGroups() != null)
		{
			for (List<Integer> group : c.getRequiredEquippedGroups())
			{
				if (group.stream().noneMatch(id -> has(worn, id)))
				{
					problems.add("Wear " + names(group));
				}
			}
		}
		if (c.getWeaponIds() != null && !c.getWeaponIds().contains(weapon))
		{
			problems.add("Wield " + names(c.getWeaponIds()));
		}
		if (c.getForbiddenWeaponIds() != null && c.getForbiddenWeaponIds().contains(weapon))
		{
			problems.add("Take off " + name(weapon));
		}
		if (c.getEmptySlots() != null)
		{
			for (int slot : c.getEmptySlots())
			{
				if (slot < worn.length && worn[slot] != null && worn[slot].getId() > 0)
				{
					problems.add("Take off " + name(worn[slot].getId()));
				}
			}
		}
		if (c.getRequiredInventoryIds() != null)
		{
			for (int id : c.getRequiredInventoryIds())
			{
				if (!has(held, id))
				{
					problems.add("Bring " + name(id));
				}
			}
		}
		if (c.getRequiredInventoryGroups() != null)
		{
			for (List<Integer> group : c.getRequiredInventoryGroups())
			{
				if (group.stream().noneMatch(id -> has(held, id)))
				{
					problems.add("Bring " + names(group));
				}
			}
		}
		if (c.getForbiddenItemIds() != null)
		{
			for (int id : c.getForbiddenItemIds())
			{
				if (has(held, id) || has(worn, id))
				{
					problems.add("Get rid of " + name(id));
				}
			}
		}
		int free = 28;
		for (Item item : held)
		{
			if (item != null && item.getId() > 0)
			{
				free--;
			}
		}
		if (Boolean.TRUE.equals(c.getEmptyInventory()) && free < 28)
		{
			problems.add("Empty your inventory");
		}
		if (c.getMinEmptyInventorySlots() != null && free < c.getMinEmptyInventorySlots())
		{
			problems.add("Keep " + c.getMinEmptyInventorySlots() + " inventory spaces free");
		}
		if (Boolean.TRUE.equals(c.getNoRun())
			&& client.getVarpValue(c.getRunVarp() != null ? c.getRunVarp() : VarPlayerID.OPTION_RUN) != 0)
		{
			problems.add("Turn run off");
		}
		if (c.getForbiddenPrayerBits() != null)
		{
			int prayers = client.getVarbitValue(VarbitID.PRAYER_ALLACTIVE);
			for (int bit : c.getForbiddenPrayerBits())
			{
				if ((prayers & (1 << bit)) != 0)
				{
					problems.add("Turn that prayer off");
				}
			}
		}
		if (c.getMaxPlayerHitpoints() != null && client.getBoostedSkillLevel(Skill.HITPOINTS) > c.getMaxPlayerHitpoints())
		{
			problems.add("Get to " + c.getMaxPlayerHitpoints() + " Hitpoints or less");
		}
		if (c.getMaxPrayer() != null && client.getBoostedSkillLevel(Skill.PRAYER) > c.getMaxPrayer())
		{
			problems.add("Keep Prayer at " + c.getMaxPrayer() + " or less");
		}
		return new ArrayList<>(problems);
	}

	private static boolean has(Item[] items, int id)
	{
		for (Item item : items)
		{
			if (item != null && item.getId() == id)
			{
				return true;
			}
		}
		return false;
	}

	/** "Bronze platelegs or Bronze plateskirt". */
	private String names(List<Integer> ids)
	{
		List<String> names = new ArrayList<>();
		for (int id : ids)
		{
			String name = name(id);
			if (!names.contains(name))
			{
				names.add(name);
			}
		}
		return String.join(" or ", names);
	}

	private String name(int id)
	{
		return client.getItemDefinition(id).getName();
	}

	private static boolean notEmpty(List<?> list)
	{
		return list != null && !list.isEmpty();
	}
}
