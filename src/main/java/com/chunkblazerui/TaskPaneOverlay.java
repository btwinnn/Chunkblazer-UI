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

import static com.chunkblazerui.TaskLists.*;
import static com.chunkblazerui.TaskStyle.*;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import com.chunkblazerui.pathing.ChunkCalculator;
import com.chunkblazerui.pathing.ChunkPathing;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.LinkBrowser;

/**
 * The quest/saved box over the inventory (or free-standing, with Movable Quest/Saved Box), and
 * the overlay the task window draws in: the window (TaskWindowHook) and the box share the mouse
 * and keys. It also starts the rest of the in-game task UI with the orbs.
 */
@Singleton
public class TaskPaneOverlay extends Overlay
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private net.runelite.client.plugins.PluginManager pluginManager;

	@Inject
	private ChunkBlazerUiConfig config;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private WorldMapLegendOverlay legend;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private QuestRequirements quests;

	@Inject
	private SavedTaskTracker savedTracker;

	private final ChunkBlazerCore plugin;
	@Inject
	private ConfigManager configManager;
	@Inject
	private TaskLists lists;
	@Inject
	private TaskWindow taskWindow;
	@Inject
	private TaskItemOverlay itemOverlay;
	@Inject
	private ChunkNameBanner banner;
	@Inject
	private ItemManager itemManager;
	@Inject
	private ChunkPathing pathing;

	// Quests with the Calc button on: their Chunks list includes the chunks to walk there.
	private final Set<String> calculated = ConcurrentHashMap.newKeySet();
	// The walk-there chunks shown so far, and when each one started sliding in. They come in
	// one after another, so the list grows a line at a time instead of jumping.
	private final Map<Integer, Long> walkShownAt = new ConcurrentHashMap<>();
	private static final int WALK_STAGGER_MS = 90;
	private static final int WALK_SLIDE_MS = 260;
	private static final int WALK_SLIDE_PX = 48;
	private volatile boolean calcWorking;

	// Ticks to wait after login before checking quests (and, in the task window, new tasks).
	private static final int SETTLE_TICKS = 10;
	private int ticksLoggedIn;

	private static final int QUEST_CHECKS_PER_TICK = 15;
	// Saved tasks opened in the saved box (click a task there to show its details under it).
	private final Set<String> paneExpanded = ConcurrentHashMap.newKeySet();
	// A quest link was clicked: scroll to and briefly outline this task.
	// The quest whose requirements are shown over the side panel (null when closed).
	public volatile NuzlockeTask questPane;
	// Stands in for the saved-tasks list in the pane, so Back and closing work the same.
	private static final NuzlockeTask SAVED_PANE = new NuzlockeTask();
	private long questPaneAt;
	// Quests opened from inside the pane, newest first, for its Back link.
	private final Deque<NuzlockeTask> questBack = new ConcurrentLinkedDeque<>();
	// The saved-list row under the mouse, for right-click to unsave.
	private volatile String paneRowId;
	// Where the side panel was when the box was last drawn, to notice it moving.
	private Rectangle paneSide;
	public volatile Rectangle taskButton;

	public final List<Hit> hits = new ArrayList<>();
	public volatile Runnable hoveredAction;
	public volatile boolean mouseInWindow;
	// Where the window or box was last drawn, on screen (null when neither is shown).
	public volatile Rectangle shownArea;
	// The quest/saved box can be up with the window open: its own scroll, area and hover.
	private volatile int paneScroll;
	public volatile boolean paneIn;
	private volatile Rectangle paneArea;
	// A hint to show by the mouse this frame (set while drawing, drawn last by drawTip).
	public String tip;
	private String tipQuest;
	private long tipQuestAt;
	private boolean tipQuestFound;

	// The mouse as the window and pane see it; the game stops following it over them.
	private volatile java.awt.Point paneMouse = new java.awt.Point(-1, -1);
	private final MouseAdapter mouse = new MouseAdapter()
	{
		// Over the task window or the quest/saved box, the game is told the mouse left the
		// screen, so nothing under it (inventory items, NPCs...) keeps hover text or a tooltip.
		// (Swallowing the move left the game's mouse where it entered, stuck on what was there.)
		// The last drawn area is checked too: mouseInWindow is a frame behind on the way in.
		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			paneMouse = event.getPoint();
			inputAt = System.currentTimeMillis();
			Rectangle area = shownArea;
			Rectangle box = paneArea;
			if ((isWindowOpen() || questPane != null) && (mouseInWindow || area != null && area.contains(event.getPoint())
				|| box != null && questPane != null && box.contains(event.getPoint())))
			{
				return new MouseEvent(event.getComponent(), event.getID(), event.getWhen(), event.getModifiersEx(),
					-1, -1, 0, false);
			}
			return event;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			inputAt = System.currentTimeMillis();
			// The window's search box and its X come first (see TaskWindowHook.pressedFirst).
			TaskWindowHook window = window();
			if (window != null && window.pressedFirst(event))
			{
				return event;
			}
			// Alt + click is RuneLite moving the free-standing quest/saved box.
			boolean open = isWindowOpen();
			if (!open && questPane == null || !mouseInWindow || event.isAltDown())
			{
				return event;
			}
			if (event.getButton() == MouseEvent.BUTTON1 && window != null && window.startDrag(event))
			{
				// Dragging the window by its title.
			}
			else if (event.getButton() == MouseEvent.BUTTON1 && hoveredAction != null)
			{
				hoveredAction.run();
			}
			else if (event.getButton() == MouseEvent.BUTTON3 && (!open || paneIn) && paneRowId != null)
			{
				// Right-click unsaves in the saved list, as it unstars in the side panel.
				lists.toggleSaved(paneRowId);
			}
			else if (event.getButton() == MouseEvent.BUTTON3 && open && window.listRowId() != null)
			{
				// Right-click saves (or unsaves) a task in the list, as it stars it in the side panel.
				lists.toggleSaved(window.listRowId());
			}
			// Clicks inside the window never reach the game.
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			inputAt = System.currentTimeMillis();
			TaskWindowHook window = window();
			if (window != null)
			{
				window.drag(event);
			}
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			inputAt = System.currentTimeMillis();
			TaskWindowHook window = window();
			if (window != null)
			{
				window.release(event);
			}
			return event;
		}
	};

	private final MouseWheelListener wheel = event ->
	{
		inputAt = System.currentTimeMillis();
		if ((isWindowOpen() || questPane != null) && mouseInWindow)
		{
			TaskWindowHook window = window();
			if (paneIn && questPane != null)
			{
				paneScroll += event.getWheelRotation() * ROW;
			}
			else if (window != null)
			{
				window.scrollBy(event.getWheelRotation() * ROW);
			}
			event.consume();
		}
		return event;
	};

	// Esc closes the window and the quest/saved box (F-keys close the box too).
	private final KeyListener keys = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent event)
		{
			inputAt = System.currentTimeMillis();
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			inputAt = System.currentTimeMillis();
			TaskWindowHook window = window();
			if (window != null)
			{
				window.tasksKey(event, true);
			}
			// Esc closes the quest/saved box, and so do the F-key tab hotkeys while it covers the
			// inventory, so it never blocks a prayer switch. Typing in chat and moving the camera
			// leave it open (switching tab any other way closes it too: onVarClientIntChanged).
			int code = event.getKeyCode();
			boolean hadPane = questPane != null;
			if (code == KeyEvent.VK_ESCAPE || code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12 && !config.movableTaskBox())
			{
				questPane = null;
			}
			if (window != null && window.isOpen() && event.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				// With the quest box up too, Esc closes just the box first.
				if (!hadPane)
				{
					window.close();
				}
				event.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			inputAt = System.currentTimeMillis();
			TaskWindowHook window = window();
			if (window != null)
			{
				window.tasksKey(event, false);
			}
		}
	};

	@Inject
	public TaskPaneOverlay(ChunkBlazerCore plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
		freeBox = new Overlay(plugin.owner())
		{
			@Override
			public String getName()
			{
				return "ChunkBlazerTaskBox";
			}

			@Override
			public Dimension render(Graphics2D graphics)
			{
				Rectangle at = getBounds();
				if (!config.movableTaskBox() || questPane == null || at == null
					|| client.getGameState() != GameState.LOGGED_IN)
				{
					return null;
				}
				Rectangle size = sidePanel();
				drawQuestPane(graphics, new Rectangle(0, 0, size.width, size.height), at.x, at.y);
				return new Dimension(size.width, size.height);
			}
		};
		freeBox.setPosition(OverlayPosition.BOTTOM_RIGHT);
		freeBox.setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** The task window, or null without ChunkBlazer UI. */
	private TaskWindowHook window()
	{
		return taskWindow;
	}

	/** True while ChunkBlazer UI's task window is open. */
	private boolean isWindowOpen()
	{
		TaskWindowHook window = window();
		return window != null && window.isOpen();
	}

	// With "Movable Quest/Saved Box" on, the box is its own overlay, so RuneLite's
	// Alt + drag moves it and remembers where; it keeps the inventory's size.
	private final Overlay freeBox;
	/**
	 * With the orbs (ChunkBlazerUi.startTaskUi): this overlay, the window's input, the saved-task
	 * bubble, item outlines, chunk name banner and map legend.
	 */
	public void startUp()
	{
		overlayManager.add(this);
		overlayManager.add(freeBox);
		mouseManager.registerMouseListener(mouse);
		mouseManager.registerMouseListener(legend.mouse);
		mouseManager.registerMouseWheelListener(wheel);
		keyManager.registerKeyListener(keys);
		eventBus.register(this);
		eventBus.register(taskWindow);
		savedTracker.startUp();
		overlayManager.add(itemOverlay);
		overlayManager.add(banner);
		overlayManager.add(legend);
	}

	public void shutDown()
	{
		taskWindow.shutDown();
		eventBus.unregister(taskWindow);
		overlayManager.remove(this);
		overlayManager.remove(freeBox);
		mouseManager.unregisterMouseListener(mouse);
		mouseManager.unregisterMouseListener(legend.mouse);
		mouseManager.unregisterMouseWheelListener(wheel);
		keyManager.unregisterKeyListener(keys);
		eventBus.unregister(this);
		savedTracker.shutDown();
		overlayManager.remove(itemOverlay);
		overlayManager.remove(banner);
		overlayManager.remove(legend);
	}

	/** Where the Points orb is on screen (null if hidden), for the new-task alert. */
	public void setTaskButtonBounds(Rectangle bounds)
	{
		taskButton = bounds;
	}

	public boolean isSavedPaneOpen()
	{
		return questPane == SAVED_PANE;
	}

	// The saved tasks box's first task you can track (not a quest), from the last frame, for the tutorial.
	private volatile Rectangle savedRow;

	/** The first trackable task in the saved tasks box, or null while it isn't showing one. */
	public Rectangle getSavedRow()
	{
		return isSavedPaneOpen() ? savedRow : null;
	}

	/** Keeps quest requirements fresh while the window or box shows them, and looks for new tasks. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || configManager.getRSProfileKey() == null)
		{
			return;
		}
		if (++ticksLoggedIn < SETTLE_TICKS)
		{
			return;
		}
		if (isWindowOpen() || questPane != null)
		{
			quests.refreshStates(QUEST_CHECKS_PER_TICK);
		}
		TaskWindowHook window = window();
		if (window != null)
		{
			window.settledTick();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			ticksLoggedIn = 0;
			TaskWindowHook window = window();
			if (window != null)
			{
				window.loggedOut();
			}
		}
	}

	/** The saved bubble: open the saved list over the side panel, or close it. */
	void toggleSavedPane()
	{
		if (questPane == SAVED_PANE)
		{
			questPane = null;
		}
		else
		{
			openQuestPane(SAVED_PANE);
		}
	}

	// --- Drawing --------------------------------------------------------------

	/** The task window (ChunkBlazer UI) with this box over it, or just the box. */
	@Override
	public Dimension render(Graphics2D graphics)
	{
		TaskWindowHook window = window();
		if (window != null)
		{
			window.beforeFrame();
		}
		boolean open = window != null && window.isOpen();
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (questPane == null)
		{
			paneSide = null;
			paneIn = false;
			paneArea = null;
		}
		if (!open && questPane != null && loggedIn)
		{
			shownArea = null;
			drawSidePane(graphics);
			return null;
		}
		if (!open || !loggedIn)
		{
			hoveredAction = null;
			mouseInWindow = false;
			shownArea = null;
			if (!open && loggedIn && window != null)
			{
				window.renderClosed(graphics);
			}
			return null;
		}
		drawWindow(graphics, window);
		return null;
	}

	// The open window (and the quest box beside it), as last drawn: while the mouse is still and
	// nothing's been clicked or typed, that picture is copied rather than the whole window drawn
	// again every frame. It's still drawn afresh a few times a second, for anything that changes
	// on its own (the search box's caret, progress coming in).
	private static final long REDRAW_MS = 250;
	// How long after the mouse moves (or anything's clicked or typed) the window is drawn live.
	private static final long LIVE_MS = 400;
	private volatile long inputAt;
	// Drawn live until then too: something's moving on its own (the calculator's chunks sliding in).
	private volatile long liveUntil;

	/** A new list (search, filters, sort) shows straight away, not on the next redraw. */
	@Inject
	void redrawOnListChanges(TaskLists lists)
	{
		lists.onRefresh(() -> windowImageGood = false);
		lists.onChange(() -> windowImageGood = false);
	}
	private java.awt.image.BufferedImage windowImage;
	private long windowDrawnAt;
	private volatile boolean windowImageGood;
	private Rectangle windowImageArea;

	private void drawWindow(Graphics2D graphics, TaskWindowHook window)
	{
		long now = System.currentTimeMillis();
		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();
		boolean live = now - inputAt < LIVE_MS || now < liveUntil || !graphics.getTransform().isIdentity() || w <= 0 || h <= 0;
		if (live)
		{
			windowImageGood = false;
			hits.clear();
			window.render(graphics, paneMouse.x, paneMouse.y);
			return;
		}
		if (!windowImageGood || now - windowDrawnAt >= REDRAW_MS || windowImage == null
			|| windowImage.getWidth() != w || windowImage.getHeight() != h)
		{
			if (windowImage == null || windowImage.getWidth() != w || windowImage.getHeight() != h)
			{
				windowImage = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			}
			Graphics2D g = windowImage.createGraphics();
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, w, h);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
			g.setRenderingHints(graphics.getRenderingHints());
			g.setFont(graphics.getFont());
			hits.clear();
			window.render(g, paneMouse.x, paneMouse.y);
			g.dispose();
			windowDrawnAt = now;
			windowImageGood = true;
			windowImageArea = drawnArea(w, h);
		}
		Rectangle a = windowImageArea;
		graphics.drawImage(windowImage, a.x, a.y, a.x + a.width, a.y + a.height, a.x, a.y, a.x + a.width, a.y + a.height, null);
	}

	/** Where the window, the quest box beside it and a tooltip by the mouse can be. */
	private Rectangle drawnArea(int w, int h)
	{
		Rectangle area = new Rectangle(paneMouse.x - 320, paneMouse.y - 40, 640, 80);
		for (Rectangle r : new Rectangle[]{shownArea, paneArea, taskButton})
		{
			if (r != null)
			{
				area = area.union(r);
			}
		}
		area.grow(8, 8);
		return area.intersection(new Rectangle(0, 0, w, h));
	}

	/** The quest/saved box over the side panel. Anything that moves the side panel out from under it closes it. */
	public void drawSidePane(Graphics2D graphics)
	{
		Rectangle side = sidePanel();
		if (paneSide != null && !paneSide.equals(side))
		{
			questPane = null;
		}
		else if (!config.movableTaskBox())
		{
			drawQuestPane(graphics, side, 0, 0);
		}
		paneSide = side;
	}

	/**
	 * Detail lines from (x, y). Clickable ones get a hit: a section header opens/closes, a
	 * chunk shows on the world map (now, or as soon as it's opened), a quest opens in the pane.
	 */
	public void drawDetail(Graphics2D graphics, List<QuestRequirements.Line> detail, int x, int y, Rectangle clip,
		int mx, int my, Font small)
	{
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		boolean in = clip.contains(mx, my);
		for (int i = 0; i < detail.size(); i++)
		{
			QuestRequirements.Line line = detail.get(i);
			Rectangle area = detailLineArea(sm, detail, x, y, i);
			if (line.clickable() && in)
			{
				hits.add(new Hit(area.intersection(clip), line.region > 0 ? () -> plugin.focusRegion(line.region)
					: line.quest != null ? () -> showQuest(line.quest)
					: line.wiki != null ? () -> LinkBrowser.browse(line.wiki)
					: () -> quests.toggleSection(line.toggle)));
			}
			int lineY = area.y + 1 + sm.getAscent();
			boolean hover = line.clickable() && in && area.contains(mx, my);
			if (hover && line.region > 0)
			{
				tip = isMapOpen() ? "Click to show it on the world map" : "Click, then open the world map to see it";
			}
			else if (hover && line.wiki != null)
			{
				tip = "Click to open this item on the OSRS Wiki";
			}
			else if (hover && line.quest != null)
			{
				tip = hasQuestTask(line.quest) ? "Click to see this quest's requirements"
					: "Not in your task list (done, or not a ChunkBlazer quest)";
			}
			// Section headings sit on a faint band; anything clickable lights up under the mouse.
			if (line.header || hover)
			{
				graphics.setColor(hover ? ROW_HOVER : ROW_ALT);
				graphics.fillRect(x - 3, area.y, line.header ? clip.width - 12 : area.width + 6, DETAIL_LINE);
			}
			if (line.header)
			{
				arrow(graphics, x + 3, lineY - 1 - sm.getAscent() / 2, line.open, hover);
			}
			int lineX = x + lineIndent(line);
			// A walk-there chunk just added slides in from the right, fading up.
			float slide = slide(line);
			Composite before = graphics.getComposite();
			if (slide > 0)
			{
				lineX += Math.round(WALK_SLIDE_PX * slide);
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1 - slide));
			}
			graphics.setColor(lineColor(line.color));
			Skin.text(graphics, line.text, lineX, lineY);
			graphics.setComposite(before);
		}
	}

	/** A task's detail lines, wrapped to {@code width}. */
	public List<QuestRequirements.Line> wrapDetail(FontMetrics sm, NuzlockeTask task, int width)
	{
		List<QuestRequirements.Line> wrapped = new ArrayList<>();
		for (QuestRequirements.Line line : detailLines(task))
		{
			boolean first = true;
			for (String part : wrap(sm, line.text, width - lineIndent(line)))
			{
				wrapped.add(line.withText(part, first));
				first = false;
			}
		}
		return wrapped;
	}

	/** A prerequisite quest was clicked: open it in the pane, or say in chat that it isn't a task right now. */
	private void showQuest(String questName)
	{
		NuzlockeTask found = lists.questTask(questName);
		if (found != null)
		{
			navigateTo(found);
		}
	}

	/** For the hover tip: whether the quest is in your task list, rechecked at most once a second. */
	private boolean hasQuestTask(String questName)
	{
		long now = System.currentTimeMillis();
		if (!questName.equals(tipQuest) || now - tipQuestAt > 1000)
		{
			tipQuest = questName;
			tipQuestAt = now;
			tipQuestFound = lists.questTask(questName) != null;
		}
		return tipQuestFound;
	}

	/**
	 * Show a quest's requirements over the side panel (inventory, prayer...), not over its
	 * tab buttons. The task window stays open beside it. It has its own close button,
	 * and switching tab or pressing any key closes it too.
	 */
	public void openQuestPane(NuzlockeTask task)
	{
		// Opened fresh (window, side panel, bubble): no Back trail from whatever was open before.
		questBack.clear();
		paneSide = null;
		showInPane(task);
	}

	/** Follow a link inside the box (a required quest, a saved quest): Back returns here. */
	private void navigateTo(NuzlockeTask task)
	{
		if (questPane != null && questPane != task)
		{
			questBack.push(questPane);
		}
		showInPane(task);
	}

	private void showInPane(NuzlockeTask task)
	{
		questPane = task;
		questPaneAt = System.currentTimeMillis();
		paneScroll = 0;
	}

	/** The quest/saved box at {@code pane}; (ox, oy) is where that's drawn on screen when it's moved. */
	private void drawQuestPane(Graphics2D graphics, Rectangle pane, int ox, int oy)
	{
		NuzlockeTask task = questPane;
		int mx = paneMouse.x - ox;
		int my = paneMouse.y - oy;
		Runnable windowHover = hoveredAction;
		Rectangle windowArea = shownArea;
		boolean windowIn = isWindowOpen() && windowArea != null && windowArea.contains(paneMouse);
		hits.clear();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// A rounded card with a darker header band and an orange rule under it.
		boolean savedList = task == SAVED_PANE;
		List<NuzlockeTask> saved = savedList ? lists.savedTasks() : null;
		graphics.setFont(Skin.bold());
		boolean isQuest = !savedList && QuestRequirements.isQuestTask(task);
		List<String> title = wrap(graphics.getFontMetrics(), savedList ? "Saved tasks (" + saved.size() + ")"
			: task.getName().replaceFirst("^Complete ", ""), pane.width - (questBack.isEmpty() ? 62 : 96) - (isQuest ? 33 : 0));
		int top = pane.y + 14 + title.size() * 15;
		Skin.panel(graphics, new Rectangle(pane.x, pane.y, pane.width, pane.height), top - pane.y, null);
		Rectangle close = new Rectangle(pane.x + pane.width - 21, pane.y + 5, 16, 16);
		hits.add(new Hit(close, () -> questPane = null));
		Skin.close(graphics, close, close.contains(mx, my));
		// The quest's OSRS Wiki page, beside the close button.
		graphics.setFont(Skin.small());
		Rectangle wiki = new Rectangle(close.x - 30, close.y + 1, 27, 14);
		if (!savedList && isQuest && questHelperOn())
		{
			// Quest Helper is on: open this quest in it.
			String quest = questName(task);
			hits.add(new Hit(wiki, () -> clientThread.invoke(() ->
			{
				openInQuestHelper(quest);
				pathToStart(task);
			})));
			boolean hover = wiki.contains(mx, my);
			if (hover)
			{
				tip = "Open " + quest + " in Quest Helper" + (config.shortestPath() ? ", with a path to where it starts" : "");
			}
			pill(graphics, wiki, "QH", hover);
		}
		else if (!savedList)
		{
			// The wiki's search jumps straight to the page when the name matches, and
			// lists the closest pages when it doesn't (the Recipe for Disaster parts).
			String quest = task.getName().replaceFirst("^Complete ", "").replace("&", "%26").replace(' ', '+');
			hits.add(new Hit(wiki, () -> LinkBrowser.browse("https://oldschool.runescape.wiki/?title=Special:Search&go=Go&search=" + quest)));
			pill(graphics, wiki, "Wiki", wiki.contains(mx, my));
		}
		// The chunk calculator: which chunks this quest still needs, and the ones to walk there.
		Rectangle calc = new Rectangle(wiki.x - 33, close.y + 1, 30, 14);
		// Hidden with Shortest path off (Settings > Assists).
		boolean showCalc = isQuest && config.shortestPath();
		if (showCalc)
		{
			String id = task.getTaskId();
			boolean on = calculated.contains(id);
			hits.add(new Hit(calc, () ->
			{
				if (!calculated.remove(id))
				{
					// Open the Chunks list now, on the click, so all that's left to change
					// when the answer comes is the new chunks sliding in.
					calculated.add(id);
					quests.openSection(id + ":chunks");
				}
				walkShownAt.clear();
			}));
			boolean hover = calc.contains(mx, my);
			if (hover)
			{
				tip = on ? "Take the chunks to walk there off the list" : "Add the chunks you'd need to walk there to the Chunks list";
			}
			if (on && calcWorking)
			{
				liveUntil = System.currentTimeMillis() + 100;
			}
			pill(graphics, calc, on && calcWorking ? "..." : "Calc", hover || on);
		}
		// Back to the quest this one was opened from.
		Rectangle back = new Rectangle((showCalc ? calc.x : wiki.x) - 33, close.y + 1, 30, 14);
		if (!questBack.isEmpty())
		{
			hits.add(new Hit(back, () ->
			{
				questPane = questBack.pop();
				questPaneAt = System.currentTimeMillis();
				paneScroll = 0;
			}));
			pill(graphics, back, "Back", back.contains(mx, my));
		}
		// A quick fade and slide in, so moving to a required quest reads as a new page.
		float in = Math.min(1f, (System.currentTimeMillis() - questPaneAt) / 200f);
		Composite oldComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, in));
		graphics.translate((int) (16 * (1 - in) * (1 - in)), 0);
		// The whole name, wrapped, without the "Complete" every quest task starts with.
		graphics.setFont(Skin.bold());
		graphics.setColor(TITLE);
		for (int i = 0; i < title.size(); i++)
		{
			Skin.text(graphics, title.get(i), pane.x + 8, pane.y + 21 + i * 15);
		}

		Font small = Skin.small();
		// The saved list keeps its how-to line pinned at the bottom.
		int foot = savedList && !saved.isEmpty() ? 19 : 0;
		Rectangle body = new Rectangle(pane.x + 1, top + 2, pane.width - 2, pane.y + pane.height - 3 - top - foot);
		if (foot > 0)
		{
			Skin.divider(graphics, pane.x + 6, body.y + body.height, pane.x + pane.width - 8);
			graphics.setFont(small);
			graphics.setColor(SUBTEXT);
			Skin.text(graphics, "Right-click to remove", pane.x + 8, body.y + body.height + 14);
		}
		Shape oldClip = graphics.getClip();
		graphics.clip(body);
		if (savedList)
		{
			drawScrollbar(graphics, body, drawSavedList(graphics, saved, body, mx, my, small), paneScroll);
		}
		else
		{
			List<QuestRequirements.Line> lines = wrapDetail(graphics.getFontMetrics(small), task, pane.width - 12);
			paneScroll = Math.max(0, Math.min(paneScroll, detailHeight(lines) - body.height));
			drawDetail(graphics, lines, pane.x + 6, body.y - paneScroll, body, mx, my, small);
			drawScrollbar(graphics, body, detailHeight(lines), paneScroll);
		}
		graphics.setClip(oldClip);
		graphics.translate(-(int) (16 * (1 - in) * (1 - in)), 0);
		graphics.setComposite(oldComposite);
		paneIn = pane.contains(mx, my);
		paneArea = new Rectangle(pane.x + ox, pane.y + oy, pane.width, pane.height);
		if (paneIn)
		{
			mouseInWindow = true;
			hoveredAction = firstHit(hits, mx, my);
			TaskWindowHook window = window();
			if (window != null)
			{
				window.mouseOverPane();
			}
		}
		else
		{
			mouseInWindow = windowIn;
			hoveredAction = windowIn ? windowHover : null;
		}
		drawTip(graphics, mx, my, ox, oy);
	}

	/**
	 * The hint set while drawing ({@code tip}), in a small box by the mouse, over everything.
	 * Drawn here rather than by RuneLite's tooltips, which follow the game's mouse: over the
	 * window and box the game is told the mouse is off screen. (ox, oy) is where (0, 0) is on screen.
	 */
	public void drawTip(Graphics2D graphics, int mx, int my, int ox, int oy)
	{
		String text = tip;
		tip = null;
		if (text == null)
		{
			return;
		}
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		Rectangle box = new Rectangle(0, 0, fm.stringWidth(text) + 10, 17);
		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		box.x = Math.max(-ox, Math.min(mx + 12, canvasWidth - ox - box.width - 2));
		box.y = my + 20 + box.height > canvasHeight - oy ? my - box.height - 4 : my + 20;
		Skin.tooltip(graphics, box, text);
	}

	/**
	 * The saved list in the pane, tasks in the chunk you're standing in first: name (red if
	 * your level's too low), then its chunk (green if you're in it) or
	 * category and progress. Click to track or untrack (a quest opens its requirements),
	 * Shift + click to unsave. Returns its full height, for the scrollbar.
	 */
	private int drawSavedList(Graphics2D graphics, List<NuzlockeTask> saved, Rectangle body, int mx, int my, Font small)
	{
		graphics.setFont(small);
		FontMetrics fm = graphics.getFontMetrics();
		paneRowId = null;
		if (saved.isEmpty())
		{
			graphics.setColor(SUBTEXT);
			Skin.text(graphics, "Star tasks in the task window", body.x + 5, body.y + 14);
			return 0;
		}
		// Tasks in the chunk you're standing in go first, their chunk in green (a stable sort,
		// so the rest keep their order).
		String hereChunk = lists.currentChunkName();
		saved = new ArrayList<>(saved);
		saved.sort(Comparator.comparing((NuzlockeTask t) -> !lists.isHere(t, hereChunk)));
		// Long names wrap onto a second line (and the card grows) rather than being cut off.
		int width = body.width - 28;
		List<List<String>> names = new ArrayList<>();
		List<List<QuestRequirements.Line>> details = new ArrayList<>();
		int total = 4;
		for (NuzlockeTask task : saved)
		{
			List<String> lines = wrap(fm, task.getName(), width);
			names.add(lines.size() > 2 ? Arrays.asList(lines.get(0), Skin.fit(fm, lines.get(1) + " " + lines.get(2), width)) : lines);
			// Opened: the same details as an expanded task in the window, under the card.
			List<QuestRequirements.Line> detail = paneExpanded.contains(task.getTaskId())
				? wrapDetail(fm, task, width) : Collections.emptyList();
			details.add(detail);
			total += 24 + 12 * names.get(names.size() - 1).size() + paneDetailHeight(detail);
		}
		paneScroll = Math.max(0, Math.min(paneScroll, total - body.height));
		boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		int y = body.y + 4 - paneScroll;
		Rectangle firstRow = null;
		savedRow = null;
		for (int i = 0; i < saved.size(); i++)
		{
			NuzlockeTask task = saved.get(i);
			List<String> name = names.get(i);
			List<QuestRequirements.Line> detail = details.get(i);
			// Each task is a small rounded card: its tier colour down the left, like the side
			// panel's, and a thin progress bar along the bottom for counted tasks.
			Rectangle row = new Rectangle(body.x + 4, y, body.width - 14, 20 + 12 * name.size() + paneDetailHeight(detail));
			y += row.height + 4;
			if (firstRow == null && row.intersects(body) && !QuestRequirements.isQuestTask(task))
			{
				firstRow = row.intersection(body);
				savedRow = firstRow;
			}
			boolean hover = row.contains(mx, my) && body.contains(mx, my);
			boolean isTracked = lists.isTracked(task);
			Color tier = TaskCardTier.fromTask(task).getAccent();
			graphics.setColor(hover && shift ? new Color(255, 70, 70, 50) : hover ? ROW_HOVER : isTracked ? TRACKED_FILL : ROW_ALT);
			graphics.fillRect(row.x, row.y, row.width, row.height);
			graphics.setColor(isTracked ? TITLE : new Color(0, 0, 0, 120));
			graphics.drawRect(row.x, row.y, row.width, row.height);
			graphics.setColor(tier);
			graphics.fillRect(row.x + 2, row.y + 4, 3, row.height - 8);
			int target = Math.max(1, task.getTargetQuantity());
			if (target > 1)
			{
				graphics.setColor(Skin.FILL);
				graphics.fillRect(row.x + 8, row.y + row.height - 3, (row.width - 14) * Math.min(task.getCurrentProgress(), target) / target, 2);
			}
			if (hover)
			{
				paneRowId = task.getTaskId();
			}
			if (body.contains(mx, my))
			{
				hits.add(new Hit(row, () ->
				{
					if (client.isKeyPressed(KeyCode.KC_SHIFT))
					{
						lists.toggleSaved(task.getTaskId());
					}
					else
					{
						// A click opens (or closes) its details too.
						if (!paneExpanded.remove(task.getTaskId()))
						{
							paneExpanded.add(task.getTaskId());
						}
						lists.trackOrOpen(task, this::navigateTo);
					}
				}));
			}
			if (!detail.isEmpty())
			{
				int dy = row.y + 20 + 12 * name.size() + 2;
				graphics.setColor(new Color(0, 0, 0, 60));
				graphics.fillRect(row.x + 8, dy - 2, row.width - 12, paneDetailHeight(detail) - 2);
				for (QuestRequirements.Line line : detail)
				{
					dy += line.gap;
					Skin.text(graphics, line.text, row.x + 12 + lineIndent(line), dy + 10, line.color);
					dy += DETAIL_LINE;
				}
			}
			String progress = target > 1 ? Math.min(task.getCurrentProgress(), target) + "/" + target : "";
			String where = plugin.isGlobalTask(task.getTaskId()) ? NuzlockeTask.displayCategory(task.getCategory())
				: lists.chunkNames.computeIfAbsent(task.getTaskId(), k -> lists.chunkName(task));
			// Red when you can't do it yet, with why ("(Lvl 40)") ahead of where it is.
			boolean ready = lists.canDo(task);
			graphics.setColor(hover && shift || !ready ? NO_LEVEL : Color.WHITE);
			for (int l = 0; l < name.size(); l++)
			{
				Skin.text(graphics, hover && shift && l == 0 ? Skin.fit(fm, "Remove " + name.get(0), width) : name.get(l),
					row.x + 8, row.y + 14 + 12 * l);
			}
			int lineY = row.y + 15 + 12 * name.size();
			String why = ready ? "" : lists.levelNote(task) + " ";
			graphics.setColor(NO_LEVEL);
			Skin.text(graphics, why, row.x + 8, lineY);
			graphics.setColor(lists.isHere(task, hereChunk) ? HERE : SUBTEXT);
			Skin.text(graphics, Skin.fit(fm, where, row.width - 19 - fm.stringWidth(why) - fm.stringWidth(progress)),
				row.x + 8 + fm.stringWidth(why), lineY);
			graphics.setColor(SUBTEXT);
			Skin.text(graphics, progress, row.x + row.width - 5 - fm.stringWidth(progress), lineY);
		}
		return total;
	}

	/** Height of a saved task's opened details in the saved box (0 when closed). */
	private static int paneDetailHeight(List<QuestRequirements.Line> detail)
	{
		if (detail.isEmpty())
		{
			return 0;
		}
		int h = 6;
		for (QuestRequirements.Line line : detail)
		{
			h += line.gap + DETAIL_LINE;
		}
		return h;
	}

	/** A small rounded button with a label, lit up while hovered. */
	private static void pill(Graphics2D graphics, Rectangle r, String text, boolean hover)
	{
		Skin.button(graphics, new Rectangle(r.x, r.y, r.width + 1, r.height + 1), hover, false);
		FontMetrics fm = graphics.getFontMetrics();
		graphics.setColor(hover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, text, r.x + (r.width - fm.stringWidth(text)) / 2 + 1, r.y + 13);
	}

	/** The side panel's area in whichever layout is on; a spot by the right edge if it's closed. */
	private Rectangle sidePanel()
	{
		for (int id : new int[]{InterfaceID.Toplevel.SIDE_PANELS, InterfaceID.ToplevelOsrsStretch.SIDE_PANELS,
			InterfaceID.ToplevelPreEoc.SIDE_PANELS, InterfaceID.ToplevelOsm.SIDE_PANELS})
		{
			Widget panel = client.getWidget(id);
			if (panel != null && !panel.isHidden())
			{
				return panel.getBounds();
			}
		}
		// A bank, shop, Grand Exchange or trade hides the side panels and shows its own
		// inventory in their place: sit over that instead.
		for (int group : new int[]{15, 301, 467, 336})
		{
			Widget side = client.getWidget(group, 0);
			if (side != null && !side.isHidden() && side.getWidth() > 0)
			{
				return side.getBounds();
			}
		}
		return new Rectangle(client.getCanvasWidth() - 214, client.getCanvasHeight() - 340, 204, 275);
	}

	// Interfaces that take over the screen and inventory: bank, deposit box, shop, Grand
	// Exchange and its collection box, seed vault and trade. Opening one closes the box.
	private static final Set<Integer> TAKEOVER = Set.of(12, 192, 300, 465, 402, 631, 335);
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (TAKEOVER.contains(event.getGroupId()))
		{
			questPane = null;
		}
	}

	/** Switching side tab (prayer, inventory...) closes the quest pane. */
	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() == VarClientID.TOPLEVEL_PANEL && !config.movableTaskBox())
		{
			questPane = null;
		}
	}

	public void drawScrollbar(Graphics2D graphics, Rectangle area, int contentHeight, int scroll)
	{
		if (contentHeight <= area.height)
		{
			return;
		}
		int thumbHeight = Math.max(20, area.height * area.height / contentHeight);
		int thumbY = area.y + (area.height - thumbHeight) * scroll / Math.max(1, contentHeight - area.height);
		Skin.scrollbar(graphics, area, thumbY, thumbHeight);
	}

	/**
	 * Everything under an expanded task: its requirements, where an Obtain or Equip task's
	 * item comes from (source, part, drop rate, cost, then how), then a quest's requirement
	 * sections. The item's wiki page is on its picture.
	 */
	public List<QuestRequirements.Line> detailLines(NuzlockeTask task)
	{
		List<QuestRequirements.Line> lines = new ArrayList<>();
		lists.requirementsFor(task).forEach(line -> lines.add(new QuestRequirements.Line(line, DETAIL)));
		TaskSources.Source source = TaskSources.get(task);
		// Where the item comes from is an assist (Settings > Assists > Task details).
		List<String> sourceLines = !config.showTaskDetails() ? Collections.emptyList()
			: TaskSources.lines(task, task == null ? null : lists.chunkNames.get(task.getTaskId()));
		for (int i = 0; i < sourceLines.size(); i++)
		{
			String text = sourceLines.get(i);
			boolean how = source != null && text.equals(source.how);
			Color color = how ? SOURCE_HOW : text.startsWith("Drop rate: ") ? RATE : DETAIL;
			lines.add(i == 0 && !lines.isEmpty() || how && i > 0
				? QuestRequirements.Line.spaced(text, color)
				: new QuestRequirements.Line(text, color));
		}
		if (sourceLines.isEmpty() && config.showTaskDetails())
		{
			boolean first = true;
			for (String[] item : wikiItems(task))
			{
				lines.add(QuestRequirements.Line.wikiLink("Wiki: " + item[0], WIKI_LINK, item[1],
					first && !lines.isEmpty() ? QuestRequirements.SECTION_GAP : 0));
				first = false;
			}
		}
		List<Integer> walk = Collections.emptyList();
		String calcNote = null;
		if (task != null && config.shortestPath() && calculated.contains(task.getTaskId()))
		{
			ChunkCalculator.Result result = pathing.calculate(task);
			calcWorking = result == null && !pathing.isLoadFailed();
			if (result != null)
			{
				walk = revealed(result.connecting);
			}
			// Always say what Calc found, so it never looks like it did nothing.
			calcNote = pathing.isLoadFailed() ? "Calc: couldn't load the map data."
				: result == null ? "Calc: working out the chunks to walk there..."
				: !result.unreachable.isEmpty() ? "Calc: " + result.unreachable.size() + (result.unreachable.size() == 1 ? " chunk" : " chunks")
					+ " can't be reached from yours yet."
				: !result.connecting.isEmpty() ? "Calc: added " + result.connecting.size() + (result.connecting.size() == 1 ? " chunk" : " chunks")
					+ " to walk there, under Chunks."
				: result.quest.isEmpty() ? "Calc: no locked chunks needed."
				: "Calc: no extra chunks needed to walk there.";
		}
		lines.addAll(quests.lines(task, lists::chunkNameFor, walk));
		if (calcNote != null)
		{
			lines.add(QuestRequirements.Line.spaced(calcNote, SOURCE_HOW));
		}
		return lines;
	}

	/** The walk-there chunks to show now: one more every WALK_STAGGER_MS, each noted when it first shows. */
	private List<Integer> revealed(List<Integer> walk)
	{
		long now = System.currentTimeMillis();
		long last = 0;
		List<Integer> shown = new ArrayList<>();
		for (int region : walk)
		{
			Long at = walkShownAt.get(region);
			if (at == null)
			{
				if (last > 0 && now - last < WALK_STAGGER_MS)
				{
					break;
				}
				at = now;
				walkShownAt.put(region, at);
			}
			last = Math.max(last, at);
			shown.add(region);
		}
		if (shown.size() < walk.size() || now - last < WALK_SLIDE_MS)
		{
			// Still dealing chunks in, or the last one's still sliding: keep the window live.
			liveUntil = now + 100;
		}
		return shown;
	}

	/** How far a walk-there chunk still has to slide in (1 just shown, 0 settled), or 0 for other lines. */
	private float slide(QuestRequirements.Line line)
	{
		Long at = line.region > 0 ? walkShownAt.get(line.region) : null;
		if (at == null)
		{
			return 0f;
		}
		float t = Math.min(1f, (System.currentTimeMillis() - at) / (float) WALK_SLIDE_MS);
		return (1 - t) * (1 - t) * (1 - t);
	}

	/** Whether the task's walk-there chunks are worked out and shown (they change as they deal in). */
	public boolean isCalculated(NuzlockeTask task)
	{
		return task != null && config.shortestPath() && calculated.contains(task.getTaskId());
	}

	// Whether Quest Helper is on, looked up at most once a second.
	private boolean questHelperOn;
	private long questHelperAt;

	private boolean questHelperOn()
	{
		long now = System.currentTimeMillis();
		if (now - questHelperAt > 1000)
		{
			questHelperAt = now;
			questHelperOn = false;
			for (net.runelite.client.plugins.Plugin p : pluginManager.getPlugins())
			{
				net.runelite.client.plugins.PluginDescriptor d = p.getClass().getAnnotation(net.runelite.client.plugins.PluginDescriptor.class);
				if (d != null && "Quest Helper".equals(d.name()) && pluginManager.isPluginActive(p))
				{
					questHelperOn = true;
					break;
				}
			}
		}
		return questHelperOn;
	}

	/** A quest task's quest as the game names it ("Cook's Assistant"). */
	private static String questName(NuzlockeTask task)
	{
		String id = task.getConstraints() == null ? null : task.getConstraints().getQuest();
		if (id != null)
		{
			try
			{
				return net.runelite.api.Quest.valueOf(id).getName();
			}
			catch (IllegalArgumentException ignored)
			{
				// Not one RuneLite knows: the task's own name below.
			}
		}
		return task.getName().replaceFirst("^Complete ", "");
	}

	/**
	 * Quest Helper opens a quest by itself when the game says you've started it (its "Auto
	 * start helper" setting, on by default, while no other quest is open in it). So say that.
	 * Client thread.
	 */
	private void openInQuestHelper(String quest)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "You've started a new quest: " + quest, null);
	}

	/**
	 * A path to where the quest starts: through your chunks, or if they don't reach, through
	 * the quest's own chunks too. Client thread.
	 */
	private void pathToStart(NuzlockeTask task)
	{
		int[] start = quests.startOf(task);
		if (start != null && start.length == 3)
		{
			pathing.pathTo(com.chunkblazerui.pathing.PathTile.pack(start[0], start[1], start[2]), quests.lockedChunksFor(task));
		}
	}

	/** Whether this is an Obtain or Equip task with items to link to the wiki. */
	public static boolean isItemTask(NuzlockeTask task)
	{
		String type = task == null || task.getCompletionType() == null ? "" : task.getCompletionType().toUpperCase();
		return (type.equals("OBTAIN") || type.equals("EQUIP"))
			&& task.getRequiredItems() != null && !task.getRequiredItems().isEmpty();
	}

	/**
	 * An Obtain or Equip task's items as {name, OSRS Wiki link}, one per item (its first id
	 * picks the page); empty for any other task. Item names are looked up on the client
	 * thread, where overlays draw.
	 */
	public List<String[]> wikiItems(NuzlockeTask task)
	{
		List<String[]> items = new ArrayList<>();
		if (!isItemTask(task))
		{
			return items;
		}
		for (RequiredItem item : task.getRequiredItems())
		{
			if (item == null)
			{
				continue;
			}
			Integer id = item.getItemIds() == null || item.getItemIds().isEmpty() ? null : item.getItemIds().get(0);
			String name = item.getItem();
			if ((name == null || name.trim().isEmpty()) && id != null && itemManager != null)
			{
				name = itemManager.getItemComposition(id).getName();
			}
			if (name == null || name.trim().isEmpty() || "null".equalsIgnoreCase(name.trim()))
			{
				continue;
			}
			name = name.trim();
			String url = id != null
				? "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + id
				: "https://oldschool.runescape.wiki/?title=Special:Search&go=Go&search="
					+ name.replace("&", "%26").replace(' ', '+');
			items.add(new String[]{name, url});
		}
		return items;
	}

	/** True if the world map is open right now. */
	private boolean isMapOpen()
	{
		return client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) != null;
	}
}
