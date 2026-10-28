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
import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * ChunkBlazer UI: the task window and quest/saved box, animated task cards, the task box,
 * completion popups, outlines, chunk name banner, map legend, themes, chunk paths and the
 * new-player tutorial, for the ChunkBlazer plugin.
 *
 * <p>It does nothing on its own. When ChunkBlazer is running, the two find each other over
 * RuneLite's plugin messages (see {@link ChunkBlazerCore}): ChunkBlazer then hands its UI over
 * to this plugin, and says when to show what (the {@link ChunkBlazerUi} calls below). Turning
 * either plugin off hands it back; ChunkBlazer carries on with its plain cards and side panel.
 */
@Slf4j
@PluginDescriptor(
	name = "ChunkBlazer UI",
	description = "The task window, animated task cards, themes, chunk paths and tutorial for ChunkBlazer",
	tags = {"chunk", "chunkblazer", "nuzlocke", "task", "ui"}
)
public class ChunkBlazerUiPlugin extends Plugin
{
	@Inject
	private EventBus eventBus;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ChunkBlazerCore core;
	@Inject
	private ChunkBlazerUiModule ui;
	@Inject
	private ChunkBlazerTutorial tutorial;
	@Inject
	private TaskLists lists;
	@Inject
	private QuestRequirements quests;
	@Inject
	private Gson gson;

	// What ChunkBlazer has asked for, so turning this plugin off takes it all down again.
	private boolean started;
	private boolean gameUi;
	private boolean taskUi;
	// The tutorial's state as ChunkBlazer last heard it.
	private Map<String, Object> tutorialSent;

	@Provides
	ChunkBlazerUiConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChunkBlazerUiConfig.class);
	}

	@Override
	protected void startUp()
	{
		Settings.migrate(configManager);
		// If ChunkBlazer is already running, it takes the UI from here (and starts it).
		eventBus.post(new PluginMessage(ChunkBlazerCore.TO_CORE, "hello"));
	}

	@Override
	protected void shutDown()
	{
		// ChunkBlazer takes its own UI back...
		eventBus.post(new PluginMessage(ChunkBlazerCore.TO_CORE, "bye"));
		// ...and whatever is still up here comes down, whatever ChunkBlazer managed to say.
		stopAll();
	}

	private synchronized void stopAll()
	{
		if (taskUi)
		{
			taskUi = false;
			ui.stopTaskUi();
		}
		if (gameUi)
		{
			gameUi = false;
			ui.showGameUi(false);
		}
		if (started)
		{
			started = false;
			ui.shutDown();
		}
		tutorialSent = null;
	}

	/** ChunkBlazer, telling the UI what to do (or asking it). */
	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		if (!ChunkBlazerCore.TO_UI.equals(message.getNamespace()))
		{
			return;
		}
		Map<String, Object> data = message.getData();
		try
		{
			handle(message.getName(), data);
		}
		catch (RuntimeException e)
		{
			log.warn("ChunkBlazer UI couldn't do {}", message.getName(), e);
		}
	}

	private synchronized void handle(String name, Map<String, Object> data)
	{
		// What ChunkBlazer says here never changes its own state (that's what the stamp is
		// for), so what's kept stays: the orb's bounds alone come many times a second.
		switch (name)
		{
			case "ping":
				data.put("r", true);
				break;
			case "start":
				core.invalidate();
				if (!started)
				{
					try
					{
						loadData();
					}
					catch (RuntimeException e)
					{
						// Missing quest or source data only means less to show: start anyway.
						log.warn("ChunkBlazer UI couldn't load its data", e);
					}
					lists.loadFilters();
					ui.startUp();
					started = true;
				}
				sendTutorial(true);
				break;
			case "stop":
				stopAll();
				break;
			case "gameUi":
			{
				boolean show = Boolean.TRUE.equals(data.get("show"));
				if (started && show != gameUi)
				{
					gameUi = show;
					ui.showGameUi(show);
				}
				break;
			}
			case "taskUi":
			{
				boolean show = Boolean.TRUE.equals(data.get("show"));
				if (started && show != taskUi)
				{
					taskUi = show;
					if (show)
					{
						ui.startTaskUi();
					}
					else
					{
						ui.stopTaskUi();
					}
				}
				break;
			}
			case "toggleWindow":
				ui.toggleWindow();
				break;
			case "taskButtonBounds":
			{
				Object bounds = data.get("bounds");
				ui.setTaskButtonBounds(bounds instanceof Rectangle ? new Rectangle((Rectangle) bounds) : null);
				break;
			}
			case "openTask":
				ui.openTask(core.task(data.get("task")));
				break;
			case "showTaskCompletion":
			{
				Object points = data.get("points");
				ui.showTaskCompletion(core.task(data.get("task")), points instanceof Number ? ((Number) points).intValue() : 0,
					(String) data.get("region"));
				break;
			}
			case "search":
			{
				Object search = data.get("search");
				lists.search = search == null ? "" : search.toString();
				lists.refresh();
				break;
			}
			case "cards":
				cards(data);
				break;
			case "revealPractice":
				ui.tutorial().revealPractice((String) data.get("id"));
				sendTutorial(false);
				break;
			default:
				break;
		}
	}

	/** ChunkBlazer asking about (or passing on a click to) the task cards. */
	private void cards(Map<String, Object> data)
	{
		TaskCardShow cards = ui.cards();
		Object op = data.get("op");
		if ("isActive".equals(op))
		{
			data.put("r", gameUi && cards.isActive());
		}
		else if ("onClick".equals(op))
		{
			data.put("r", gameUi && cards.onClick(((Number) data.get("x")).intValue(), ((Number) data.get("y")).intValue()));
		}
		else if ("clear".equals(op))
		{
			cards.clear();
		}
		else if ("sweepStarted".equals(op))
		{
			Object duration = data.get("durationMs");
			cards.onSweepStarted(Boolean.TRUE.equals(data.get("test")), duration instanceof Number ? ((Number) duration).longValue() : 0);
		}
		else if ("sweepFinished".equals(op))
		{
			cards.onSweepFinished();
		}
	}

	/**
	 * ChunkBlazer asks the tutorial things many times a frame (is this chunk a practice
	 * unlock?), so it keeps a copy of the tutorial's state, sent here whenever it changes.
	 */
	private synchronized void sendTutorial(boolean always)
	{
		if (!started)
		{
			return;
		}
		Map<String, Object> state = tutorialState();
		if (always || !state.equals(tutorialSent))
		{
			tutorialSent = state;
			eventBus.post(new PluginMessage(ChunkBlazerCore.TO_CORE, "tutorial", new HashMap<>(state)));
		}
	}

	private Map<String, Object> tutorialState()
	{
		Map<String, Object> state = new HashMap<>();
		state.put("unlocked", new HashSet<>(tutorial.practiceUnlockedIds()));
		state.put("preview", tutorial.isPreview());
		state.put("onIsland", tutorial.isOnIsland());
		state.put("holdsUnlock", tutorial.holdsUnlock());
		state.put("quiet", tutorial.isQuiet());
		state.put("cards", new ArrayList<>(tutorial.practiceCards()));
		state.put("completed", new HashSet<>(tutorial.practiceCompleted()));
		List<String> active = new ArrayList<>();
		for (NuzlockeTask task : tutorial.practiceActive())
		{
			active.add(task.getTaskId());
		}
		state.put("active", active);
		Map<String, String> tasks = new HashMap<>();
		Map<String, String> targets = new HashMap<>();
		for (NuzlockeTask task : tutorial.practiceTasks())
		{
			tasks.put(task.getTaskId(), core.toJson(task));
			String target = tutorial.practiceTarget(task.getTaskId());
			if (target != null)
			{
				targets.put(task.getTaskId(), target);
			}
		}
		state.put("tasks", tasks);
		state.put("targets", targets);
		return state;
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (started)
		{
			core.checkStamp();
			sendTutorial(false);
		}
	}

	/** The quest, task source and target data the UI reads (ChunkBlazer keeps its own). */
	private void loadData()
	{
		TaskTargetExtras.load(core.targetExtrasJson(), gson);
		TaskSources.load(gson);
		com.chunkblazerui.pathing.TaskTiles.load(gson);
		quests.load();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			// The next account may differ.
			quests.reset();
		}
		else if (state == GameState.LOGGED_IN && started)
		{
			// ChunkBlazer reloads its catalog at the login screen when there's a newer one.
			TaskTargetExtras.load(core.targetExtrasJson(), gson);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		core.invalidate();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (started)
		{
			lists.loadFilters();
			lists.refresh();
		}
	}

	/** The task filters are shared with ChunkBlazer's side panel: either can change them. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (started && TaskStyle.CONFIG_GROUP.equals(event.getGroup()) && "taskFilters".equals(event.getKey()))
		{
			lists.loadFilters();
			lists.refresh();
		}
	}
}
