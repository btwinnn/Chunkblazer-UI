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

import com.chunkblazerui.pathing.ChunkPathing;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Everything ChunkBlazer UI shows: the task window and quest/saved box, the animated cards,
 * task box, completion popup, outlines, chunk name banner, map legend, themes, chunk paths and
 * tutorial, started and stopped when ChunkBlazer says (see ChunkBlazerUiPlugin).
 */
@Slf4j
@Singleton
public class ChunkBlazerUiModule implements ChunkBlazerUi
{
	private static final String CONFIG_GROUP = ChunkBlazerUiConfig.GROUP;

	@Inject
	private OverlayManager overlayManager;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private EventBus eventBus;
	@Inject
	private ChunkBlazerUiConfig config;
	@Inject
	private ChunkBlazerCore core;
	@Inject
	private ChunkBlazerTutorial tutorial;
	@Inject
	private TaskCardOverlay cards;
	@Inject
	private TaskCompletionAnimationOverlay completion;
	@Inject
	private StampCompletionOverlay stamp;
	@Inject
	private TaskTargetHighlighter highlighter;
	@Inject
	private TaskWindow window;
	@Inject
	private TaskPaneOverlay pane;
	@Inject
	private SelectedTaskOverlay taskBox;
	@Inject
	private SelectedTaskOverlayInput taskBoxInput;
	@Inject
	private ChunkPathing pathing;
	@Inject
	private FightConditions fightConditions;
	@Inject
	private SidePanelSkin sidePanelSkin;
	@Inject
	private CardSounds cardSounds;

	@Override
	public void startUp()
	{
		applyTheme();
		eventBus.register(this);
		// The new-player tutorial runs before a mode is picked.
		overlayManager.add(tutorial);
		mouseManager.registerMouseListener(tutorial.mouse);
		eventBus.register(tutorial);
	}

	@Override
	public void shutDown()
	{
		// The side panel goes back to ChunkBlazer's own look.
		core.setSidePainter(null);
		core.setUiCompletionSound(false);
		overlayManager.remove(tutorial);
		mouseManager.unregisterMouseListener(tutorial.mouse);
		eventBus.unregister(tutorial);
		eventBus.unregister(this);
	}

	/** Settings > Theme (or its sounds) changed: everything takes the new look, the side panel included. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"theme".equals(event.getKey()) && !"themeSounds".equals(event.getKey()))
		{
			return;
		}
		applyTheme();
	}

	/** The theme: everything here takes it, and ChunkBlazer's side panel too (drawn from here; its own look for ChunkBlazer). */
	private void applyTheme()
	{
		ThemePalette palette = Themes.palette(config.theme());
		Skin.setPalette(palette);
		core.setSidePainter(palette.theme == Theme.ORIGINAL ? null : sidePanelSkin);
		// Varlamore's own completion sound plays instead of the area jingle.
		core.setUiCompletionSound(cardSounds.on());
	}

	@Override
	public void showGameUi(boolean show)
	{
		if (show)
		{
			overlayManager.add(completion);
			overlayManager.add(stamp);
			overlayManager.add(cards);
			overlayManager.add(taskBox);
			overlayManager.add(highlighter);
			eventBus.register(highlighter);
			eventBus.register(fightConditions);
			mouseManager.registerMouseListener(taskBoxInput);
			// Paths are an extra: if they fail to start, everything else must still come up
			// (the plugin starts the orbs and side panel list right after this).
			try
			{
				pathing.startUp();
			}
			catch (RuntimeException | LinkageError e)
			{
				log.warn("Chunk paths failed to start", e);
			}
			return;
		}
		try
		{
			pathing.shutDown();
		}
		catch (RuntimeException | LinkageError e)
		{
			log.warn("Chunk paths failed to stop", e);
		}
		mouseManager.unregisterMouseListener(taskBoxInput);
		overlayManager.remove(taskBox);
		eventBus.unregister(highlighter);
		eventBus.unregister(fightConditions);
		overlayManager.remove(highlighter);
		highlighter.reset();
		overlayManager.remove(completion);
		overlayManager.remove(stamp);
		overlayManager.remove(cards);
		// Drop cards WITHOUT revealing: the pending set is durable, so they are still
		// waiting on the next startup. Revealing here would flip them for free.
		cards.clear();
	}

	@Override
	public void startTaskUi()
	{
		pane.startUp();
	}

	@Override
	public void stopTaskUi()
	{
		pane.shutDown();
	}

	@Override
	public void toggleWindow()
	{
		window.toggle();
	}

	@Override
	public void setTaskButtonBounds(Rectangle bounds)
	{
		pane.setTaskButtonBounds(bounds);
	}

	/** A quest task clicked in the side panel opens its requirements in the box. */
	@Override
	public void openTask(NuzlockeTask task)
	{
		pane.openQuestPane(task);
	}

	@Override
	public TaskCardShow cards()
	{
		return cards;
	}

	@Override
	public TutorialHooks tutorial()
	{
		return tutorial;
	}

	@Override
	public void showTaskCompletion(NuzlockeTask task, int points, String region)
	{
		// ChunkBlazer's own look keeps the original popup; Old School and Varlamore get their
		// card flipped and stamped COMPLETE.
		if (Skin.theme() == Theme.ORIGINAL)
		{
			completion.showTaskCompletion(task, points, region);
		}
		else
		{
			stamp.showTaskCompletion(task, points, region);
		}
	}
}
