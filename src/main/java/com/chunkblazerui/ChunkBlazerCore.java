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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;

/**
 * ChunkBlazer, as ChunkBlazer UI sees it: the account's tasks, chunks and points, and the few
 * things the UI may do (track a task, reveal a card, the tutorial's mode picking).
 *
 * <p>The two are separate plugins, so nothing here touches ChunkBlazer's classes. Each call is
 * a {@link PluginMessage} to ChunkBlazer (RuneLite's plugin-to-plugin channel), which answers
 * it before {@code post} returns. Only plain Java and RuneLite types go across; tasks go as
 * their JSON and are read back into this plugin's own {@link NuzlockeTask}.
 *
 * <p>The overlays ask the same things many times a frame, so answers are kept until something
 * may have changed them: the next game tick, anything the UI itself does, or a change in
 * ChunkBlazer's state stamp (checked once a client tick).
 */
@Singleton
public class ChunkBlazerCore
{
	/** Messages to ChunkBlazer. */
	public static final String TO_CORE = "chunkblazer.core";
	/** Messages from ChunkBlazer. */
	public static final String TO_UI = "chunkblazer.ui";

	/** Plugin Hub's required wording for a 3rd-party-server toggle (ChunkBlazer's Server Sync). */
	public static final String SERVER_SYNC_WARNING = "This feature submits your IP address to a 3rd-party server "
		+ "not controlled or verified by RuneLite developers";

	private static final Object NULL = new Object();

	private final EventBus eventBus;
	private final Gson gson;
	private final Plugin owner;

	// Answers kept until invalidate() (call name + arguments -> answer, NULL for null).
	private final Map<List<Object>, Object> answers = new HashMap<>();
	// Tasks read back from their JSON: this round's, and the last round's (kept while unchanged,
	// so the same task stays the same object and isn't read again). ChunkBlazer sends the same
	// String for a task until it changes, so finding it here is quick.
	private Map<String, NuzlockeTask> tasks = new HashMap<>();
	private Map<String, NuzlockeTask> lastTasks = new HashMap<>();
	private Object stamp;

	@Inject
	ChunkBlazerCore(EventBus eventBus, Gson gson, ChunkBlazerUiPlugin owner)
	{
		this.eventBus = eventBus;
		// Runtime fields (progress, rolled amounts) are transient, and they go across too.
		this.gson = gson.newBuilder().excludeFieldsWithModifiers(Modifier.STATIC).create();
		this.owner = owner;
	}

	// --- The link ----------------------------------------------------------------------

	/** Ask ChunkBlazer something; null when it isn't there to answer. */
	private Object ask(String method, Object... args)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("m", method);
		data.put("a", Arrays.asList(args));
		eventBus.post(new PluginMessage(TO_CORE, "call", data));
		return data.get("r");
	}

	/** {@link #ask}, kept until something may have changed it. */
	private synchronized Object kept(String method, Object... args)
	{
		List<Object> key = new ArrayList<>(args.length + 1);
		key.add(method);
		key.addAll(Arrays.asList(args));
		Object answer = answers.get(key);
		if (answer == null)
		{
			answer = ask(method, args);
			answers.put(key, answer == null ? NULL : answer);
			return answer;
		}
		return answer == NULL ? null : answer;
	}

	/**
	 * {@link #kept}, turned into what the UI uses (tasks read back, a set or list) once, and
	 * kept like that: the overlays ask for these many times a frame, so they share one
	 * read-only copy rather than each making their own.
	 */
	@SuppressWarnings("unchecked")
	private synchronized <T> T keptAs(Function<Object, T> convert, String method, Object... args)
	{
		List<Object> key = new ArrayList<>(args.length + 2);
		key.add(convert);
		key.add(method);
		key.addAll(Arrays.asList(args));
		Object answer = answers.get(key);
		if (answer == null)
		{
			answer = convert.apply(ask(method, args));
			answers.put(key, answer);
		}
		return (T) answer;
	}

	private final Function<Object, List<NuzlockeTask>> toTasks = o -> Collections.unmodifiableList(taskList(o));
	private final Function<Object, Set<Object>> toSet = o -> Collections.unmodifiableSet(set(o));
	private final Function<Object, List<Object>> toList = o -> Collections.unmodifiableList(list(o));

	/** Tell ChunkBlazer to do something: anything kept may be out of date after it. */
	private Object tell(String method, Object... args)
	{
		Object answer = ask(method, args);
		invalidate();
		return answer;
	}

	/** Forget everything kept. */
	public synchronized void invalidate()
	{
		answers.clear();
		// Tasks asked about since the last time carry over; ones that weren't are let go.
		if (!tasks.isEmpty())
		{
			lastTasks = tasks;
			tasks = new HashMap<>();
		}
	}

	/** Once a client tick: forget what's kept if ChunkBlazer's state has moved on. */
	public void checkStamp()
	{
		Object now = ask("stamp");
		if (now == null ? stamp != null : !now.equals(stamp))
		{
			stamp = now;
			invalidate();
		}
	}

	// --- Tasks across the link -----------------------------------------------------------

	/** A task's JSON, the way ChunkBlazer sends it. */
	String toJson(NuzlockeTask task)
	{
		return task == null ? null : gson.toJson(task);
	}

	/** A task read back from ChunkBlazer's JSON. */
	synchronized NuzlockeTask task(Object json)
	{
		if (!(json instanceof String))
		{
			return null;
		}
		String s = (String) json;
		NuzlockeTask task = tasks.get(s);
		if (task == null)
		{
			task = lastTasks.get(s);
			if (task == null)
			{
				task = gson.fromJson(s, NuzlockeTask.class);
			}
			tasks.put(s, task);
		}
		return task;
	}

	private List<NuzlockeTask> taskList(Object jsons)
	{
		List<NuzlockeTask> list = new ArrayList<>();
		if (jsons instanceof Collection)
		{
			for (Object json : (Collection<?>) jsons)
			{
				NuzlockeTask task = task(json);
				if (task != null)
				{
					list.add(task);
				}
			}
		}
		return list;
	}

	private static String id(NuzlockeTask task)
	{
		return task == null ? null : task.getTaskId();
	}

	private static boolean bool(Object o)
	{
		return Boolean.TRUE.equals(o);
	}

	private static int integer(Object o, int otherwise)
	{
		return o instanceof Number ? ((Number) o).intValue() : otherwise;
	}

	private static long longValue(Object o)
	{
		return o instanceof Number ? ((Number) o).longValue() : 0;
	}

	@SuppressWarnings("unchecked")
	private static <T> Set<T> set(Object o)
	{
		return o instanceof Collection ? new LinkedHashSet<>((Collection<T>) o) : new HashSet<>();
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> list(Object o)
	{
		return o instanceof Collection ? new ArrayList<>((Collection<T>) o) : new ArrayList<>();
	}

	// --- Questions ---------------------------------------------------------------------

	public boolean canRollMissing()
	{
		return bool(kept("canRollMissing"));
	}

	public int findRegionForTask(String taskId)
	{
		return integer(kept("findRegionForTask", taskId), -1);
	}

	public List<NuzlockeTask> getActiveTasks()
	{
		return keptAs(toTasks, "getActiveTasks");
	}

	public List<NuzlockeTask> getVisibleGlobalTasks()
	{
		return keptAs(toTasks, "getVisibleGlobalTasks");
	}

	public Rectangle getBossOrbBounds()
	{
		Object r = kept("getBossOrbBounds");
		return r instanceof Rectangle ? new Rectangle((Rectangle) r) : null;
	}

	@SuppressWarnings("unchecked")
	public Set<String> getCompletedTaskIdSet()
	{
		return (Set<String>) (Set<?>) keptAs(toSet, "getCompletedTaskIdSet");
	}

	public int getCurrentRegionId()
	{
		return integer(kept("getCurrentRegionId"), -1);
	}

	public long getLastUnlockChangeMs()
	{
		return longValue(kept("getLastUnlockChangeMs"));
	}

	@SuppressWarnings("unchecked")
	public Set<Integer> getNeighborRegionIds()
	{
		return (Set<Integer>) (Set<?>) keptAs(toSet, "getNeighborRegionIds");
	}

	@SuppressWarnings("unchecked")
	public Set<String> getUnlockedRegionIds()
	{
		return (Set<String>) (Set<?>) keptAs(toSet, "getUnlockedRegionIds");
	}

	public String getPendingVerificationCode()
	{
		return (String) kept("getPendingVerificationCode");
	}

	public String getPracticeTarget(String taskId)
	{
		return (String) kept("getPracticeTarget", taskId);
	}

	public String getRegionName(int regionId)
	{
		return (String) kept("getRegionName", regionId);
	}

	public NuzlockeTask getSelectedTask()
	{
		return task(kept("getSelectedTask"));
	}

	public NuzlockeTask getTaskById(String taskId)
	{
		return task(kept("getTaskById", taskId));
	}

	public Point getTasksOrbCentre()
	{
		Object p = kept("getTasksOrbCentre");
		return p instanceof Point ? new Point((Point) p) : null;
	}

	public int getTotalPoints()
	{
		return integer(kept("getTotalPoints"), 0);
	}

	@SuppressWarnings("unchecked")
	public List<String> getUnrevealedTaskIds()
	{
		return (List<String>) (List<?>) keptAs(toList, "getUnrevealedTaskIds");
	}

	public String getUnrevealedTasksRaw()
	{
		return (String) kept("getUnrevealedTasksRaw");
	}

	public boolean isFreeRegion(int regionId)
	{
		return bool(kept("isFreeRegion", regionId));
	}

	public boolean isModeLocked()
	{
		return bool(kept("isModeLocked"));
	}

	public boolean isOnTutorialIsland()
	{
		return bool(kept("isOnTutorialIsland"));
	}

	public boolean isPlayOffline()
	{
		return bool(kept("isPlayOffline"));
	}

	public boolean isRegionUnlocked(int regionId)
	{
		return bool(kept("isRegionUnlocked", regionId));
	}

	public boolean isServerSyncEnabled()
	{
		return bool(kept("isServerSyncEnabled"));
	}

	public boolean isTaskCardsOn()
	{
		return bool(kept("isTaskCardsOn"));
	}

	public int countPayableUnlockedChunks()
	{
		return integer(kept("countPayableUnlockedChunks"), 0);
	}

	public int getRegionUnlockCost(int regionId)
	{
		return integer(kept("getRegionUnlockCost", regionId), 0);
	}

	/** Points cost of the r-th paid chunk unlock. */
	public int curveUnlockCost(int rank)
	{
		return integer(kept("curveUnlockCost", rank), 1);
	}

	public boolean isBossRegion(int regionId)
	{
		return bool(kept("isBossRegion", regionId));
	}

	public boolean isBossTask(NuzlockeTask task)
	{
		return task != null && bool(kept("isBossTask", id(task)));
	}

	public boolean isCharterRegion(int regionId)
	{
		return bool(kept("isCharterRegion", regionId));
	}

	public boolean isFreeUnlockableRegion(int regionId)
	{
		return bool(kept("isFreeUnlockableRegion", regionId));
	}

	public boolean isKnownRegion(int regionId)
	{
		return bool(kept("isKnownRegion", regionId));
	}

	public boolean isGlobalTask(String taskId)
	{
		return bool(kept("isGlobalTask", taskId));
	}

	public String getTaskArea(NuzlockeTask task)
	{
		return task == null ? null : (String) kept("getTaskArea", id(task));
	}

	public String getTaskRegionName(NuzlockeTask task)
	{
		return task == null ? null : (String) kept("getTaskRegionName", id(task));
	}

	public boolean meetsLevelRequirement(NuzlockeTask task)
	{
		return task != null && bool(kept("meetsLevelRequirement", id(task)));
	}

	/** What a chunk is to this account (unlocked, unlockable, a boss chunk...). */
	public ChunkUnlockType unlockType(int regionId, boolean unlocked, boolean neighbor)
	{
		return ChunkUnlockType.of(this, regionId, unlocked, neighbor);
	}

	/** What unlocking a chunk costs, for a chunk of {@code type}. */
	public String costLabel(int regionId, ChunkUnlockType type)
	{
		return ChunkUnlockType.costLabel(this, regionId, type);
	}

	/** Whether the player has the levels (and quests) for a task yet. */
	public boolean canDo(Client client, NuzlockeTask task)
	{
		return TaskTargetExtras.canDo(client, this, task);
	}

	/** "(20 Defence, 20 Ranged)": every level the player is missing for a task. */
	public String levelNote(Client client, NuzlockeTask task)
	{
		return TaskTargetExtras.levelNote(client, this, task);
	}

	/** The game tick the fight with a task's NPC began, or -1. */
	public int getActiveFightStartTick(NuzlockeTask task)
	{
		return task == null ? -1 : integer(kept("getActiveFightStartTick", id(task)), -1);
	}

	/**
	 * A boss challenge task's attempt: "1|" while one is going, "0|" when not, with what
	 * broke it after the bar; null before any.
	 */
	public String challengeStatus(NuzlockeTask task)
	{
		Object status = task == null ? null : kept("challengeStatus", id(task));
		return status == null ? null : status.toString();
	}

	/** The plugin the UI's overlays belong to (RuneLite's Overlay(Plugin)). */
	public Plugin owner()
	{
		return owner;
	}

	/** The catalog's _Target_Extras.json (name aliases, extra NPC/object ids, level needs), or null. */
	public String targetExtrasJson()
	{
		Object json = ask("targetExtrasJson");
		return json instanceof String ? (String) json : null;
	}

	// --- ChunkBlazer's settings the UI goes by ----------------------------------------------

	public boolean showTaskCompletionPopup()
	{
		return bool(kept("setting", "showTaskCompletionPopup"));
	}

	public boolean showWorldMapChunks()
	{
		return bool(kept("setting", "showWorldMapChunks"));
	}

	public Keybind worldMapTasksKey()
	{
		Object key = kept("setting", "worldMapTasksKey");
		return key instanceof Keybind ? (Keybind) key : Keybind.NOT_SET;
	}

	/** The world map's fill for a kind of chunk (its colours are ChunkBlazer's settings). */
	public Color chunkFill(ChunkUnlockType type)
	{
		Object c = kept("chunkFill", type.name());
		return c instanceof Color ? (Color) c : type.fill;
	}

	// --- ChunkBlazer's world map ------------------------------------------------------------

	public int getHoveredRegionId()
	{
		return integer(ask("getHoveredRegionId"), -1);
	}

	/** Point out a chunk on the world map. */
	public void focusRegion(int regionId)
	{
		ask("focusRegion", regionId);
	}

	/** ChunkBlazer's world map tooltip, drawn on top of the legend. */
	public void drawMapTooltip(Graphics2D graphics)
	{
		ask("drawMapTooltip", graphics);
	}

	// --- Things the UI does -------------------------------------------------------------------

	public void cancelPendingCompetitive()
	{
		tell("cancelPendingCompetitive");
	}

	public void clearSelectedTask()
	{
		tell("clearSelectedTask");
	}

	/** The task window's own pick, as if it had been picked in game. */
	public void selectTaskFromGame(NuzlockeTask task)
	{
		if (task != null)
		{
			tell("selectTaskFromGame", id(task), toJson(task));
		}
	}

	/** The task complete sound for where you are, if it's switched on (the popup's Test). */
	public void playTaskCompleteSound()
	{
		ask("playTaskCompleteSound");
	}

	public void leftTutorialIsland()
	{
		tell("leftTutorialIsland");
	}

	public void lockCompetitive()
	{
		tell("lockCompetitive");
	}

	public void lockGameMode(GameMode mode)
	{
		tell("lockGameMode", mode.name());
	}

	public void refreshPanel()
	{
		tell("refreshPanel");
	}

	public void revealTaskCard(String taskId)
	{
		tell("revealTaskCard", taskId);
	}

	public void showChunkView(boolean show)
	{
		tell("showChunkView", show);
	}

	public void showPracticeComplete(NuzlockeTask task)
	{
		tell("showPracticeComplete", toJson(task));
	}

	public void choosePlayOffline()
	{
		tell("choosePlayOffline");
	}

	public void enableServerSync()
	{
		tell("enableServerSync");
	}

	/** The side panel takes the UI's theme (after a Theme change), or its own look (null). */
	public void applyPanelTheme(ThemePalette palette)
	{
		ask("applyPanelTheme", palette == null ? null : palette.toMap());
	}

	/** The side panel's "show all": no search, filters or chunk limit. */
	public void sidePanelShowAll()
	{
		tell("sidePanelShowAll");
	}

	/** The side panel's task search, from the task window's. */
	public void setSidePanelSearch(String text)
	{
		ask("setSidePanelSearch", text);
	}

	/** The task filters (shared with the side panel) changed. */
	public void sidePanelChanged()
	{
		tell("sidePanelChanged");
	}

	/** The tutorial's fence round the start room (x1, y1, x2, y2), or null for none. */
	public void setFence(int[] box)
	{
		ask("setFence", (Object) box);
	}

	/** The tutorial's fence falls, from (x, y). */
	public void dropFence(double x, double y)
	{
		ask("dropFence", x, y);
	}
}
