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

import net.runelite.client.config.ConfigManager;

/**
 * How much help the game gives, picked in the tutorial (and changeable one by one under
 * Settings > Assists). Picking one sets every assist at once.
 */
public enum AssistLevel
{
	/** Everything on: details, outlines on every task, item highlights, the Tasks menu, tooltips, auto-track, paths. */
	CRITIC("Video Game Critic", "Level 1", new String[]{"Task details", "Outlines: all tasks", "Item highlights",
		"Right-click Tasks menu", "Task tooltips", "Auto-track", "Shortest path"}),
	/** Task details, outlines only for the tasks you've saved, and shortest path. */
	DADSCAPE("Dadscape", "Level 2", new String[]{"Task details", "Outlines: saved tasks", "Shortest path"}),
	/** No help at all. */
	GAMER("Certified GAMER", "Level 3", new String[0]);

	public final String title;
	public final String level;
	/** What's on at this level, for its card. */
	public final String[] assists;

	AssistLevel(String title, String level, String[] assists)
	{
		this.title = title;
		this.level = level;
		this.assists = assists;
	}

	/** Sets every assist to this level's. */
	public void apply(ConfigManager configManager)
	{
		String group = ChunkBlazerUiConfig.GROUP;
		configManager.setConfiguration(group, "assistLevel", this);
		configManager.setConfiguration(group, "showTaskDetails", this != GAMER);
		configManager.setConfiguration(group, "taskOutlineMode", this == CRITIC ? OutlineMode.ALL : this == DADSCAPE ? OutlineMode.SAVED : OutlineMode.OFF);
		configManager.setConfiguration(group, "highlightEquipItems", this == CRITIC);
		configManager.setConfiguration(group, "taskRightClickMenu", this == CRITIC);
		configManager.setConfiguration(group, "taskTooltipKey", this == CRITIC ? TaskTooltipKey.SHIFT : TaskTooltipKey.OFF);
		configManager.setConfiguration(group, "autoTrackTasks", this == CRITIC);
		configManager.setConfiguration(group, "shortestPath", this != GAMER);
	}

	@Override
	public String toString()
	{
		return title;
	}
}
