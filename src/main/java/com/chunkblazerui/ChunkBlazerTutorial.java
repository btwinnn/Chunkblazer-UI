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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/**
 * The ChunkBlazer tutorial for new players: what chunks, unlocking, tasks and Boss Tokens
 * are, choosing a mode, then the game's own interface.
 *
 * Starting on Tutorial Island, the island is shown as locked chunks, the first one is
 * unlocked as practice (and every island chunk walked into after it), and some practice
 * tasks worth 0 points follow the island's own steps. They go through the real task UI:
 * dealt as cards, then in the task window and the task box, as the plugin hands them out
 * in place of real tasks while the island is shown (see ChunkBlazer's isTutorialPreview). The last one is talking to the
 * Iron Man tutor, then a mode picker: Casual or Competitive. That choice is kept until the
 * player reaches the mainland, then locked in with the plugin's usual mode lock. Group
 * ironmen get a word about groups on The Node. Turning the plugin on anywhere else starts
 * at the Lumbridge version, which picks a mode straight away.
 *
 * Practice unlocks and tasks are local and for show only: never saved as account progress or synced.
 * All the wording is in tutorial.txt. Progress is kept per account, so it carries on after
 * a logout; it can be replayed from the task window's Settings tab.
 */
@Singleton
public class ChunkBlazerTutorial extends Overlay implements TutorialHooks
{
	private static final String GROUP = "chunkblazer";
	// Tutorial Island's regions (RuneLite's Discord plugin uses the same list).
	private static final Set<Integer> ISLAND = Set.of(12079, 12080, 12335, 12336, 12436, 12592);
	// Unlocked along with the first island chunk, on leaving the Gielinor Guide's house.
	private static final int COOK_CHUNK = 12080;
	// The island's progress (VarPlayerID.TUTORIAL) once you've left it.
	private static final int ISLAND_DONE = 1000;
	// Game ticks (about 30 seconds) a step that waits for the player can wait before it offers
	// "Skip this step", so nobody's stuck on one they can't do.
	private static final int STUCK_TICKS = 50;
	// Game ticks the first chunk's unlock gets to play before the first box shows.
	private static final int UNLOCK_SETTLE_TICKS = 5;
	// Reaching the combat area: time to mention bosses.
	private static final int ISLAND_COMBAT = 370;
	private static Color TITLE = new Color(255, 152, 31);
	private static final Color BODY = new Color(230, 220, 200);
	private static final Color DONE = new Color(120, 220, 120);
	private static Color DIM = new Color(150, 140, 120);
	private static final Color RED = new Color(255, 110, 110);
	private static final int WIDTH = 300;
	private static final int PAD = 10;

	// The steps of each start, in order. A step with no button in tutorial.txt waits for
	// the player (see waitDone). "group" only shows for group ironmen, "cards" only when
	// tasks have just been dealt.
	// The first chunk unlocks once the practice cards are dealt and the first one's tracked, so
	// nobody's off across the island before they've seen them.
	private static final List<String> ISLAND_FLOW = Arrays.asList("welcome", "tasks", "deal", "iorb", "itrack", "iclose", "itaskshow", "iassist",
		"iunlock",
		"imap", "map", "walls", "practice", "leave", "arrive", "sync", "mode", "verify", "home");
	// Tutorial Island's progress (VarPlayerID.TUTORIAL) from walking into the Quest Guide's
	// house until heading down the ladder to the mines: when the themes are shown.
	private static final int QUEST_GUIDE = 220;
	private static final int QUEST_GUIDE_LEFT = 250;
	// (The first chunk unlocks on leaving the start room; there's no step for it.)
	private static final List<String> LUMBRIDGE_FLOW = Arrays.asList("lwelcome", "lunlock", "ltasks", "ltokens", "group", "sync", "mode",
		"verify");
	// The task box's book archives the tracked task; the next steps bring that same task back
	// from the Archived list, so nothing's left archived by accident.
	private static final List<String> TOUR = Arrays.asList("cards", "orb", "track", "close", "taskshow", "assist", "tracker", "boxmode", "boxbook",
		"archived", "unarchive", "newtab", "filters", "settings", "save", "sclose", "boss", "strack",
		"unlocking", "panel", "done");
	// Steps done in the task window: their box sits over the chat box instead.
	private static final Set<String> OVER_CHAT = Set.of("itrack", "track", "save", "archived", "unarchive", "newtab",
		"filters", "settings");
	// Tour steps the island already settled: where the tracked task shows, and the assists.
	// Opening the list and tracking a task run again on the mainland, with real tasks, since
	// the task box steps after them need a tracked task.
	private static final Set<String> SHOWN_ON_ISLAND = Set.of("taskshow", "assist");

	@javax.inject.Inject
	private TaskArchive archive;
	// The task box's look when "boxmode" began, the archived tasks when "boxbook" began, and
	// the one archived then (for "unarchive" to point at).
	private TaskTrackerStyle styleBefore;
	private Set<String> archivedBefore = Collections.emptySet();
	private String archivedTask;
	// The task tracked when "strack" began: tracking a saved one moves it on.
	private String trackedBefore;
	// Replaying from Settings: the whole tour, even the steps the island showed.
	private boolean replaying;

	private final Client client;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final ConfigManager configManager;
	private final ChunkBlazerCore plugin;
	private final ChunkBlazerUiConfig config;
	private final TaskWindow browser;
	private final TaskLists lists;
	private final TaskPaneOverlay pane;
	private final TaskCardOverlay cards;
	private final SelectedTaskOverlay taskBox;
	private final SavedTaskTracker savedButton;

	// Step id -> {title, body, button or null}.
	private final Map<String, String[]> text = new HashMap<>();
	// The practice tasks, and the island's progress once each is done (0: the Iron Man tutor).
	private final List<NuzlockeTask> practiceTasks = new ArrayList<>();
	private final Map<String, Integer> practiceAt = new HashMap<>();
	// What each practice task outlines in game ("npc:Chicken", "object:Tree"), when the plugin's
	// own outlines don't cover it.
	private final Map<String, String> practiceTargets = new HashMap<>();
	// Practice tasks still face down as cards, and the ones flipped into the task list.
	private final List<String> dealt = new CopyOnWriteArrayList<>();
	private final Set<String> revealed = ConcurrentHashMap.newKeySet();

	// Per account, loaded on the first tick an account is known (null until then).
	private volatile String step;
	// A one-off message shown over the step it interrupted, which carries on after it.
	private volatile String aside;
	private final Set<Integer> practice = new LinkedHashSet<>();
	private final Set<String> seen = new LinkedHashSet<>();
	// On Tutorial Island (its progress below ISLAND_DONE), as of the last tick.
	private volatile boolean onIsland;
	// What ChunkBlazer GPU was last told about the locked view (null: not yet this login).
	private Boolean previewWritten;
	private volatile boolean showingChunks;
	private int stepTicks;
	// How many tasks were saved when the "save" step began.
	private Set<String> savedBefore = new HashSet<>();
	// The step's cards have been on screen (they can wait on an unlock's sweep before dealing).
	private boolean sawCards;
	// True while the name or character design screen is up: everything waits for it.
	private volatile boolean settingUp;
	// The Gielinor Guide's house a new account starts in, as x1,y1,x2,y2 world tiles (from
	// tutorial.txt): kept clear of grey, and the tutorial starts on leaving it.
	private int[] startRoom = {3086, 3099, 3097, 3114};
	// Its tiles (world x << 16 | y), found on the first look this login; empty when it couldn't be.
	private Set<Integer> startTiles;
	// More than this and it isn't a room: a door's open, or it's outdoors.
	private static final int MAX_ROOM_TILES = 600;
	// What ChunkBlazer GPU was last told to keep clear ("" for nothing; null: not yet).
	private String clearWritten;
	// The low flame wall round the start room is up.
	private boolean fenced;
	private int[] fenceBox;
	// The mainland setup's steps, Lumbridge's square round its spawn (from tutorial.txt), which
	// of the two squares is in use, and how long it stays clear once Lumbridge unlocks.
	private static final Set<String> MAINLAND_SETUP = Set.of("arrive", "sync", "mode", "verify", "home");
	private int[] lumbridgeSquare = {3218, 3214, 3226, 3222};
	private int[] clearBox = lumbridgeSquare;
	private long clearUntil;
	// No box until then: a practice task's completion popup is playing.
	private volatile long quietUntil;
	private int island;
	// The account type (VarbitID.IRONMAN): 1-3 ironmen, 4-6 group ironmen. Read each tick, as
	// the picker's buttons run on the Swing thread.
	private volatile int accountType;
	private int practiceDone;

	// What a click hits, from the last frame drawn.
	private volatile List<Hit> hits = new ArrayList<>();
	private volatile Rectangle shown;

	private static final class Hit
	{
		final Rectangle area;
		final Runnable action;

		Hit(Rectangle area, Runnable action)
		{
			this.area = area;
			this.action = action;
		}
	}

	/** Clicks on the tutorial box: its buttons, and nothing behind it. */
	final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			Rectangle box = shown;
			if (box == null || !box.contains(event.getPoint()))
			{
				return event;
			}
			if (event.getButton() == MouseEvent.BUTTON1)
			{
				for (Hit hit : hits)
				{
					if (hit.area.contains(event.getPoint()))
					{
						hit.action.run();
						break;
					}
				}
			}
			event.consume();
			return event;
		}
	};

	@Inject
	ChunkBlazerTutorial(Client client, ClientThread clientThread, DrawManager drawManager, ConfigManager configManager, ChunkBlazerCore plugin,
		ChunkBlazerUiConfig config, TaskWindow browser, TaskLists lists, TaskPaneOverlay pane, TaskCardOverlay cards,
		SelectedTaskOverlay taskBox, SavedTaskTracker savedButton)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.drawManager = drawManager;
		this.configManager = configManager;
		this.plugin = plugin;
		this.config = config;
		this.browser = browser;
		this.lists = lists;
		this.pane = pane;
		this.cards = cards;
		this.taskBox = taskBox;
		this.savedButton = savedButton;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// Over the task window and cards, which share this layer, so boxes and arrows stay on top.
		setPriority(PRIORITY_HIGHEST);
		loadText();
	}

	/** True while a practice unlock covers this chunk (Tutorial Island only). */
	@Override
	public boolean isPracticeUnlocked(int regionId)
	{
		return island < ISLAND_DONE && practice.contains(regionId);
	}

	/** The practice chunks shown unlocked (for ChunkBlazer's copy of isPracticeUnlocked). */
	Set<Integer> practiceUnlockedIds()
	{
		return island < ISLAND_DONE ? new HashSet<>(practice) : Collections.emptySet();
	}

	/** Every practice task, dealt or not. */
	List<NuzlockeTask> practiceTasks()
	{
		return new ArrayList<>(practiceTasks);
	}

	/**
	 * True while the island is shown as chunks before a mode is picked: everything reads as
	 * locked then (Lumbridge and the free chunks too) except the practice chunks.
	 */
	@Override
	public boolean isPreview()
	{
		return showingChunks;
	}

	/** The practice tasks flipped into the task list (finished ones included, like real tasks). */
	@Override
	public List<NuzlockeTask> practiceActive()
	{
		List<NuzlockeTask> active = new ArrayList<>();
		for (NuzlockeTask task : practiceTasks)
		{
			if (revealed.contains(task.getTaskId()))
			{
				active.add(task);
			}
		}
		return active;
	}

	@Override
	public List<String> practiceCards()
	{
		return new ArrayList<>(dealt);
	}

	@Override
	public Set<String> practiceCompleted()
	{
		Set<String> done = new LinkedHashSet<>();
		practiceTasks.stream().filter(NuzlockeTask::isCompleted).forEach(task -> done.add(task.getTaskId()));
		return done;
	}

	/** A practice task by id, or null for any other task. */
	@Override
	public NuzlockeTask practiceTask(String id)
	{
		for (NuzlockeTask task : practiceTasks)
		{
			if (task.getTaskId().equals(id))
			{
				return task;
			}
		}
		return null;
	}

	/** What a practice task outlines ("npc:..." or "object:..."), or null. */
	@Override
	public String practiceTarget(String id)
	{
		return practiceTargets.get(id);
	}

	/** A practice card flipped: into the task list. */
	@Override
	public void revealPractice(String id)
	{
		if (dealt.remove(id))
		{
			revealed.add(id);
			saveCards();
		}
	}

	/** On Tutorial Island: the side panel holds the mode picker back until the mainland. */
	@Override
	public boolean isOnIsland()
	{
		return onIsland;
	}

	/**
	 * While the tutorial sets the account up (sync, mode, its checks), a locked-in mode waits
	 * for the "Unlock Lumbridge" button before the game starts, so that unlock plays out
	 * like any other: from where you stand.
	 */
	@Override
	public boolean holdsUnlock()
	{
		return isRunning() && !TOUR.contains(step);
	}

	boolean isRunning()
	{
		return step != null && !step.equals("over");
	}

	/** While it runs (or hasn't loaded yet), the plugin's own "pick a mode" reminders stay quiet. */
	@Override
	public boolean isQuiet()
	{
		return step == null || isRunning();
	}

	// --- Progress -----------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			// Another account may log in next: load afresh.
			step = null;
			aside = null;
			practice.clear();
			seen.clear();
			dealt.clear();
			revealed.clear();
			showChunks(false);
			onIsland = false;
			previewWritten = null;
			clearWritten = null;
			setFence(null);
			fenced = false;
			startTiles = null;
		}
	}

	static
	{
		Skin.onRetheme(ChunkBlazerTutorial::retheme);
	}

	/** The theme's colours for the tutorial box (see Skin.setPalette). */
	private static void retheme(ThemePalette p)
	{
		TITLE = p.title;
		DIM = p.label;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player me = client.getLocalPlayer();
		// Not waiting for a name: a new account is greyed out from the moment it loads in.
		if (me == null || configManager.getRSProfileKey() == null)
		{
			return;
		}
		island = client.getVarpValue(VarPlayerID.TUTORIAL);
		accountType = client.getVarbitValue(VarbitID.IRONMAN);
		boolean onIsland = island < ISLAND_DONE;
		if (onIsland != this.onIsland)
		{
			// The side panel only offers the modes off the island.
			this.onIsland = onIsland;
			plugin.refreshPanel();
			if (!onIsland)
			{
				plugin.leftTutorialIsland();
			}
			NuzlockeTask tracked = plugin.getSelectedTask();
			if (!onIsland && tracked != null && practiceTask(tracked.getTaskId()) != null)
			{
				plugin.clearSelectedTask();
			}
		}
		// The island shows as chunks from the first moment, tutorial or not, so the maps are
		// already locked the first time they're opened. Players who started there keep that
		// view until they pick a mode in Lumbridge, which unlocks it as usual.
		showChunks((!plugin.isModeLocked() || holdsUnlock()) && (onIsland || seen.contains("island")));
		// The island part starts on walking out of the Gielinor Guide's house (name, look and
		// his first chat done) with the first box. The chunk you're in stays locked, fence and
		// all, until its box's button unlocks it ("iunlock"), so the cards come before you
		// wander off.
		if (onIsland && !metGuide() && !inStartRoom(me))
		{
			configManager.setRSProfileConfiguration(GROUP, "tutorialGuide", true);
		}
		// On the mainland, while the account's set up (shown locked, or pretend while testing):
		// a square round Lumbridge's spawn stays clear and fenced, like the island's house.
		boolean mainland = !onIsland && isRunning() && MAINLAND_SETUP.contains(step) && isPreview();
		int[] box = onIsland ? startRoom : lumbridgeSquare;
		boolean islandFenced = onIsland && !firstUnlocked();
		if ((islandFenced || mainland) && !fenced)
		{
			setFence(box.clone());
			fenced = true;
		}
		else if (fenced && !islandFenced && !mainland)
		{
			setFence(null);
			fenced = false;
		}
		// The house stays clear for the rest of the island (the first chunk unlocking around it
		// leaves it alone), while it's known: found on this login, from inside it. Lumbridge's
		// square stays clear through its setup and a little after, while its unlock plays.
		clearBox = box;
		setClearArea(onIsland ? !plugin.isModeLocked() && (!metGuide() || startTiles != null && !startTiles.isEmpty())
			: mainland || System.currentTimeMillis() < clearUntil);
		settingUp = onIsland && !metGuide();
		if (settingUp)
		{
			return;
		}
		if (step == null && !load())
		{
			return;
		}
		showChunks((!plugin.isModeLocked() || holdsUnlock()) && (onIsland || seen.contains("island")));
		if (!isRunning())
		{
			return;
		}
		stepTicks++;
		int region = me.getWorldLocation().getRegionID();

		// After the first practice unlock, every island chunk walked into unlocks too.
		if (onIsland && ISLAND.contains(region) && !practice.isEmpty() && !practice.contains(region)
			&& !plugin.isFreeRegion(region))
		{
			practiceUnlock(region);
			interrupt("newchunk");
		}
		if (ISLAND_FLOW.indexOf(step) >= ISLAND_FLOW.indexOf("deal"))
		{
			if (onIsland && island >= QUEST_GUIDE && island < QUEST_GUIDE_LEFT)
			{
				interrupt("themes");
			}
			if (onIsland && island >= ISLAND_COMBAT)
			{
				interrupt("tokens");
			}
			if (!onIsland && onTheNode())
			{
				interrupt("node");
			}
		}
		String talking = chatName();
		if (!talking.isEmpty())
		{
			talkedTo(talking);
		}
		countPractice();
		if ("practice".equals(step))
		{
			trackNextPractice();
		}
		if (waitDone(onIsland))
		{
			next();
		}
	}

	/** The first island chunk unlocked yet (its box's button, or on leaving the house before). */
	private boolean firstUnlocked()
	{
		return !split(configManager.getRSProfileConfiguration(GROUP, "tutorialChunks")).isEmpty();
	}

	/** Out of the start room yet: the island part has begun. */
	private boolean metGuide()
	{
		return configManager.getRSProfileConfiguration(GROUP, "tutorialGuide") != null;
	}

	private boolean inStartRoom(Player me)
	{
		net.runelite.api.coords.WorldPoint at = me.getWorldLocation();
		if (startTiles == null)
		{
			findStartRoom(me);
		}
		if (!startTiles.isEmpty())
		{
			return startTiles.contains(at.getX() << 16 | at.getY());
		}
		return at.getX() >= startRoom[0] && at.getY() >= startRoom[1] && at.getX() <= startRoom[2] && at.getY() <= startRoom[3];
	}

	/**
	 * The room a new account starts in, found by walking out from where it stands until walls
	 * and closed doors stop it, so it's the real room whatever its shape: its tiles (furniture
	 * and all) are what ChunkBlazer GPU keeps clear, and walking off them starts the tutorial. Anything big enough to be outdoors
	 * (a door left open) falls back to the rectangle in tutorial.txt.
	 */
	private void findStartRoom(Player me)
	{
		startTiles = new HashSet<>();
		WorldView wv = client.getTopLevelWorldView();
		LocalPoint here = me.getLocalLocation();
		CollisionData[] maps = wv.getCollisionMaps();
		if (here == null || maps == null || maps[wv.getPlane()] == null)
		{
			return;
		}
		int[][] flags = maps[wv.getPlane()].getFlags();
		Set<Integer> room = new HashSet<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{here.getSceneX(), here.getSceneY()});
		room.add(here.getSceneX() << 16 | here.getSceneY());
		int[][] steps = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
		while (!queue.isEmpty() && room.size() < MAX_ROOM_TILES)
		{
			int[] at = queue.poll();
			for (int[] step : steps)
			{
				int x = at[0] + step[0];
				int y = at[1] + step[1];
				if (x >= 0 && y >= 0 && x < flags.length && y < flags[x].length && canStep(flags, at[0], at[1], step)
					&& room.add(x << 16 | y))
				{
					queue.add(new int[]{x, y});
				}
			}
		}
		if (room.size() >= MAX_ROOM_TILES)
		{
			return;
		}
		// Furniture and angled walls: solid tiles reached without going through a wall.
		Set<Integer> solid = new HashSet<>();
		for (int tile : room)
		{
			for (int[] step : steps)
			{
				int x = (tile >> 16) + step[0];
				int y = (tile & 0xFFFF) + step[1];
				if (x >= 0 && y >= 0 && x < flags.length && y < flags[x].length && !room.contains(x << 16 | y)
					&& wallFree(flags, tile >> 16, tile & 0xFFFF, step))
				{
					solid.add(x << 16 | y);
				}
			}
		}
		room.addAll(solid);
		fillHoles(room, steps);
		int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int tile : room)
		{
			int x = wv.getBaseX() + (tile >> 16);
			int y = wv.getBaseY() + (tile & 0xFFFF);
			startTiles.add(x << 16 | y);
			bounds[0] = Math.min(bounds[0], x);
			bounds[1] = Math.min(bounds[1], y);
			bounds[2] = Math.max(bounds[2], x);
			bounds[3] = Math.max(bounds[3], y);
		}
		// A square round the house: one tile out, for its walls.
		startRoom = new int[]{bounds[0] - 1, bounds[1] - 1, bounds[2] + 1, bounds[3] + 1};
	}

	/** Fill the room's holes (the middle of a big table): tiles in its bounds the outside can't reach. */
	private static void fillHoles(Set<Integer> room, int[][] steps)
	{
		int x1 = Integer.MAX_VALUE;
		int y1 = Integer.MAX_VALUE;
		int x2 = Integer.MIN_VALUE;
		int y2 = Integer.MIN_VALUE;
		for (int tile : room)
		{
			x1 = Math.min(x1, tile >> 16);
			y1 = Math.min(y1, tile & 0xFFFF);
			x2 = Math.max(x2, tile >> 16);
			y2 = Math.max(y2, tile & 0xFFFF);
		}
		// Flood the outside from just beyond the bounds.
		Set<Integer> outside = new HashSet<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		queue.add(new int[]{x1 - 1, y1 - 1});
		outside.add(x1 - 1 << 16 | y1 - 1);
		while (!queue.isEmpty())
		{
			int[] at = queue.poll();
			for (int[] step : steps)
			{
				int x = at[0] + step[0];
				int y = at[1] + step[1];
				if (x >= x1 - 1 && y >= y1 - 1 && x <= x2 + 1 && y <= y2 + 1 && !room.contains(x << 16 | y)
					&& outside.add(x << 16 | y))
				{
					queue.add(new int[]{x, y});
				}
			}
		}
		for (int x = x1; x <= x2; x++)
		{
			for (int y = y1; y <= y2; y++)
			{
				if (!outside.contains(x << 16 | y))
				{
					room.add(x << 16 | y);
				}
			}
		}
	}

	/**
	 * What ChunkBlazer GPU keeps clear, for tutorialClearArea: the square round the start room,
	 * as its corner tile then 32 rows of 32 bits (bit x of row y set for tile corner + (x, y)).
	 */
	private String clearMask()
	{
		int ox = clearBox[0];
		int oy = clearBox[1];
		int[] rows = new int[32];
		for (int y = 0; y <= Math.min(31, clearBox[3] - oy); y++)
		{
			for (int x = 0; x <= Math.min(31, clearBox[2] - ox); x++)
			{
				rows[y] |= 1 << x;
			}
		}
		StringBuilder out = new StringBuilder().append(ox).append(',').append(oy);
		for (int row : rows)
		{
			out.append(',').append(row);
		}
		return out.toString();
	}

	/** Whether a step from scene tile (x, y) is open: no wall on the way, nothing solid there. */
	private static boolean canStep(int[][] flags, int x, int y, int[] step)
	{
		return wallFree(flags, x, y, step) && (flags[x + step[0]][y + step[1]] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	/** Whether there's no wall between scene tile (x, y) and the next one along {@code step}. */
	private static boolean wallFree(int[][] flags, int x, int y, int[] step)
	{
		int out;
		int in;
		if (step[1] == 1)
		{
			out = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
			in = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
		}
		else if (step[1] == -1)
		{
			out = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
			in = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
		}
		else if (step[0] == 1)
		{
			out = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
			in = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
		}
		else
		{
			out = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
			in = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
		}
		return (flags[x][y] & out) == 0 && (flags[x + step[0]][y + step[1]] & in) == 0;
	}

	/**
	 * The fence round the start room or Lumbridge's square (null: none): the plugin's own low
	 * wall, and ChunkBlazer GPU's flame wall, which reads it as tutorialFence ("x1,y1,x2,y2").
	 */
	private void setFence(int[] box)
	{
		fenceBox = box;
		plugin.setFence(box);
		if (box == null)
		{
			configManager.unsetRSProfileConfiguration(GROUP, "tutorialFence");
		}
		else
		{
			configManager.setRSProfileConfiguration(GROUP, "tutorialFence", box[0] + "," + box[1] + "," + box[2] + "," + box[3]);
		}
	}

	/** The fence falls in a wave from (x, y): for ChunkBlazer GPU, "...,when,x,y" on the end. */
	private void dropFence(double x, double y)
	{
		plugin.dropFence(x, y);
		int[] box = fenceBox;
		if (box != null)
		{
			configManager.setRSProfileConfiguration(GROUP, "tutorialFence", box[0] + "," + box[1] + "," + box[2] + ","
				+ box[3] + "," + System.currentTimeMillis() + "," + x + "," + y);
		}
	}

	/** Tell ChunkBlazer GPU to keep the start room clear of grey, or to stop. */
	private void setClearArea(boolean clear)
	{
		String area = clear ? clearMask() : "";
		if (!area.equals(clearWritten))
		{
			clearWritten = area;
			if (clear)
			{
				configManager.setRSProfileConfiguration(GROUP, "tutorialClearArea", area);
			}
			else
			{
				configManager.unsetRSProfileConfiguration(GROUP, "tutorialClearArea");
			}
		}
	}

	/**
	 * A step's cards dealt and put away. They can wait a few seconds for an unlock's sweep
	 * before dealing, so wait for them to show, or give up after about 15 seconds (cards off).
	 */
	private boolean cardsDone()
	{
		sawCards |= cards.isActive();
		return !cards.isActive() && (sawCards || stepTicks > 25);
	}

	/** Every task the lists hold, active and global. */
	private Set<String> taskIds()
	{
		Set<String> ids = new HashSet<>();
		for (NuzlockeTask task : lists.pool(true))
		{
			ids.add(task.getTaskId());
		}
		return ids;
	}

	/** A task saved since the last look: starred again counts, even one saved before. */
	private boolean savedJustNow()
	{
		Set<String> now = lists.savedIdsCopy();
		boolean added = !savedBefore.containsAll(now);
		savedBefore = now;
		return added;
	}

	/** Who's talking in the chat box (letters only), or "". */
	private String chatName()
	{
		Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
		return name == null || name.isHidden() ? "" : letters(name.getText());
	}

	/** The game's "Click here to continue" in the chat box, while one's showing, or null. */
	private Rectangle clickToContinue()
	{
		Widget chat = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (chat == null || chat.isHidden())
		{
			return null;
		}
		Widget found = findContinue(chat, 0);
		return found == null ? null : found.getBounds();
	}

	private static Widget findContinue(Widget widget, int depth)
	{
		if (widget == null || widget.isHidden() || depth > 8)
		{
			return null;
		}
		String text = widget.getText();
		if (text != null && Text.removeTags(text).toLowerCase().contains("click here to continue"))
		{
			return widget;
		}
		for (Widget[] children : new Widget[][]{widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				Widget hit = findContinue(child, depth + 1);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		return null;
	}

	private boolean isMapOpen()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map != null && !map.isHidden();
	}

	/**
	 * The world map's close button: of the parts of its window offering "Close", the one
	 * nearest the window's top right corner, where the X sits.
	 */
	private Rectangle mapCloseButton()
	{
		int group = InterfaceID.Worldmap.MAP_CONTAINER >>> 16;
		Widget root = client.getWidget(group << 16);
		if (root == null)
		{
			return null;
		}
		Rectangle window = root.getBounds();
		Rectangle best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int child = 0; child < 64; child++)
		{
			Widget w = client.getWidget(group << 16 | child);
			if (w == null || w.isHidden())
			{
				continue;
			}
			List<Widget> parts = new ArrayList<>();
			parts.add(w);
			if (w.getDynamicChildren() != null)
			{
				parts.addAll(Arrays.asList(w.getDynamicChildren()));
			}
			for (Widget part : parts)
			{
				if (part != null && !part.isHidden() && part.getActions() != null
					&& Arrays.asList(part.getActions()).contains("Close"))
				{
					Rectangle r = part.getBounds();
					double distance = Math.hypot(window.x + window.width - (r.x + r.width), r.y - window.y);
					if (r.width > 0 && r.width < 60 && distance < bestDistance)
					{
						best = r;
						bestDistance = distance;
					}
				}
			}
		}
		return best != null ? best : new Rectangle(window.x + window.width - 30, window.y + 4, 26, 26);
	}

	/** Load this account's progress; false while it's too early to tell what it should be. */
	private boolean load()
	{
		// With sync on, an existing account on a new device looks brand new until the
		// server's copy arrives: wait for it. Nobody on Tutorial Island has progress to restore.
		if (island >= ISLAND_DONE && plugin.isServerSyncEnabled() && !plugin.canRollMissing())
		{
			return false;
		}
		String saved = configManager.getRSProfileConfiguration(GROUP, "tutorialStep");
		if (saved == null)
		{
			// Players who already have a mode, or any progress, don't need it.
			boolean veteran = plugin.isModeLocked() || !plugin.getCompletedTaskIdSet().isEmpty();
			saved = veteran ? "over" : island < ISLAND_DONE ? "welcome" : "lwelcome";
		}
		practice.clear();
		seen.clear();
		for (String id : split(configManager.getRSProfileConfiguration(GROUP, "tutorialChunks")))
		{
			practice.add(Integer.parseInt(id));
		}
		seen.addAll(split(configManager.getRSProfileConfiguration(GROUP, "tutorialSeen")));
		dealt.clear();
		revealed.clear();
		dealt.addAll(split(configManager.getRSProfileConfiguration(GROUP, "tutorialCards")));
		revealed.addAll(split(configManager.getRSProfileConfiguration(GROUP, "tutorialTasks")));
		practiceDone = -1;
		setStep(saved);
		return true;
	}

	/** Whether a step that waits for the player is done. */
	private boolean waitDone(boolean onIsland)
	{
		switch (step)
		{
			case "imap":
				return isMapOpen();
			case "map":
				return !isMapOpen();
			case "deal":
				// Once the cards are flipped (or straight on, with cards turned off).
				return cardsDone();
			case "iorb":
				return browser.isOpen() || stepTicks > 10 && browser.getTaskButtonBounds() == null;
			case "itrack":
				return plugin.getSelectedTask() != null;
			case "practice":
				// On once every practice task is done, or the island is left behind.
				return practiceDone == practiceTasks.size() || !onIsland;
			case "leave":
				// On the mainland (not The Node), after a few ticks for the server login.
				return !onIsland && !onTheNode() && stepTicks > 5 && aside == null;
			case "sync":
				// Or in the side panel.
				return plugin.isServerSyncEnabled() || plugin.isPlayOffline();
			case "mode":
				// Picked in the side panel instead.
				return plugin.isModeLocked();
			case "verify":
				// And no chat code still to type.
				return plugin.isModeLocked() && plugin.getPendingVerificationCode() == null;
			case "cards":
				return cardsDone();
			case "orb":
				// Or on, if the orbs are turned off in the settings.
				return browser.isOpen() || stepTicks > 10 && browser.getTaskButtonBounds() == null;
			case "track":
				// Or with no task to track at all.
				return plugin.getSelectedTask() != null || lists.pool(true).isEmpty();
			case "save":
				// A task saved now, not one from before (or nothing left that could be saved).
				return savedJustNow() || savedBefore.containsAll(taskIds());
			case "close":
			case "iclose":
			case "sclose":
				return !browser.isOpen();
			case "boss":
				// Or straight on, with the saved tasks button turned off.
				return pane.isSavedPaneOpen() || savedButton.getButtonBounds() == null;
			case "boxmode":
				// Switched, or straight on when the box has no switch (Simple, Off, nothing tracked).
				return config.taskTrackerStyle() != styleBefore || stepTicks > 10 && !boxHasSwitch();
			case "boxbook":
			{
				// The book on the task box clicked (or straight on, with no task box to click).
				if (stepTicks > 10 && !boxHasSwitch())
				{
					return true;
				}
				Set<String> now = archive.ids();
				for (String id : now)
				{
					if (!archivedBefore.contains(id))
					{
						archivedTask = id;
						return true;
					}
				}
				return false;
			}
			case "archived":
				return browser.isOnTab("ARCHIVED");
			case "unarchive":
				// Brought back, or no longer a task to bring back (done, or gone from the list).
				return archivedTask == null || !archive.ids().contains(archivedTask) || !taskIds().contains(archivedTask);
			case "newtab":
				return browser.isOnTab("NEW");
			case "strack":
			{
				// A saved task tracked from the box (or nothing saved to track).
				NuzlockeTask selected = plugin.getSelectedTask();
				Set<String> saved = lists.savedIdsCopy();
				return saved.isEmpty() || selected != null && saved.contains(selected.getTaskId())
					&& !selected.getTaskId().equals(trackedBefore);
			}
			case "filters":
				return browser.isFilterMenuOpen();
			case "settings":
				return browser.isSettingsOpen();
			default:
				return false;
		}
	}

	/** On to the next step of the current start (skipping the ones that don't apply). */
	private void next()
	{
		String current = step;
		if (current.equals("mode"))
		{
			// Picked in the side panel (the picker itself goes straight to "verify").
			setStep("verify");
			return;
		}
		for (List<String> flow : Arrays.asList(ISLAND_FLOW, LUMBRIDGE_FLOW, TOUR))
		{
			int at = flow.indexOf(current);
			if (at < 0)
			{
				continue;
			}
			if (at == flow.size() - 1)
			{
				replaying = false;
				setStep(flow == TOUR ? "over" : "cards");
				return;
			}
			String upcoming = flow.get(at + 1);
			if (upcoming.equals("group") && !isGroupIron())
			{
				upcoming = flow.get(at + 2);
			}
			while (flow == TOUR && !replaying && seen.contains("practice") && SHOWN_ON_ISLAND.contains(upcoming))
			{
				upcoming = flow.get(flow.indexOf(upcoming) + 1);
			}
			if (upcoming.equals("boxbook") && !boxHasSwitch())
			{
				// No detailed task box on screen: nothing to point at, nothing to archive.
				archivedTask = null;
				upcoming = flow.get(flow.indexOf(upcoming) + 1);
			}
			while ((upcoming.equals("archived") || upcoming.equals("unarchive")) && archivedTask == null)
			{
				upcoming = flow.get(flow.indexOf(upcoming) + 1);
			}
			if (upcoming.equals("mode") && plugin.isModeLocked())
			{
				upcoming = flow == ISLAND_FLOW ? "verify" : "cards";
			}
			setStep(upcoming);
			return;
		}
		// "verify" ends both starts' setup; then the interface.
		setStep("cards");
	}

	private void setStep(String id)
	{
		if (id.equals("practice") || id.equals("welcome") || id.equals("lwelcome"))
		{
			// A first start: its locked view lasts until Lumbridge is unlocked ("home").
			see(id.equals("practice") ? id : "island");
		}
		if (id.equals("save"))
		{
			savedBefore = lists.savedIdsCopy();
		}
		if (id.equals("boxmode"))
		{
			styleBefore = config.taskTrackerStyle();
		}
		if (id.equals("strack"))
		{
			NuzlockeTask selected = plugin.getSelectedTask();
			trackedBefore = selected == null ? null : selected.getTaskId();
		}
		if (id.equals("boxbook"))
		{
			archivedBefore = new HashSet<>(archive.ids());
			archivedTask = null;
		}
		step = id;
		stepTicks = 0;
		sawCards = false;
		configManager.setRSProfileConfiguration(GROUP, "tutorialStep", id);
	}

	/** Whether the task box is on screen with its detailed/compact switch. */
	private boolean boxHasSwitch()
	{
		TaskTrackerStyle style = config.taskTrackerStyle();
		Rectangle box = taskBox.getBounds();
		return (style == TaskTrackerStyle.VANI || style == TaskTrackerStyle.COMPACT) && box != null && !box.isEmpty();
	}

	/**
	 * Settings > Tutorial > Restart: the tour of the interface again, from the mainland part
	 * (the island's own steps need Tutorial Island). Client thread.
	 */
	void replay()
	{
		browser.close();
		aside = null;
		replaying = true;
		setStep("orb");
	}

	/** Show a one-off message (once per account) over the current step. */
	private void interrupt(String id)
	{
		if (aside == null && !seen.contains(id))
		{
			see(id);
			aside = id;
		}
	}

	private void see(String id)
	{
		if (seen.add(id))
		{
			configManager.setRSProfileConfiguration(GROUP, "tutorialSeen", String.join(",", seen));
		}
	}

	private void practiceUnlock(int region)
	{
		practice.add(region);
		List<String> ids = new ArrayList<>();
		practice.forEach(id -> ids.add(String.valueOf(id)));
		configManager.setRSProfileConfiguration(GROUP, "tutorialChunks", String.join(",", ids));
	}

	/**
	 * Which practice tasks are done. One finished in the task list gets the usual
	 * completion popup (worth 0), and stops being tracked.
	 */
	private void countPractice()
	{
		int done = 0;
		for (NuzlockeTask task : practiceTasks)
		{
			int at = practiceAt.get(task.getTaskId());
			String npc = talkTarget(task);
			boolean now = at == 0 ? npc != null && (seen.contains("talk:" + npc) || npc.contains("ironman") && accountType > 0)
				: island >= at;
			if (now && !task.isCompleted() && practiceDone >= 0 && revealed.contains(task.getTaskId()))
			{
				// Done: off the task box and out of the list, like a real task (the next one,
				// if there is one, is tracked on the next tick).
				NuzlockeTask tracked = plugin.getSelectedTask();
				if (tracked != null && task.getTaskId().equals(tracked.getTaskId()))
				{
					plugin.clearSelectedTask();
				}
				plugin.showPracticeComplete(task);
				// The next box waits for the completion popup to finish.
				quietUntil = System.currentTimeMillis() + com.chunkblazerui.TaskCompletionAnimationOverlay.TOTAL;
			}
			task.setCompleted(now);
			task.setCurrentProgress(now ? 1 : 0);
			done += now ? 1 : 0;
		}
		practiceDone = done;
	}

	/** Who a practice task worth talking to is ("iron man tutor"), or null for other tasks. */
	private String talkTarget(NuzlockeTask task)
	{
		String target = practiceTargets.get(task.getTaskId());
		return practiceAt.get(task.getTaskId()) == 0 && target != null && target.startsWith("npc:")
			? letters(target.split(":")[1]) : null;
	}

	/** Talking to a practice task's NPC (they're speaking in the chat box), once it's been dealt. */
	private void talkedTo(String name)
	{
		for (NuzlockeTask task : practiceTasks)
		{
			String target = talkTarget(task);
			if (target != null && name.contains(target) && (dealt.contains(task.getTaskId()) || revealed.contains(task.getTaskId())))
			{
				see("talk:" + target);
			}
		}
	}

	/** Lowercase letters only: names in game can carry tags and non-breaking spaces. */
	static String letters(String text)
	{
		return text == null ? "" : Text.removeTags(text).toLowerCase().replaceAll("[^a-z]", "");
	}

	/** The practice tasks go to the task box one at a time, in order, as each is done. */
	private void trackNextPractice()
	{
		for (NuzlockeTask task : practiceTasks)
		{
			if (revealed.contains(task.getTaskId()) && !task.isCompleted())
			{
				NuzlockeTask tracked = plugin.getSelectedTask();
				if (tracked == null || !task.getTaskId().equals(tracked.getTaskId()))
				{
					plugin.selectTaskFromGame(task);
				}
				return;
			}
		}
	}

	/** Deal the practice tasks not done yet: as cards, or straight into the list with cards off. */
	private void deal()
	{
		countPractice();
		for (NuzlockeTask task : practiceTasks)
		{
			String id = task.getTaskId();
			if (!task.isCompleted() && !dealt.contains(id) && !revealed.contains(id))
			{
				if (plugin.isTaskCardsOn())
				{
					dealt.add(id);
				}
				else
				{
					revealed.add(id);
				}
			}
		}
		saveCards();
	}

	private void saveCards()
	{
		configManager.setRSProfileConfiguration(GROUP, "tutorialCards", String.join(",", dealt));
		configManager.setRSProfileConfiguration(GROUP, "tutorialTasks", String.join(",", revealed));
	}

	/** On The Node, group ironmen's island after Tutorial Island: its tutors are about. */
	private boolean onTheNode()
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc.getId() == NpcID.IRONMAN_TUTOR_NODE || npc.getId() == NpcID.GIM_TUTOR_SHARED_STORAGE)
			{
				return true;
			}
		}
		return false;
	}

	private boolean isGroupIron()
	{
		return accountType >= 4 && accountType <= 6;
	}

	private boolean isIronman()
	{
		return accountType >= 1 && accountType <= 6;
	}

	/** The island's chunks shown locked (maps and walls), before the mode turns them on. */
	private void showChunks(boolean show)
	{
		if (show != showingChunks)
		{
			showingChunks = show;
			plugin.showChunkView(show);
			NuzlockeTask tracked = plugin.getSelectedTask();
			if (!show && tracked != null && practiceTask(tracked.getTaskId()) != null)
			{
				plugin.clearSelectedTask();
			}
		}
		// ChunkBlazer GPU reads this too: only practice chunks unlocked.
		if (previewWritten == null || previewWritten != show)
		{
			previewWritten = show;
			if (show)
			{
				configManager.setRSProfileConfiguration(GROUP, "tutorialPreview", true);
			}
			else
			{
				configManager.unsetRSProfileConfiguration(GROUP, "tutorialPreview");
			}
		}
	}

	// --- Choosing a mode ----------------------------------------------------------------

	/**
	 * A mode picked from the picker. Asks to be sure (it's permanent), sorts out Server Sync
	 * (Competitive needs it; Casual offers it), then locks it in, or keeps it for the
	 * mainland when picked on Tutorial Island. Runs on the Swing thread, like a panel button.
	 */
	private void choose(GameMode mode)
	{
		boolean competitive = mode == GameMode.NUZLOCKE;
		if (competitive && !isIronman())
		{
			JOptionPane.showMessageDialog(client.getCanvas(), island < ISLAND_DONE
				? "Competitive needs an ironman account. Talk to the Iron Man tutor first."
				: "Competitive needs a new ironman account. This account can play Casual.",
				"Competitive", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (JOptionPane.showConfirmDialog(client.getCanvas(), "Play " + mode.getName() + "?\n\nThis choice is PERMANENT for this account!",
			"Confirm Game Mode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
		{
			return;
		}
		// Server Sync was settled on the step before; Competitive can't go without it.
		if (competitive && !plugin.isServerSyncEnabled())
		{
			if (JOptionPane.showConfirmDialog(client.getCanvas(), "Competitive needs Server Sync to check your account.\n\n"
				+ ChunkBlazerCore.SERVER_SYNC_WARNING, "Server Sync", JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		// The plugin's own mode lock (Competitive: its server checks). Casual unlocks the
		// chunk you're standing in, and the walls open out from you.
		clientThread.invoke(() ->
		{
			if (!plugin.isModeLocked())
			{
				if (competitive)
				{
					plugin.lockCompetitive();
				}
				else
				{
					plugin.lockGameMode(mode);
				}
			}
			setStep("verify");
		});
	}

	// --- Drawing ------------------------------------------------------------------------

	public Dimension render(Graphics2D graphics)
	{
		String id = aside != null ? aside : step;
		// Nothing while the name and look are picked, or while a chunk's unlock plays out.
		// ChunkBlazer's add-ons check comes first: nothing here until it's done or skipped.
		if (Boolean.parseBoolean(configManager.getConfiguration("chunkblazer", "companionCheckPending")))
		{
			shown = null;
			return null;
		}
		if (id == null || !isRunning() || settingUp || client.getGameState() != GameState.LOGGED_IN
			|| System.currentTimeMillis() < quietUntil
			|| id.equals("practice") || id.equals("deal") || id.equals("leave") || id.equals("cards")
			|| (id.equals("cards") || id.equals("deal")) && !cards.isActive())
		{
			shown = null;
			return null;
		}
		List<Hit> frame = new ArrayList<>();
		Rectangle box;
		if (id.equals("mode"))
		{
			box = drawPicker(graphics, frame);
		}
		else if (id.equals("themes"))
		{
			box = drawThemes(graphics, frame);
		}
		else if (id.equals("iassist") || id.equals("assist"))
		{
			box = drawAssists(graphics, id, frame);
		}
		else if (id.equals("itaskshow") || id.equals("taskshow"))
		{
			if (!snapped())
			{
				// A frame without this box in it first, for the pictures' game view.
				shown = null;
				return null;
			}
			box = drawTaskShows(graphics, id, frame);
		}
		else
		{
			box = drawStep(graphics, id, frame);
		}
		if (!id.equals("itaskshow") && !id.equals("taskshow"))
		{
			viewSnap = null;
			snapAt = 0;
		}
		highlight(graphics, id, box);
		hits = frame;
		shown = box;
		return null;
	}

	/**
	 * Where boxes go: centred along the top of the game view, or over the chat box for the
	 * steps done in the task window, so it doesn't cover them.
	 */
	private Rectangle place(int width, int height)
	{
		Widget chat = OVER_CHAT.contains(aside != null ? aside : step) ? client.getWidget(InterfaceID.Chatbox.UNIVERSE) : null;
		if (chat != null && !chat.isHidden())
		{
			Rectangle c = chat.getBounds();
			return new Rectangle(c.x + (c.width - width) / 2, Math.max(c.y, c.y + (c.height - height) / 2), width, height);
		}
		return new Rectangle(client.getViewportXOffset() + (client.getViewportWidth() - width) / 2,
			client.getViewportYOffset() + 30, width, height);
	}

	/** A step: its title, its text, and its button (or nothing, while it waits for you). */
	private Rectangle drawStep(Graphics2D graphics, String id, List<Hit> frame)
	{
		String[] content = text.getOrDefault(id, new String[]{id, "", null});
		if (id.equals("practice"))
		{
			content = new String[]{content[0] + " (" + practiceDone + "/" + practiceTasks.size() + ")", content[1], null};
		}
		if (id.equals("sync"))
		{
			content = new String[]{content[0], content[1] + "\n" + ChunkBlazerCore.SERVER_SYNC_WARNING + ".", null};
		}
		String code = plugin.getPendingVerificationCode();
		if (id.equals("verify") && code != null)
		{
			content = new String[]{"Type your code in chat", "Type " + code + " in public chat and press Enter.", content[2]};
		}
		graphics.setFont(Skin.regular());
		FontMetrics fm = graphics.getFontMetrics();
		List<String> lines = new ArrayList<>();
		for (String paragraph : content[1].split("\n"))
		{
			if (!lines.isEmpty())
			{
				lines.add("");
			}
			lines.addAll(TaskLists.wrap(fm, paragraph, WIDTH - PAD * 2));
		}
		int height = PAD + 22 + lines.size() * 15 + PAD + 26;
		Rectangle box = place(WIDTH, height);
		Skin.panel(graphics, box, 24, TITLE);
		graphics.setFont(Skin.bold());
		Skin.centred(graphics, content[0], box.x + box.width / 2, box.y + 20, TITLE);
		graphics.setFont(Skin.regular());
		int y = box.y + PAD + 30;
		for (String line : lines)
		{
			Skin.text(graphics, line, box.x + PAD, y, BODY);
			y += 15;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (content[2] != null)
		{
			Rectangle button = new Rectangle(box.x + box.width - PAD - 110, box.y + box.height - 30, 110, 22);
			button(graphics, button, content[2], mouse);
			frame.add(new Hit(button, () -> clientThread.invoke(() -> press(id))));
		}
		if (id.equals("sync"))
		{
			// Server Sync, the same choice as the side panel's.
			Rectangle on = new Rectangle(box.x + box.width - PAD - 196, box.y + box.height - 30, 96, 22);
			Rectangle off = new Rectangle(box.x + box.width - PAD - 96, box.y + box.height - 30, 96, 22);
			button(graphics, on, "Turn on sync", mouse);
			button(graphics, off, "Play offline", mouse);
			frame.add(new Hit(on, plugin::enableServerSync));
			frame.add(new Hit(off, plugin::choosePlayOffline));
		}
		// Skip, bottom left, small.
		graphics.setFont(Skin.small());
		Rectangle skip = new Rectangle(box.x + PAD, box.y + box.height - 24, 80, 14);
		Skin.text(graphics, "Skip tutorial", skip.x, skip.y + 11, hovered(skip, mouse) ? Color.WHITE : DIM);
		frame.add(new Hit(skip, () -> clientThread.invoke(() -> setStep("over"))));
		if (content[2] == null && id.equals(step) && !id.equals("sync") && stepTicks >= STUCK_TICKS)
		{
			// A step that waits for something, still waiting after a while: a way on, in case
			// it can't be done from where the player is (no orb, nothing to track...).
			String label = "Skip this step";
			int w = graphics.getFontMetrics().stringWidth(label);
			Rectangle skipStep = new Rectangle(box.x + box.width - PAD - w, box.y + box.height - 24, w, 14);
			Skin.text(graphics, label, skipStep.x, skipStep.y + 11, hovered(skipStep, mouse) ? Color.WHITE : DIM);
			frame.add(new Hit(skipStep, () -> clientThread.invoke(() ->
			{
				if (id.equals(step))
				{
					next();
				}
			})));
		}
		return box;
	}

	/** A step's button pressed. */
	private void press(String id)
	{
		if (id.equals(aside))
		{
			aside = null;
			return;
		}
		if (id.equals("iunlock"))
		{
			// The chunk you're standing in, and the cook's side of the island with it, so
			// leaving his house doesn't stop the tutorial for a second "new chunk" straight
			// away. The fence round the house falls from where you stand, like Lumbridge's.
			net.runelite.api.coords.WorldPoint at = client.getLocalPlayer().getWorldLocation();
			practiceUnlock(at.getRegionID());
			practiceUnlock(COOK_CHUNK);
			quietUntil = System.currentTimeMillis() + UNLOCK_SETTLE_TICKS * 600L;
			dropFence(at.getX() + 0.5, at.getY() + 0.5);
			fenced = false;
		}
		if (id.equals("tasks"))
		{
			deal();
		}
		if (id.equals("verify"))
		{
			// Choose again (Competitive turned down, or a change of mind before it locked).
			if (!plugin.isModeLocked())
			{
				plugin.cancelPendingCompetitive();
			}
			setStep("mode");
			return;
		}
		if (id.equals("done"))
		{
			setStep("over");
			return;
		}
		if (id.equals("home"))
		{
			// Lumbridge's fence falls from where you stand, and its square stays clear while
			// the unlock plays out.
			net.runelite.api.coords.WorldPoint at = client.getLocalPlayer().getWorldLocation();
			dropFence(at.getX() + 0.5, at.getY() + 0.5);
			fenced = false;
			clearUntil = System.currentTimeMillis() + 8000;
		}
		next();
	}

	// Each theme's card, drawn once (they're the same every frame).
	private final Map<Theme, BufferedImage> themeCards = new java.util.EnumMap<>(Theme.class);
	private static final int THEME_CARD_W = 140;
	private static final int THEME_CARD_H = 104;

	/** The three themes, each as a little window in its look: click one to switch to it, then Confirm. */
	private Rectangle drawThemes(Graphics2D graphics, List<Hit> frame)
	{
		String[] content = text.getOrDefault("themes", new String[]{"Pick a theme", "", "Confirm"});
		Theme[] themes = Theme.values();
		int width = themes.length * THEME_CARD_W + (themes.length + 1) * PAD;
		graphics.setFont(Skin.regular());
		FontMetrics fm = graphics.getFontMetrics();
		List<String> lines = new ArrayList<>();
		for (String paragraph : content[1].split("\n"))
		{
			if (!lines.isEmpty())
			{
				lines.add("");
			}
			lines.addAll(TaskLists.wrap(fm, paragraph, width - PAD * 2));
		}
		int textTop = PAD + 30;
		int cardsTop = textTop + lines.size() * 15 + PAD;
		int height = cardsTop + THEME_CARD_H + 22 + PAD + 30;
		Rectangle box = place(width, height);
		Skin.panel(graphics, box, 24, TITLE);
		graphics.setFont(Skin.bold());
		Skin.centred(graphics, content[0], box.x + box.width / 2, box.y + 20, TITLE);
		graphics.setFont(Skin.regular());
		int y = box.y + textTop;
		for (String line : lines)
		{
			Skin.text(graphics, line, box.x + PAD, y, BODY);
			y += 15;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		Theme current = Skin.theme();
		for (int i = 0; i < themes.length; i++)
		{
			Theme theme = themes[i];
			Rectangle card = new Rectangle(box.x + PAD + i * (THEME_CARD_W + PAD), box.y + cardsTop, THEME_CARD_W, THEME_CARD_H);
			boolean hover = hovered(card, mouse);
			boolean picked = theme == current;
			BufferedImage art = themeCards.computeIfAbsent(theme, this::themeCard);
			// Lifted a little under the mouse.
			graphics.drawImage(art, card.x, card.y - (hover && !picked ? 3 : 0), null);
			if (picked)
			{
				java.awt.Stroke old = graphics.getStroke();
				graphics.setStroke(new java.awt.BasicStroke(3f));
				graphics.setColor(TITLE);
				graphics.drawRoundRect(card.x - 4, card.y - 4, card.width + 8, card.height + 8, 10, 10);
				graphics.setStroke(old);
			}
			graphics.setFont(Skin.bold());
			Skin.centred(graphics, theme.toString(), card.x + card.width / 2, card.y + card.height + 18,
				picked ? TITLE : hover ? Color.WHITE : BODY);
			frame.add(new Hit(card, () -> configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, "theme", theme)));
		}
		Rectangle confirm = new Rectangle(box.x + (box.width - 110) / 2, box.y + box.height - 30, 110, 22);
		button(graphics, confirm, content[2] == null ? "Confirm" : content[2], mouse);
		frame.add(new Hit(confirm, () -> clientThread.invoke(() -> press("themes"))));
		return box;
	}

	/**
	 * A theme's look: a little task window drawn in it (frame, header, rows, a progress bar and
	 * a button). Drawn with that theme's palette for the moment, then the current one's back.
	 */
	private BufferedImage themeCard(Theme theme)
	{
		BufferedImage art = new BufferedImage(THEME_CARD_W, THEME_CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = art.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		ThemePalette before = Skin.palette();
		ThemePalette p = Themes.palette(theme);
		try
		{
			Skin.setPalette(p);
			// The frame's shadow falls 3 pixels right and down.
			Rectangle window = new Rectangle(0, 0, THEME_CARD_W - 4, THEME_CARD_H - 4);
			Skin.stoneFrame(g, window, 20);
			g.setFont(Skin.bold());
			Skin.centred(g, "Tasks", window.width / 2, 15, p.title);
			g.setFont(Skin.small());
			String[][] rows = {{"Catch a shrimp", "2"}, {"Chop a tree", "1"}, {"Bake some bread", "3"}};
			int y = 26;
			for (int i = 0; i < rows.length; i++)
			{
				if (i % 2 == 1 && p.rowAlt != null)
				{
					g.setColor(p.rowAlt);
					g.fillRect(5, y, window.width - 10, 15);
				}
				Skin.text(g, rows[i][0], 9, y + 11, i == 0 ? Color.WHITE : p.subtext);
				Skin.text(g, rows[i][1], window.width - 16, y + 11, p.title);
				y += 15;
			}
			Skin.bar(g, 9, y + 4, window.width - 18, 6, 0.6f, null, 0, false);
			Rectangle button = new Rectangle(window.width / 2 - 28, window.height - 20, 56, 15);
			Skin.button(g, button, false, false);
			Skin.centred(g, "Track", button.x + button.width / 2, button.y + 12, p.title);
		}
		finally
		{
			Skin.setPalette(before);
			g.dispose();
		}
		return art;
	}

	/**
	 * How much help you want: three cards (Video Game Critic, Dadscape, Certified GAMER), each
	 * listing the assists it turns on. Clicking one sets them all; Confirm carries on.
	 */
	private Rectangle drawAssists(Graphics2D graphics, String id, List<Hit> frame)
	{
		String[] content = text.getOrDefault(id, new String[]{"How much help?", "", "Confirm"});
		AssistLevel[] levels = AssistLevel.values();
		int cardH = 136;
		int width = levels.length * THEME_CARD_W + (levels.length + 1) * PAD;
		graphics.setFont(Skin.regular());
		FontMetrics fm = graphics.getFontMetrics();
		List<String> lines = new ArrayList<>();
		for (String paragraph : content[1].split("\n"))
		{
			if (!lines.isEmpty())
			{
				lines.add("");
			}
			lines.addAll(TaskLists.wrap(fm, paragraph, width - PAD * 2));
		}
		int textTop = PAD + 30;
		int cardsTop = textTop + lines.size() * 15 + PAD;
		int height = cardsTop + cardH + PAD + 30;
		Rectangle box = place(width, height);
		Skin.panel(graphics, box, 24, TITLE);
		graphics.setFont(Skin.bold());
		Skin.centred(graphics, content[0], box.x + box.width / 2, box.y + 20, TITLE);
		graphics.setFont(Skin.regular());
		int y = box.y + textTop;
		for (String line : lines)
		{
			Skin.text(graphics, line, box.x + PAD, y, BODY);
			y += 15;
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		AssistLevel current = config.assistLevel();
		ThemePalette p = Skin.palette();
		for (int i = 0; i < levels.length; i++)
		{
			AssistLevel level = levels[i];
			Rectangle card = new Rectangle(box.x + PAD + i * (THEME_CARD_W + PAD), box.y + cardsTop, THEME_CARD_W, cardH);
			boolean hover = hovered(card, mouse);
			boolean picked = level == current;
			int lift = hover && !picked ? 3 : 0;
			Rectangle face = new Rectangle(card.x, card.y - lift, card.width, card.height);
			graphics.setColor(p.panelSection != null ? p.panelSection : DIM.darker());
			graphics.fillRect(face.x, face.y, face.width, face.height);
			Skin.bevel(graphics, face, true);
			graphics.setFont(Skin.small());
			Skin.centred(graphics, level.level, face.x + face.width / 2, face.y + 14, DIM);
			graphics.setFont(Skin.bold());
			Skin.centred(graphics, level.title, face.x + face.width / 2, face.y + 30, picked ? TITLE : hover ? Color.WHITE : BODY);
			graphics.setFont(Skin.small());
			int ly = face.y + 48;
			if (level.assists.length == 0)
			{
				Skin.centred(graphics, "No assists at all", face.x + face.width / 2, ly + 14, RED);
			}
			for (String assist : level.assists)
			{
				// A little tick, then the assist.
				graphics.setColor(DONE);
				graphics.drawLine(face.x + 8, ly - 4, face.x + 10, ly - 2);
				graphics.drawLine(face.x + 10, ly - 2, face.x + 14, ly - 7);
				Skin.text(graphics, assist, face.x + 18, ly, BODY);
				ly += 12;
			}
			if (picked)
			{
				java.awt.Stroke old = graphics.getStroke();
				graphics.setStroke(new java.awt.BasicStroke(3f));
				graphics.setColor(TITLE);
				graphics.drawRoundRect(face.x - 4, face.y - 4, face.width + 8, face.height + 8, 10, 10);
				graphics.setStroke(old);
			}
			frame.add(new Hit(card, () -> level.apply(configManager)));
		}
		Rectangle confirm = new Rectangle(box.x + (box.width - 110) / 2, box.y + box.height - 30, 110, 22);
		button(graphics, confirm, content[2] == null ? "Confirm" : content[2], mouse);
		frame.add(new Hit(confirm, () -> clientThread.invoke(() -> press(id))));
		return box;
	}

	// Each "Tracked task in" choice's picture, for the palette it was drawn in.
	private final Map<TrackedTaskShows, BufferedImage> showCards = new java.util.EnumMap<>(TrackedTaskShows.class);
	private ThemePalette showCardsPalette;

	/**
	 * Where the tracked task shows: Side panel, Widget (the task box on screen) or Both, each as
	 * a little picture of the game. Click one to pick it (Widget to start with), then Confirm.
	 */
	private Rectangle drawTaskShows(Graphics2D graphics, String id, List<Hit> frame)
	{
		String[] content = text.getOrDefault(id, new String[]{"Where's your task?", "", "Confirm"});
		TrackedTaskShows[] choices = TrackedTaskShows.values();
		int width = choices.length * THEME_CARD_W + (choices.length + 1) * PAD;
		graphics.setFont(Skin.regular());
		FontMetrics fm = graphics.getFontMetrics();
		List<String> lines = new ArrayList<>();
		for (String paragraph : content[1].split("\n"))
		{
			if (!lines.isEmpty())
			{
				lines.add("");
			}
			lines.addAll(TaskLists.wrap(fm, paragraph, width - PAD * 2));
		}
		int textTop = PAD + 30;
		int cardsTop = textTop + lines.size() * 15 + PAD;
		int height = cardsTop + THEME_CARD_H + 22 + PAD + 30;
		Rectangle box = place(width, height);
		Skin.panel(graphics, box, 24, TITLE);
		graphics.setFont(Skin.bold());
		Skin.centred(graphics, content[0], box.x + box.width / 2, box.y + 20, TITLE);
		graphics.setFont(Skin.regular());
		int y = box.y + textTop;
		for (String line : lines)
		{
			Skin.text(graphics, line, box.x + PAD, y, BODY);
			y += 15;
		}
		if (showCardsPalette != Skin.palette())
		{
			// Drawn in the theme's colours: again after a theme change.
			showCards.clear();
			showCardsPalette = Skin.palette();
		}
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		TrackedTaskShows current = config.trackedTaskShows();
		for (int i = 0; i < choices.length; i++)
		{
			TrackedTaskShows choice = choices[i];
			Rectangle card = new Rectangle(box.x + PAD + i * (THEME_CARD_W + PAD), box.y + cardsTop, THEME_CARD_W, THEME_CARD_H);
			boolean hover = hovered(card, mouse);
			boolean picked = choice == current;
			graphics.drawImage(showCards.computeIfAbsent(choice, this::showCard), card.x, card.y - (hover && !picked ? 3 : 0), null);
			if (picked)
			{
				java.awt.Stroke old = graphics.getStroke();
				graphics.setStroke(new java.awt.BasicStroke(3f));
				graphics.setColor(TITLE);
				graphics.drawRoundRect(card.x - 4, card.y - 4, card.width + 8, card.height + 8, 10, 10);
				graphics.setStroke(old);
			}
			graphics.setFont(Skin.bold());
			Skin.centred(graphics, choice.toString(), card.x + card.width / 2, card.y + card.height + 18,
				picked ? TITLE : hover ? Color.WHITE : BODY);
			frame.add(new Hit(card, () -> configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, "trackedTaskShows", choice)));
		}
		Rectangle confirm = new Rectangle(box.x + (box.width - 110) / 2, box.y + box.height - 30, 110, 22);
		button(graphics, confirm, content[2] == null ? "Confirm" : content[2], mouse);
		frame.add(new Hit(confirm, () -> clientThread.invoke(() -> press(id))));
		return box;
	}

	// The game view the "Tracked task in" pictures show: a snapshot of your own game, around
	// your character, taken as the popup opens (plain grass green until then, or if it fails).
	private static final Color GRASS = new Color(84, 110, 44);
	private static final int VIEW_W = THEME_CARD_W - 4 - 36;
	private static final int VIEW_H = THEME_CARD_H - 4;
	private volatile BufferedImage viewSnap;
	private long snapAt;

	/** True once the snapshot's in (or given up on); asks for one the first time. */
	private boolean snapped()
	{
		if (viewSnap != null)
		{
			return true;
		}
		long now = System.currentTimeMillis();
		if (snapAt != 0)
		{
			if (now - snapAt < 1000)
			{
				return false;
			}
			viewSnap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			showCards.clear();
			return true;
		}
		snapAt = now;
		Rectangle crop = snapArea();
		int canvasW = Math.max(1, client.getCanvasWidth());
		int canvasH = Math.max(1, client.getCanvasHeight());
		drawManager.requestNextFrameListener(image ->
		{
			// The frame may be bigger than the canvas (stretched mode): scale the area to it.
			double sx = image.getWidth(null) / (double) canvasW;
			double sy = image.getHeight(null) / (double) canvasH;
			BufferedImage full = new BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = full.createGraphics();
			g.drawImage(image, 0, 0, crop.width, crop.height,
				(int) (crop.x * sx), (int) (crop.y * sy), (int) ((crop.x + crop.width) * sx), (int) ((crop.y + crop.height) * sy), null);
			g.dispose();
			BufferedImage small = new BufferedImage(VIEW_W, VIEW_H, BufferedImage.TYPE_INT_RGB);
			g = small.createGraphics();
			g.drawImage(full.getScaledInstance(VIEW_W, VIEW_H, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
			g.dispose();
			viewSnap = small;
			showCards.clear();
		});
		return false;
	}

	/** A square-ish bit of the game view around your character, in canvas pixels. */
	private Rectangle snapArea()
	{
		int vx = client.getViewportXOffset();
		int vy = client.getViewportYOffset();
		int vw = Math.max(1, client.getViewportWidth());
		int vh = Math.max(1, client.getViewportHeight());
		int h = Math.max(1, Math.min(vh, vh * 3 / 5));
		int w = Math.max(1, Math.min(vw, h * VIEW_W / VIEW_H));
		int cx = vx + vw / 2;
		int cy = vy + vh / 2;
		Player me = client.getLocalPlayer();
		if (me != null && me.getCanvasTilePoly() != null)
		{
			Rectangle feet = me.getCanvasTilePoly().getBounds();
			cx = (int) feet.getCenterX();
			cy = (int) feet.getCenterY() - h / 4;
		}
		int x = Math.max(vx, Math.min(cx - w / 2, vx + vw - w));
		int y = Math.max(vy, Math.min(cy - h / 2, vy + vh - h));
		return new Rectangle(x, y, w, h);
	}

	/**
	 * A choice's picture: the game view with the side panel down its right, and the tracked
	 * task where it would show (a box in the game view's bottom left, a row in the panel, or both).
	 */
	private BufferedImage showCard(TrackedTaskShows choice)
	{
		ThemePalette p = Skin.palette();
		Color accent = p.title != null ? p.title : new Color(255, 152, 31);
		Color fill = p.barFill != null ? p.barFill : new Color(90, 200, 90);
		BufferedImage art = new BufferedImage(THEME_CARD_W, THEME_CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = art.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		int w = THEME_CARD_W - 4;
		int h = THEME_CARD_H - 4;
		Rectangle view = new Rectangle(0, 0, w - 36, h);
		Rectangle panel = new Rectangle(w - 36, 0, 36, h);

		// The game view: your own game around your character, on grass green.
		g.setColor(GRASS);
		g.fillRect(view.x, view.y, view.width, view.height);
		BufferedImage snap = viewSnap;
		if (snap != null && snap.getWidth() > 1)
		{
			g.drawImage(snap, view.x, view.y, view.width, view.height, null);
		}
		g.setColor(new Color(0, 0, 0, 90));
		g.drawLine(view.x + view.width, 0, view.x + view.width, h);

		// The side panel, in its colours: a header, the tracked task's card (lit, with the
		// accent down its edge and a progress bar) when it shows there, then other rows.
		g.setColor(p.panelBg != null ? p.panelBg : new Color(40, 40, 40));
		g.fillRect(panel.x, panel.y, panel.width, panel.height);
		Color section = p.panelSection != null ? p.panelSection : new Color(30, 30, 30);
		g.setColor(section);
		g.fillRect(panel.x, 0, panel.width, 8);
		g.setColor(p.panelAccent != null ? p.panelAccent : accent);
		g.fillRect(panel.x + 4, 3, 12, 2);
		int rowY = 12;
		if (choice != TrackedTaskShows.WIDGET)
		{
			g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
			g.fillRect(panel.x + 3, rowY, panel.width - 6, 17);
			g.setColor(accent);
			g.fillRect(panel.x + 3, rowY, 2, 17);
			g.setColor(new Color(255, 255, 255, 200));
			g.fillRect(panel.x + 8, rowY + 4, panel.width - 16, 2);
			g.setColor(section);
			g.fillRect(panel.x + 8, rowY + 11, panel.width - 14, 3);
			g.setColor(fill);
			g.fillRect(panel.x + 8, rowY + 11, (panel.width - 14) * 3 / 5, 3);
			rowY += 21;
		}
		for (int row = 0; rowY + 10 < h - 2; row++)
		{
			g.setColor(new Color(255, 255, 255, 14));
			g.fillRect(panel.x + 3, rowY, panel.width - 6, 10);
			g.setColor(new Color(255, 255, 255, 50));
			g.fillRect(panel.x + 6, rowY + 4, (panel.width - 16) * (row % 3 == 0 ? 3 : 2) / 3, 2);
			rowY += 13;
		}

		// The task box, in the game view's bottom left: its frame, the task's name and progress.
		if (choice != TrackedTaskShows.SIDE_PANEL)
		{
			Rectangle box = new Rectangle(6, h - 30, 56, 24);
			Skin.stoneFrame(g, box, 0);
			g.setColor(accent);
			g.fillRect(box.x + 6, box.y + 7, box.width - 20, 3);
			g.setColor(new Color(0, 0, 0, 150));
			g.fillRect(box.x + 6, box.y + box.height - 10, box.width - 12, 4);
			g.setColor(fill);
			g.fillRect(box.x + 6, box.y + box.height - 10, (box.width - 12) * 3 / 5, 4);
		}
		g.setColor(new Color(0, 0, 0, 140));
		g.drawRect(0, 0, w - 1, h - 1);
		g.dispose();
		return art;
	}

	/** Casual or Competitive, side by side, each with what it means and a button. */
	private Rectangle drawPicker(Graphics2D graphics, List<Hit> frame)
	{
		String[] content = text.getOrDefault("mode", new String[]{"Choose how you'll play", "", null});
		int cardWidth = 200;
		Rectangle box = place(cardWidth * 2 + PAD * 3, 230);
		Skin.panel(graphics, box, 24, TITLE);
		graphics.setFont(Skin.bold());
		Skin.centred(graphics, content[0], box.x + box.width / 2, box.y + 20, TITLE);
		graphics.setFont(Skin.small());
		Skin.text(graphics, content[1].trim(), box.x + PAD, box.y + 40, RED);
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		boolean ironman = isIronman();
		String[][] modes = {text.getOrDefault("casual", new String[]{"Casual", "", null}),
			text.getOrDefault("competitive", new String[]{"Competitive", "", null})};
		for (int i = 0; i < 2; i++)
		{
			GameMode mode = i == 0 ? GameMode.CASUAL : GameMode.NUZLOCKE;
			Rectangle card = new Rectangle(box.x + PAD + i * (cardWidth + PAD), box.y + 50, cardWidth, box.height - 60);
			Skin.button(graphics, card, false, false);
			graphics.setFont(Skin.bold());
			Skin.text(graphics, modes[i][0], card.x + 8, card.y + 18, Color.WHITE);
			graphics.setFont(Skin.small());
			int y = card.y + 36;
			for (String line : TaskLists.wrap(graphics.getFontMetrics(), modes[i][1].replace('\n', ' ').trim(), cardWidth - 16))
			{
				Skin.text(graphics, line, card.x + 8, y, BODY);
				y += 13;
			}
			if (i == 1)
			{
				Skin.text(graphics, ironman ? "Ironman account: eligible" : "Needs an ironman account", card.x + 8, y + 4, ironman ? DONE : RED);
			}
			Rectangle button = new Rectangle(card.x + 8, card.y + card.height - 30, cardWidth - 16, 22);
			button(graphics, button, "Play " + mode.getName(), mouse);
			frame.add(new Hit(button, () -> choose(mode)));
		}
		return box;
	}

	private void button(Graphics2D graphics, Rectangle r, String label, net.runelite.api.Point mouse)
	{
		Skin.button(graphics, r, hovered(r, mouse), false);
		graphics.setFont(Skin.regular());
		Skin.centred(graphics, label, r.x + r.width / 2, r.y + 16, TITLE);
	}

	private static boolean hovered(Rectangle r, net.runelite.api.Point mouse)
	{
		return mouse != null && r.contains(mouse.getX(), mouse.getY());
	}

	/**
	 * A pulsing outline round what each step talks about, and an arrow from the box to what
	 * to click (or type in) next.
	 */
	private void highlight(Graphics2D graphics, String id, Rectangle from)
	{
		List<Rectangle> targets = new ArrayList<>();
		boolean arrow = true;
		// Arriving in Lumbridge, the game's own message ("Welcome to Lumbridge!") is in the chat
		// box: point at closing it first, as nothing can be typed until it's gone.
		Rectangle dialogue = MAINLAND_SETUP.contains(id) ? clickToContinue() : null;
		if (dialogue != null)
		{
			targets.add(dialogue);
			// Keeps pulsing until it's closed (not just the first few seconds, like a button).
			pulse(graphics, "dialogue:" + id, targets, arrow, from);
			return;
		}
		String tracked = plugin.getSelectedTask() == null ? null : plugin.getSelectedTask().getTaskId();
		switch (id)
		{
			case "imap":
				widgets(targets, InterfaceID.Orbs.ORB_WORLDMAP);
				break;
			case "map":
				targets.add(mapCloseButton());
				break;
			case "lwelcome":
			case "newchunk":
			case "lunlock":
				arrow = false;
				widgets(targets, InterfaceID.ToplevelOsrsStretch.MINIMAP, InterfaceID.Toplevel.MINIMAP,
					InterfaceID.ToplevelPreEoc.MINIMAP, InterfaceID.Orbs.ORB_WORLDMAP);
				break;
			case "verify":
				// The line you type on, at the bottom of the chat box.
				Widget chat = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
				if (chat != null && !chat.isHidden())
				{
					Rectangle c = chat.getBounds();
					// Above the tabs along its bottom (about 1/7 of its height), the "Press Enter
					// to Chat" line: about the next 1/8 up.
					int line = c.height / 8;
					targets.add(new Rectangle(c.x + 10, c.y + c.height - c.height / 7 - line, c.width - 20, line));
					targets.add(c);
				}
				break;
			case "orb":
			case "iorb":
				targets.add(browser.getTaskButtonBounds());
				break;
			case "tokens":
			case "ltokens":
				arrow = false;
				targets.add(plugin.getBossOrbBounds());
				break;
			case "boss":
				targets.add(savedButton.getButtonBounds());
				break;
			case "strack":
			{
				// A saved task in the box, or the button that opens it.
				Rectangle row = pane.getSavedRow();
				targets.add(row != null ? row : pane.isSavedPaneOpen() ? null : savedButton.getButtonBounds());
				break;
			}
			case "close":
			case "iclose":
			case "sclose":
				targets.add(browser.getCloseBounds());
				break;
			case "itrack":
				// The practice task to track (talking to the Survival Expert), on the Active list.
				targets.add(toward("ACTIVE", practiceTasks.get(0).getTaskId(), 0));
				break;
			case "track":
				// Any task on the list showing (Active or New), or back to Active from anywhere else.
				targets.add(toward(browser.isOnTab("NEW") ? "NEW" : "ACTIVE", browser.getFirstRowId(), 0));
				break;
			case "save":
				// The star on the tracked task's row (or the first one) on the Active list.
				targets.add(toward("ACTIVE", tracked != null && browser.getRowSpot(tracked, 1) != null ? tracked
					: browser.getFirstRowId(), 1));
				break;
			case "boxmode":
			{
				// The switch; on the compact strip it only shows while hovered, so the strip until then.
				Rectangle button = taskBox.getButtonOnScreen(0);
				Rectangle strip = taskBox.getBounds();
				targets.add(button != null ? button : strip == null || strip.isEmpty() ? null : strip);
				break;
			}
			case "boxbook":
			{
				// The book; on the compact strip, the strip until it's hovered open.
				Rectangle book = taskBox.getButtonOnScreen(1);
				Rectangle strip = taskBox.getBounds();
				targets.add(book != null ? book : strip == null || strip.isEmpty() ? null : strip);
				break;
			}
			case "archived":
				targets.add(toward("ARCHIVED", null, 0));
				break;
			case "unarchive":
				targets.add(toward("ARCHIVED", archivedTask, 2));
				break;
			case "newtab":
				targets.add(inWindow(browser.getMark("tab:NEW")));
				break;
			case "filters":
				// The search, Filters and Sort strip shows over a list: back to Active from Settings.
				targets.add(browser.isOpen() && browser.getMark("filters") == null ? browser.getMark("tab:ACTIVE")
					: inWindow(browser.getMark("filters")));
				break;
			case "settings":
				targets.add(inWindow(browser.getMark("cog")));
				break;
			case "tracker":
			case "practice":
				Rectangle box = taskBox.getBounds();
				targets.add(box == null || box.isEmpty() ? null : box);
				break;
			default:
				return;
		}
		pulse(graphics, id, targets, arrow, from);
	}

	/** The pulsing outline round {@code targets}, with an arrow from the box to the first. */
	private void pulse(Graphics2D graphics, String id, List<Rectangle> targets, boolean arrow, Rectangle from)
	{
		// Points for as long as the step shows: until its button or the thing itself is clicked.
		long now = System.currentTimeMillis();
		int alpha = 140 + (int) (100 * Math.sin(now / 180.0));
		graphics.setColor(new Color(255, 200, 40, alpha));
		for (Rectangle r : targets)
		{
			if (r != null)
			{
				graphics.drawRect(r.x - 3, r.y - 3, r.width + 5, r.height + 5);
				graphics.drawRect(r.x - 2, r.y - 2, r.width + 3, r.height + 3);
			}
		}
		Rectangle to = targets.isEmpty() ? null : targets.get(0);
		if (arrow && from != null && to != null)
		{
			arrow(graphics, from, to);
		}
	}

	/**
	 * Where to point for a task on one of the window's lists ({@code part}: 0 its row, 1 its
	 * star, 2 its book), always somewhere that leads there: the fire orb while the window's shut,
	 * the list's tab while another page shows, the list while the task's scrolled out of
	 * sight (or there's no task to point at), else the spot itself.
	 */
	private Rectangle toward(String tab, String taskId, int part)
	{
		if (!browser.isOpen())
		{
			return browser.getTaskButtonBounds();
		}
		if (!browser.isOnTab(tab))
		{
			return browser.getMark("tab:" + tab);
		}
		Rectangle spot = taskId == null ? null : browser.getRowSpot(taskId, part);
		return spot != null ? spot : browser.getListBounds();
	}

	/** Something in the task window, or the fire orb to open it while it's shut. */
	private Rectangle inWindow(Rectangle spot)
	{
		return browser.isOpen() ? spot : browser.getTaskButtonBounds();
	}

	/**
	 * A line from the box to {@code to}: it leaves from a little stud on the box's edge (the
	 * side facing the target, slid along towards it), with a dark shadow under it so it reads
	 * on any background, and a solid head at the target's edge.
	 */
	private static void arrow(Graphics2D graphics, Rectangle from, Rectangle to)
	{
		java.awt.Color color = graphics.getColor();
		int x1;
		int y1;
		boolean above = to.y + to.height < from.y;
		boolean below = to.y > from.y + from.height;
		if (above || below)
		{
			// From the top or bottom edge, under the target as far as the corners allow.
			x1 = Math.max(from.x + 16, Math.min(to.x + to.width / 2, from.x + from.width - 16));
			y1 = above ? from.y : from.y + from.height;
		}
		else
		{
			// Beside it: from the left or right edge.
			boolean left = to.x + to.width / 2 < from.x + from.width / 2;
			x1 = left ? from.x : from.x + from.width;
			y1 = Math.max(from.y + 16, Math.min(to.y + to.height / 2, from.y + from.height - 16));
		}
		int x2 = Math.max(to.x, Math.min(x1, to.x + to.width));
		int y2 = Math.max(to.y, Math.min(y1, to.y + to.height));
		double angle = Math.atan2(y2 - y1, x2 - x1);
		// Stop just short of the target, so the head sits by its edge.
		x2 -= (int) Math.round(5 * Math.cos(angle));
		y2 -= (int) Math.round(5 * Math.sin(angle));
		if (Math.hypot(x2 - x1, y2 - y1) < 24)
		{
			return;
		}
		graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		// The head: a solid triangle, its tip at (x2, y2).
		int headLength = 13;
		java.awt.Polygon head = new java.awt.Polygon();
		head.addPoint(x2, y2);
		head.addPoint(x2 - (int) Math.round(headLength * Math.cos(angle - 0.45)), y2 - (int) Math.round(headLength * Math.sin(angle - 0.45)));
		head.addPoint(x2 - (int) Math.round(headLength * Math.cos(angle + 0.45)), y2 - (int) Math.round(headLength * Math.sin(angle + 0.45)));
		int lineEndX = x2 - (int) Math.round((headLength - 3) * Math.cos(angle));
		int lineEndY = y2 - (int) Math.round((headLength - 3) * Math.sin(angle));

		// Shadow first, then the line and head in the pulse colour.
		graphics.setColor(new java.awt.Color(0, 0, 0, 130));
		graphics.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.drawLine(x1, y1, lineEndX, lineEndY);
		graphics.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.draw(head);
		graphics.setColor(color);
		graphics.drawLine(x1, y1, lineEndX, lineEndY);
		graphics.fill(head);

		// The stud it comes out of: a dark ring set into the box's edge with a lit centre.
		int r = 6;
		graphics.setStroke(new BasicStroke(1));
		graphics.setColor(new java.awt.Color(0, 0, 0, 200));
		graphics.fillOval(x1 - r - 1, y1 - r - 1, (r + 1) * 2, (r + 1) * 2);
		graphics.setColor(new java.awt.Color(60, 50, 38));
		graphics.fillOval(x1 - r, y1 - r, r * 2, r * 2);
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawOval(x1 - r + 1, y1 - r + 1, (r - 1) * 2, (r - 1) * 2);
		graphics.fillOval(x1 - 2, y1 - 2, 5, 5);
		graphics.setStroke(new BasicStroke(1));
	}

	/** The bounds of whichever of these widgets are showing. */
	private void widgets(List<Rectangle> targets, int... ids)
	{
		for (int id : ids)
		{
			Widget w = client.getWidget(id);
			if (w != null && !w.isHidden())
			{
				targets.add(w.getBounds());
			}
		}
	}

	// --- Text ---------------------------------------------------------------------------

	/** tutorial.txt: [id|button] headers, a title line, then the body. */
	private void loadText()
	{
		try (InputStream in = ChunkBlazerTutorial.class.getResourceAsStream("tutorial.txt"))
		{
			if (in == null)
			{
				return;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String id = null;
			String button = null;
			List<String> lines = new ArrayList<>();
			for (String line = reader.readLine(); ; line = reader.readLine())
			{
				if (line == null || line.startsWith("["))
				{
					store(id, button, lines);
					if (line == null)
					{
						break;
					}
					String[] header = line.substring(1, line.indexOf(']')).split("\\|");
					id = header[0];
					button = header.length > 1 ? header[1] : null;
					lines = new ArrayList<>();
				}
				else if (!line.startsWith("#"))
				{
					lines.add(line);
				}
			}
		}
		catch (IOException e)
		{
			// No text: the steps show their ids, and the tutorial still works.
		}
	}

	private void store(String id, String button, List<String> lines)
	{
		while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty())
		{
			lines.remove(lines.size() - 1);
		}
		if (id == null || lines.isEmpty())
		{
			return;
		}
		if (id.equals("lumbridge"))
		{
			String[] corners = lines.get(0).split(",");
			for (int i = 0; i < 4; i++)
			{
				lumbridgeSquare[i] = Integer.parseInt(corners[i].trim());
			}
			return;
		}
		if (id.equals("startroom"))
		{
			String[] corners = lines.get(0).split(",");
			for (int i = 0; i < 4; i++)
			{
				startRoom[i] = Integer.parseInt(corners[i].trim());
			}
			return;
		}
		if (id.equals("tasks:practice"))
		{
			for (String line : lines)
			{
				// progress|category|type|name|description|target, made into a task like a real one.
				String[] p = line.split("\\|", 6);
				NuzlockeTask task = new NuzlockeTask();
				task.setTaskId("tutorial_" + practiceTasks.size());
				task.setCategory(p[1]);
				task.setCompletionType(p[2]);
				task.setName(p[3]);
				task.setDescription(p[4]);
				task.setTargetQuantity(1);
				practiceAt.put(task.getTaskId(), Integer.parseInt(p[0]));
				if (p.length > 5 && !p[5].trim().isEmpty())
				{
					// npc:Name or object:Name, with npc:Name:id,id for NPCs known by id.
					String[] target = p[5].trim().split(":");
					practiceTargets.put(task.getTaskId(), p[5].trim());
					if (target.length > 2)
					{
						TargetNpc npc = new TargetNpc();
						npc.setName(target[1]);
						List<Integer> ids = new ArrayList<>();
						for (String npcId : target[2].split(","))
						{
							ids.add(Integer.parseInt(npcId.trim()));
						}
						npc.setNpcIds(ids);
						task.setTargetNpc(npc);
					}
				}
				practiceTasks.add(task);
			}
			return;
		}
		text.put(id, new String[]{lines.get(0), String.join("\n", lines.subList(1, lines.size())).replaceAll("\n(?!\n)", " ")
			.replaceAll(" ?\n ?", "\n").trim(), button});
	}

	private static List<String> split(String csv)
	{
		return csv == null || csv.isEmpty() ? new ArrayList<>() : Arrays.asList(csv.split(","));
	}
}
