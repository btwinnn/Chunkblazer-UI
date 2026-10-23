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

import java.util.Arrays;
import java.util.List;
import net.runelite.client.config.ConfigManager;

/**
 * ChunkBlazer UI's settings were ChunkBlazer's until the two became separate plugins. The
 * first time this plugin starts, it takes over whatever the player had chosen for them.
 */
final class Settings
{
	private static final String OLD_GROUP = "chunkblazer";
	private static final String MOVED = "settingsMoved";
	private static final List<String> KEYS = Arrays.asList("cardEntrance", "theme", "taskTrackerStyle", "autoTrackTasks",
		"showSavedTaskTracker", "movableTaskBox", "taskRightClickMenu", "highlightTaskTargets", "taskOutlineMode",
		"taskHighlightColor", "taskHighlightUnavailableColor", "highlightEquipItems", "taskTooltipKey", "showChunkLegend",
		"showChunkNamePopups");

	private Settings()
	{
	}

	static void migrate(ConfigManager configManager)
	{
		if (configManager.getConfiguration(ChunkBlazerUiConfig.GROUP, MOVED) != null)
		{
			return;
		}
		for (String key : KEYS)
		{
			String value = configManager.getConfiguration(OLD_GROUP, key);
			if (value != null && configManager.getConfiguration(ChunkBlazerUiConfig.GROUP, key) == null)
			{
				configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, key, value);
			}
		}
		configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, MOVED, true);
	}
}
