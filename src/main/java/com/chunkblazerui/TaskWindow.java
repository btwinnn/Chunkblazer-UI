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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.util.LinkBrowser;

/**
 * Leagues-style task window, opened from the Points orb (ChunkBlazerOrbWidget).
 *
 * Tabs: Active, Saved (starred), Archived (book button) and, after new tasks arrive,
 * New. Search matches name, description, category and chunk; "Current chunk" follows
 * you. The tasks key + click on an unlocked chunk on the world map opens its tasks.
 * Filter: a skill, a tier, task types (any ticked type) and conditions (all ticked
 * conditions); the eye button hides a type or condition. Sort only changes the order.
 * Settings live in RuneLite's plugin config. Drag the title to move the window.
 *
 * Hover is worked out while drawing, from the game's own mouse position, so clicks
 * line up in stretched and resized modes. ChunkBlazer's TaskPaneOverlay draws it and
 * passes it the mouse (see TaskWindowHook).
 */
@Singleton
public class TaskWindow implements TaskWindowHook
{
	@Inject
	private TaskArchive archive;

	@Inject
	private TaskCardOverlay cardOverlay;
	@Inject
	private StampCompletionOverlay stampCompletion;
	@Inject
	private com.chunkblazerui.TaskCompletionAnimationOverlay originalCompletion;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChunkBlazerUiConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChunkBlazerCore plugin;

	@Inject
	private SkillIconManager skillIcons;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private TaskPaneOverlay pane;
	@Inject
	private TaskLists lists;

	/** The lists tell the window when to start again from the top, or just rebuild. */
	@Inject
	void listenTo(TaskLists lists)
	{
		lists.onRefresh(() ->
		{
			scroll = 0;
			rowsBuiltAt = 0;
		});
		lists.onChange(() -> rowsBuiltAt = 0);
	}

	private static final String SEEN_KEY = "seenTasks";
	// The New tab: the last few tasks you became able to do (newest first), and the chunk you unlocked last.
	private static final String DOABLE_KEY = "newlyDoable";
	private static final String NEWEST_CHUNK_KEY = "newestChunk";
	private static final int DOABLE_KEPT = 5;
	private static final int SECTION_ROW = 22;
	private static final long PULSE_MS = 1200;

	private static final int MAX_WIDTH = 600;
	private static final int MAX_HEIGHT = 440;
	private static final int HEADER = 40;
	// Each extra line of a long task name.
	private static final int NAME_LINE = 15;
	// Between task cards; the tabs and tier filter down the left; the hint line at the foot.
	private static final int ROW_GAP = 4;
	private static final int SIDEBAR = 132;
	private static final int FOOTER = 20;
	static final int PAD = 8;
	private static final long ROW_REFRESH_MS = 1000;
	// Star, book and arrow icons (ui/*.png): on, hovered and off.
	private static final BufferedImage STAR_ON = Skin.image("star_on");
	private static final BufferedImage STAR_HOVER = Skin.image("star_hover");
	private static final BufferedImage STAR_OFF = Skin.image("star_off");
	private static final BufferedImage BOOK_ON = Skin.image("book_on");
	private static final BufferedImage BOOK_HOVER = Skin.image("book_hover");
	private static final BufferedImage BOOK_OFF = Skin.image("book_off");

	private enum Tab
	{
		ACTIVE, SAVED, ARCHIVED, NEW
	}

	/** A list line: a task, the header of one skill's level-up tasks (group set), or a section heading (no members). */
	private static final class Entry
	{
		final NuzlockeTask task;
		final String group;
		final List<NuzlockeTask> members;
		final boolean child;

		Entry(NuzlockeTask task, String group, List<NuzlockeTask> members, boolean child)
		{
			this.task = task;
			this.group = group;
			this.members = members;
			this.child = child;
		}
	}

	private final Map<Integer, BufferedImage> sprites = new HashMap<>();
	// Every task icon's slot, in pixels (quest, item, skill, combat or letter).
	private static final int ICON_SLOT = 27;

	private volatile boolean open;
	private volatile boolean tasksKeyHeld;
	private int lastRegionSeen = -1;
	private final Set<String> expanded = ConcurrentHashMap.newKeySet();
	// With "Expand all tasks" on: the tasks you've closed again.
	private final Set<String> collapsed = ConcurrentHashMap.newKeySet();
	private final Set<String> expandedGroups = ConcurrentHashMap.newKeySet();
	private volatile Tab tab = Tab.ACTIVE;
	// The window's Filters dropdown (the same filters as the side panel's), and where it was drawn.
	private boolean filtersOpen;
	private Rectangle filterPop;
	// The window's Sort dropdown (the same sort as the side panel's).
	private boolean sortOpen;
	// The window's search box, where it was drawn, and whether typing goes into it.
	private volatile Rectangle searchBox;
	// The X at the right of the search box that clears it (null when there's nothing to clear).
	private volatile Rectangle searchClear;
	// The game's chatbox search (like the Grand Exchange's) is open for the window's search.
	private volatile boolean searchFocused;
	// The chatbox search this window opened, while it's up (client thread).
	private ChatboxTextInput searchInput;
	/** The search last opened in the chatbox, kept until the chatbox itself closes it. */
	private volatile ChatboxTextInput openedSearch;
	private volatile int scroll;
	// The Patch notes tab (in the header): shown in place of the task list while on.
	private volatile boolean patchNotes;
	// The Settings tab (below the lists): every in-game UI setting, in place of the task list.
	private volatile boolean settingsOpen;
	private List<Setting> settings;
	private volatile int patchScroll;
	private static List<String> patchNoteLines;
	// The task-list row under the mouse, for right-click to save, as in the side panel.
	private volatile String listRowId;

	// New-task alert: unseen active tasks, the snapshot the New tab shows, the Points orb.
	private final Set<String> newIds = ConcurrentHashMap.newKeySet();
	private volatile Set<String> shownNew = Collections.emptySet();
	// Tasks you've just become able to do (levels, quests) that you haven't looked at yet, and
	// the ones the New tab is pointing out while it shows.
	private final Set<String> doableAlert = ConcurrentHashMap.newKeySet();
	private volatile Set<String> shownDoable = Collections.emptySet();
	// What was doable, known and unlocked at the last check (null until the first after login).
	private Set<String> doableBefore;
	private Set<String> knownBefore;
	private Set<String> unlockedBefore;
	private int checkTicks;
	// The task list's area in the last frame drawn, for the tutorial to point at.
	private volatile Rectangle listBounds;
	// Each task row on screen and its star, and the first row, from the last frame: for the
	// tutorial to point at.
	private final Map<String, Rectangle[]> rowSpots = new ConcurrentHashMap<>();
	private volatile String firstRowId;

	// Dragging the window by its title. Drag events are swallowed, so movement is read
	// from the events, not the game's mouse position.
	private volatile boolean titleHovered;
	private volatile boolean dragging;
	private volatile boolean moved;
	private volatile int pressX;
	private volatile int pressY;
	private volatile int startX;
	private volatile int startY;
	private volatile int windowX;
	private volatile int windowY;
	private volatile int customX;
	private volatile int customY;
	// The window's Interface Size while it's being drawn (1 for the quest/saved box).

	// The redesigned window: where the sidebar's tab marker is (it slides to the chosen tab),
	// the header's numbers and the tier counts (read once a second), and the ChunkBlazer icon.
	private float tabMarkerY = -1;
	private long statsAt;
	private int statDone;
	private int statChunks;
	private static BufferedImage logo;

	private List<Entry> rows = new ArrayList<>();
	private long rowsBuiltAt;

	/** Open or close the window (the Points orb's "Tasks" option). */
	@Override
	public void toggle()
	{
		if (open)
		{
			close();
			return;
		}
		open = true;
		pane.questPane = null;
		lists.pinnedChunk = null;
		lists.refresh();
		if (alerts() > 0)
		{
			showNew();
		}
	}

	/** Unseen new tasks and tasks you've just become able to do (none on Tutorial Island: the tutorial points there). */
	private int alerts()
	{
		return onIsland() ? 0 : newIds.size() + doableAlert.size();
	}

	private boolean onIsland()
	{
		return client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TUTORIAL) < 1000;
	}

	/** To the New tab, pointing out what's new since you last looked. */
	private void showNew()
	{
		shownNew = Collections.unmodifiableSet(new LinkedHashSet<>(newIds));
		shownDoable = Collections.unmodifiableSet(new LinkedHashSet<>(doableAlert));
		tab = Tab.NEW;
		rowsBuiltAt = 0;
	}

	@Override
	public void close()
	{
		open = false;
		clientThread.invokeLater(this::closeSearchInput);
		lists.pinnedChunk = null;
		finishNew();
	}

	/** Leaving the New tab: its tasks are now seen, and the window opens on Active next time. */
	private void finishNew()
	{
		if (tab != Tab.NEW)
		{
			return;
		}
		seenNew();
		tab = Tab.ACTIVE;
	}

	/** What the New tab pointed out is now seen. */
	private void seenNew()
	{
		markSeen(shownNew);
		newIds.removeAll(shownNew);
		doableAlert.removeAll(shownDoable);
		shownNew = Collections.emptySet();
		shownDoable = Collections.emptySet();
		rowsBuiltAt = 0;
	}

	/** The Points orb's bounds, and whether the window is open, for the tutorial. */
	public Rectangle getTaskButtonBounds()
	{
		Rectangle button = pane.taskButton;
		if (button == null)
		{
			// Not heard from ChunkBlazer yet: ask it where the orb is.
			java.awt.Point centre = plugin.getTasksOrbCentre();
			button = centre == null ? null : new Rectangle(centre.x - 15, centre.y - 15, 30, 30);
		}
		return button;
	}

	/** Whether the Settings page is up, for the tutorial. */
	boolean isSettingsOpen()
	{
		return open && settingsOpen;
	}

	/** Whether the Filters or Sort dropdown is open, for the tutorial. */
	boolean isFilterMenuOpen()
	{
		return open && (filtersOpen || sortOpen);
	}

	@Override
	public boolean isOpen()
	{
		return open;
	}

	/** A task's row on screen (0), its star (1) or its archive book (2), or null when it isn't showing. */
	Rectangle getRowSpot(String taskId, int part)
	{
		Rectangle[] spots = open && taskId != null ? rowSpots.get(taskId) : null;
		return spots == null ? null : spots[part];
	}

	// The window's close cross, from the last frame drawn.
	private volatile Rectangle closeBounds;

	/** The window's close cross on screen, or null while it's shut. */
	Rectangle getCloseBounds()
	{
		return open ? closeBounds : null;
	}

	/** True while the window shows a list of tasks to track: New tasks or Active (not Settings, Saved...). */
	boolean isShowingTasks()
	{
		return open && (tab == Tab.ACTIVE || tab == Tab.NEW) && !settingsOpen && !patchNotes;
	}

	// More of the window for the tutorial to point at, from the last frame drawn: "tab:ARCHIVED",
	// "tab:NEW", "filters" (the Filters button), "cog".
	private final Map<String, Rectangle> marks = new ConcurrentHashMap<>();

	/** One of the marked parts of the window on screen, or null while it isn't drawn. */
	Rectangle getMark(String key)
	{
		return open ? marks.get(key) : null;
	}

	/** Whether the window is open on this list (ACTIVE, SAVED, ARCHIVED or NEW). */
	boolean isOnTab(String name)
	{
		return open && tab.name().equals(name) && !settingsOpen && !patchNotes;
	}

	/** The tutorial's restart button (Settings). */
	@Inject
	private javax.inject.Provider<ChunkBlazerTutorial> tutorial;

	/** The first task row on screen, or null. */
	String getFirstRowId()
	{
		return open ? firstRowId : null;
	}

	/** The task list on screen, or null while the window is shut. */
	Rectangle getListBounds()
	{
		return open ? listBounds : null;
	}

	/**
	 * Tasks key + click on an unlocked chunk on the world map: show that chunk's tasks.
	 * Any other click out in the game world (walk, attack, chop...) closes the window,
	 * like the game's own interfaces do. Clicks on interfaces, like the orb, don't.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (open && !tasksKeyHeld && event.getMenuAction() != MenuAction.CANCEL
			&& event.getMenuEntry().getWidget() == null)
		{
			close();
		}
		if (!tasksKeyHeld || client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) == null)
		{
			return;
		}
		int regionId = plugin.getHoveredRegionId();
		if (regionId <= 0 || !plugin.isRegionUnlocked(regionId) || plugin.isFreeRegion(regionId))
		{
			return;
		}
		String name = lists.chunkNameFor(regionId);
		if (name.isEmpty())
		{
			return;
		}
		event.consume();
		finishNew();
		lists.pinnedChunk = name;
		lists.currentChunkOnly = false;
		tab = Tab.ACTIVE;
		lists.clearFilters();
		plugin.setSidePanelSearch("");
		open = true;
		lists.refresh();
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			tasksKeyHeld = false;
		}
	}

	/**
	 * The window's search, typed in the game's chatbox like the Grand Exchange's: the list
	 * filters as you type, and Enter or Esc closes it (the search stays). Client thread.
	 */
	private void openSearch()
	{
		if (!open)
		{
			return;
		}
		if (searchInput != null && chatboxPanelManager.getCurrentInput() == searchInput)
		{
			return;
		}
		searchFocused = true;
		ChatboxTextInput[] made = new ChatboxTextInput[1];
		made[0] = chatboxPanelManager.openTextInput("Search tasks")
			.value(lists.search)
			.onChanged(text -> plugin.setSidePanelSearch(text == null ? "" : text))
			.onDone((String text) ->
			{
				searchFocused = false;
				searchInput = null;
			})
			.onClose(() ->
			{
				// Only this search's own close: an older one closing mustn't undo a newer one.
				if (openedSearch == made[0])
				{
					openedSearch = null;
					searchFocused = false;
					searchInput = null;
				}
			})
			.build();
		searchInput = made[0];
		openedSearch = made[0];
	}

	/**
	 * Closes this window's chatbox search if it's still the chatbox's open input (never
	 * another plugin's or the game's), so the chatbox stops taking keys and the movement
	 * keys work again. Client thread.
	 */
	private void closeSearchInput()
	{
		ChatboxTextInput input = openedSearch != null ? openedSearch : searchInput;
		searchInput = null;
		searchFocused = false;
		if (input == null)
		{
			return;
		}
		// The chatbox opens a moment after it's asked to, so keep checking for a few ticks
		// until it has, then close it (never another plugin's or the game's input).
		int[] tries = {0};
		clientThread.invokeLater(() ->
		{
			if (chatboxPanelManager.getCurrentInput() == input)
			{
				chatboxPanelManager.close();
				return true;
			}
			return openedSearch != input || ++tries[0] > 50;
		});
	}

	/**
	 * Settings > Theme's Test button: closes this window and deals 4 sample cards,
	 * arriving the way Card entrance is set and drawn in the current Theme.
	 */
	/** Settings' Test beside Task complete popup: the popup for this theme, for a made-up task. */
	private void testCompletion()
	{
		close();
		NuzlockeTask demo = new NuzlockeTask();
		demo.setTaskId("demo_completion");
		demo.setName("Defeat the Kalphite Queen");
		demo.setBasePoints(3);
		// And the sound a real completion plays.
		plugin.playTaskCompleteSound();
		if (Skin.theme() == Theme.ORIGINAL)
		{
			originalCompletion.showTaskCompletion(demo, 3, "Kalphite Lair");
		}
		else
		{
			stampCompletion.showTest(demo, 3, "Kalphite Lair");
		}
	}

	private void testCards()
	{
		close();
		cardOverlay.requestDemoDeal(4);
	}

	private void markSeen(Set<String> ids)
	{
		Set<String> seen = lists.idSet(SEEN_KEY);
		Set<String> updated = seen == null ? new LinkedHashSet<>() : seen;
		updated.addAll(ids);
		lists.storeIds(SEEN_KEY, updated);
	}

	// The Filters dropdown's layout: skills in a grid, then types and conditions as buttons.
	private static final int POP_WIDTH = 252;
	private static final int POP_PAD = 8;
	private static final int SKILL_COLUMNS = 8;
	/** The skills as the game's Skills tab lays them out: three columns, read row by row. */
	private static final Skill[] SKILL_TAB = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};

	/** Which plugin a Settings row's key belongs to: the completion popup is ChunkBlazer's. */
	private static String groupOf(String key)
	{
		return "showTaskCompletionPopup".equals(key) || "worldMapTasksKey".equals(key) || "worldMapUnlockKey".equals(key)
			? "chunkblazer" : ChunkBlazerUiConfig.GROUP;
	}
	private static final int SKILL_CELL_H = 24;
	private static final int CHIP_COLUMNS = 3;
	private static final int CHIP_H = 18;

	// The Sort dropdown: each field both ways, its usual direction first (as the side panel's).
	private static final int SORT_POP_WIDTH = 170;
	private static final int SORT_ROW_H = 18;

	private Rectangle sortPopBounds(Rectangle window, int left, int top)
	{
		int height = 8 + SortField.values().length * 2 * SORT_ROW_H + 6;
		return new Rectangle(Math.min(left, window.x + window.width - SORT_POP_WIDTH - 6), top, SORT_POP_WIDTH,
			Math.min(height, window.y + window.height - 4 - top));
	}

	/** A dropdown's drop shadow and stone frame. */
	private static void popFrame(Graphics2D graphics, Rectangle pop)
	{
		graphics.setColor(new Color(0, 0, 0, 90));
		graphics.fillRect(pop.x + 3, pop.y + 3, pop.width, pop.height);
		Skin.stoneFrame(graphics, pop, 0);
	}

	/** The Sort dropdown: pick a field and direction; the side panel's Sort follows. */
	private void drawSortPop(Graphics2D graphics, Rectangle window, Rectangle pop, int mx, int my)
	{
		List<Hit> popHits = new ArrayList<>();
		Shape old = graphics.getClip();
		popFrame(graphics, pop);
		graphics.clip(pop);
		graphics.setFont(Skin.small());
		int y = pop.y + 6;
		for (SortField field : SortField.values())
		{
			for (boolean asc : new boolean[]{field.ascendingByDefault, !field.ascendingByDefault})
			{
				Rectangle row = new Rectangle(pop.x + 4, y, pop.width - 8, SORT_ROW_H - 2);
				boolean picked = field == lists.sortField && asc == lists.ascending;
				boolean hover = row.contains(mx, my);
				Skin.button(graphics, row, hover, picked);
				graphics.setColor(picked ? new Color(120, 220, 120) : hover ? Color.WHITE : SUBTEXT);
				Skin.text(graphics, field.label, row.x + 6, row.y + 12);
				String arrow = asc ? "\u25B2" : "\u25BC";
				Skin.text(graphics, arrow, row.x + row.width - 6 - graphics.getFontMetrics().stringWidth(arrow), row.y + 12);
				popHits.add(new Hit(row, () ->
				{
					lists.sortField = field;
					lists.ascending = asc;
					sortOpen = false;
					lists.saveFilters();
					lists.refresh();
					plugin.sidePanelChanged();
				}));
				y += SORT_ROW_H;
			}
		}
		graphics.setClip(old);
		// Clicks in the dropdown stay in it; anywhere else in the window closes it.
		popHits.add(new Hit(pop, () ->
		{
		}));
		java.awt.geom.Area outside = new java.awt.geom.Area(window);
		outside.subtract(new java.awt.geom.Area(pop));
		popHits.add(new Hit(outside, () -> sortOpen = false));
		pane.hits.addAll(0, popHits);
	}

	/**
	 * The search box in the list's heading: matches name, description, category and chunk, and
	 * looks past the filters until it's cleared. The side panel's search box shows the same text.
	 */
	private void drawSearchBox(Graphics2D graphics, Rectangle box, int mx, int my)
	{
		searchBox = box;
		boolean hover = box.contains(mx, my);
		boolean focused = searchFocused;
		graphics.setColor(new Color(0, 0, 0, focused ? 150 : hover ? 110 : 80));
		graphics.fillRect(box.x, box.y, box.width, box.height);
		graphics.setColor(focused ? TITLE : hover ? SUBTEXT : LABEL.darker());
		graphics.drawRect(box.x, box.y, box.width - 1, box.height - 1);
		FontMetrics fm = graphics.getFontMetrics();
		String text = lists.search;
		int textY = box.y + box.height - 3;
		// An X to clear it, once there's something typed.
		Rectangle clear = text.isEmpty() ? null : new Rectangle(box.x + box.width - 16, box.y + 2, 14, box.height - 4);
		searchClear = clear;
		boolean clearHover = clear != null && clear.contains(mx, my);
		int room = clear == null ? box.width - 10 : box.width - 26;
		if (text.isEmpty())
		{
			graphics.setColor(LABEL);
			Skin.text(graphics, Skin.fit(fm, "Search tasks", box.width - 8), box.x + 4, textY);
		}
		else
		{
			// The end of the text when it's too long to fit, and a blinking caret while typing.
			String shown = text;
			while (!shown.isEmpty() && fm.stringWidth(shown) > room)
			{
				shown = shown.substring(1);
			}
			graphics.setColor(Color.WHITE);
			Skin.text(graphics, shown, box.x + 4, textY);
		}
		if (clear != null)
		{
			Skin.close(graphics, clear, clearHover);
		}
		if (clearHover)
		{
			pane.tip = "Clear the search";
		}
		else if (hover && !focused)
		{
			pane.tip = "Click to search in the chatbox, like the Grand Exchange";
		}
	}

	/** The search box's X: empties the search, and closes the chatbox search if it's open. Client thread. */
	private void clearSearch()
	{
		closeSearchInput();
		plugin.setSidePanelSearch("");
	}

	/** Where the dropdown goes: under the Filters tab, kept inside the window. */
	private Rectangle filterPopBounds(Rectangle window, int left, int top)
	{
		int types = (int) Arrays.stream(Filter.values()).filter(f -> !f.condition).count();
		int conditions = Filter.values().length - types;
		int skillRows = (SKILL_TAB.length + SKILL_COLUMNS - 1) / SKILL_COLUMNS;
		int chipRows = (types + CHIP_COLUMNS - 1) / CHIP_COLUMNS + (conditions + CHIP_COLUMNS - 1) / CHIP_COLUMNS;
		int height = 22 + 3 * 14 + skillRows * SKILL_CELL_H + chipRows * (CHIP_H + 3) + 12 + 16;
		int x = Math.min(left, window.x + window.width - POP_WIDTH - 6);
		int y = top;
		height = Math.min(height, window.y + window.height - 4 - y);
		return new Rectangle(x, y, POP_WIDTH, height);
	}

	/**
	 * The Filters dropdown: the same filters as the side panel, set from either. A skill (click
	 * again to clear), then the types and conditions: click once to show only those, again to
	 * hide them, again to clear. A click anywhere else in the window closes it.
	 */
	private void drawFilterPop(Graphics2D graphics, Rectangle window, Rectangle pop, int mx, int my)
	{
		List<Hit> popHits = new ArrayList<>();
		Shape old = graphics.getClip();
		popFrame(graphics, pop);
		graphics.clip(pop);
		Font small = Skin.small();
		graphics.setFont(small);
		FontMetrics fm = graphics.getFontMetrics();
		int left = pop.x + POP_PAD;
		int inner = pop.width - 2 * POP_PAD;
		int y = pop.y + 6;

		// Title, and Clear when anything is set.
		graphics.setColor(TITLE);
		Skin.text(graphics, "Filters", left, y + 11);
		if (lists.anyFilter())
		{
			String clear = "Clear all";
			Rectangle clearArea = new Rectangle(pop.x + pop.width - POP_PAD - fm.stringWidth(clear) - 4, y, fm.stringWidth(clear) + 4, 14);
			boolean hover = clearArea.contains(mx, my);
			graphics.setColor(hover ? Color.WHITE : NO_LEVEL);
			Skin.text(graphics, clear, clearArea.x + 2, y + 11);
			popHits.add(new Hit(clearArea, () ->
			{
				lists.clearFilters();
				lists.refresh();
				plugin.sidePanelChanged();
			}));
		}
		y += 18;

		// Skills, in the Skills tab's order.
		graphics.setColor(LABEL);
		Skin.text(graphics, "SKILL" + (lists.filterSkill != null ? ": " + lists.filterSkill.getName() : ""), left, y + 10);
		y += 14;
		int cellW = inner / SKILL_COLUMNS;
		for (int i = 0; i < SKILL_TAB.length; i++)
		{
			Skill skill = SKILL_TAB[i];
			Rectangle cell = new Rectangle(left + (i % SKILL_COLUMNS) * cellW, y + (i / SKILL_COLUMNS) * SKILL_CELL_H,
				cellW - 2, SKILL_CELL_H - 2);
			boolean picked = skill == lists.filterSkill;
			boolean hover = cell.contains(mx, my);
			Skin.button(graphics, cell, hover, picked);
			BufferedImage icon = skillIcons.getSkillImage(skill, true);
			if (icon != null)
			{
				graphics.drawImage(icon, cell.x + (cell.width - icon.getWidth()) / 2, cell.y + (cell.height - icon.getHeight()) / 2, null);
			}
			if (picked)
			{
				graphics.setColor(TITLE);
				graphics.drawRect(cell.x, cell.y, cell.width - 1, cell.height - 1);
			}
			if (hover)
			{
				pane.tip = skill.getName() + (picked ? " (click to clear)" : "");
			}
			popHits.add(new Hit(cell, () ->
			{
				lists.filterSkill = lists.filterSkill == skill ? null : skill;
				lists.saveFilters();
				lists.refresh();
				plugin.sidePanelChanged();
			}));
		}
		y += ((SKILL_TAB.length + SKILL_COLUMNS - 1) / SKILL_COLUMNS) * SKILL_CELL_H + 4;

		// Types, then conditions.
		for (boolean conditions : new boolean[]{false, true})
		{
			graphics.setColor(LABEL);
			Skin.text(graphics, conditions ? "ONLY IF" : "TYPES", left, y + 10);
			y += 14;
			int chipW = (inner - (CHIP_COLUMNS - 1) * 3) / CHIP_COLUMNS;
			int n = 0;
			for (Filter f : Filter.values())
			{
				if (f.condition != conditions)
				{
					continue;
				}
				Rectangle chip = new Rectangle(left + (n % CHIP_COLUMNS) * (chipW + 3), y + (n / CHIP_COLUMNS) * (CHIP_H + 3),
					chipW, CHIP_H);
				boolean on = lists.filterOn.contains(f);
				boolean hidden = lists.filterHidden.contains(f);
				boolean hover = chip.contains(mx, my);
				Skin.button(graphics, chip, hover, on || hidden);
				String label = (on ? "\u2713 " : hidden ? "\u2717 " : "") + f.shortLabel;
				graphics.setColor(on ? new Color(120, 220, 120) : hidden ? new Color(255, 110, 110) : hover ? Color.WHITE : SUBTEXT);
				Skin.text(graphics, Skin.fit(fm, label, chip.width - 6), chip.x + 4, chip.y + 13);
				if (hover)
				{
					pane.tip = f.label + (on ? ": showing only these. Click to hide them" : hidden ? ": hidden. Click to clear"
						: ": click to show only these");
				}
				popHits.add(new Hit(chip, () ->
				{
					lists.toggleFilter(f, lists.filterOn.contains(f) || lists.filterHidden.contains(f));
					plugin.sidePanelChanged();
				}));
				n++;
			}
			y += ((n + CHIP_COLUMNS - 1) / CHIP_COLUMNS) * (CHIP_H + 3) + 4;
		}

		graphics.setColor(LABEL);
		Skin.text(graphics, Skin.fit(fm, "Click: show only  \u00B7  again: hide  \u00B7  again: off", inner), left, y + 10);
		graphics.setClip(old);

		// Clicks in the dropdown stay in it; anywhere else in the window closes it.
		popHits.add(new Hit(pop, () ->
		{
		}));
		java.awt.geom.Area outside = new java.awt.geom.Area(window);
		outside.subtract(new java.awt.geom.Area(pop));
		popHits.add(new Hit(outside, () -> filtersOpen = false));
		pane.hits.addAll(0, popHits);
	}

	/** The list for the current tab, search, chunk and filters; rebuilt at most once a second. */
	private List<Entry> currentRows()
	{
		long now = System.currentTimeMillis();
		if (now - rowsBuiltAt < ROW_REFRESH_MS)
		{
			return rows;
		}
		rowsBuiltAt = now;
		return buildRows();
	}

	private List<Entry> buildRows()
	{
		rowNames.clear(); // levels or readiness may have changed: names re-wrap with the new rows
		details.clear();

		String query = lists.search.trim().toLowerCase();
		Set<String> saved = lists.savedIds();
		Set<String> archived = archive.ids();
		if (tab == Tab.NEW)
		{
			rows = newRows();
			return rows;
		}
		Set<Filter> on = lists.filterOn;
		Predicate<NuzlockeTask> test = lists.filterTest(lists.filterOn, lists.filterHidden, lists.filterSkill, lists.filterTier);
		String here = lists.pinnedChunk != null ? lists.pinnedChunk : lists.currentChunkOnly ? lists.currentChunkName() : null;
		// Global tasks (quests, level-ups) join the Active list when asked for, or when searching.
		boolean global = tab == Tab.SAVED || tab == Tab.ARCHIVED || (tab == Tab.ACTIVE && (lists.filterSkill != null
			|| !query.isEmpty() || on.contains(Filter.QUESTS) || on.contains(Filter.PROGRESSION)
			|| on.contains(Filter.OFFLINE)));

		List<NuzlockeTask> list = new ArrayList<>();
		for (NuzlockeTask task : lists.pool(global))
		{
			String id = task.getTaskId();
			lists.chunkNames.computeIfAbsent(id, k -> lists.chunkName(task));
			boolean keep;
			if (!query.isEmpty())
			{
				// Searching looks past the filters and the chunk limit until the box is cleared.
				keep = (tab == Tab.ARCHIVED) == archived.contains(id)
					&& (tab != Tab.SAVED || saved.contains(id))
					&& lists.matchesSearch(task, query);
			}
			else
			{
				keep = (tab == Tab.ARCHIVED) == archived.contains(id)
					&& (tab != Tab.SAVED || saved.contains(id))
					&& (tab != Tab.ACTIVE || test.test(task))
					&& (here == null || here.equals(lists.chunkNames.get(id)));
			}
			if (keep)
			{
				list.add(task);
			}
		}
		list.sort(lists.sorter(lists.sortField, lists.ascending));
		rows = group(list);
		return rows;
	}

	/**
	 * The New tab, in sections: the tasks you've most recently become able to do (the last
	 * five), then every task in the chunk you unlocked last. Both stay until something newer
	 * replaces them. No search, filter or chunk limits.
	 */
	private List<Entry> newRows()
	{
		Map<String, NuzlockeTask> byId = new LinkedHashMap<>();
		for (NuzlockeTask task : lists.pool(true))
		{
			lists.chunkNames.computeIfAbsent(task.getTaskId(), k -> lists.chunkName(task));
			byId.put(task.getTaskId(), task);
		}
		Set<String> archived = archive.ids();
		Set<String> used = new HashSet<>();
		List<Entry> entries = new ArrayList<>();

		List<NuzlockeTask> ready = new ArrayList<>();
		Set<String> recent = lists.idSet(DOABLE_KEY);
		for (String id : recent == null ? Collections.<String>emptySet() : recent)
		{
			NuzlockeTask task = byId.get(id);
			if (task != null && !archived.contains(id) && used.add(id))
			{
				ready.add(task);
			}
		}
		section(entries, "Now doable", ready);

		// The chunk (or chunks, unlocked together) you unlocked last: each its own section.
		Set<String> newest = lists.idSet(NEWEST_CHUNK_KEY);
		for (String regionText : newest == null ? Collections.<String>emptySet() : newest)
		{
			int region;
			try
			{
				region = Integer.parseInt(regionText);
			}
			catch (NumberFormatException ignored)
			{
				continue;
			}
			String name = lists.chunkNameFor(region);
			if (name.isEmpty())
			{
				continue;
			}
			// Matched by name, as the task list's own chunk filter is.
			List<NuzlockeTask> inChunk = new ArrayList<>();
			for (NuzlockeTask task : byId.values())
			{
				String id = task.getTaskId();
				if (!archived.contains(id) && name.equals(lists.chunkNames.get(id)) && used.add(id))
				{
					inChunk.add(task);
				}
			}
			inChunk.sort(lists.sorter(lists.sortField, lists.ascending));
			section(entries, "Newest chunk: " + name, inChunk);
		}
		return entries;
	}

	/** A heading, then its tasks; nothing when there are none. */
	private static void section(List<Entry> entries, String heading, List<NuzlockeTask> tasks)
	{
		if (tasks.isEmpty())
		{
			return;
		}
		entries.add(new Entry(null, heading, null, false));
		for (NuzlockeTask task : tasks)
		{
			entries.add(new Entry(task, null, null, false));
		}
	}

	/** Fold each skill's level-ups into one header where its first rung sorted; rungs under it. */
	List<Entry> group(List<NuzlockeTask> sorted)
	{
		Map<String, List<NuzlockeTask>> bySkill = new LinkedHashMap<>();
		for (NuzlockeTask task : sorted)
		{
			String skill = levelUpSkill(task);
			if (skill != null)
			{
				bySkill.computeIfAbsent(skill, k -> new ArrayList<>()).add(task);
			}
		}
		bySkill.values().forEach(m -> m.sort(Comparator.comparingInt(TaskLists::requiredLevel)));

		List<Entry> entries = new ArrayList<>();
		Set<String> placed = new HashSet<>();
		for (NuzlockeTask task : sorted)
		{
			String skill = levelUpSkill(task);
			if (skill == null)
			{
				entries.add(new Entry(task, null, null, false));
			}
			else if (placed.add(skill))
			{
				List<NuzlockeTask> members = bySkill.get(skill);
				entries.add(new Entry(null, skill, members, false));
				if (expandedGroups.contains(skill))
				{
					members.forEach(m -> entries.add(new Entry(m, null, null, true)));
				}
			}
		}
		return entries;
	}

	/** The header: the ChunkBlazer icon and title (the drag handle), Patch notes, the account's numbers, close. */
	private void drawHeader(Graphics2D graphics, Rectangle window, int mx, int my)
	{
		int x = window.x;
		int y = window.y;
		int width = window.width;
		Font bold = Skin.bold();
		Font small = Skin.small();
		BufferedImage icon = logo();
		int titleX = x + PAD + (icon != null ? 22 : 0);
		if (icon != null)
		{
			graphics.drawImage(icon, x + PAD, y + (HEADER - 16) / 2, null);
		}
		graphics.setFont(bold);
		FontMetrics bm = graphics.getFontMetrics();
		titleHovered = new Rectangle(x, y, titleX - x + bm.stringWidth("ChunkBlazer Tasks") + 10, HEADER).contains(mx, my);
		graphics.setColor(titleHovered || dragging ? Color.WHITE : TITLE);
		Skin.text(graphics, "ChunkBlazer", titleX, y + 25);
		graphics.setColor(new Color(255, 255, 0));
		Skin.text(graphics, "Tasks", titleX + bm.stringWidth("ChunkBlazer "), y + 25);
		// The Patch notes tab, beside the title.
		graphics.setFont(Skin.small());
		String notesLabel = "Patch notes";
		Rectangle notesTab = new Rectangle(titleX + bm.stringWidth("ChunkBlazer Tasks") + 14, y + 11,
			graphics.getFontMetrics().stringWidth(notesLabel) + 16, 18);
		pane.hits.add(new Hit(notesTab, () ->
		{
			patchNotes = !patchNotes;
			settingsOpen = false;
			filtersOpen = false;
			sortOpen = false;
			patchScroll = 0;
		}));
		boolean notesHover = notesTab.contains(mx, my);
		Skin.button(graphics, notesTab, notesHover, patchNotes);
		graphics.setColor(patchNotes || notesHover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, notesLabel, notesTab.x + 8, notesTab.y + 13);
		graphics.setFont(bold);
		Rectangle close = new Rectangle(x + width - 30, y + 10, 20, 20);
		closeBounds = close;
		pane.hits.add(new Hit(close, this::close));
		Skin.close(graphics, close, close.contains(mx, my));
		// Settings: a cog beside the X.
		Rectangle cog = new Rectangle(close.x - 26, close.y, 20, 20);
		marks.put("cog", cog);
		pane.hits.add(new Hit(cog, () ->
		{
			settingsOpen = !settingsOpen;
			filtersOpen = false;
			sortOpen = false;
			patchNotes = false;
			patchScroll = 0;
		}));
		boolean cogHover = cog.contains(mx, my);
		if (cogHover)
		{
			pane.tip = "Settings";
		}
		Skin.button(graphics, cog, cogHover, settingsOpen);
		cog(graphics, cog, settingsOpen || cogHover ? Color.WHITE : SUBTEXT);
		graphics.setFont(small);
		long now = System.currentTimeMillis();
		if (now - statsAt > 1000)
		{
			statsAt = now;
			statDone = lists.doneIds().size();
			statChunks = lists.paidChunks();
		}
		int chipX = cog.x - 6;
		for (String[] stat : new String[][]{{statChunks + "", "chunks"}, {statDone + "", "done"},
			{plugin.getTotalPoints() + "", "pts"}})
		{
			chipX -= statChip(graphics, chipX, y + 11, stat[0], stat[1]) + 5;
		}
	}

	/** A small cog: a toothed ring with a hole, centred in {@code r}. */
	private static void cog(Graphics2D g, Rectangle r, Color color)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double cx = r.getCenterX();
		double cy = r.getCenterY();
		double outer = Math.min(r.width, r.height) * 0.36;
		java.awt.geom.Path2D teeth = new java.awt.geom.Path2D.Double();
		for (int i = 0; i < 16; i++)
		{
			double a = Math.PI * 2 * i / 16;
			double rr = i % 2 == 0 ? outer : outer * 0.72;
			double a2 = a + Math.PI * 2 / 16;
			if (i == 0)
			{
				teeth.moveTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			else
			{
				teeth.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			teeth.lineTo(cx + Math.cos(a2) * rr, cy + Math.sin(a2) * rr);
		}
		teeth.closePath();
		java.awt.geom.Area shape = new java.awt.geom.Area(teeth);
		shape.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(cx - outer * 0.34, cy - outer * 0.34,
			outer * 0.68, outer * 0.68)));
		g.setColor(new Color(0, 0, 0, 170));
		g.fill(java.awt.geom.AffineTransform.getTranslateInstance(1, 1).createTransformedShape(shape));
		g.setColor(color);
		g.fill(shape);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	/** Down the left: the list tabs, the tier filter and the current-chunk switch. Returns the column. */
	private Rectangle drawSidebar(Graphics2D graphics, Rectangle window, int mx, int my)
	{
		int x = window.x;
		int y = window.y;
		int width = window.width;
		int height = window.height;
		Font small = Skin.small();
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		// (A little narrower in the fixed-size client.)
		Rectangle side = new Rectangle(x + 1, y + HEADER + 1, width < 540 ? SIDEBAR - 20 : SIDEBAR, height - HEADER - 2);
		// A sunken stone column, inside the frame (and clear of Varlamore's corner trim).
		int trim = Skin.palette().flourishes ? 2 : 0;
		Rectangle column = new Rectangle(side.x + 3 + trim, side.y + 1, side.width - 3 - trim, side.height - 4 - trim);
		graphics.setColor(SIDEBAR_FILL);
		graphics.fillRect(column.x, column.y, column.width, column.height);
		Skin.bevel(graphics, column, false);
		int sy = side.y + 10;
		graphics.setFont(small);
		graphics.setColor(LABEL);
		Skin.text(graphics, "LISTS", side.x + PAD + 2, sy + 8);
		sy += 14;
		List<Object[]> tabs = new ArrayList<>();
		Set<String> archived = archive.ids();
		Set<String> saved = lists.savedIds();
		tabs.add(new Object[]{Tab.ACTIVE, "Active", lists.count(false, id -> !archived.contains(id))});
		tabs.add(new Object[]{Tab.SAVED, "Saved", lists.count(true, id -> saved.contains(id) && !archived.contains(id))});
		tabs.add(new Object[]{Tab.ARCHIVED, "Archived", lists.count(true, archived::contains)});
		// New: what you've just unlocked or can now do. Its badge counts what you haven't looked at.
		tabs.add(new Object[]{Tab.NEW, "New", tab == Tab.NEW ? shownNew.size() + shownDoable.size() : alerts()});
		int chosenY = sy;
		for (Object[] t : tabs)
		{
			if (t[0] == tab)
			{
				chosenY = sy;
			}
			Rectangle tabArea = new Rectangle(side.x + 6, sy, side.width - 12, 24);
			marks.put("tab:" + ((Tab) t[0]).name(), tabArea);
			drawTab(graphics, tabArea, (String) t[1], (Integer) t[2], (Tab) t[0], mx, my);
			sy += 27;
		}
		// The marker slides to the chosen tab.
		tabMarkerY = tabMarkerY < 0 ? chosenY : tabMarkerY + (chosenY - tabMarkerY) * 0.3f;
		graphics.setColor(TITLE);
		graphics.fillRect(side.x + 6, Math.round(tabMarkerY) + 4, 3, 16);

		if (!settingsOpen && !patchNotes)
		{
			// How the list shows: every task opened up, and the red line of what you're missing.
			sy += 10;
			graphics.setFont(small);
			graphics.setColor(LABEL);
			Skin.text(graphics, "VIEW", side.x + PAD + 2, sy + 8);
			sy += 13;
			boolean narrow = side.width < SIDEBAR;
			drawSwitch(graphics, new Rectangle(side.x + 6, sy, side.width - 12, 22), narrow ? "Expand all" : "Expand all tasks",
				config.expandAllTasks(), mx, my, () -> setUi("expandAllTasks", !config.expandAllTasks()));
			sy += 24;
			drawSwitch(graphics, new Rectangle(side.x + 6, sy, side.width - 12, 22), narrow ? "Missing reqs" : "Missing levels",
				config.showMissingRequirements(), mx, my, () -> setUi("showMissingRequirements", !config.showMissingRequirements()));
		}
		// The current-chunk switch (or the chunk picked on the map) sits at the bottom.
		String chunkLabel = lists.pinnedChunk != null ? Skin.fit(sm, lists.pinnedChunk, side.width - 40) : side.width < SIDEBAR ? "This chunk" : "This chunk only";
		drawChunkToggle(graphics, new Rectangle(side.x + 6, side.y + side.height - 30, side.width - 12, 22), chunkLabel, mx, my);
		return side;
	}

	/** The hint line under the list: how many, what narrows it, and what clicks do. */
	private void drawFooter(Graphics2D graphics, Rectangle window, int cx, int shown, int mx, int my)
	{
		int x = window.x;
		int y = window.y;
		int width = window.width;
		int height = window.height;
		graphics.setFont(Skin.small());
		FontMetrics sm = graphics.getFontMetrics();
		graphics.setColor(LABEL);
		int footY = y + height - 7;
		String countLabel = shown + (shown == 1 ? " task" : " tasks");
		Skin.text(graphics, countLabel, cx, footY);
		// When something narrows the list, say what, with a link that shows everything again.
		List<String> narrowing = new ArrayList<>();
		if (!lists.search.trim().isEmpty())
		{
			narrowing.add("search");
		}
		if (lists.anyFilter())
		{
			narrowing.add("filters");
		}
		if (lists.pinnedChunk != null || lists.currentChunkOnly)
		{
			narrowing.add("this chunk");
		}
		if (!narrowing.isEmpty() && tab != Tab.NEW)
		{
			String why = " (" + String.join(", ", narrowing) + ")  ";
			int whyX = cx + sm.stringWidth(countLabel);
			Skin.text(graphics, why, whyX, footY);
			String showAll = "Show all";
			Rectangle showAllArea = new Rectangle(whyX + sm.stringWidth(why), footY - 11, sm.stringWidth(showAll), 14);
			boolean showAllHover = showAllArea.contains(mx, my);
			graphics.setColor(showAllHover ? Color.WHITE : TITLE);
			Skin.text(graphics, showAll, showAllArea.x, footY);
			graphics.drawLine(showAllArea.x, footY + 1, showAllArea.x + showAllArea.width, footY + 1);
			pane.hits.add(new Hit(showAllArea, this::showAll));
			graphics.setColor(LABEL);
		}
		// Each control on its own: the key in the title colour, what it does in the label colour,
		// with a divider between, so it doesn't read as one long sentence.
		String[][] hints = {{"Click", "track"}, {"Right-click", "save"}, {"Arrow", "details"}};
		String gap = "   |   ";
		int hintX = x + width - PAD;
		for (int i = hints.length - 1; i >= 0; i--)
		{
			String action = " " + hints[i][1];
			hintX -= sm.stringWidth(action);
			graphics.setColor(LABEL);
			Skin.text(graphics, action, hintX, footY);
			hintX -= sm.stringWidth(hints[i][0]);
			graphics.setColor(TITLE);
			Skin.text(graphics, hints[i][0], hintX, footY);
			if (i > 0)
			{
				hintX -= sm.stringWidth(gap);
				graphics.setColor(LABEL.darker());
				Skin.text(graphics, gap, hintX, footY);
			}
		}
	}

	/** After the window: the hover state, then the quest pane beside it or the tooltip. */
	private void finishFrame(Graphics2D graphics, Rectangle window, int mx, int my)
	{
		finishHover(window, mx, my);
		pane.shownArea = new Rectangle(window);
		if (pane.questPane != null)
		{
			// A quest opened from the window shows beside it; the window stays open.
			pane.drawSidePane(graphics);
		}
		else
		{
			pane.drawTip(graphics, mx, my, 0, 0);
		}
	}

	/** A header number with its label ("123 pts"), right-aligned to {@code right}; returns its width. */
	private static int statChip(Graphics2D graphics, int right, int y, String value, String label)
	{
		FontMetrics fm = graphics.getFontMetrics();
		int w = fm.stringWidth(value) + fm.stringWidth(label) + 16;
		graphics.setColor(FIELD);
		graphics.fillRect(right - w, y, w, 18);
		Skin.bevel(graphics, new Rectangle(right - w, y, w, 18), false);
		graphics.setColor(POINTS);
		Skin.text(graphics, value, right - w + 6, y + 13);
		graphics.setColor(SUBTEXT);
		Skin.text(graphics, label, right - w + 10 + fm.stringWidth(value), y + 13);
		return w;
	}

	// --- Settings tab -----------------------------------------------------------

	private static final int SETTINGS_ROW = 22;

	/**
	 * One line of the Settings tab: a section heading (no key), a toggle, a choice (click
	 * for the next one) or a number (- and +). Keys are RuneLite's config keys, so they
	 * keep working everywhere the plugin reads its config.
	 */
	private static final class Setting
	{
		final String label;
		final String description;
		final String key;
		final Supplier<Object> current;
		final Object[] choices;
		final int min;
		final int max;
		final int step;
		// A key to press, set by clicking it and pressing the new key.
		boolean keybind;
		// A small button beside the control (e.g. "Test" by Card design), or null.
		String actionLabel;
		Runnable action;

		Setting(String label, String description, String key, Supplier<Object> current, Object[] choices,
			int min, int max, int step)
		{
			this.label = label;
			this.description = description;
			this.key = key;
			this.current = current;
			this.choices = choices;
			this.min = min;
			this.max = max;
			this.step = step;
		}
	}

	/** The same setting with a small button beside its control. */
	private static Setting withAction(Setting setting, String label, Runnable action)
	{
		setting.actionLabel = label;
		setting.action = action;
		return setting;
	}

	private static Setting heading(String label)
	{
		return new Setting(label, null, null, null, null, 0, 0, 0);
	}

	private static Setting toggle(String label, String key, Supplier<Object> current, String description)
	{
		return new Setting(label, description, key, current, null, 0, 0, 0);
	}

	private static Setting choice(String label, String key, Supplier<Object> current, Object[] choices, String description)
	{
		return new Setting(label, description, key, current, choices, 0, 0, 0);
	}

	private static Setting keybind(String label, String key, Supplier<Object> current, String description)
	{
		Setting setting = new Setting(label, description, key, current, null, 0, 0, 0);
		setting.keybind = true;
		return setting;
	}

	/** One of ChunkBlazer's own keybinds (the world map's), or its default. */
	private Keybind coreKey(String key, Keybind fallback)
	{
		Keybind value = configManager.getConfiguration("chunkblazer", key, Keybind.class);
		return value != null ? value : fallback;
	}

	// The keybind waiting for its new key (its config key), and the key pressed so far.
	private volatile String listeningKey;
	private volatile Keybind pressedKey;

	@Override
	public boolean capturingKey()
	{
		return listeningKey != null && open && settingsOpen;
	}

	/**
	 * ChunkBlazer UI's own settings, in sections. Everything the core plugin uses too (chunk
	 * borders, popups, sounds, chat messages), colours, keybinds and server sync are in
	 * RuneLite's settings panel, so they're there with or without ChunkBlazer UI.
	 */
	private List<Setting> settings()
	{
		if (settings == null)
		{
			settings = Arrays.asList(
				heading("Look"),
				withAction(choice("Theme", "theme", config::theme, Theme.values(),
					"ChunkBlazer's look: cards, this window, boxes and the side panel. Click to change; Test deals 4 cards."),
					"Test", this::testCards),
				choice("Card entrance", "cardEntrance", config::cardEntrance, CardEntrance.values(),
					"How a fresh deal of task cards arrives on screen. Click to change."),
				heading("Tracked task"),
				choice("Task box", "taskTrackerStyle", config::taskTrackerStyle, TaskTrackerStyle.values(),
					"How the task you track is shown in game. Off hides it. Click to change."),
				choice("Tracked task in", "trackedTaskShows", config::trackedTaskShows, TrackedTaskShows.values(),
					"Where the task you track shows: the side panel, the task box on screen (widget), or both. Click to change."),
				toggle("Saved tasks bubble", "showSavedTaskTracker", config::showSavedTaskTracker,
					"A bubble that opens your saved tasks (Alt + drag to move)."),
				toggle("Movable quest/saved box", "movableTaskBox", config::movableTaskBox,
					"Open quest requirements and saved tasks in a box you can Alt + drag, instead of over the inventory."),
				heading("Assists"),
				toggle("Task details", "showTaskDetails", config::showTaskDetails,
					"Where a task's item comes from: its source, drop rate, cost and how to get it. Off for no help."),
				choice("Outlines", "taskOutlineMode", config::taskOutlineMode, OutlineMode.values(),
					"Which task NPCs and objects get outlined: all, saved, doable now, or none. Click to change."),
				toggle("Highlight task items", "highlightEquipItems", config::highlightEquipItems,
					"Outline items your tasks need (gear, tools) in your inventory, bank and shops."),
				toggle("Right-click Tasks menu", "taskRightClickMenu", config::taskRightClickMenu,
					"Adds a Tasks submenu when right-clicking NPCs and objects your tasks need, to track one."),
				toggle("Auto-track tasks", "autoTrackTasks", config::autoTrackTasks,
					"Using an NPC or object a task needs tracks that task. A task you picked yourself is kept."),
				toggle("Shortest path", "shortestPath", config::shortestPath,
					"Paths through your unlocked chunks (Path here, the map's path key, Quest Helper) and the quest Calc button."),
				heading("Keybinds"),
				keybind("View chunk tasks", "worldMapTasksKey", () -> coreKey("worldMapTasksKey", Keybind.CTRL),
					"Hold this and click an unlocked chunk on the world map to see its tasks. Click, then press the new key."),
				keybind("Unlock chunk", "worldMapUnlockKey", () -> coreKey("worldMapUnlockKey", Keybind.SHIFT),
					"Hold this and click a chunk next to yours on the world map to unlock it. Click, then press the new key."),
				keybind("Set path", "pathKey", config::pathKey,
					"Hold this and click the world map to set a path there, or clear the one showing. Click, then press the new key."),
				choice("Task tooltip key", "taskTooltipKey", config::taskTooltipKey, TaskTooltipKey.values(),
					"Hold this key over an NPC or object to list its tasks in a tooltip (an assist). Click to change."),
				heading("World map"),
				toggle("Colour legend", "showChunkLegend", config::showChunkLegend,
					"Show a key explaining the chunk colours in the corner of the world map."),
				toggle("Keys legend", "showMapKeys", config::showMapKeys,
					"Show the world map's click keys (tasks, path) in its bottom-right corner."),
				heading("In game"),
				toggle("Chunk name banner", "showChunkNamePopups", config::showChunkNamePopups,
					"Show a chunk's name at the top of the screen when you enter it."),
				withAction(toggle("Task complete popup", "showTaskCompletionPopup", plugin::showTaskCompletionPopup,
					"The popup when you complete a task (also in RuneLite's settings). Test shows it."),
					"Test", this::testCompletion),
				heading("Tutorial"),
				withAction(new Setting("Tutorial", "Go through the tutorial's tour of the interface again, from the mainland part.",
					"", () -> null, null, 0, 0, 0), "Restart", () -> clientThread.invoke(() -> tutorial.get().replay())));
		}
		return settings;
	}

	/**
	 * The Settings tab in the list's place: its sections in one column, or two when there's
	 * room, scrolling. Returns the hovered setting's description (for the footer), or null.
	 */
	private String drawSettingsPage(Graphics2D graphics, Rectangle area, int mx, int my)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		List<Setting> all = settings();
		// Split into sections at each heading, then share them between the columns.
		List<List<Setting>> sections = new ArrayList<>();
		for (Setting setting : all)
		{
			if (setting.key == null || sections.isEmpty())
			{
				sections.add(new ArrayList<>());
			}
			sections.get(sections.size() - 1).add(setting);
		}
		boolean two = area.width >= 440;
		int columnWidth = two ? (area.width - 30) / 2 : area.width - 20;
		List<List<List<Setting>>> columns = new ArrayList<>();
		columns.add(new ArrayList<>());
		if (two)
		{
			columns.add(new ArrayList<>());
			// Fill the left column to about half the rows, the rest on the right.
			int half = (all.size() + sections.size()) / 2;
			int count = 0;
			for (List<Setting> section : sections)
			{
				columns.get(count < half ? 0 : 1).add(section);
				count += section.size() + 1;
			}
		}
		else
		{
			columns.get(0).addAll(sections);
		}
		int contentHeight = 0;
		for (List<List<Setting>> column : columns)
		{
			contentHeight = Math.max(contentHeight, 8 + column.stream().mapToInt(sec -> sec.size() * SETTINGS_ROW + 8).sum());
		}
		patchScroll = Math.max(0, Math.min(patchScroll, Math.max(0, contentHeight - area.height)));

		Shape oldClip = graphics.getClip();
		graphics.clip(area);
		graphics.setColor(new Color(0, 0, 0, 40));
		graphics.fillRect(area.x, area.y, area.width, area.height);
		boolean in = area.contains(mx, my);
		String hovered = null;
		for (int c = 0; c < columns.size(); c++)
		{
			int colX = area.x + 8 + c * (columnWidth + 10);
			int rowY = area.y + 6 - patchScroll;
			for (List<Setting> section : columns.get(c))
			{
				for (Setting setting : section)
				{
					Rectangle row = new Rectangle(colX, rowY, columnWidth, SETTINGS_ROW);
					if (setting.key == null)
					{
						graphics.setColor(TITLE);
						Skin.text(graphics, setting.label.toUpperCase(), colX + 2, rowY + SETTINGS_ROW - 7);
						Skin.divider(graphics, colX, rowY + SETTINGS_ROW - 2, colX + columnWidth);
					}
					else
					{
						boolean hover = in && row.contains(mx, my);
						if (hover)
						{
							hovered = setting.description;
						}
						drawSetting(graphics, fm, row, setting, in ? mx : -1, my, area);
					}
					rowY += SETTINGS_ROW;
				}
				rowY += 8;
			}
		}
		graphics.setClip(oldClip);
		pane.drawScrollbar(graphics, area, contentHeight, patchScroll);
		return hovered;
	}

	/** One setting row: its label, then a switch, a choice button or - value +, at the right. */
	private void drawSetting(Graphics2D graphics, FontMetrics fm, Rectangle row, Setting setting, int mx, int my,
		Rectangle clip)
	{
		boolean hover = row.contains(mx, my);
		if (hover)
		{
			graphics.setColor(CARD_HOVER);
			graphics.fillRect(row.x, row.y + 1, row.width, row.height - 2);
		}
		int textY = row.y + (row.height + fm.getAscent()) / 2 - 1;
		Object value = setting.current.get();
		int right = row.x + row.width - 6;
		int controlWidth;
		if (setting.key.isEmpty())
		{
			// Just a button (Restart), no setting behind it.
			drawAction(graphics, fm, setting, right + 4, row, mx, my, clip, textY);
			controlWidth = fm.stringWidth(setting.actionLabel) + 14;
		}
		else if (setting.keybind)
		{
			boolean listening = setting.key.equals(listeningKey);
			Keybind pressed = pressedKey;
			String text = listening ? (pressed != null ? pressed.toString() : "Press a key...") : String.valueOf(value);
			controlWidth = Math.min(Math.max(fm.stringWidth("Press a key...") + 16, fm.stringWidth(text) + 16), row.width / 2);
			Rectangle button = new Rectangle(right - controlWidth, row.y + 3, controlWidth, row.height - 6);
			if (mx >= 0)
			{
				pane.hits.add(new Hit(row.intersection(clip), () ->
				{
					listeningKey = listening ? null : setting.key;
					pressedKey = null;
				}));
			}
			Skin.button(graphics, button, hover, listening);
			graphics.setColor(listening ? POINTS : hover ? Color.WHITE : TITLE);
			text = Skin.fit(fm, text, controlWidth - 8);
			Skin.text(graphics, text, button.x + (button.width - fm.stringWidth(text)) / 2, textY);
			if (listening && hover)
			{
				pane.tip = "Press the new key (with Ctrl, Shift or Alt if you like). Backspace clears it, Esc cancels.";
			}
		}
		else if (setting.choices != null)
		{
			int widest = 0;
			for (Object choice : setting.choices)
			{
				widest = Math.max(widest, fm.stringWidth(String.valueOf(choice)));
			}
			controlWidth = Math.min(widest + 16, row.width / 2);
			Rectangle button = new Rectangle(right - controlWidth, row.y + 3, controlWidth, row.height - 6);
			int index = Arrays.asList(setting.choices).indexOf(value);
			Object next = setting.choices[(index + 1) % setting.choices.length];
			drawAction(graphics, fm, setting, button.x, row, mx, my, clip, textY);
			if (mx >= 0)
			{
				pane.hits.add(new Hit(row.intersection(clip), () -> configManager.setConfiguration(groupOf(setting.key), setting.key, next)));
			}
			Skin.button(graphics, button, hover, false);
			graphics.setColor(hover ? Color.WHITE : TITLE);
			String text = Skin.fit(fm, String.valueOf(value), controlWidth - 8);
			Skin.text(graphics, text, button.x + (button.width - fm.stringWidth(text)) / 2, textY);
		}
		else if (setting.step > 0)
		{
			int current = value instanceof Integer ? (Integer) value : setting.min;
			controlWidth = 74;
			Rectangle minus = new Rectangle(right - controlWidth, row.y + 3, 18, row.height - 6);
			Rectangle plus = new Rectangle(right - 18, row.y + 3, 18, row.height - 6);
			if (mx >= 0)
			{
				pane.hits.add(new Hit(minus.intersection(clip), () -> configManager.setConfiguration(groupOf(setting.key), setting.key,
					Math.max(setting.min, current - setting.step))));
				pane.hits.add(new Hit(plus.intersection(clip), () -> configManager.setConfiguration(groupOf(setting.key), setting.key,
					Math.min(setting.max, current + setting.step))));
			}
			for (Rectangle b : new Rectangle[]{minus, plus})
			{
				boolean bh = b.contains(mx, my);
				Skin.button(graphics, b, bh, false);
				graphics.setColor(bh ? Color.WHITE : TITLE);
				String sign = b == minus ? "-" : "+";
				Skin.text(graphics, sign, b.x + (b.width - fm.stringWidth(sign)) / 2, textY);
			}
			String n = String.valueOf(current);
			graphics.setColor(POINTS);
			Skin.text(graphics, n, minus.x + minus.width + (controlWidth - 36 - fm.stringWidth(n)) / 2, textY);
		}
		else
		{
			boolean on = Boolean.TRUE.equals(value);
			controlWidth = 22;
			if (drawAction(graphics, fm, setting, right - controlWidth - 6, row, mx, my, clip, textY))
			{
				controlWidth += fm.stringWidth(setting.actionLabel) + 24;
			}
			if (mx >= 0)
			{
				pane.hits.add(new Hit(row.intersection(clip), () -> configManager.setConfiguration(groupOf(setting.key), setting.key, !on)));
			}
			// The same little switch as "This chunk only": the knob slides right and goes orange when on.
			int sx = right - controlWidth;
			int sy = row.y + (row.height - 12) / 2;
			graphics.setColor(on ? new Color(255, 152, 31, 150) : FIELD);
			graphics.fillRect(sx, sy, controlWidth, 12);
			graphics.setColor(on ? Color.WHITE : SUBTEXT);
			graphics.fillOval(on ? sx + controlWidth - 11 : sx + 1, sy + 1, 10, 10);
		}
		graphics.setColor(hover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, Skin.fit(fm, setting.label, row.width - controlWidth - 18), row.x + 6, textY);
	}

	/**
	 * A setting's own small button (Test) just left of its control at {@code left}, added
	 * before the row's own click so it wins. True if there is one.
	 */
	private boolean drawAction(Graphics2D graphics, FontMetrics fm, Setting setting, int left, Rectangle row, int mx, int my,
		Rectangle clip, int textY)
	{
		if (setting.action == null)
		{
			return false;
		}
		int aw = fm.stringWidth(setting.actionLabel) + 14;
		Rectangle act = new Rectangle(left - aw - 4, row.y + 3, aw, row.height - 6);
		boolean actHover = act.contains(mx, my);
		if (mx >= 0)
		{
			pane.hits.add(new Hit(act.intersection(clip), setting.action));
		}
		Skin.button(graphics, act, actHover, false);
		graphics.setColor(actHover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, setting.actionLabel, act.x + (act.width - fm.stringWidth(setting.actionLabel)) / 2, textY);
		return true;
	}

	/** patch_notes.txt, read once: "## " starts a version, "- " a note. Missing just means none. */
	private static List<String> patchNoteLines()
	{
		if (patchNoteLines == null)
		{
			List<String> lines = new ArrayList<>();
			try (java.io.InputStream in = TaskWindow.class.getResourceAsStream("patch_notes.txt"))
			{
				if (in != null)
				{
					java.io.BufferedReader reader = new java.io.BufferedReader(
						new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
					for (String line = reader.readLine(); line != null; line = reader.readLine())
					{
						lines.add(line);
					}
				}
			}
			catch (java.io.IOException e)
			{
				lines.clear();
			}
			patchNoteLines = lines;
		}
		return patchNoteLines;
	}

	/** The patch notes in the list's place: version headings and wrapped bullet points, scrolling. */
	private void drawPatchNotes(Graphics2D graphics, Rectangle area, Font regular, Font small)
	{
		FontMetrics rm = graphics.getFontMetrics(regular);
		FontMetrics sm = graphics.getFontMetrics(small);
		int textWidth = area.width - 40;
		// Lay out first, to know the height for scrolling.
		List<Object[]> parts = new ArrayList<>(); // {text, font, colour, x offset, line height}
		for (String raw : patchNoteLines())
		{
			String line = raw.trim();
			if (line.isEmpty())
			{
				parts.add(new Object[]{"", small, SUBTEXT, 0, 8});
			}
			else if (line.startsWith("## "))
			{
				parts.add(new Object[]{line.substring(3), regular, TITLE, 0, rm.getHeight() + 4});
			}
			else
			{
				boolean bullet = line.startsWith("- ");
				boolean first = true;
				for (String part : wrap(sm, bullet ? line.substring(2) : line, textWidth - 12))
				{
					parts.add(new Object[]{(bullet && first ? "\u2022 " : "") + part, small, Color.WHITE,
						bullet && !first ? 20 : 12, DETAIL_LINE});
					first = false;
				}
			}
		}
		int contentHeight = 12;
		for (Object[] part : parts)
		{
			contentHeight += (Integer) part[4];
		}
		if (parts.isEmpty())
		{
			graphics.setFont(regular);
			graphics.setColor(SUBTEXT);
			drawCentered(graphics, "No patch notes yet.", area);
			return;
		}
		patchScroll = Math.max(0, Math.min(patchScroll, Math.max(0, contentHeight - area.height)));
		Shape oldClip = graphics.getClip();
		graphics.clip(area);
		graphics.setColor(new Color(0, 0, 0, 40));
		graphics.fillRect(area.x, area.y, area.width, area.height);
		int ty = area.y + 8 - patchScroll;
		for (Object[] part : parts)
		{
			int lineHeight = (Integer) part[4];
			Font font = (Font) part[1];
			graphics.setFont(font);
			if (font == regular && ty > area.y + 8 - patchScroll)
			{
				// A divider over each version after the first.
				Skin.divider(graphics, area.x + 8, ty - 2, area.x + area.width - 14);
			}
			graphics.setColor((Color) part[2]);
			Skin.text(graphics, (String) part[0], area.x + 10 + (Integer) part[3], ty + graphics.getFontMetrics().getAscent());
			ty += lineHeight;
		}
		graphics.setClip(oldClip);
		pane.drawScrollbar(graphics, area, contentHeight, patchScroll);
	}

	/** The ChunkBlazer icon for the header, read once (null if missing). */
	private static BufferedImage logo()
	{
		if (logo == null)
		{
			try (java.io.InputStream in = TaskPaneOverlay.class.getResourceAsStream("icon.png"))
			{
				logo = in == null ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) : javax.imageio.ImageIO.read(in);
			}
			catch (java.io.IOException e)
			{
				logo = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			}
		}
		return logo.getWidth() > 1 ? logo : null;
	}

	/** The task list as cards, with expanded details, scrolling and the empty-list message. Returns how many tasks. */
	private int drawList(Graphics2D graphics, Rectangle list, int mx, int my, Font regular, Font small)
	{
		List<Entry> entries = currentRows();
		FontMetrics sm = graphics.getFontMetrics(small);
		int detailWidth = list.width - 70;
		FontMetrics rm = graphics.getFontMetrics(regular);
		List<List<QuestRequirements.Line>> details = new ArrayList<>();
		List<Integer> heights = new ArrayList<>();
		int contentHeight = 4;
		int tasks = 0;
		for (Entry entry : entries)
		{
			List<QuestRequirements.Line> wrapped = entry.task != null && isExpanded(entry.task)
				? detail(sm, entry.task, detailWidth) : new ArrayList<>();
			details.add(wrapped);
			int nameLines = entry.task == null ? 1 : rowName(rm, entry.task, list.width - 12 - (entry.child ? 18 : 0) - 160).size();
			heights.add(isSection(entry) ? SECTION_ROW : ROW + (nameLines - 1) * NAME_LINE + detailHeight(wrapped));
			contentHeight += heights.get(heights.size() - 1) + ROW_GAP;
			tasks += entry.task != null ? 1 : isSection(entry) ? 0 : entry.members.size();
		}
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - list.height)));

		Set<String> saved = lists.savedIds();
		Shape oldClip = graphics.getClip();
		graphics.clip(list);
		if (entries.isEmpty())
		{
			graphics.setFont(regular);
			graphics.setColor(SUBTEXT);
			// The empty Archived list's message is about the book icon, so it shows the book.
			if (tab == Tab.ARCHIVED)
			{
				graphics.drawImage(Skin.image("book_empty"), list.x + list.width / 2 - 10, list.y + list.height / 2 - 35, null);
			}
			else
			{
				graphics.drawImage(Skin.image("star_empty"), list.x + list.width / 2 - 13, list.y + list.height / 2 - 35, null);
			}
			drawCentered(graphics, emptyMessage(), list);
		}
		int rowY = list.y + 4 - scroll;
		for (int i = 0; i < entries.size(); i++)
		{
			Entry entry = entries.get(i);
			int rowHeight = heights.get(i);
			if (rowY + rowHeight >= list.y && rowY <= list.y + list.height)
			{
				int indent = entry.child ? 18 : 0;
				Rectangle area = new Rectangle(list.x + 2 + indent, rowY, list.width - 12 - indent, rowHeight);
				if (isSection(entry))
				{
					// A section heading on the New tab: its name in the theme's colour, a line under it.
					graphics.setFont(small);
					graphics.setColor(TITLE);
					Skin.text(graphics, entry.group.toUpperCase(), area.x + 4, area.y + SECTION_ROW - 7);
					Skin.divider(graphics, area.x, area.y + SECTION_ROW - 2, area.x + area.width);
					rowY += rowHeight + ROW_GAP;
					continue;
				}
				boolean hover = list.contains(mx, my) && area.contains(mx, my);
				// Leagues-style rows: alternately darker, lit under the mouse, a thin line between.
				graphics.setColor(hover ? CARD_HOVER : i % 2 == 0 ? new Color(0, 0, 0, 48) : new Color(0, 0, 0, 22));
				graphics.fillRect(area.x, area.y, area.width, area.height);
				graphics.setColor(new Color(0, 0, 0, 120));
				graphics.drawLine(area.x, area.y + area.height + ROW_GAP / 2, area.x + area.width, area.y + area.height + ROW_GAP / 2);
				if (entry.task == null)
				{
					drawGroupRow(graphics, entry, area, list, mx, my, regular, small);
				}
				else
				{
					drawRow(graphics, entry.task, area, details.get(i), saved, list, mx, my, regular, small);
					if (tab == Tab.NEW && (shownDoable.contains(entry.task.getTaskId()) || shownNew.contains(entry.task.getTaskId())))
					{
						// What's new since you last looked: pointed out until you leave the tab.
						pulseOutline(graphics, area);
					}
				}
			}
			rowY += rowHeight + ROW_GAP;
		}
		graphics.setClip(oldClip);
		pane.drawScrollbar(graphics, list, contentHeight, scroll);
		return tasks;
	}

	private static boolean isSection(Entry entry)
	{
		return entry.task == null && entry.members == null;
	}

	private String emptyMessage()
	{
		if (lists.pinnedChunk != null)
		{
			return "No tasks left in " + lists.pinnedChunk + ".";
		}
		if (lists.currentChunkOnly)
		{
			String here = lists.currentChunkName();
			return here.isEmpty() ? "You're not in a chunk with tasks." : "No tasks here in " + here + ".";
		}
		if (lists.search.isEmpty() && tab != Tab.ACTIVE)
		{
			return tab == Tab.NEW ? "Unlock a chunk or level up: new tasks show here."
				: tab == Tab.ARCHIVED ? "Click a task's book icon to archive it here."
				: "Star tasks in the Active tab to save them here.";
		}
		return "No tasks match.";
	}

	/** Work out what a click would do. */
	private void finishHover(Rectangle window, int mx, int my)
	{
		// The quest box is drawn on top, so over it, it gets the click.
		boolean inPane = pane.paneIn && pane.questPane != null;
		pane.mouseInWindow = inPane || window.contains(mx, my);
		if (inPane)
		{
			titleHovered = false;
		}
		else
		{
			pane.hoveredAction = firstHit(pane.hits, mx, my);
		}
	}

	/** A pulsing outline in the theme's colour, pointing something out. */
	private static void pulseOutline(Graphics2D graphics, Rectangle area)
	{
		double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() * 2 * Math.PI / PULSE_MS);
		Stroke previous = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2f));
		graphics.setColor(Tints.alpha(TITLE, (int) (90 + 150 * pulse)));
		graphics.drawRect(area.x + 1, area.y + 1, area.width - 2, area.height - 2);
		graphics.setStroke(previous);
	}

	/**
	 * While the window is closed with new tasks waiting: a pulsing glow around the Points orb.
	 */
	private void drawNewTaskAlert(Graphics2D graphics)
	{
		Rectangle b = pane.taskButton;
		if (b == null)
		{
			return;
		}
		double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() * 2 * Math.PI / PULSE_MS);
		Stroke previousStroke = graphics.getStroke();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(new Color(255, 152, 31, (int) (35 * pulse)));
		graphics.fillRect(b.x - 3, b.y - 3, b.width + 6, b.height + 6);
		graphics.setColor(new Color(255, 152, 31, (int) (90 + 150 * pulse)));
		graphics.setStroke(new BasicStroke(2.5f));
		graphics.drawRect(b.x - 3, b.y - 3, b.width + 6, b.height + 6);
		graphics.setStroke(previousStroke);
	}

	// --- Header controls -------------------------------------------------------

	/** "This chunk only" as a switch; or, showing a chunk picked on the map, its name with an x. */
	private void drawChunkToggle(Graphics2D graphics, Rectangle area, String label, int mx, int my)
	{
		boolean hover = area.contains(mx, my);
		pane.hits.add(0, new Hit(area, () ->
		{
			if (lists.pinnedChunk != null)
			{
				lists.pinnedChunk = null;
			}
			else
			{
				lists.currentChunkOnly = !lists.currentChunkOnly;
			}
			lists.refresh();
		}));
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int textY = area.y + (area.height + fm.getAscent()) / 2 - 1;
		if (hover)
		{
			graphics.setColor(CARD_HOVER);
			graphics.fillRect(area.x, area.y, area.width, area.height);
		}
		if (lists.pinnedChunk != null)
		{
			graphics.setColor(TITLE);
			Skin.text(graphics, label, area.x + 6, textY);
			cross(graphics, area.x + area.width - 9, area.y + area.height / 2, 3, hover ? NO_LEVEL : SUBTEXT);
			return;
		}
		switchKnob(graphics, area, label, lists.currentChunkOnly, hover);
	}

	/** A labelled switch down the sidebar that runs {@code flip} when clicked. */
	private void drawSwitch(Graphics2D graphics, Rectangle area, String label, boolean on, int mx, int my, Runnable flip)
	{
		boolean hover = area.contains(mx, my);
		pane.hits.add(new Hit(area, flip));
		if (hover)
		{
			graphics.setColor(CARD_HOVER);
			graphics.fillRect(area.x, area.y, area.width, area.height);
		}
		switchKnob(graphics, area, label, on, hover);
	}

	/** A little switch: the knob slides right and goes the theme's colour when on. */
	private void switchKnob(Graphics2D graphics, Rectangle area, String label, boolean on, boolean hover)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int textY = area.y + (area.height + fm.getAscent()) / 2 - 1;
		int sw = 22;
		int sx = area.x + 6;
		int sy = area.y + (area.height - 12) / 2;
		graphics.setColor(on ? Tints.alpha(TITLE, 150) : FIELD);
		graphics.fillRect(sx, sy, sw, 12);
		graphics.setColor(on ? Color.WHITE : SUBTEXT);
		graphics.fillOval(on ? sx + sw - 11 : sx + 1, sy + 1, 10, 10);
		graphics.setColor(on || hover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, label, sx + sw + 6, textY);
	}

	/** Changes one of ChunkBlazer UI's own settings. */
	private void setUi(String key, Object value)
	{
		configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, key, value);
	}

	/** A tab down the sidebar: its name and a count badge. (The marker by the chosen one is drawn after.) */
	private void drawTab(Graphics2D graphics, Rectangle area, String label, int count, Tab which, int mx, int my)
	{
		pane.hits.add(new Hit(area, () ->
		{
			if (tab == Tab.NEW && which != Tab.NEW)
			{
				seenNew();
			}
			if (which == Tab.NEW && tab != Tab.NEW)
			{
				showNew();
			}
			tab = which;
			patchNotes = false;
			settingsOpen = false;
			lists.refresh();
		}));
		boolean chosen = tab == which && !patchNotes && !settingsOpen;
		boolean hover = area.contains(mx, my);
		// Stone tab buttons; the chosen one glows red like the game's open side tab.
		Skin.button(graphics, area, hover, chosen);
		if (which == Tab.NEW && !chosen && alerts() > 0)
		{
			// Something new: the tab pulses until you look.
			pulseOutline(graphics, area);
		}
		graphics.setFont(Skin.regular());
		FontMetrics fm = graphics.getFontMetrics();
		graphics.setColor(chosen || hover ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, label, area.x + 12, area.y + (area.height + fm.getAscent()) / 2 - 2);
		graphics.setFont(Skin.small());
		fm = graphics.getFontMetrics();
		String n = String.valueOf(count);
		int bw = Math.max(18, fm.stringWidth(n) + 10);
		int bx = area.x + area.width - bw - 4;
		graphics.setColor(chosen || which == Tab.NEW && count > 0 ? new Color(255, 152, 31, 170) : FIELD);
		graphics.fillRect(bx, area.y + 5, bw, 14);
		graphics.setColor(chosen ? Color.WHITE : SUBTEXT);
		Skin.text(graphics, n, bx + (bw - fm.stringWidth(n)) / 2, area.y + 16);
	}

	// --- List rows ---------------------------------------------------------------

	/** Header for one skill's level-up tasks; click to show or hide its rungs. */
	private void drawGroupRow(Graphics2D graphics, Entry entry, Rectangle row, Rectangle list, int mx, int my,
		Font regular, Font small)
	{
		boolean hover = list.contains(mx, my) && row.contains(mx, my);
		if (list.contains(mx, my))
		{
			pane.hits.add(new Hit(row.intersection(list), () ->
			{
				if (!expandedGroups.remove(entry.group))
				{
					expandedGroups.add(entry.group);
				}
				rowsBuiltAt = 0;
			}));
		}
		arrow(graphics, row.x + 11, row.y + ROW / 2, expandedGroups.contains(entry.group), hover);
		Skill skill = null;
		try
		{
			skill = Skill.valueOf(entry.group);
		}
		catch (IllegalArgumentException ignored)
		{
			// Not a skill we know: no icon.
		}
		drawTaskIcon(graphics, row.x + 22, row.y + (ROW - 22) / 2, skill, null);
		int points = entry.members.stream().mapToInt(NuzlockeTask::getBasePoints).sum();
		int left = entry.members.size();
		String name = entry.group.charAt(0) + entry.group.substring(1).toLowerCase() + " levels";
		String info = left + (left == 1 ? " level-up left" : " level-ups left") + "  ·  next: level "
			+ requiredLevel(entry.members.get(0));
		drawRowText(graphics, row, row.x + 50, name, Color.WHITE, points + " pts", POINTS, info, SUBTEXT, regular, small);
	}

	/**
	 * Line 1: the name (left) and a points chip (right, tinted {@code chipColor}). Line 2: the
	 * info text. Returns where the chip starts, so the row's icons can sit before it.
	 */
	private int drawRowText(Graphics2D graphics, Rectangle row, int textX, String name, Color nameColor, String points,
		Color chipColor, String info, Color infoColor, Font regular, Font small)
	{
		int rightEdge = row.x + row.width - 8;
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		int chipW = sm.stringWidth(points) + 12;
		int chipX = rightEdge - chipW;
		graphics.setColor(POINTS);
		// A plain number in the Points column, as on the Leagues list.
		Skin.text(graphics, points, rightEdge - sm.stringWidth(points), row.y + 17);
		graphics.setFont(regular);
		FontMetrics fm = graphics.getFontMetrics();
		String[] lines = name.split("\n");
		for (int i = 0; i < lines.length; i++)
		{
			// A line starting with MISSING is what you're missing for it, in red.
			boolean missing = lines[i].startsWith(MISSING);
			graphics.setColor(missing ? NO_LEVEL : nameColor);
			String line = missing ? lines[i].substring(1) : lines[i];
			Skin.text(graphics, Skin.fit(fm, line, chipX - 48 - textX), textX, row.y + 17 + i * NAME_LINE);
		}
		if (info != null)
		{
			graphics.setFont(small);
			graphics.setColor(infoColor);
			Skin.text(graphics, Skin.fit(sm, info, rightEdge - 90 - textX), textX, row.y + 32 + (lines.length - 1) * NAME_LINE);
		}
		return chipX;
	}

	/**
	 * Every task's icon, in the same light stone slot: a quest's quest icon, an Obtain or Equip
	 * task's item, a skill task's skill icon, a combat task's crossed swords, or (none of
	 * those) the tier's colour with the category's first letter. Each picture is scaled to fit
	 * inside the slot, keeping its shape, so none hangs over the edge.
	 */
	private void drawTaskIcon(Graphics2D graphics, int x, int y, Skill skill, NuzlockeTask task)
	{
		Rectangle slot = new Rectangle(x - 1, y - 3, ICON_SLOT, ICON_SLOT);
		graphics.setColor(ITEM_SLOT);
		graphics.fillRect(slot.x, slot.y, slot.width, slot.height);
		Skin.bevel(graphics, slot, false);
		BufferedImage image = iconFor(task, skill);
		if (image != null && image.getWidth() > 0 && image.getHeight() > 0)
		{
			int room = ICON_SLOT - 4;
			double scale = Math.min(room / (double) image.getWidth(), room / (double) image.getHeight());
			int w = (int) Math.round(image.getWidth() * scale);
			int h = (int) Math.round(image.getHeight() * scale);
			Object hint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, slot.x + (ICON_SLOT - w) / 2, slot.y + (ICON_SLOT - h) / 2, w, h, null);
			if (hint != null)
			{
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
			}
			return;
		}
		Color tier = task == null ? SUBTEXT : TaskCardTier.fromTask(task).getAccent();
		graphics.setColor(Tints.alpha(tier, 120));
		graphics.fillOval(slot.x + 4, slot.y + 4, ICON_SLOT - 8, ICON_SLOT - 8);
		String letter = task == null || task.getCategory() == null || task.getCategory().isEmpty() ? "?"
			: NuzlockeTask.displayCategory(task.getCategory()).substring(0, 1);
		graphics.setFont(Skin.small());
		graphics.setColor(Color.WHITE);
		drawCentered(graphics, letter, slot);
	}

	/** The picture for a task's slot (see drawTaskIcon), or null for the letter fallback. */
	private BufferedImage iconFor(NuzlockeTask task, Skill skill)
	{
		if (task != null && QuestRequirements.isQuestTask(task))
		{
			return sprite(net.runelite.api.gameval.SpriteID.SideIcons.QUEST);
		}
		BufferedImage item = itemImage(task);
		if (item != null)
		{
			return item;
		}
		if (skill == null && task != null && typeIs(task, "SKILL_THRESHOLD"))
		{
			skill = progressionSkill(task); // "Reach Level 50 Mining": the skill it's a goal for
		}
		if (skill != null)
		{
			return skillIcons.getSkillImage(skill, false);
		}
		if (task != null && typeIs(task, "NPC_DIALOGUE"))
		{
			return sprite(net.runelite.api.gameval.SpriteID.OptionsIcons.CHAT); // the speech bubble
		}
		if (task != null && (typeIs(task, "NPC_KILL", "SLAYER", "COMBAT")
			|| (task.getCategory() != null && task.getCategory().toLowerCase().contains("combat"))))
		{
			return sprite(net.runelite.api.gameval.SpriteID.SideIcons.COMBAT);
		}
		return null;
	}

	/** The skill a progression (level-up) task is for, or null. */
	private static Skill progressionSkill(NuzlockeTask task)
	{
		TaskConstraints c = task.getConstraints();
		try
		{
			return c == null || c.getRequiredSkill() == null ? null : Skill.valueOf(c.getRequiredSkill().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** A game sprite (frame 0), loaded once. Overlays draw on the client thread, as loading needs. */
	private BufferedImage sprite(int id)
	{
		BufferedImage image = sprites.get(id);
		if (image == null && spriteManager != null)
		{
			image = spriteManager.getSprite(id, 0);
			if (image != null)
			{
				sprites.put(id, image);
			}
		}
		return image;
	}

	/** The picture of an Obtain or Equip task's (first) item, or null for any other task. */
	private BufferedImage itemImage(NuzlockeTask task)
	{
		String type = task == null || task.getCompletionType() == null ? "" : task.getCompletionType().toUpperCase();
		if (itemManager == null || !(type.equals("OBTAIN") || type.equals("EQUIP"))
			|| task.getRequiredItems() == null || task.getRequiredItems().isEmpty())
		{
			return null;
		}
		RequiredItem first = task.getRequiredItems().get(0);
		List<Integer> ids = first == null ? null : first.getItemIds();
		return ids == null || ids.isEmpty() ? null : itemManager.getImage(ids.get(0));
	}

	private void drawRow(Graphics2D graphics, NuzlockeTask task, Rectangle row, List<QuestRequirements.Line> detail,
		Set<String> saved, Rectangle list, int mx, int my, Font regular, Font small)
	{
		boolean inList = list.contains(mx, my);
		boolean hoverRow = inList && row.contains(mx, my);
		String taskId = task.getTaskId();
		if (hoverRow)
		{
			listRowId = taskId;
		}
		boolean isTracked = lists.isTracked(task);
		Color tier = TaskCardTier.fromTask(task).getAccent();
		if (isTracked)
		{
			// The task in the task box: a warm glow and an orange outline.
			graphics.setColor(TRACKED_FILL);
			graphics.fillRect(row.x, row.y, row.width, row.height);
			graphics.setColor(TITLE);
			graphics.drawRect(row.x, row.y, row.width - 1, row.height - 1);
		}
		// Its tier down the left edge.
		graphics.setColor(tier);
		graphics.fillRect(row.x + 2, row.y + 6, 3, row.height - 12);

		// Expand arrow (a quest's opens its pane), and, at the right, star and archive book;
		// added first so they win the click over the row.
		int iconY = row.y + (ROW - 18) / 2;
		Rectangle arrowArea = new Rectangle(row.x + 6, iconY, 14, 18);
		// Something to show under it: requirements, a quest's pane, or (with Task details on) where its item comes from.
		boolean expandable = !lists.requirementsFor(task).isEmpty() || QuestRequirements.isQuestTask(task)
			|| config.showTaskDetails() && TaskPaneOverlay.isItemTask(task);
		int textX = row.x + 50;
		List<String> names = rowName(graphics.getFontMetrics(regular), task, row.width - 160);
		String name = String.join("\n", names);
		// Everything under the name moves down a line when the name takes two.
		int lift = (names.size() - 1) * NAME_LINE;
		String chunk = lists.chunkNames.getOrDefault(taskId, "");
		String here = lists.currentChunkName();
		boolean inHere = !chunk.isEmpty() && chunk.equals(here);
		String info = NuzlockeTask.displayCategory(task.getCategory()) + (chunk.isEmpty() ? "" : "  ·  " + chunk);
		int chipX = drawRowText(graphics, row, textX, name, Color.WHITE,
			task.getBasePoints() + (task.getBasePoints() == 1 ? " pt" : " pts"), tier, info, inHere ? HERE : SUBTEXT, regular, small);

		// A fixed spot from the right edge, so they line up down the list whatever the points say.
		Rectangle book = new Rectangle(row.x + row.width - 70, iconY - 3, 14, 18);
		Rectangle star = new Rectangle(book.x - 20, iconY - 3, 18, 18);
		if (row.intersects(list))
		{
			rowSpots.put(taskId, new Rectangle[]{row.intersection(list), star.intersection(list), book.intersection(list)});
			if (firstRowId == null)
			{
				firstRowId = taskId;
			}
		}
		if (inList)
		{
			if (expandable)
			{
				pane.hits.add(new Hit(arrowArea.intersection(list), () ->
				{
					if (QuestRequirements.isQuestTask(task))
					{
						pane.openQuestPane(task);
					}
					else if (config.expandAllTasks())
					{
						if (!collapsed.remove(taskId))
						{
							collapsed.add(taskId);
						}
					}
					else if (!expanded.remove(taskId))
					{
						expanded.add(taskId);
					}
				}));
			}
			pane.hits.add(new Hit(star.intersection(list), () -> lists.toggleSaved(taskId)));
			pane.hits.add(new Hit(book.intersection(list), () -> lists.toggleArchived(task)));
		}
		// An Obtain or Equip task's item picture opens that item's OSRS Wiki page.
		Rectangle iconSlot = new Rectangle(row.x + 21, row.y + (ROW - 22) / 2 - 3, ICON_SLOT, ICON_SLOT);
		List<String[]> wikiItems = pane.wikiItems(task);
		boolean iconHover = false;
		if (inList && !wikiItems.isEmpty())
		{
			String url = wikiItems.get(0)[1];
			pane.hits.add(new Hit(iconSlot.intersection(list), () -> LinkBrowser.browse(url)));
			iconHover = iconSlot.contains(mx, my);
			if (iconHover)
			{
				pane.tip = "Click to open " + wikiItems.get(0)[0] + " on the OSRS Wiki";
			}
		}
		pane.drawDetail(graphics, detail, textX, row.y + ROW - 1 + lift, list, mx, my, small);
		if (inList)
		{
			pane.hits.add(new Hit(row.intersection(list), () ->
			{
				// A click tracks (right-click saves, see the mouse listener).
				lists.trackOrOpen(task, pane::openQuestPane);
			}));
		}
		if (expandable)
		{
			arrow(graphics, arrowArea.x + 7, arrowArea.y + 9, isExpanded(task), inList && arrowArea.contains(mx, my));
		}
		drawTaskIcon(graphics, row.x + 22, row.y + (ROW - 22) / 2, TaskTargetExtras.categorySkill(task.getCategory()), task);
		if (iconHover)
		{
			graphics.setColor(WIKI_LINK);
			graphics.drawRect(iconSlot.x, iconSlot.y, iconSlot.width - 1, iconSlot.height - 1);
		}
		// Star and book show on hover, and always once they're on.
		boolean isSaved = saved.contains(taskId);
		boolean isArchived = archive.isArchived(task);
		if (hoverRow || isSaved)
		{
			graphics.drawImage(isSaved ? STAR_ON : star.contains(mx, my) ? STAR_HOVER : STAR_OFF, star.x + 1, star.y + 1, null);
		}
		if (hoverRow || isArchived)
		{
			graphics.drawImage(isArchived ? BOOK_ON : book.contains(mx, my) ? BOOK_HOVER : BOOK_OFF, book.x + 1, book.y + 2, null);
		}

		// Line 2's right end: "TRACKING", or progress as a small bar and count. One-off
		// tasks (target 1) leave it blank, since a "0/1" bar says nothing.
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		int rightEdge = row.x + row.width - 8;
		int target = Math.max(1, task.getTargetQuantity());
		if (isTracked)
		{
			String tag = "TRACKING";
			graphics.setColor(TITLE);
			Skin.text(graphics, tag, rightEdge - sm.stringWidth(tag), row.y + 32 + lift);
		}
		else if (target > 1)
		{
			String progress = Math.min(task.getCurrentProgress(), target) + "/" + target;
			int progressWidth = sm.stringWidth(progress);
			graphics.setColor(SUBTEXT);
			Skin.text(graphics, progress, rightEdge - progressWidth, row.y + 32 + lift);
			// A fixed slot for the count, so every row's bar lines up whether it says 0/4 or 0/56.
			int slot = Math.max(progressWidth, sm.stringWidth("888/888"));
			int barX = rightEdge - slot - 62;
			Skin.bar(graphics, barX, row.y + 26 + lift, 56, 6, (float) TaskTargetExtras.progress(task), tier, 0, false);
		}
	}

	/** Split text into lines that fit the width, breaking between words. */
	/** A task's name (with any missing levels) as its row shows it, wrapped onto up to three lines. */
	// Wrapped task names, per task and width: worked out once per list rebuild, not every frame
	// for every task (each one checks levels and quest readiness and measures every word).
	private final Map<String, List<String>> rowNames = new HashMap<>();

	private List<String> rowName(FontMetrics fm, NuzlockeTask task, int width)
	{
		return rowNames.computeIfAbsent(task.getTaskId() + "|" + width + "|" + fm.getFont().getSize() + "|" + config.showMissingRequirements(),
			k -> buildRowName(fm, task, width));
	}

	/** Whether a task's details show: opened with its arrow, or all of them with "Expand all tasks" (not quests, which open their pane). */
	private boolean isExpanded(NuzlockeTask task)
	{
		String id = task.getTaskId();
		return config.expandAllTasks() && !QuestRequirements.isQuestTask(task) ? !collapsed.contains(id) : expanded.contains(id);
	}

	// Wrapped details per task and width, kept until the rows rebuild (with every task open,
	// wrapping them all each frame would be slow). A task mid-calculation is always fresh.
	private final Map<String, List<QuestRequirements.Line>> details = new HashMap<>();

	private List<QuestRequirements.Line> detail(FontMetrics fm, NuzlockeTask task, int width)
	{
		if (pane.isCalculated(task))
		{
			return pane.wrapDetail(fm, task, width);
		}
		return details.computeIfAbsent(task.getTaskId() + "|" + width + "|" + fm.getFont().getSize(), k -> pane.wrapDetail(fm, task, width));
	}

	// Marks a row-name line as what you're missing (drawn red, under the white name).
	private static final String MISSING = "\u0001";

	private List<String> buildRowName(FontMetrics fm, NuzlockeTask task, int width)
	{
		List<String> lines = new ArrayList<>(firstLines(fm, task.getName() == null ? task.getTaskId() : task.getName(), width));
		if (config.showMissingRequirements() && !lists.canDo(task))
		{
			String note = lists.levelNote(task).trim();
			if (note.startsWith("(") && note.endsWith(")"))
			{
				note = note.substring(1, note.length() - 1);
			}
			for (String line : firstLines(fm, note, width))
			{
				lines.add(MISSING + line);
			}
		}
		return lines;
	}

	/** Text wrapped to the width, onto two lines at most (the rest cut short). */
	private List<String> firstLines(FontMetrics fm, String text, int width)
	{
		List<String> lines = wrap(fm, text, width);
		return lines.size() <= 2 ? lines : Arrays.asList(lines.get(0), Skin.fit(fm, String.join(" ", lines.subList(1, lines.size())), width));
	}

	// --- TaskWindowHook: TaskPaneOverlay's input and drawing, passed on ------------------

	@Override
	public void shutDown()
	{
		open = false;
		clientThread.invokeLater(this::closeSearchInput);
	}

	/**
	 * The search box's X clears the search (and closes the chatbox search if open); a click in
	 * the box opens the game's chatbox search, like the Grand Exchange's, so typing never fights
	 * with key binds. A click anywhere else (the list, another tab, the game) finishes the
	 * search: the chatbox closes and the keys go back to the game. The search itself stays.
	 */
	@Override
	public boolean pressedFirst(MouseEvent event)
	{
		Rectangle clear = searchClear;
		if (open && event.getButton() == MouseEvent.BUTTON1 && clear != null && clear.contains(event.getPoint()))
		{
			clientThread.invokeLater(this::clearSearch);
			event.consume();
			return true;
		}
		Rectangle box = searchBox;
		if (open && event.getButton() == MouseEvent.BUTTON1 && box != null && box.contains(event.getPoint()))
		{
			clientThread.invokeLater(this::openSearch);
			event.consume();
			return true;
		}
		if (searchFocused)
		{
			clientThread.invokeLater(this::closeSearchInput);
		}
		return false;
	}

	/** A press on the title starts dragging the window. */
	@Override
	public boolean startDrag(MouseEvent event)
	{
		if (!titleHovered)
		{
			return false;
		}
		pressX = event.getX();
		pressY = event.getY();
		startX = windowX;
		startY = windowY;
		dragging = true;
		return true;
	}

	@Override
	public void drag(MouseEvent event)
	{
		if (dragging)
		{
			customX = startX + event.getX() - pressX;
			customY = startY + event.getY() - pressY;
			moved = true;
			event.consume();
		}
	}

	@Override
	public void release(MouseEvent event)
	{
		if (dragging)
		{
			dragging = false;
			event.consume();
		}
	}

	@Override
	public String listRowId()
	{
		return listRowId;
	}

	@Override
	public void mouseOverPane()
	{
		titleHovered = false;
	}

	/** The wheel over the window: the patch notes or settings page, or the task list. */
	@Override
	public void scrollBy(int amount)
	{
		if (patchNotes || settingsOpen)
		{
			patchScroll += amount;
		}
		else
		{
			scroll += amount;
		}
	}

	/** The world map tasks key: held for tasks key + click on a chunk. */
	@Override
	public void tasksKey(KeyEvent event, boolean down)
	{
		String listening = listeningKey;
		if (listening != null && capturingKey())
		{
			// Like RuneLite's own keybind boxes: the keys held make it (Ctrl + X), set on letting go.
			event.consume();
			int code = event.getKeyCode();
			if (down)
			{
				if (code == KeyEvent.VK_ESCAPE)
				{
					listeningKey = null;
					pressedKey = null;
				}
				else if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE)
				{
					listeningKey = null;
					pressedKey = null;
					configManager.setConfiguration(groupOf(listening), listening, Keybind.NOT_SET);
				}
				else
				{
					pressedKey = new Keybind(event);
				}
				return;
			}
			Keybind pressed = pressedKey;
			if (pressed != null)
			{
				listeningKey = null;
				pressedKey = null;
				configManager.setConfiguration(groupOf(listening), listening, pressed);
			}
			return;
		}
		listeningKey = null;
		if (plugin.worldMapTasksKey().matches(event))
		{
			tasksKeyHeld = down;
		}
	}

	/**
	 * Find active chunk tasks not seen yet (each tick once logged in a moment). With nothing
	 * stored (first run on an account), everything present counts as seen, so there's no
	 * flood of "new" tasks.
	 */
	@Override
	public void settledTick()
	{
		checkUnlocks();
		checkDoable();
		Set<String> active = new LinkedHashSet<>();
		for (NuzlockeTask task : lists.pool(false))
		{
			active.add(task.getTaskId());
		}
		Set<String> seen = lists.idSet(SEEN_KEY);
		if (seen == null)
		{
			markSeen(active);
			return;
		}
		for (String id : active)
		{
			if (!seen.contains(id))
			{
				newIds.add(id);
			}
		}
		newIds.retainAll(active);
	}

	@Override
	public void loggedOut()
	{
		newIds.clear();
		doableAlert.clear();
		doableBefore = null;
		knownBefore = null;
		unlockedBefore = null;
	}

	/** Chunks unlocked since the last look become the New tab's newest chunk, replacing the last. */
	private void checkUnlocks()
	{
		Set<String> unlocked = plugin.getUnlockedRegionIds();
		if (unlocked == null)
		{
			return;
		}
		if (unlockedBefore != null)
		{
			// Everything unlocked since the last look (Lumbridge and your own chunk can come together).
			Set<String> newest = new LinkedHashSet<>();
			for (String id : unlocked)
			{
				if (!unlockedBefore.contains(id))
				{
					newest.add(id);
				}
			}
			if (!newest.isEmpty())
			{
				lists.storeIds(NEWEST_CHUNK_KEY, newest);
				rowsBuiltAt = 0;
			}
		}
		unlockedBefore = new HashSet<>(unlocked);
	}

	/**
	 * Every few ticks: tasks you couldn't do before and now can (a level gained, a quest done)
	 * join the New tab's "Now doable" (the last five kept) and light up the ChunkBlazer button.
	 * The first look after logging in only notes where things stand.
	 */
	private void checkDoable()
	{
		if (doableBefore != null && ++checkTicks % 5 != 0)
		{
			return;
		}
		Set<String> archived = archive.ids();
		Set<String> known = new HashSet<>();
		Set<String> doable = new HashSet<>();
		for (NuzlockeTask task : lists.pool(true))
		{
			String id = task.getTaskId();
			if (archived.contains(id))
			{
				continue;
			}
			known.add(id);
			if (lists.canDo(task))
			{
				doable.add(id);
			}
		}
		if (doableBefore != null)
		{
			List<String> fresh = new ArrayList<>();
			for (String id : doable)
			{
				if (knownBefore.contains(id) && !doableBefore.contains(id))
				{
					fresh.add(id);
				}
			}
			if (!fresh.isEmpty())
			{
				Set<String> recent = new LinkedHashSet<>(fresh);
				Set<String> stored = lists.idSet(DOABLE_KEY);
				if (stored != null)
				{
					recent.addAll(stored);
				}
				Set<String> kept = new LinkedHashSet<>();
				for (String id : recent)
				{
					if (kept.size() < DOABLE_KEPT)
					{
						kept.add(id);
					}
				}
				lists.storeIds(DOABLE_KEY, kept);
				doableAlert.addAll(fresh);
				doableAlert.retainAll(kept);
				rowsBuiltAt = 0;
			}
		}
		doableBefore = doable;
		knownBefore = known;
	}

	/** Opening the world map closes the window (the tasks key + click opens it again). */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.WORLDMAP && open)
		{
			close();
		}
	}

	/** Called before each frame: no task-list row is under the mouse until this frame says so. */
	@Override
	public void beforeFrame()
	{
		listRowId = null;
	}

	/** While the window's closed with new tasks waiting: the glow round the Points orb. */
	@Override
	public void renderClosed(Graphics2D graphics)
	{
		if (alerts() > 0)
		{
			drawNewTaskAlert(graphics);
		}
	}

	/** Draws the open window; TaskPaneOverlay has cleared the frame's hits. */
	@Override
	public void render(Graphics2D graphics, int mx, int my)
	{
		// What the tutorial can point at is what this frame draws: nothing left over from another page.
		marks.clear();
		// Walked into another chunk: rebuild now if the list follows you.
		int region = plugin.getCurrentRegionId();
		if (region != lastRegionSeen)
		{
			lastRegionSeen = region;
			if (lists.currentChunkOnly)
			{
				lists.refresh();
			}
		}

		// Centred over the game view; in fixed mode that's always the 512 x 334 box at the top left.
		Rectangle view = client.isResized() ? new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
			client.getViewportWidth(), client.getViewportHeight()) : new Rectangle(4, 4, 512, 334);
		int width = Math.min(MAX_WIDTH, view.width - 20);
		int height = Math.min(MAX_HEIGHT, view.height - 20);
		int x = view.x + (view.width - width) / 2;
		int y = view.y + (view.height - height) / 2;
		if (moved)
		{
			x = Math.max(0, Math.min(customX, client.getCanvasWidth() - width - 1));
			y = Math.max(0, Math.min(customY, client.getCanvasHeight() - height - 1));
		}
		windowX = x;
		windowY = y;
		Rectangle window = new Rectangle(x, y, width, height);

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Font regular = Skin.regular();
		Font small = Skin.small();

		// The window: a soft shadow, a rounded body darkening downwards, a lighter header
		// (Classic; Old School's stone frame comes from Skin).
		Skin.stoneFrame(graphics, window, HEADER);

		drawHeader(graphics, window, mx, my);

		Rectangle side = drawSidebar(graphics, window, mx, my);

		// The content: the list, then a hint line.
		int cx = side.x + side.width + PAD;
		int cw = x + width - PAD - cx;

		// Old School: column headings over the list, like the Leagues task table.
		// The headings carry the search box, Filters and Sort too, so the strip is a little taller.
		int columns = patchNotes || settingsOpen ? 0 : 24;
		Rectangle filtersTab = null;
		Rectangle sortTab = null;
		if (columns > 0)
		{
			Rectangle strip = new Rectangle(cx - 2, y + HEADER + 6, cw + 6, columns);
			graphics.setColor(DARK);
			graphics.fillRect(strip.x, strip.y, strip.width, strip.height);
			Skin.bevel(graphics, strip, true);
			graphics.setFont(small);
			FontMetrics stripFm = graphics.getFontMetrics();
			int textY = strip.y + strip.height / 2 + 5;
			graphics.setColor(TITLE);
			Skin.text(graphics, "Task", strip.x + 52, textY);
			int pointsX = strip.x + strip.width - 12 - stripFm.stringWidth("Points");
			Skin.text(graphics, "Points", pointsX, textY);
			int tabY = strip.y + (strip.height - 18) / 2;

			// Sort, just left of Points: what the list is sorted by, opening the choices. Short,
			// so "Level needed" shows as "Level".
			String sortLabel = "Sort: " + lists.sortField.label.replace(" needed", "") + (lists.ascending ? " \u25B2" : " \u25BC");
			int sortW = stripFm.stringWidth(sortLabel) + 16;
			sortTab = new Rectangle(pointsX - 12 - sortW, tabY, sortW, 18);
			pane.hits.add(new Hit(sortTab, () ->
			{
				sortOpen = !sortOpen;
				filtersOpen = false;
			}));
			boolean sortHover = sortTab.contains(mx, my);
			Skin.button(graphics, sortTab, sortHover, sortOpen);
			graphics.setColor(sortOpen || sortHover ? Color.WHITE : SUBTEXT);
			Skin.text(graphics, sortLabel, sortTab.x + 8, sortTab.y + 13);

			// Filters beside it, showing how many are set.
			int setCount = lists.filterCount();
			String filtersLabel = setCount > 0 ? "Filters (" + setCount + ")" : "Filters";
			int filtersW = stripFm.stringWidth(filtersLabel) + 16;
			filtersTab = new Rectangle(sortTab.x - 6 - filtersW, tabY, filtersW, 18);
			pane.hits.add(new Hit(filtersTab, () ->
			{
				filtersOpen = !filtersOpen;
				sortOpen = false;
			}));
			boolean filtersHover = filtersTab.contains(mx, my);
			Skin.button(graphics, filtersTab, filtersHover, filtersOpen);
			graphics.setColor(filtersOpen || filtersHover ? Color.WHITE : setCount > 0 ? new Color(120, 220, 120) : SUBTEXT);
			Skin.text(graphics, filtersLabel, filtersTab.x + 8, filtersTab.y + 13);

			// The search box fills the space between Task and Filters.
			int searchX = strip.x + 52 + stripFm.stringWidth("Task") + 12;
			drawSearchBox(graphics, new Rectangle(searchX, tabY, Math.max(40, filtersTab.x - 8 - searchX), 18), mx, my);
			marks.put("filters", filtersTab);
		}
		else
		{
			searchBox = null;
			searchClear = null;
			filtersOpen = false;
			sortOpen = false;
		}
		Rectangle list = new Rectangle(cx - 2, y + HEADER + 8 + columns, cw + 6, height - HEADER - 8 - FOOTER - 2 - columns);
		listBounds = list;
		rowSpots.clear();
		firstRowId = null;
		if (patchNotes || settingsOpen)
		{
			String footer = "Click Patch notes again, or a list, to go back to your tasks";
			if (settingsOpen)
			{
				String hovered = drawSettingsPage(graphics, list, mx, my);
				footer = hovered != null ? hovered
					: "Hover a setting to see what it does. Colours and keys are in RuneLite's plugin settings.";
			}
			else
			{
				drawPatchNotes(graphics, list, regular, small);
			}
			graphics.setFont(small);
			graphics.setColor(settingsOpen && !footer.startsWith("Hover") ? Color.WHITE : LABEL);
			Skin.text(graphics, Skin.fit(graphics.getFontMetrics(), footer, x + width - PAD - cx), cx, y + height - 7);
			finishFrame(graphics, window, mx, my);
			return;
		}
		// The Filters or Sort dropdown sits over the list: rows under it don't take the mouse.
		int popTop = y + HEADER + 6 + columns + 2;
		Rectangle pop = filtersOpen && filtersTab != null ? filterPopBounds(window, filtersTab.x, popTop)
			: sortOpen && sortTab != null ? sortPopBounds(window, sortTab.x, popTop) : null;
		boolean overPop = pop != null && pop.contains(mx, my);
		int shown = drawList(graphics, list, overPop ? -1 : mx, overPop ? -1 : my, regular, small);

		drawFooter(graphics, window, cx, shown, mx, my);

		if (pop != null && filtersOpen)
		{
			drawFilterPop(graphics, window, pop, mx, my);
		}
		else if (pop != null)
		{
			drawSortPop(graphics, window, pop, mx, my);
		}
		filterPop = pop;

		finishFrame(graphics, window, mx, my);
		return;
	}

	/** Show every task again, here and in the side panel: no search, filters or chunk limit. */
	private void showAll()
	{
		lists.search = "";
		lists.clearFilters();
		lists.currentChunkOnly = false;
		lists.pinnedChunk = null;
		lists.refresh();
		plugin.sidePanelShowAll();
	}
}
