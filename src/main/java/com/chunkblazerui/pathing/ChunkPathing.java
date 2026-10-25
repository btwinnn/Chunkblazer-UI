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
package com.chunkblazerui.pathing;

import com.chunkblazerui.ChunkBlazerCore;
import com.chunkblazerui.ChunkBlazerUiConfig;
import com.chunkblazerui.NuzlockeTask;
import com.chunkblazerui.QuestRequirements;
import com.chunkblazerui.TaskSources;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;

/**
 * Chunk-aware paths and the quest chunk calculator. Right-click the world map (or
 * Shift + right-click a tile) and pick "Path here", or hold the path key (Alt unless changed) and click the world map: the path only goes where your
 * unlocked chunks let you walk, using stairs, shortcuts, boats and the like when both
 * ends are in chunks you own. If the spot is behind locked chunks it stops as close as
 * it can (the end shows red). The path follows you, and is worked out again if you stray, or
 * when a chunk is unlocked.
 *
 * <p>The map data loads in the background the first time either is used.
 */
@Slf4j
@Singleton
public class ChunkPathing
{
	private static final String OPTION_PATH = "Path here";
	private static final String SHORTEST_PATH = "shortestpath";
	private static final String OPTION_CLEAR = "Clear path";
	private static final String TARGET = ColorUtil.wrapWithColorTag("ChunkBlazer", new java.awt.Color(255, 150, 40));
	// How far off the path you can wander before it's worked out again, in tiles.
	private static final int OFF_PATH = 4;
	// Transport requirements (levels, quests, items) are checked again this often, in ticks.
	private static final int TRANSPORT_REFRESH_TICKS = 50;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ChatMessageManager chat;
	@Inject
	private EventBus eventBus;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private QuestRequirements quests;
	@Inject
	private ChunkBlazerCore core;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private ChunkBlazerUiConfig config;

	// Whether the path key (Alt unless changed) is held right now.
	private volatile boolean pathKeyHeld;

	private final KeyListener pathKey = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			if (config.pathKey().matches(e))
			{
				pathKeyHeld = true;
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			if (config.pathKey().matches(e))
			{
				pathKeyHeld = false;
			}
		}
	};

	/** Whether paths are on (Settings > Assists > Shortest path). */
	public boolean enabled()
	{
		return config.shortestPath();
	}

	/** The path key + click on the world map sets a path, or clears the one showing; the click is kept from the map. */
	private final MouseAdapter altClick = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!pathKeyHeld || !enabled() || event.getButton() != MouseEvent.BUTTON1)
			{
				return event;
			}
			Widget worldMap = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
			if (worldMap == null || !worldMap.getBounds().contains(event.getPoint()))
			{
				return event;
			}
			// With a path showing, Alt + click clears it; otherwise it heads for the spot clicked.
			int tile = mapTileAt(worldMap, new Point(event.getX(), event.getY()));
			if (target >= 0)
			{
				clientThread.invoke(ChunkPathing.this::clear);
			}
			else if (tile >= 0)
			{
				clientThread.invoke(() -> pathTo(tile));
			}
			event.consume();
			return event;
		}
	};

	private ExecutorService executor;
	private ChunkPathOverlays overlays;

	private volatile CollisionMap map;
	private volatile Transports transports;
	private volatile ChunkCalculator calculator;
	private volatile boolean loading;
	private volatile boolean loadFailed;

	// Transports this player can use, by start tile; refreshed on the client thread.
	private volatile Map<Integer, List<Transports.Link>> usable;
	private volatile int usableVersion;
	private final Map<String, ChunkCalculator.Result> results = new ConcurrentHashMap<>();
	private final Set<String> working = ConcurrentHashMap.newKeySet();
	private int usableAge = Integer.MAX_VALUE;

	private volatile int target = -1;
	private volatile int[] path;
	private volatile boolean reached;
	private volatile boolean searching;
	private volatile int progress;
	private boolean pending;
	private long unlockStamp;
	private int cooldown;
	private final AtomicInteger generation = new AtomicInteger();

	public void startUp()
	{
		if (executor == null)
		{
			executor = Executors.newSingleThreadExecutor(r ->
			{
				Thread thread = new Thread(r, "chunkblazer-path");
				thread.setDaemon(true);
				return thread;
			});
		}
		if (overlays == null)
		{
			overlays = new ChunkPathOverlays(this, client);
		}
		overlays.add(overlayManager);
		eventBus.register(this);
		mouseManager.registerMouseListener(altClick);
		keyManager.registerKeyListener(pathKey);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		mouseManager.unregisterMouseListener(altClick);
		keyManager.unregisterKeyListener(pathKey);
		pathKeyHeld = false;
		if (overlays != null)
		{
			overlays.remove(overlayManager);
		}
		clear();
	}

	// --- Data -----------------------------------------------------------------------

	/** Starts loading the map data if it isn't yet. True once it's there. */
	public boolean ensureLoaded()
	{
		if (calculator != null)
		{
			return true;
		}
		if (!loading && !loadFailed && executor != null)
		{
			loading = true;
			executor.submit(() ->
			{
				try
				{
					long started = System.currentTimeMillis();
					CollisionMap loadedMap = CollisionMap.load();
					Transports loadedTransports = Transports.load();
					ChunkCalculator loadedCalculator = new ChunkCalculator(loadedMap, loadedTransports);
					map = loadedMap;
					transports = loadedTransports;
					calculator = loadedCalculator;
					log.debug("Path data loaded in {} ms: {} regions, {} transports", System.currentTimeMillis() - started,
						loadedMap.regionIds().size(), loadedTransports.all().size());
				}
				catch (Throwable e)
				{
					loadFailed = true;
					log.warn("Could not load the path data", e);
				}
				finally
				{
					loading = false;
				}
			});
		}
		return false;
	}

	public boolean isLoadFailed()
	{
		return loadFailed;
	}

	/** Client thread: transports you can use now, checked again every so often. */
	private Map<Integer, List<Transports.Link>> usableNow(boolean fresh)
	{
		if (transports == null)
		{
			return Collections.emptyMap();
		}
		if (usable == null || fresh || usableAge >= TRANSPORT_REFRESH_TICKS)
		{
			Map<Integer, List<Transports.Link>> now = transports.usable(client);
			if (!now.equals(usable))
			{
				usable = now;
				usableVersion++;
			}
			usableAge = 0;
		}
		return usable;
	}

	// --- Calculator -----------------------------------------------------------------

	/**
	 * Client thread. The chunks a quest still needs, with the ones to walk there, or null
	 * while it's being worked out (in the background, so the game doesn't stutter). The
	 * answer's kept until a chunk is unlocked or the transports you can use change; while it's
	 * on screen this is just a lookup.
	 */
	public ChunkCalculator.Result calculate(NuzlockeTask task)
	{
		if (!ensureLoaded() || task == null)
		{
			return null;
		}
		if (usable == null)
		{
			// Not checked on a tick yet: check now, rather than wait (or wait for ever if the
			// ticks aren't reaching here).
			try
			{
				usableNow(true);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not check which transports you can use", e);
				usable = Collections.emptyMap();
			}
		}
		// Worked out again only when a chunk's unlocked (or the transports were checked again).
		String key = task.getTaskId() + ":" + core.getLastUnlockChangeMs() + ":" + usableVersion;
		ChunkCalculator.Result known = results.get(key);
		if (known != null || !working.add(key))
		{
			return known;
		}
		if (results.size() > 30)
		{
			results.clear();
		}
		// Everything the game state says, read now on the client thread; the search runs elsewhere.
		Set<Integer> needed = quests.lockedChunksFor(task);
		Set<Integer> open = new HashSet<>();
		Map<Integer, Integer> weights = new HashMap<>();
		for (int region : map.regionIds())
		{
			// Free and charter chunks unlock as you walk in, at no cost, so they're open too.
			if (quests.reachable(region) || quests.freeToEnter(region))
			{
				open.add(region);
			}
			else
			{
				weights.put(region, quests.unlockWeight(region));
			}
		}
		Player player = client.getLocalPlayer();
		int here = player == null ? -1 : PathTile.pack(WorldPoint.fromLocalInstance(client, player.getLocalLocation()));
		Map<Integer, List<Transports.Link>> links = usable;
		ChunkCalculator calc = calculator;
		executor.submit(() ->
		{
			try
			{
				results.put(key, calc.calculate(needed, open::contains, r -> weights.getOrDefault(r, 1), links, here));
			}
			catch (Throwable e)
			{
				log.warn("Chunk calculator failed", e);
			}
			finally
			{
				working.remove(key);
			}
		});
		return null;
	}

	// --- Paths ----------------------------------------------------------------------

	public int[] path()
	{
		return path;
	}

	public boolean reached()
	{
		return reached;
	}

	/** Client thread: the path just asked for goes to a quest's start (Quest Helper's button). */
	public void markQuestStart()
	{
		questStart = true;
	}

	/** Whether the path is still the one to a quest's start, not since moved on by Quest Helper. */
	public boolean isQuestStart()
	{
		return questStart && !fromQuestHelper;
	}

	/** The transport the path takes from one tile to the next, or null if it walks. */
	public Transports.Link link(int from, int to)
	{
		Map<Integer, List<Transports.Link>> links = usable;
		List<Transports.Link> here = links == null ? null : links.get(from);
		if (here != null)
		{
			for (Transports.Link link : here)
			{
				if (link.to == to)
				{
					return link;
				}
			}
		}
		return null;
	}

	/** Index of the path tile nearest the player; the overlays draw from here on. */
	public int progress()
	{
		return progress;
	}

	/** Client thread: head for {@code tile}. */
	public void pathTo(int tile)
	{
		pathTo(tile, Collections.emptySet());
	}

	// Locked chunks a path may go through when your own chunks don't reach (a quest's chunks).
	private volatile Set<Integer> fallback = Collections.emptySet();

	/**
	 * Client thread: head for {@code tile} through your chunks; if they don't reach it, through
	 * {@code fallback} as well (the chunks a quest needs), saying so.
	 */
	public void pathTo(int tile, Set<Integer> fallback)
	{
		if (!enabled())
		{
			return;
		}
		// Whoever asked for this path (a task, Quest Helper) says so after; a fresh one is nobody's.
		taskPath = null;
		fromQuestHelper = false;
		questStart = false;
		this.fallback = new HashSet<>(fallback);
		// Two paths at once would just be confusing: clear Shortest Path's, if it's installed,
		// through the message it offers other plugins for that.
		postingClear = true;
		try
		{
			eventBus.post(new PluginMessage(SHORTEST_PATH, "clear"));
		}
		finally
		{
			postingClear = false;
		}
		target = tile;
		path = null;
		reached = false;
		search();
	}

	public void clear()
	{
		taskPath = null;
		fromQuestHelper = false;
		questStart = false;
		generation.incrementAndGet();
		target = -1;
		path = null;
		searching = false;
		pending = false;
	}

	/** Client thread. Starts a search from where the player stands, or waits for the data. */
	private void search()
	{
		Player player = client.getLocalPlayer();
		if (target < 0 || player == null)
		{
			return;
		}
		if (!ensureLoaded())
		{
			pending = true;
			searching = true;
			return;
		}
		pending = false;
		int start = PathTile.pack(WorldPoint.fromLocalInstance(client, player.getLocalLocation()));
		Set<Integer> open = new HashSet<>();
		for (int region : map.regionIds())
		{
			// Free and charter chunks unlock as you walk in, at no cost, so they're open too.
			if (quests.reachable(region) || quests.freeToEnter(region))
			{
				open.add(region);
			}
		}
		// Wherever you're standing counts, or you couldn't step off it.
		open.add(PathTile.region(start));
		Map<Integer, List<Transports.Link>> links = usableNow(false);
		int goal = target;
		int run = generation.incrementAndGet();
		searching = true;
		unlockStamp = core.getLastUnlockChangeMs();
		cooldown = 5;
		CollisionMap tiles = map;
		Set<Integer> extra = fallback;
		executor.submit(() ->
		{
			try
			{
				ChunkPathfinder.Result result = ChunkPathfinder.find(tiles, links, open::contains, start, goal,
					() -> generation.get() != run);
				if (!result.reached && !extra.isEmpty() && generation.get() == run)
				{
					// Your chunks don't get there: try again through the quest's chunks too.
					Set<Integer> wider = new HashSet<>(open);
					wider.addAll(extra);
					ChunkPathfinder.Result through = ChunkPathfinder.find(tiles, links, wider::contains, start, goal,
						() -> generation.get() != run);
					if (through.reached)
					{
						result = through;
						message("Your chunks don't reach it yet, so this path goes through the quest's chunks.");
					}
				}
				if (generation.get() != run)
				{
					return;
				}
				path = result.path;
				reached = result.reached;
				progress = 0;
			}
			catch (Throwable e)
			{
				log.warn("Path search failed", e);
			}
			finally
			{
				searching = false;
			}
		});
	}

	private void message(String text)
	{
		chat.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value("<col=ff9628>ChunkBlazer:</col> " + text)
			.build());
	}

	// --- Paths other things ask for --------------------------------------------------

	// Our own "clear" to Shortest Path is heard by us too: ignored.
	private boolean postingClear;
	// The path is Quest Helper's current step (it asked Shortest Path for it).
	private boolean fromQuestHelper;
	// The path is to a quest's start, from the quest pane's Quest Helper button.
	private boolean questStart;
	// The tracked task this path heads for, and whether it's still only to its chunk (no NPC seen yet).
	private String taskPath;
	private boolean toChunkOnly;
	private String lastTracked;

	/**
	 * Quest Helper, with its "Use Shortest Path" option on, asks the Shortest Path plugin for a
	 * path to each step of the quest. That same message is heard here, so each step gets a
	 * path through your chunks (through the quest's own chunks when yours don't reach).
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!SHORTEST_PATH.equals(event.getNamespace()) || postingClear || !enabled())
		{
			return;
		}
		if ("clear".equals(event.getName()))
		{
			if (fromQuestHelper || questStart)
			{
				clientThread.invoke(this::clear);
			}
			return;
		}
		if (!"path".equals(event.getName()))
		{
			return;
		}
		Object to = event.getData().get("target");
		if (!(to instanceof WorldPoint))
		{
			return;
		}
		int tile = PathTile.pack((WorldPoint) to);
		clientThread.invoke(() ->
		{
			NuzlockeTask tracked = core.getSelectedTask();
			Set<Integer> through = tracked != null && QuestRequirements.isQuestTask(tracked)
				? quests.lockedChunksFor(tracked) : Collections.emptySet();
			pathTo(tile, through);
			fromQuestHelper = true;
		});
	}

	/**
	 * Client thread: a path to where the tracked task is done: its NPC if one's in sight, or
	 * else its chunk (then its NPC once you're close enough to see it). Quests are left to
	 * Quest Helper's steps.
	 */
	public void pathToTask(NuzlockeTask task)
	{
		if (!enabled() || task == null || task.getTaskId() == null || QuestRequirements.isQuestTask(task))
		{
			return;
		}
		int npc = taskNpcTile(task);
		if (npc >= 0)
		{
			pathTo(npc);
			taskPath = task.getTaskId();
			toChunkOnly = false;
			return;
		}
		int region = core.findRegionForTask(task.getTaskId());
		if (region <= 0)
		{
			return;
		}
		pathTo(walkableNear(region));
		taskPath = task.getTaskId();
		toChunkOnly = true;
	}

	/** The nearest of the task's NPCs in the scene (by id, or by the names it's outlined by), or -1. */
	private int taskNpcTile(NuzlockeTask task)
	{
		Set<Integer> ids = new HashSet<>(TaskSources.npcIds(task));
		if (task.getTargetNpc() != null && task.getTargetNpc().getNpcIds() != null)
		{
			ids.addAll(task.getTargetNpc().getNpcIds());
		}
		Set<String> names = new HashSet<>();
		for (String name : TaskSources.outlineNames(task))
		{
			names.add(name.toLowerCase());
		}
		Player player = client.getLocalPlayer();
		if (player == null || ids.isEmpty() && names.isEmpty())
		{
			return -1;
		}
		int here = PathTile.pack(WorldPoint.fromLocalInstance(client, player.getLocalLocation()));
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc == null || !ids.contains(npc.getId()) && (npc.getName() == null || !names.contains(npc.getName().toLowerCase())))
			{
				continue;
			}
			int tile = PathTile.pack(WorldPoint.fromLocalInstance(client, npc.getLocalLocation()));
			int distance = PathTile.distance(here, tile);
			if (distance < bestDistance)
			{
				best = tile;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** A walkable tile as near the middle of {@code region} as there is (its middle when the map isn't loaded). */
	private int walkableNear(int region)
	{
		int baseX = (region >> 8) << 6;
		int baseY = (region & 0xFF) << 6;
		CollisionMap tiles = map;
		if (tiles != null)
		{
			for (int r = 0; r < 32; r++)
			{
				for (int dx = -r; dx <= r; dx++)
				{
					for (int dy = -r; dy <= r; dy++)
					{
						if (Math.max(Math.abs(dx), Math.abs(dy)) == r && tiles.walkable(baseX + 32 + dx, baseY + 32 + dy, 0))
						{
							return PathTile.pack(baseX + 32 + dx, baseY + 32 + dy, 0);
						}
					}
				}
			}
		}
		return PathTile.pack(baseX + 32, baseY + 32, 0);
	}

	/**
	 * Each tick: a newly tracked task gets a path to it (unless you're already in its chunk),
	 * and a path that only went to the task's chunk heads for its NPC once one's in sight.
	 */
	private void followTrackedTask()
	{
		NuzlockeTask tracked = core.getSelectedTask();
		String id = tracked == null ? null : tracked.getTaskId();
		if (id == null ? lastTracked != null : !id.equals(lastTracked))
		{
			lastTracked = id;
			if (taskPath != null)
			{
				clear();
			}
			if (tracked != null && core.findRegionForTask(id) != core.getCurrentRegionId())
			{
				pathToTask(tracked);
			}
			return;
		}
		if (taskPath != null && toChunkOnly && tracked != null && taskPath.equals(id))
		{
			int npc = taskNpcTile(tracked);
			if (npc >= 0)
			{
				pathTo(npc);
				taskPath = id;
				toChunkOnly = false;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		usableAge++;
		if (enabled() && client.getGameState() == GameState.LOGGED_IN)
		{
			followTrackedTask();
		}
		// Checked once when the data's loaded; after that only when a path is worked out
		// (usableNow), not over and over while the calculator's answer sits on screen.
		if (calculator != null && usable == null && client.getGameState() == GameState.LOGGED_IN)
		{
			usableNow(true);
		}
		if (target < 0)
		{
			return;
		}
		if (pending)
		{
			if (calculator != null)
			{
				search();
			}
			return;
		}
		Player player = client.getLocalPlayer();
		int[] tiles = path;
		if (player == null || tiles == null || searching)
		{
			return;
		}
		int here = PathTile.pack(WorldPoint.fromLocalInstance(client, player.getLocalLocation()));
		if (reached && PathTile.distance(here, target) <= 1)
		{
			message("You've arrived.");
			clear();
			return;
		}
		// Follow the player along the path: the nearest tile from a little behind where they were.
		int from = Math.max(0, progress - 5);
		int bestIndex = progress;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = from; i < tiles.length && i < from + 60; i++)
		{
			int d = PathTile.distance(here, tiles[i]);
			if (d < bestDistance)
			{
				bestDistance = d;
				bestIndex = i;
			}
		}
		progress = bestIndex;
		if (cooldown > 0)
		{
			cooldown--;
			return;
		}
		if (bestDistance > OFF_PATH || core.getLastUnlockChangeMs() != unlockStamp)
		{
			search();
		}
	}

	// --- Menus ----------------------------------------------------------------------

	/**
	 * Added only when a right-click menu opens, never while the game builds the menu for
	 * hovering, so a left-click (dragging the world map) can't pick it.
	 */
	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			pathKeyHeld = false;
		}
	}

	/** Shortest path switched off: the path showing goes too. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (ChunkBlazerUiConfig.GROUP.equals(event.getGroup()) && "shortestPath".equals(event.getKey()) && !enabled())
		{
			clientThread.invoke(this::clear);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!enabled())
		{
			return;
		}
		Widget worldMap = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		Point mouse = client.getMouseCanvasPosition();
		if (worldMap != null && mouse != null && worldMap.getBounds().contains(mouse.getX(), mouse.getY()))
		{
			int tile = mapTileAt(worldMap, mouse);
			if (tile >= 0)
			{
				add(OPTION_PATH, () -> pathTo(tile));
			}
			if (target >= 0)
			{
				add(OPTION_CLEAR, this::clear);
			}
			return;
		}
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			Tile selected = client.getTopLevelWorldView().getSelectedSceneTile();
			if (selected != null)
			{
				int tile = PathTile.pack(WorldPoint.fromLocalInstance(client, selected.getLocalLocation()));
				add(OPTION_PATH, () -> pathTo(tile));
			}
			if (target >= 0)
			{
				add(OPTION_CLEAR, this::clear);
			}
		}
	}

	private void add(String option, Runnable action)
	{
		for (MenuEntry entry : client.getMenu().getMenuEntries())
		{
			if (option.equals(entry.getOption()) && TARGET.equals(entry.getTarget()))
			{
				return;
			}
		}
		client.getMenu().createMenuEntry(1)
			.setOption(option)
			.setTarget(TARGET)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> action.run());
	}

	/** The world map tile under a point on screen (plane 0), or -1. */
	private int mapTileAt(Widget widget, Point at)
	{
		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null || worldMap.getWorldMapPosition() == null)
		{
			return -1;
		}
		java.awt.Rectangle bounds = widget.getBounds();
		float zoom = worldMap.getWorldMapZoom();
		Point centre = worldMap.getWorldMapPosition();
		int x = centre.getX() + (int) Math.floor((at.getX() - (bounds.x + bounds.width / 2)) / zoom);
		int y = centre.getY() - (int) Math.floor((at.getY() - (bounds.y + bounds.height / 2)) / zoom);
		return x < 0 || y < 0 ? -1 : PathTile.pack(x, y, 0);
	}
}
