/*
 * Copyright (c) 2026, btwinnn
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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

/**
 * ChunkBlazer UI's own settings. Most are shown on the task window's Settings page, so they
 * are hidden here. ChunkBlazer's settings (task cards, the completion popup, the world map)
 * stay in ChunkBlazer and are read from it.
 */
@ConfigGroup(ChunkBlazerUiConfig.GROUP)
public interface ChunkBlazerUiConfig extends Config
{
	String GROUP = "chunkblazerui";

	@ConfigSection(
		name = "Task Outlines",
		description = "The outline colours for task NPCs and objects",
		position = 0
	)
	String taskSection = "tasks";

	@ConfigItem(
		keyName = "pathKey",
		hidden = true, // the task window's Settings page (Keybinds)
		name = "Path Click Key",
		description = "Hold this key (Alt by default) and click the world map to set a path there, or clear the one showing",
		section = taskSection,
		position = 26
	)
	default Keybind pathKey()
	{
		return Keybind.ALT;
	}

	@ConfigItem(
		keyName = "showMapKeys",
		hidden = true, // shown on the task window's Settings page
		name = "Keys Legend",
		description = "Show the world map's click keys in its bottom-right corner",
		section = taskSection,
		position = 4
	)
	default boolean showMapKeys()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shortestPath",
		hidden = true, // the task window's Settings page (Assists)
		name = "Shortest Path",
		description = "Paths through your unlocked chunks (Path here, the map's path key, Quest Helper's start) and the quest chunk calculator",
		section = taskSection,
		position = 25
	)
	default boolean shortestPath()
	{
		return true;
	}

	@ConfigItem(
		keyName = "routeStyle",
		hidden = true, // the task window's Settings page (Assists)
		name = "Route",
		description = "How Shortest path picks its way: fastest (teleports for walks over 80 steps) or scenic (walks)",
		section = taskSection,
		position = 26
	)
	default RouteStyle routeStyle()
	{
		return RouteStyle.FASTEST;
	}

	@ConfigItem(
		keyName = "cardEntrance",
		hidden = true, // shown on the task window's Settings page
		name = "Card Entrance",
		description = "How a fresh deal of task cards arrives on screen",
		section = taskSection,
		position = 0
	)
	default CardEntrance cardEntrance()
	{
		return CardEntrance.DEALT;
	}

	@ConfigItem(
		keyName = "themeSounds",
		hidden = true, // shown on the task window's Settings page
		name = "Theme Sounds",
		description = "Varlamore's sounds for dealing and turning task cards and the task complete popup",
		section = taskSection,
		position = 0
	)
	default boolean themeSounds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "theme",
		hidden = true, // shown on the task window's Settings page
		name = "Theme",
		description = "ChunkBlazer's look: task cards, the task window, boxes and the side panel",
		section = taskSection,
		position = 0
	)
	default Theme theme()
	{
		return Theme.OLD_SCHOOL;
	}

	@ConfigItem(
		keyName = "taskTrackerStyle",
		hidden = true, // shown on the task window's Settings page
		name = "Task Box",
		description = "How the task you track is shown in game. Off hides it",
		section = taskSection,
		position = 1
	)
	default TaskTrackerStyle taskTrackerStyle()
	{
		return TaskTrackerStyle.VANI;
	}

	@ConfigItem(
		keyName = "autoTrackTasks",
		hidden = true, // shown on the task window's Settings page
		name = "Auto-Track Tasks",
		description = "Using an NPC or object a task needs tracks that task. A task you picked yourself is kept",
		section = taskSection,
		position = 2
	)
	default boolean autoTrackTasks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSavedTaskTracker",
		hidden = true, // shown on the task window's Settings page
		name = "Saved Tasks Bubble",
		description = "A bubble that opens your saved tasks (Alt + drag to move)",
		section = taskSection,
		position = 3
	)
	default boolean showSavedTaskTracker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "movableTaskBox",
		hidden = true, // shown on the task window's Settings page
		name = "Movable Quest/Saved Box",
		description = "Open quest requirements and saved tasks in a box you can Alt + drag, instead of over the inventory",
		section = taskSection,
		position = 3
	)
	default boolean movableTaskBox()
	{
		return false;
	}

	@ConfigItem(
		keyName = "taskRightClickMenu",
		hidden = true, // shown on the task window's Settings page
		name = "Right-Click Tasks Menu",
		description = "Adds a Tasks submenu when right-clicking NPCs and objects your tasks need",
		section = taskSection,
		position = 4
	)
	default boolean taskRightClickMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightTaskTargets",
		name = "Outline Task Targets",
		description = "Older on/off outline setting, kept so Outlines starts where players left it",
		section = taskSection,
		position = 5,
		hidden = true
	)
	default boolean highlightTaskTargets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskOutlineMode",
		hidden = true, // shown on the task window's Settings page
		name = "Outlines",
		description = "Which task NPCs and objects get outlined: all, saved, doable now, or none",
		section = taskSection,
		position = 5
	)
	default OutlineMode taskOutlineMode()
	{
		return highlightTaskTargets() ? OutlineMode.ALL : OutlineMode.OFF;
	}

	@ConfigItem(
		keyName = "taskHighlightColor",
		name = "Outline Colour",
		description = "Outline colour for task NPCs and objects you can do now",
		section = taskSection,
		position = 6
	)
	default java.awt.Color taskHighlightColor()
	{
		return new java.awt.Color(255, 140, 0);
	}

	@ConfigItem(
		keyName = "taskHighlightUnavailableColor",
		name = "Outline Colour (Level Too Low)",
		description = "Outline colour when you don't have the level for any of that target's tasks",
		section = taskSection,
		position = 7
	)
	default java.awt.Color taskHighlightUnavailableColor()
	{
		return new java.awt.Color(255, 60, 60);
	}

	@ConfigItem(
		keyName = "highlightEquipItems",
		hidden = true, // shown on the task window's Settings page
		name = "Highlight Task Items",
		description = "Outline items your tasks need (gear, tools) in your inventory, bank and shops",
		section = taskSection,
		position = 8
	)
	default boolean highlightEquipItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskTooltipKey",
		hidden = true, // shown on the task window's Settings page
		name = "Task Tooltip Key",
		description = "Hold this key over an NPC or object to list its tasks in a tooltip",
		section = taskSection,
		position = 9
	)
	default TaskTooltipKey taskTooltipKey()
	{
		return TaskTooltipKey.SHIFT;
	}

	@ConfigItem(
		keyName = "showChunkLegend",
		hidden = true, // shown on the task window's Settings page
		name = "Colour Legend",
		description = "Show a key explaining the chunk colours in the corner of the world map",
		section = taskSection,
		position = 2
	)
	default boolean showChunkLegend()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChunkNamePopups",
		hidden = true, // shown on the task window's Settings page
		name = "Chunk Name Banner",
		description = "Show a chunk's name at the top of the screen when you enter it",
		section = taskSection,
		position = 3
	)
	default boolean showChunkNamePopups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackedTaskShows",
		hidden = true, // shown on the task window's Settings page, and picked in the tutorial
		name = "Tracked Task Shows In",
		description = "Where the task you track shows: ChunkBlazer's side panel, the task box on screen, or both",
		section = taskSection,
		position = 20
	)
	default TrackedTaskShows trackedTaskShows()
	{
		return TrackedTaskShows.WIDGET;
	}

	@ConfigItem(
		keyName = "expandAllTasks",
		hidden = true, // the task window's sidebar and Settings page
		name = "Expand All Tasks",
		description = "Every task in the task window shows its details (quests open their own pane)",
		section = taskSection,
		position = 21
	)
	default boolean expandAllTasks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showMissingRequirements",
		hidden = true, // the task window's sidebar and Settings page
		name = "Show Missing Levels",
		description = "A red line under a task you can't do yet, saying what you're missing",
		section = taskSection,
		position = 22
	)
	default boolean showMissingRequirements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskDetails",
		hidden = true, // the task window's Settings page (Assists)
		name = "Task Details",
		description = "Where a task's item comes from: its source, drop rate, cost and how to get it",
		section = taskSection,
		position = 23
	)
	default boolean showTaskDetails()
	{
		return true;
	}

	@ConfigItem(
		keyName = "assistLevel",
		hidden = true, // picked in the tutorial; each assist is on the Settings page
		name = "Assist Level",
		description = "The assist level picked in the tutorial",
		section = taskSection,
		position = 24
	)
	default AssistLevel assistLevel()
	{
		return AssistLevel.CRITIC;
	}
}
