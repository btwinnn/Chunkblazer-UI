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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;

/**
 * The task-roll reveal: freshly rolled tasks arrive as face-down cards in the middle of
 * the viewport and stay there until the player flips them.
 *
 * <p><b>Why this exists as a gate, not just decoration.</b> A rolled task is not added to
 * the active list — and is not registered with any tracking module — until its card is
 * turned over. That is the whole point of the feature: the roll should feel like opening
 * a pack rather than silently gaining five list entries. The pending set lives in config
 * ({@code unrevealedTasks}), so cards left unflipped are still waiting after a relog.
 *
 * <p><b>What flipping does NOT do.</b> It does not decide the task. The roll itself is
 * already committed to {@code regionRolledTasks} the moment it happens, so refusing to
 * flip a card you don't like cannot reroll it — the card is a reveal, never a gamble.
 *
 * <p><b>The show.</b> A fresh deal waits for ChunkBlazer GPU's unlock sweep, then (by
 * default) the cards are dealt one at a time from random spots along the top of the
 * screen into a scruffy pile, each settling at an angle set by the way it came in.
 * Clicking the top card lifts it out, straightens it and turns it over in perspective.
 * Clicking a face-up card, or sending the pile to the task list, tosses the cards into
 * the Tasks orb like paper into a bin, and a little flame flares up from the orb as
 * each lands. The cards are drawn in the game's style by {@link CardArt}.
 */
@Slf4j
@Singleton
public class TaskCardOverlay extends Overlay implements TaskCardShow
{
	/**
	 * Drag and flip, milliseconds, Easy to Master: the card is dragged off the pile, flipped
	 * over on its leading edge like turning a card over on a table, then settles in the middle.
	 * Rarer cards turn slower, so the reveal builds.
	 */
	private static final long[] FLIP_DURATION = {640, 780, 940, 1140, 1400};
	/** Shares of the flip: dragging out, then hinging over, then settling. */
	private static final double FLIP_DRAG = 0.24;
	private static final double FLIP_TURN_END = 0.86;
	/** How far the lifting side grows towards you, for the perspective. */
	private static final double FLIP_PERSPECTIVE = 0.18;
	/**
	 * Where the card flips over, as a share of its width right of centre (0 = turns
	 * in place, 0.5 = over its edge). Dragged left by this much first, it lands the
	 * same distance right of the middle, then settles.
	 */
	private static final double FLIP_AXIS = 0.2;
	/** A face-up card is raised towards you: this much bigger than in the pile. */
	private static final double FACE_UP_SCALE = 1.22;
	/** Vertical strips a turning card is drawn in (to fake the perspective). */
	private static final int FLIP_SLICES = 28;

	/**
	 * Dealing, one card at a time: each card's flight from the top, and the gap
	 * between one card setting off and the next (its flight plus a short beat, so a
	 * card has landed before the next one leaves).
	 */
	private static final long DEAL_FLIGHT = 467;
	private static final long DEAL_GAP = 507;
	/** At most this many cards are drawn in the pile; the rest sit hidden beneath. */
	private static final int PILE_MAX = 10;

	/** Tossed into the Tasks orb, like a ball of paper into a bin. */
	private static final long FLY_DURATION = 720;
	/** "Send all": cards are tossed one after another, this far apart. */
	private static final long BATCH_GAP = 140;
	/** The fire each card lights on the orb as it lands. */
	private static final long IMPACT_DURATION = 900;
	/** Heat a card adds to the orb's fire: batch cards stoke it harder. */
	private static final double HEAT_SINGLE = 1.0;
	private static final double HEAT_BATCH = 1.4;
	/** Radius of the orb's round icon, px. */
	private static final double ORB_RADIUS = 13;

	/**
	 * Preferred width of the single centred card. One card at a time (pack-opening),
	 * so it can be larger than the old grid allowed; clamped to the viewport below.
	 */
	private static final int CARD_WIDTH = 195;
	/** How far each stacked "deck" card behind the current one is offset, px. */
	private static final int DECK_OFFSET = 7;
	/** How many face-down cards to draw in the neat deck behind the current card. */
	private static final int DECK_MAX_VISIBLE = 5;
	/** Height over width, as the original card art (356 x 200). */
	private static final double ASPECT = 200.0 / 356.0;
	private static final Color PROMPT = new Color(235, 235, 235, 225);

	/** Share of a fall spent falling; the rest is the bounce on landing. */
	private static final double DROP_FALL = 0.72;
	/** Cascade: how long each card takes to fall, and the gap between cards. */
	private static final long CASCADE_FALL = 280;
	private static final long CASCADE_STAGGER = 73;

	/** A deal arriving within this long of a chunk unlock may belong to a GPU unlock sweep. */
	private static final long UNLOCK_LINK_MS = 3000;
	/** After an unlock, how long to wait for ChunkBlazer GPU to say its sweep started. */
	private static final long SWEEP_START_GRACE_MS = 500;
	/** Never hold cards longer than this, whatever the GPU plugin says. */
	private static final long MAX_SWEEP_WAIT_MS = 15000;
	// Cards start dealing this long before a ChunkBlazer GPU sweep ends, when it says how long it runs.
	private static final long DEAL_BEFORE_SWEEP_ENDS_MS = 3000;
	/** A beat after the sweep ends before the cards fall. */
	private static final long SWEEP_GRACE_MS = 150;

	private final Client client;
	private final ChunkBlazerUiConfig config;
	private final ChunkBlazerCore plugin;
	private final CardSounds sounds;
	// The cascade the drop sounds are for (its dealStart), and how many cards have sounded.
	private long cascadeFor = -1;
	private int cascadeSounded;
	private final Random random = new Random();

	/** Cards currently on screen: the pile (index 0 on top) plus any flying to the orb. */
	private final List<Card> cards = new ArrayList<>();

	/** Raw pending-set text as of the last rebuild — see syncCards(). */
	private String lastPendingRaw = null;

	/**
	 * Bounds of the "Send 5" / "Send all" batch buttons drawn last frame, or null
	 * when they aren't shown. Read in onClick (mouse thread), so render only swaps in a
	 * frame's finished bounds at its end: never null mid-frame while the buttons are up,
	 * which used to let a click fall through to the game.
	 */
	private volatile Rectangle revealFiveBounds;
	private volatile Rectangle revealAllBounds;
	// This frame's buttons, as drawPrompt places them (client thread only).
	private Rectangle nextFiveBounds;
	private Rectangle nextAllBounds;

	/**
	 * A batch-reveal asked for by a button click. 0 = none, -1 = reveal all, N =
	 * reveal N. Written on the mouse thread by onClick, consumed on the client thread
	 * in render() so the actual reveal (config writes, task activation) stays on the
	 * same thread the single-card flip already uses.
	 */
	private volatile int batchRevealRequest = 0;
	/** A click on a face-up card, handed to the client thread like the batch request. */
	private volatile boolean sendCurrentRequest;

	/** Wall-clock ms the current deal starts (see startDeal). */
	private volatile long dealStart;

	/**
	 * ChunkBlazer GPU unlock sweeps in progress, from its "unlockSweepStarted" /
	 * "unlockSweepFinished" PluginMessages (ChunkBlazer passes them on).
	 * While one is running, a fresh deal is held and dealt once they've all finished.
	 */
	private int sweepsRunning;
	// When the running sweeps end, if ChunkBlazer GPU said how long they take; 0 if not.
	private long sweepEndsAt;
	/** A deal is being held for a sweep; released by the sweep finishing or holdUntil. */
	private volatile boolean holding;
	private volatile long holdUntil;

	/** Set (from any thread) to deal a few demo cards; consumed in render. */
	private volatile boolean demoRequested;
	private volatile int demoCount = DEMO_CARDS;
	// Sample names for demo cards, so they show off the card design like real tasks.
	private static final String[] DEMO_NAMES = {"Plant some Potatoes", "Obtain a Blue Wizard Hat",
		"Defeat the Kalphite Queen", "Equip an Ancient Wyvern Shield", "Obtain the Olm Pet"};
	private static final int DEMO_CARDS = 3;

	/** Each card that's landed in the orb recently, and the heat it added. */
	private final List<Impact> impacts = new ArrayList<>();

	private static final class Impact
	{
		final long at;
		final double heat;

		Impact(long at, double heat)
		{
			this.at = at;
			this.heat = heat;
		}
	}

	/** Card backs per tier at the current card size, and the size they were drawn at. */
	private final Map<TaskCardTier, BufferedImage[]> backArt = new EnumMap<>(TaskCardTier.class);
	private int artWidth;
	private int artHeight;
	// Card art is painted this many times finer than it's shown (more on a scaled-up screen),
	// then drawn down to size, so small cards and their words stay crisp.
	private int artDetail = 2;
	// The face design the cached art was drawn in (Settings > Theme).
	private Theme artDesign;

	/**
	 * One card and where it is in its life.
	 *
	 * <p>Dealt → in the pile, face down → (click) → lifted and turned → FACE_UP →
	 * (click) → tossed into the Tasks orb → gone. A card sits FACE_UP for as long as
	 * the player wants: there is nothing to read on a card that has already gone, and
	 * a fixed hold either rushes a slow reader or annoys a fast one.
	 *
	 * <p>The task is activated at the END OF THE TURN, not when it flies off — the
	 * reveal is what earns it. Leaving a card up therefore parks nothing.
	 */
	private static class Card
	{
		final String taskId;
		final String taskName;
		final TaskCardTier tier;
		/** A preview card (see requestDemoDeal): flips and flies, but is no real task. */
		final boolean demo;
		/** How long its turn takes: rarer tiers turn over slower, for the suspense. */
		final long flipDuration;
		Rectangle bounds = new Rectangle();
		/** Wall-clock ms the turn began, or -1 while still face down. */
		long flipStart = -1;
		/** Wall-clock ms it set off for the orb (may be just ahead, for a batch), or -1. */
		long flyStart = -1;
		/** Set once the task has been handed to the plugin, so it happens exactly once. */
		boolean activated;
		/** Its deal sound has played. */
		boolean dealSounded;
		/** Where its show is, face up (the part behind is drawn under the pile, next frame). */
		Rectangle flareAt;

		// Where it comes from and how it lies in the scruffy pile, in card widths and
		// radians, fixed when the card is made: it overshoots the middle a little in
		// the direction it was travelling, tilted the way it came in.
		double fromFx;
		double restDx;
		double restDy;
		double restAngle;
		double spin;
		/** Order within its deal (0 first), or -1 if dealt on its own at dealAt. */
		int dealOrder = -1;
		long dealAt = -1;

		// Where it was when it set off for the orb.
		double flyFromX;
		double flyFromY;
		double flyFromAngle;
		boolean flyFaceUp;
		/** Its size when it set off (a face-up card is raised, so bigger). */
		double flyFromScale = 1;
		/** Heat it adds to the orb's fire when it lands. */
		double flyHeat = HEAT_SINGLE;

		/** Face art and a darkened copy, drawn at the current card size. */
		BufferedImage[] frontArt;

		Card(String taskId, String taskName, TaskCardTier tier, boolean demo)
		{
			this.taskId = taskId;
			this.taskName = taskName;
			this.tier = tier;
			this.flipDuration = FLIP_DURATION[Math.min(tier.ordinal(), FLIP_DURATION.length - 1)];
			this.demo = demo;
		}

		boolean isFaceDown()
		{
			return flipStart < 0;
		}

		boolean isTurning()
		{
			return flipStart > 0 && sinceFlip() < flipDuration;
		}

		/** Turned over and readable — the state a card rests in. */
		boolean isFaceUp()
		{
			return flipStart > 0 && !isTurning() && flyStart < 0;
		}

		boolean isFlying()
		{
			return flyStart > 0;
		}

		/** Anything past face-down in the pile survives a pending-set rebuild. */
		boolean isStarted()
		{
			return flipStart > 0 || flyStart > 0;
		}

		long sinceFlip()
		{
			return System.currentTimeMillis() - flipStart;
		}
	}

	@Inject
	private TaskCardOverlay(Client client, ChunkBlazerUiConfig config, ChunkBlazerCore plugin, CardSounds sounds)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		this.sounds = sounds;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	/**
	 * Take a click at viewport coordinates.
	 *
	 * @return true if it landed on the cards or their buttons, which consumes the click
	 * so it doesn't also walk the player somewhere.
	 */
	@Override
	public boolean onClick(int x, int y)
	{
		if (!isActive() || !hasLanded())
		{
			return false;
		}
		// Batch controls first — the escape hatch from clicking through a big pack
		// (e.g. a boss chunk's ~30 cards). Handled on the client thread in render().
		Rectangle all = revealAllBounds;
		Rectangle five = revealFiveBounds;
		if (all != null && all.contains(x, y))
		{
			batchRevealRequest = -1;
			return true;
		}
		if (five != null && five.contains(x, y))
		{
			batchRevealRequest = 5;
			return true;
		}
		// Pack-opening: only the top card is interactive. Click it to flip; click the
		// face-up card to send it to the task list (into the Tasks orb).
		Card current = topCard();
		if (current == null || !current.bounds.contains(x, y))
		{
			return false;
		}
		if (current.isFaceDown())
		{
			current.flipStart = System.currentTimeMillis();
			sounds.flip(current.tier.ordinal());
			return true;
		}
		if (current.isFaceUp())
		{
			sendCurrentRequest = true;
			return true;
		}
		// Mid-turn: swallow the click so an impatient double-click doesn't fall
		// through to the game world underneath.
		return true;
	}

	/** True while there is anything to draw or click. */
	@Override
	public boolean isActive()
	{
		return plugin.isTaskCardsOn() && !cards.isEmpty();
	}

	public Dimension render(Graphics2D graphics)
	{
		nextFiveBounds = null;
		nextAllBounds = null;
		try
		{
			return renderCards(graphics);
		}
		finally
		{
			revealFiveBounds = nextFiveBounds;
			revealAllBounds = nextAllBounds;
		}
	}

	private Dimension renderCards(Graphics2D graphics)
	{
		if (!plugin.isTaskCardsOn())
		{
			// Disabled mid-session: don't strand pending tasks behind an overlay that
			// will never draw again. Reveal them and let the old flow take over.
			revealAllPending();
			return null;
		}

		Rectangle viewport = viewportBounds();
		if (viewport == null)
		{
			return null;
		}
		Rectangle rest = restBounds(viewport);
		// Finer still where the screen itself is scaled up (stretched mode, display scaling).
		double screen = Math.max(1, graphics.getTransform().getScaleX());
		prepareArt(rest.width, rest.height, (int) Math.min(4, Math.max(2, Math.ceil(2 * screen))));

		long now = System.currentTimeMillis();
		processBatchReveal(rest, now);
		processSendCurrent(rest, now);
		dealDemoIfRequested();
		syncCards();
		retireFinishedCards(now);

		if (cards.isEmpty() && impacts.isEmpty())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		if (holding)
		{
			if (now < holdUntil)
			{
				return null; // waiting for the chunk's unlock sweep to finish
			}
			holding = false; // no word from the GPU plugin: deal anyway
			dealStart = now;
		}

		List<Card> pile = pile();
		Card current = pile.isEmpty() ? null : pile.get(0);
		boolean entering = false;
		if (current != null && now >= dealStart && current.flareAt != null)
		{
			// The show's part behind the card goes under the whole pile, so it shows round the cards.
			flare(graphics, current, current.flareAt, true);
		}
		if (current != null && now >= dealStart)
		{
			CardEntrance style = config.cardEntrance();
			entering = style == CardEntrance.DEALT
				? drawDealtPile(graphics, pile, rest, viewport, now)
				: drawNeatDeck(graphics, pile, rest, viewport, now, style);
		}

		drawFlyingCards(graphics, viewport, rest, now);
		drawImpacts(graphics, viewport, now);

		if (current != null && now >= dealStart && !entering)
		{
			drawPrompt(graphics, viewport, rest, current);
		}
		return null;
	}

	// ---------------------------------------------------------------------
	// Pile state
	// ---------------------------------------------------------------------

	/** Cards still in the pile (not flying), top first. */
	private List<Card> pile()
	{
		List<Card> pile = new ArrayList<>();
		for (Card card : cards)
		{
			if (!card.isFlying())
			{
				pile.add(card);
			}
		}
		return pile;
	}

	/** The card on top of the pile, or null. Safe to call from the mouse thread. */
	private Card topCard()
	{
		// Copied: this runs on the mouse thread while render may be changing the
		// list; a copy taken mid-change can hold a null slot, so skip those.
		for (Card card : new ArrayList<>(cards))
		{
			if (card != null && !card.isFlying())
			{
				return card;
			}
		}
		return null;
	}

	/**
	 * Rebuild the card list from the plugin's pending set, preserving the state of
	 * cards already on screen, so a roll that happens while the overlay is up simply
	 * appears (dealt onto the pile).
	 *
	 * <p>Guarded by a string compare against the last seen value. render() runs every
	 * frame, and parsing the pending list per frame would mean a config read, a split
	 * and a task lookup per id, forever — this plugin has already hard-locked a client
	 * once by doing per-repaint work that looked individually cheap (see
	 * ChunkBlazer's tasksById). The pending set only changes on a roll or a flip.
	 */
	private void syncCards()
	{
		String raw = plugin.getUnrevealedTasksRaw();
		if (raw == null)
		{
			raw = "";
		}
		if (raw.equals(lastPendingRaw) && !cards.isEmpty())
		{
			return;
		}
		lastPendingRaw = raw;

		List<String> pending = plugin.getUnrevealedTaskIds();
		boolean wasEmpty = pile().isEmpty();

		// A card the player has started drops out of the pending set as soon as it
		// finishes turning, so match on "not started" — otherwise every face-up card
		// would be swept away by the very rebuild its own reveal triggered.
		cards.removeIf(card -> !card.demo && !pending.contains(card.taskId) && !card.isStarted());

		int added = 0;
		long now = System.currentTimeMillis();
		for (String taskId : pending)
		{
			if (hasCard(taskId))
			{
				continue;
			}
			NuzlockeTask task = plugin.getTaskById(taskId);
			if (task == null)
			{
				// A pending id with no task behind it can never be revealed by clicking,
				// so it would wedge the queue forever. Drop it rather than draw nothing.
				log.warn("Unknown unrevealed task '{}' dropped", taskId);
				plugin.revealTaskCard(taskId);
				continue;
			}
			Card card = new Card(taskId, task.getName(), TaskCardTier.fromTask(task), false);
			assignPose(card);
			if (!wasEmpty)
			{
				// Joining a pile already on screen: deal it in on its own.
				card.dealAt = now + added * DEAL_GAP;
			}
			cards.add(card);
			added++;
		}

		if (wasEmpty && !pile().isEmpty())
		{
			startDeal();
		}
	}

	private boolean hasCard(String taskId)
	{
		for (Card card : cards)
		{
			if (card.taskId.equals(taskId))
			{
				return true;
			}
		}
		return false;
	}

	/** Random start spot along the top and resting pose in the pile, set by its direction. */
	private void assignPose(Card card)
	{
		card.fromFx = 0.08 + 0.84 * random.nextDouble();
		// Direction of travel, start (top, fromFx) -> middle, roughly in card widths.
		double dirX = 0.5 - card.fromFx;
		double dirY = 0.9;
		double len = Math.hypot(dirX, dirY);
		dirX /= len;
		dirY /= len;
		// Slides on a little past the middle the way it was going, plus some mess.
		double slide = 0.03 + 0.06 * random.nextDouble();
		card.restDx = dirX * slide + (random.nextDouble() - 0.5) * 0.12;
		card.restDy = dirY * slide * 0.5 + (random.nextDouble() - 0.5) * 0.09;
		// Spun on the way in, so it lands at whatever angle the spin leaves it:
		// mostly random, a little biased by the way it came.
		card.restAngle = dirX * 0.2 + (random.nextDouble() - 0.5) * 1.1;
		card.spin = (random.nextBoolean() ? 1 : -1) * Math.PI * (0.35 + 0.5 * random.nextDouble());
	}

	/**
	 * Activate tasks whose card has finished turning, and drop cards that have
	 * finished flying into the orb. Activation is deliberately at the end of the
	 * TURN — see the Card comment: a card left up must not hold a task hostage.
	 */
	private void retireFinishedCards(long now)
	{
		Iterator<Card> it = cards.iterator();
		while (it.hasNext())
		{
			Card card = it.next();

			if (!card.demo && !card.activated && card.flipStart > 0 && card.sinceFlip() >= card.flipDuration)
			{
				card.activated = true;
				plugin.revealTaskCard(card.taskId);
			}

			if (card.isFlying() && now - card.flyStart >= FLY_DURATION)
			{
				impacts.add(new Impact(card.flyStart + FLY_DURATION, card.flyHeat));
				it.remove();
			}
		}
	}

	/** A click on the face-up top card: send it into the orb. Client thread. */
	private void processSendCurrent(Rectangle rest, long now)
	{
		if (!sendCurrentRequest)
		{
			return;
		}
		sendCurrentRequest = false;
		Card current = topCard();
		if (current != null && current.isFaceUp())
		{
			launch(current, rest.getCenterX(), rest.getCenterY(), 0, true, now);
			current.flyFromScale = FACE_UP_SCALE;
		}
	}

	/**
	 * Honour a batch-reveal button click: reveal N cards (or all) at once, and send
	 * them into the orb together. Runs on the client thread (called from render) so
	 * plugin.revealTaskCard, which activates the task and writes config, stays on the
	 * same thread as the single-card flip path in retireFinishedCards.
	 */
	private void processBatchReveal(Rectangle rest, long now)
	{
		int req = batchRevealRequest;
		if (req == 0)
		{
			return;
		}
		batchRevealRequest = 0;

		List<Card> pile = pile();
		int n = req < 0 ? pile.size() : Math.min(req, pile.size());
		boolean dealt = config.cardEntrance() == CardEntrance.DEALT;
		for (int i = 0; i < n; i++)
		{
			Card card = pile.get(i);
			if (!card.demo && !card.activated)
			{
				card.activated = true;
				plugin.revealTaskCard(card.taskId);
			}
			// Off from wherever it lies in the pile; cards hidden under the pile
			// go too, but there's nothing to see of them.
			double x = rest.getCenterX() + (dealt ? card.restDx * rest.width : -Math.min(i, DECK_MAX_VISIBLE) * DECK_OFFSET);
			double y = rest.getCenterY() + (dealt ? card.restDy * rest.width : -Math.min(i, DECK_MAX_VISIBLE) * DECK_OFFSET);
			launch(card, x, y, dealt ? card.restAngle : 0, !card.isFaceDown(), now + i * BATCH_GAP);
			card.flyHeat = HEAT_BATCH;
		}
		lastPendingRaw = null;
	}

	/** Toss a card into the Tasks orb from (x, y) at the given angle. */
	private void launch(Card card, double x, double y, double angle, boolean faceUp, long at)
	{
		card.flyStart = Math.max(1, at);
		card.flyFromX = x;
		card.flyFromY = y;
		card.flyFromAngle = angle;
		card.flyFaceUp = faceUp;
	}

	private void revealAllPending()
	{
		if (!cards.isEmpty())
		{
			cards.clear();
		}
		for (String taskId : plugin.getUnrevealedTaskIds())
		{
			plugin.revealTaskCard(taskId);
		}
	}

	// ---------------------------------------------------------------------
	// Deals, demo cards and waiting for the GPU sweep
	// ---------------------------------------------------------------------

	/**
	 * Deal a few demo cards, dealt once ChunkBlazer GPU's unlock sweep has played, so
	 * the card animations can be tried with the GPU plugin's "Play Unlock On Test
	 * Chunk" (which unlocks nothing, so rolls no real tasks). Thread-safe.
	 */
	public void requestDemoDeal()
	{
		requestDemoDeal(DEMO_CARDS);
	}

	/** The same with {@code count} cards (Settings > Theme's Test button). Thread-safe. */
	public void requestDemoDeal(int count)
	{
		demoCount = Math.max(1, count);
		demoRequested = true;
	}

	private void dealDemoIfRequested()
	{
		if (!demoRequested)
		{
			return;
		}
		demoRequested = false;
		if (!pile().isEmpty())
		{
			return; // real cards are up: don't bury them under demos
		}
		TaskCardTier[] tiers = TaskCardTier.values();
		int count = demoCount;
		for (int i = 0; i < count; i++)
		{
			// Rising tiers, each with a sample name of its tier.
			int tier = count >= tiers.length ? i % tiers.length : Math.round(i * (tiers.length - 1f) / Math.max(1, count - 1));
			Card card = new Card("demo-" + i, DEMO_NAMES[tier], tiers[tier], true);
			assignPose(card);
			cards.add(card);
		}
		startDeal();
	}

	/**
	 * A new deal: number the cards in the order they're dealt (bottom of the visible
	 * pile first, top last), then hold it while a ChunkBlazer GPU unlock sweep is
	 * running (or briefly, right after an unlock, in case the sweep is about to
	 * start), and otherwise deal it now.
	 */
	private void startDeal()
	{
		List<Card> pile = pile();
		int visible = Math.min(pile.size(), PILE_MAX);
		for (int i = 0; i < pile.size(); i++)
		{
			Card card = pile.get(i);
			card.dealAt = -1;
			// Hidden cards under the pile "arrive" with the first one.
			card.dealOrder = i < visible ? visible - 1 - i : 0;
		}

		long now = System.currentTimeMillis();
		long sinceUnlock = now - plugin.getLastUnlockChangeMs();
		if (sweepsRunning > 0)
		{
			holding = true;
			holdUntil = sweepEndsAt > 0 ? Math.max(now, sweepEndsAt - DEAL_BEFORE_SWEEP_ENDS_MS) : now + MAX_SWEEP_WAIT_MS;
		}
		else if (sinceUnlock >= 0 && sinceUnlock < UNLOCK_LINK_MS)
		{
			holding = true;
			holdUntil = now + SWEEP_START_GRACE_MS;
		}
		else
		{
			holding = false;
			dealStart = now;
		}
	}

	/** ChunkBlazer GPU began an unlock sweep. Client thread. */
	@Override
	public void onSweepStarted(boolean test, long durationMs)
	{
		sweepsRunning++;
		long now = System.currentTimeMillis();
		sweepEndsAt = durationMs > 0 ? Math.max(sweepEndsAt, now + durationMs) : 0;
		if (test)
		{
			requestDemoDeal();
		}
		if (holding)
		{
			holdUntil = sweepEndsAt > 0 ? Math.max(now, sweepEndsAt - DEAL_BEFORE_SWEEP_ENDS_MS) : now + MAX_SWEEP_WAIT_MS;
		}
	}

	/** ChunkBlazer GPU finished an unlock sweep. Client thread. */
	@Override
	public void onSweepFinished()
	{
		sweepsRunning = Math.max(0, sweepsRunning - 1);
		if (sweepsRunning == 0)
		{
			sweepEndsAt = 0;
		}
		if (sweepsRunning == 0 && holding)
		{
			holding = false;
			dealStart = System.currentTimeMillis() + SWEEP_GRACE_MS;
		}
	}

	/** When a card is (or was) dealt. */
	private long dealTime(Card card)
	{
		return card.dealAt >= 0 ? card.dealAt : dealStart + card.dealOrder * DEAL_GAP;
	}

	/** True once the deal has finished arriving and the cards can be clicked. */
	private boolean hasLanded()
	{
		if (holding)
		{
			return false;
		}
		long now = System.currentTimeMillis();
		if (config.cardEntrance() == CardEntrance.DEALT)
		{
			for (Card card : new ArrayList<>(cards))
			{
				if (card != null && !card.isFlying() && now < dealTime(card) + DEAL_FLIGHT)
				{
					return false;
				}
			}
			return true;
		}
		int deckVisible = Math.min(Math.max(0, cards.size() - 1), DECK_MAX_VISIBLE);
		return now >= dealStart + entranceDuration(config.cardEntrance(), deckVisible);
	}

	// ---------------------------------------------------------------------
	// Drawing: the dealt (scruffy) pile
	// ---------------------------------------------------------------------

	/** Draw the scruffy pile, cards arriving as they're dealt. True while still dealing. */
	private boolean drawDealtPile(Graphics2D graphics, List<Card> pile, Rectangle rest, Rectangle viewport, long now)
	{
		boolean entering = false;
		int visible = Math.min(pile.size(), PILE_MAX);
		Card top = pile.get(0);
		for (int i = visible - 1; i >= 0; i--)
		{
			Card card = pile.get(i);
			double t = (now - dealTime(card)) / (double) DEAL_FLIGHT;
			if (t < 1)
			{
				entering = true;
			}
			if (t > 0 && t < 1 && !card.dealSounded)
			{
				card.dealSounded = true;
				sounds.deal(card.taskId.hashCode());
			}
			if (i == 0 && card.isStarted())
			{
				continue; // the top card is being turned or is face up: drawn below
			}
			if (t <= 0)
			{
				continue; // not dealt yet
			}
			drawDealtCard(graphics, card, rest, viewport, Math.min(1, t));
		}
		if (top.isStarted())
		{
			drawTurningCard(graphics, top, rest, top.restDx * rest.width, top.restDy * rest.width, top.restAngle);
			top.bounds = top.isFaceUp() ? scaled(rest, FACE_UP_SCALE) : rest;
		}
		else
		{
			top.bounds = new Rectangle(rest.x + (int) Math.round(top.restDx * rest.width),
				rest.y + (int) Math.round(top.restDy * rest.width), rest.width, rest.height);
		}
		return entering;
	}

	/** One card flying in from the top (t < 1) or lying in the pile (t = 1). */
	private void drawDealtCard(Graphics2D graphics, Card card, Rectangle rest, Rectangle viewport, double t)
	{
		double w = rest.width;
		double restX = rest.getCenterX() + card.restDx * w;
		double restY = rest.getCenterY() + card.restDy * w;
		double fromX = viewport.x + card.fromFx * viewport.width;
		double fromY = viewport.y - rest.height * 0.75;
		// Comes in fast and loses speed all the way to its spot (no overshoot),
		// its spin slowing with it.
		double u = easeOutQuart(t);
		double x = fromX + (restX - fromX) * u;
		double y = fromY + (restY - fromY) * u;
		double settle = easeOutCubic(t);
		double angle = card.restAngle + card.spin * (1 - easeOutQuart(t));
		// A little bigger in the air (nearer the camera), down to size as it lands.
		double scale = 1 + 0.16 * (1 - settle);
		drawShadow(graphics, x, y, angle, scale, rest, 0.35 * settle + 0.1);
		drawArt(graphics, backArt(card.tier), x, y, angle, scale, scale, 1f, 0f);
	}

	// ---------------------------------------------------------------------
	// Drawing: the neat deck (Cascade, or no entrance)
	// ---------------------------------------------------------------------

	private boolean drawNeatDeck(Graphics2D graphics, List<Card> pile, Rectangle rest, Rectangle viewport, long now,
		CardEntrance style)
	{
		Card current = pile.get(0);
		current.bounds = current.isFaceUp() ? scaled(rest, FACE_UP_SCALE) : rest;
		int deckVisible = Math.min(pile.size() - 1, DECK_MAX_VISIBLE);
		long elapsed = now - dealStart;
		long total = entranceDuration(style, deckVisible);
		boolean entering = elapsed < total;
		if (entering && style == CardEntrance.CASCADE)
		{
			drawCascade(graphics, current, deckVisible, elapsed, viewport, rest);
			return true;
		}
		TaskCardTier tier = pile.size() > 1 ? pile.get(1).tier : current.tier;
		for (int k = deckVisible; k >= 1; k--)
		{
			drawArt(graphics, backArt(tier), rest.getCenterX() - k * DECK_OFFSET, rest.getCenterY() - k * DECK_OFFSET,
				0, 1, 1, 1f, 0f);
		}
		if (current.isStarted())
		{
			drawTurningCard(graphics, current, rest, 0, 0, 0);
		}
		else
		{
			drawArt(graphics, backArt(current.tier), rest.getCenterX(), rest.getCenterY(), 0, 1, 1, 1f, 0f);
		}
		return entering;
	}

	/** How long a neat-deck style's entrance runs, with {@code deckVisible} cards behind. */
	private static long entranceDuration(CardEntrance style, int deckVisible)
	{
		return style == CardEntrance.CASCADE ? CASCADE_FALL + CASCADE_STAGGER * deckVisible : 0;
	}

	/**
	 * Cascade: the deck builds up one card at a time, farthest first and the current
	 * card last, each falling straight down from above the viewport and bouncing once.
	 */
	private void drawCascade(Graphics2D graphics, Card current, int deckVisible, long elapsed, Rectangle viewport,
		Rectangle rest)
	{
		List<Card> pile = pile();
		TaskCardTier tier = pile.size() > 1 ? pile.get(1).tier : current.tier;
		if (cascadeFor != dealStart)
		{
			cascadeFor = dealStart;
			cascadeSounded = 0;
		}
		for (int i = 0; i <= deckVisible; i++)
		{
			double t = (elapsed - i * CASCADE_STAGGER) / (double) CASCADE_FALL;
			if (t <= 0)
			{
				continue; // not dealt yet
			}
			t = Math.min(1, t);
			if (t >= DROP_FALL && i == cascadeSounded)
			{
				// It hits the deck: a note higher each card.
				cascadeSounded++;
				sounds.cascade(i);
			}
			int k = deckVisible - i; // deck offset: 0 = the current card
			double cx = rest.getCenterX() - k * DECK_OFFSET;
			double cy = rest.getCenterY() - k * DECK_OFFSET;
			double start = -(cy - rest.height / 2.0 - viewport.y + rest.height + 20);
			double offsetY;
			if (t < DROP_FALL)
			{
				double u = t / DROP_FALL;
				offsetY = start * (1 - u * u);
			}
			else
			{
				double u = (t - DROP_FALL) / (1 - DROP_FALL);
				offsetY = -Math.sin(Math.PI * u) * rest.height * 0.08;
			}
			drawArt(graphics, backArt(k == 0 ? current.tier : tier), cx, cy + offsetY, 0, 1, 1, 1f, 0f);
		}
	}

	// ---------------------------------------------------------------------
	// Drawing: the flip
	// ---------------------------------------------------------------------

	/**
	 * The top card being turned over (or resting face up), as a drag and flip: it's
	 * dragged a little out of its pile pose, tilting slightly as if under a finger;
	 * then flips over about an axis just right of its centre (the side that lifts
	 * coming towards you in perspective), rising towards you as it turns; then
	 * settles into the middle, raised above the pile.
	 */
	private void drawTurningCard(Graphics2D graphics, Card card, Rectangle rest, double pileDx, double pileDy,
		double pileAngle)
	{
		double w = rest.width;
		double p = Math.min(1, card.sinceFlip() / (double) card.flipDuration);
		double midX = rest.getCenterX();
		double midY = rest.getCenterY();

		if (p >= 1)
		{
			Rectangle up = scaled(rest, FACE_UP_SCALE);
			card.flareAt = up;
			drawShadow(graphics, midX + 6, midY + 12, 0, FACE_UP_SCALE, rest, 0.45);
			drawArt(graphics, frontArt(card), midX, midY, 0, FACE_UP_SCALE, FACE_UP_SCALE, 1f, 0f);
			flare(graphics, card, up, false);
			return;
		}
		if (p < FLIP_DRAG)
		{
			// Dragged out: from the pile to just left of the middle.
			double a = easeOutCubic(p / FLIP_DRAG);
			double x = midX + pileDx + (-FLIP_AXIS * w - pileDx) * a;
			double y = midY + pileDy + (0 - pileDy) * a;
			double angle = pileAngle + (-0.03 - pileAngle) * a;
			double scale = 1 + 0.03 * a;
			drawShadow(graphics, x, y + 3 * a, angle, scale, rest, 0.3);
			drawArt(graphics, backArt(card.tier), x, y, angle, scale, scale, 1f, 0f);
			return;
		}
		if (p < FLIP_TURN_END)
		{
			// Flipped over about the axis (which stays put), rising as it turns.
			double q = (p - FLIP_DRAG) / (FLIP_TURN_END - FLIP_DRAG);
			double theta = easeInOutSine(q) * Math.PI;
			double x = midX - FLIP_AXIS * w;
			double angle = -0.03 * (1 - q);
			double scale = 1.03 + (FACE_UP_SCALE - 1.03) * easeInOutSine(q);
			drawShadow(graphics, midX, midY + 4 + 8 * q, 0, scale * Math.max(0.2, Math.abs(Math.cos(theta))), rest,
				0.3 + 0.15 * q);
			drawHingeFlip(graphics, card, x, midY, angle, scale, theta);
			return;
		}
		// Settles: face up and raised, from just right of the middle into the middle.
		double a = easeOutCubic((p - FLIP_TURN_END) / (1 - FLIP_TURN_END));
		double x = midX + FLIP_AXIS * w * (1 - a);
		Rectangle up = scaled(rest, FACE_UP_SCALE);
		up.x += (int) Math.round(x - midX);
		card.flareAt = up;
		drawShadow(graphics, x + 6, midY + 12, 0, FACE_UP_SCALE, rest, 0.45);
		drawArt(graphics, frontArt(card), x, midY, 0, FACE_UP_SCALE, FACE_UP_SCALE, 1f, 0f);
		flare(graphics, card, up, false);
	}

	/**
	 * The theme's show as the card lands face up ({@code behind}: the part under it), bigger for
	 * rarer tiers (CardFlare). The ChunkBlazer theme, the core look, has none.
	 */
	private void flare(Graphics2D graphics, Card card, Rectangle b, boolean behind)
	{
		if (card.isFaceDown() || card.isFlying())
		{
			return;
		}
		Theme theme = artDesign == null ? Theme.OLD_SCHOOL : artDesign;
		long age = card.sinceFlip() - (long) (card.flipDuration * FLIP_TURN_END);
		CardFlare.draw(graphics, theme, b, card.tier.getAccent(), card.tier.ordinal(), age, card.taskId.hashCode(), behind);
	}

	/** A rectangle grown about its centre. */
	private static Rectangle scaled(Rectangle r, double scale)
	{
		int w = (int) Math.round(r.width * scale);
		int h = (int) Math.round(r.height * scale);
		return new Rectangle((int) Math.round(r.getCenterX() - w / 2.0), (int) Math.round(r.getCenterY() - h / 2.0), w, h);
	}

	/**
	 * A card (centred at cx, cy when lying back up) flipped theta (0 = back up, PI =
	 * face up) about a vertical axis FLIP_AXIS of its width right of centre. Drawn in
	 * vertical strips so the side lifting towards you can be taller than the side
	 * dipping away: a cheap perspective. It darkens a little as it tilts.
	 */
	private void drawHingeFlip(Graphics2D graphics, Card card, double cx, double cy, double angle, double scale,
		double theta)
	{
		boolean front = theta > Math.PI / 2;
		BufferedImage[] art = front ? frontArt(card) : backArt(card.tier);
		BufferedImage img = art[0];
		BufferedImage dark = art[1];
		int w = img.getWidth();
		int h = img.getHeight();
		double c = Math.cos(theta);
		double s = Math.sin(theta);
		if (Math.abs(c) * w < 1)
		{
			return; // standing on its edge, seen side-on
		}
		AffineTransform before = graphics.getTransform();
		Composite oldComposite = graphics.getComposite();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.translate(cx, cy);
		graphics.rotate(angle);
		graphics.scale(scale, scale);
		double unit = artUnit(img);
		graphics.scale(unit, unit);
		double axis = FLIP_AXIS * w; // card-local x of the axis
		for (int i = 0; i < FLIP_SLICES; i++)
		{
			double f0 = i / (double) FLIP_SLICES;
			double f1 = (i + 1) / (double) FLIP_SLICES;
			// Card-local x (back up) of each side of the strip. Face up, the face
			// is mirrored relative to the back, so it reads the right way round.
			double l0 = front ? w / 2.0 - f0 * w : f0 * w - w / 2.0;
			double l1 = front ? w / 2.0 - f1 * w : f1 * w - w / 2.0;
			double x0 = axis + (l0 - axis) * c;
			double x1 = axis + (l1 - axis) * c;
			int dx0 = (int) Math.floor(Math.min(x0, x1));
			int dx1 = (int) Math.ceil(Math.max(x0, x1));
			// The side left of the axis lifts towards you; the right side dips away.
			double lift = (axis - (l0 + l1) / 2) / w;
			double k = 1 + FLIP_PERSPECTIVE * s * lift * 2;
			int hh = (int) Math.round(h * k / 2);
			int sx0 = (int) Math.floor(f0 * w);
			int sx1 = Math.max(sx0 + 1, (int) Math.floor(f1 * w));
			graphics.setComposite(oldComposite);
			graphics.drawImage(img, dx0, -hh, dx1 + 1, hh, sx0, 0, sx1, h, null);
			float shade = (float) (0.25 * s);
			if (shade > 0.01f)
			{
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, shade)));
				graphics.drawImage(dark, dx0, -hh, dx1 + 1, hh, sx0, 0, sx1, h, null);
			}
		}
		graphics.setComposite(oldComposite);
		graphics.setTransform(before);
	}

	// ---------------------------------------------------------------------
	// Drawing: into the Tasks orb
	// ---------------------------------------------------------------------

	/** Where cards fly to: the Tasks orb, or the top-right of the view if it's hidden. */
	private Point2D orbCentre(Rectangle viewport)
	{
		Point p = plugin.getTasksOrbCentre();
		if (p != null)
		{
			return p;
		}
		return new Point2D.Double(viewport.x + viewport.width - 40, viewport.y + 40);
	}

	/**
	 * Cards on their way into the orb: tossed like a ball of paper into a bin, up in
	 * an arc and dropping in from above, tumbling and shrinking as they go, with a
	 * faint trail.
	 */
	private void drawFlyingCards(Graphics2D graphics, Rectangle viewport, Rectangle rest, long now)
	{
		Point2D target = orbCentre(viewport);
		for (Card card : cards)
		{
			if (!card.isFlying())
			{
				continue;
			}
			double t = (now - card.flyStart) / (double) FLY_DURATION;
			BufferedImage[] art = card.flyFaceUp ? frontArt(card) : backArt(card.tier);
			if (t <= 0)
			{
				// A batch card waiting its turn to be tossed: still where it lay.
				drawArt(graphics, art, card.flyFromX, card.flyFromY, card.flyFromAngle, card.flyFromScale,
					card.flyFromScale, 1f, 0f);
				continue;
			}
			t = Math.min(1, t);
			for (int ghost = 2; ghost >= 0; ghost--)
			{
				double tg = t - ghost * 0.04;
				if (tg <= 0)
				{
					continue;
				}
				float alpha = ghost == 0 ? 1f : (ghost == 1 ? 0.25f : 0.1f);
				drawTossAt(graphics, card, art, target, viewport, tg, alpha);
			}
		}
	}

	private void drawTossAt(Graphics2D graphics, Card card, BufferedImage[] art, Point2D target, Rectangle viewport,
		double t, float alpha)
	{
		double sx = card.flyFromX;
		double sy = card.flyFromY;
		double tx = target.getX();
		double ty = target.getY();
		double dist = Math.hypot(tx - sx, ty - sy);
		// A lob: the control point sits up above both ends, so the card rises, tops
		// out and drops into the orb from above. Kept on screen near the top.
		double kx = (sx + tx) / 2;
		double ky = Math.max(viewport.y - 20, Math.min(sy, ty) - 0.55 * dist);
		// Even steps in time along a quadratic curve trace a parabola: a real throw.
		double u = t;
		double x = (1 - u) * (1 - u) * sx + 2 * (1 - u) * u * kx + u * u * tx;
		double y = (1 - u) * (1 - u) * sy + 2 * (1 - u) * u * ky + u * u * ty;
		// Shrinks to about the size of the orb as it goes in.
		double scale = Math.max(0.03, card.flyFromScale * (1 - 0.9 * Math.pow(u, 1.3)));
		// Tumbles end over end on the way.
		double spinDir = tx >= sx ? 1 : -1;
		double angle = card.flyFromAngle + spinDir * u * 1.5 * Math.PI;
		float fade = (float) (1 - smoothstep(0.9, 1.0, t));
		drawArt(graphics, art, x, y, angle, scale, scale, alpha * fade, 0f);
	}

	/**
	 * The orb catches fire as cards land in it: the whole circle burns, glowing like
	 * an ember with tongues of flame rising from all round its rim, tallest at the
	 * top. Each landing adds heat and overlapping landings stack, so sending the
	 * whole pile in stokes it into a much bigger blaze.
	 */
	private void drawImpacts(Graphics2D graphics, Rectangle viewport, long now)
	{
		double heat = 0;
		for (Iterator<Impact> it = impacts.iterator(); it.hasNext(); )
		{
			Impact impact = it.next();
			long age = now - impact.at;
			if (age > IMPACT_DURATION)
			{
				it.remove();
				continue;
			}
			if (age >= 0)
			{
				// Catches fast, then burns down.
				double f = age / (double) IMPACT_DURATION;
				heat += impact.heat * Math.min(1, age / 90.0) * (1 - smoothstep(0.3, 1.0, f));
			}
		}
		if (heat <= 0.01)
		{
			return;
		}
		double strength = Math.min(1, heat);
		// One card: normal size. Each extra card's worth of heat makes it bigger.
		// Capped so a full blaze stays on screen (the orb sits near the top).
		double size = 1 + 0.4 * Math.max(0, Math.min(heat, 5) - 1);
		Point2D c = orbCentre(viewport);
		double cx = c.getX();
		double cy = c.getY();
		double r = ORB_RADIUS;
		Paint oldPaint = graphics.getPaint();
		Composite oldComposite = graphics.getComposite();

		// Warm glow behind.
		double gr = r * (2.2 + 0.8 * size);
		graphics.setPaint(new RadialGradientPaint(c, (float) gr, new float[]{0f, 0.5f, 1f},
			new Color[]{new Color(255, 160, 50, (int) (170 * strength)), new Color(255, 90, 20, (int) (70 * strength)),
				new Color(200, 40, 10, 0)}));
		graphics.fill(new Ellipse2D.Double(cx - gr, cy - gr, gr * 2, gr * 2));

		// The orb itself, burning like an ember.
		graphics.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy + r * 0.3), (float) (r * 1.1),
			new float[]{0f, 0.6f, 1f},
			new Color[]{new Color(255, 230, 120, (int) (220 * strength)), new Color(255, 120, 30, (int) (200 * strength)),
				new Color(170, 30, 10, (int) (190 * strength))}));
		graphics.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));

		// Tongues of flame from all round the rim, all rising, tallest over the top.
		int tongues = 18;
		for (int k = 0; k < tongues; k++)
		{
			double a = 2 * Math.PI * k / tongues + 0.17;
			double rx = cx + Math.cos(a) * r;
			double ry = cy + Math.sin(a) * r;
			double top = 0.5 - 0.5 * Math.sin(a); // 1 at the top of the circle, 0 at the bottom
			double seed = Math.sin(k * 12.9898) * 43758.5453;
			double rnd = seed - Math.floor(seed);
			double flick = 1 + 0.22 * Math.sin(now / 55.0 + k * 1.9) + 0.12 * Math.sin(now / 23.0 + k * 3.1);
			double height = (7 + 9 * rnd + 14 * top) * size * strength * flick;
			double width = (3.2 + 2.2 * rnd) * Math.pow(size, 0.7) * strength;
			// Lean out a little from the side they grow on, but always upwards.
			double lean = Math.cos(a) * 0.55 + Math.sin(now / 70.0 + k) * 0.12;
			drawFlameTongue(graphics, rx, ry, width, height, lean, strength);
		}
		// A big central flame once it's really going.
		if (size > 1.4)
		{
			double flick = 1 + 0.15 * Math.sin(now / 40.0);
			drawFlameTongue(graphics, cx, cy - r * 0.4, 7 * Math.sqrt(size), (18 + 16 * (size - 1)) * flick,
				Math.sin(now / 90.0) * 0.15, strength);
		}

		// Sparks drifting up.
		for (int k = 0; k < (int) (6 * size); k++)
		{
			double seed = Math.sin(k * 78.233 + 1) * 12345.6789;
			double rnd = seed - Math.floor(seed);
			double life = ((now / 1000.0) * (0.8 + rnd) + rnd) % 1.0;
			double px = cx + (rnd - 0.5) * r * 2.4 * Math.sqrt(size) + Math.sin(now / 200.0 + k) * 3;
			double py = cy - r - life * (24 + 20 * size);
			int sa = (int) Math.round(255 * strength * (1 - life));
			graphics.setColor(new Color(255, 210 - (k % 4) * 30, 60, Math.max(0, sa)));
			graphics.fillRect((int) px, (int) py, 2, 2);
		}

		graphics.setPaint(oldPaint);
		graphics.setComposite(oldComposite);
	}

	/** One tongue of flame standing on (bx, by), leaning by {@code lean} radians. */
	private static void drawFlameTongue(Graphics2D graphics, double bx, double by, double width, double height,
		double lean, double life)
	{
		if (height < 1 || width < 0.5)
		{
			return;
		}
		AffineTransform before = graphics.getTransform();
		graphics.translate(bx, by);
		graphics.rotate(lean * 0.6);
		java.awt.geom.Path2D.Double flame = new java.awt.geom.Path2D.Double();
		flame.moveTo(-width, 0);
		flame.curveTo(-width, -height * 0.45, -width * 0.2, -height * 0.7, 0, -height);
		flame.curveTo(width * 0.2, -height * 0.7, width, -height * 0.45, width, 0);
		flame.curveTo(width * 0.6, width * 0.6, -width * 0.6, width * 0.6, -width, 0);
		flame.closePath();
		int a = (int) Math.round(235 * Math.min(1, life));
		// Hot yellow at the root, deep red at the tip.
		graphics.setPaint(new java.awt.GradientPaint(0, 0, new Color(255, 225, 90, a),
			0, (float) -height, new Color(220, 40, 10, (int) (a * 0.6))));
		graphics.fill(flame);
		graphics.setTransform(before);
	}

	// ---------------------------------------------------------------------
	// Drawing: card art
	// ---------------------------------------------------------------------

	/** Where the card rests: centred in the viewport, clamped to fit. */
	private static Rectangle restBounds(Rectangle viewport)
	{
		int cardWidth = Math.min(CARD_WIDTH, viewport.width - 80);
		cardWidth = Math.max(48, cardWidth); // a truly tiny viewport still gets something
		int cardHeight = (int) Math.round(cardWidth * ASPECT);
		int x = viewport.x + (viewport.width - cardWidth) / 2;
		int y = viewport.y + (viewport.height - cardHeight) / 2;
		return new Rectangle(x, y, cardWidth, cardHeight);
	}

	/** Throw away cached art if the card size changed (the window was resized). */
	private void prepareArt(int w, int h, int detail)
	{
		Theme design = config.theme();
		if (w == artWidth && h == artHeight && design == artDesign && detail == artDetail)
		{
			return;
		}
		artWidth = w;
		artHeight = h;
		artDetail = detail;
		artDesign = design;
		backArt.clear();
		for (Card card : cards)
		{
			card.frontArt = null;
		}
	}

	private BufferedImage[] backArt(TaskCardTier tier)
	{
		return backArt.computeIfAbsent(tier, t -> renderArt(t, false, null));
	}

	private BufferedImage[] frontArt(Card card)
	{
		if (card.frontArt == null)
		{
			card.frontArt = renderArt(card.tier, true, card.taskName);
		}
		return card.frontArt;
	}

	/** Paint a card face into an image (plus a darkened copy for shading) once, to reuse. */
	private BufferedImage[] renderArt(TaskCardTier tier, boolean front, String name)
	{
		// Faces are drawn at the size they're read at (lifted, FACE_UP_SCALE bigger), so their
		// text is pixel-sharp there instead of stretched; backs at the pile's size.
		double res = front ? FACE_UP_SCALE : 1;
		int w = Math.max(1, (int) Math.round(artWidth * res));
		int h = Math.max(1, (int) Math.round(artHeight * res));
		// Laid out at its size, painted finer (drawArt scales it back down): faces, with their
		// words, the most; backs, drawn many times over in the pile, just enough for the screen.
		int detail = front ? artDetail : Math.max(1, artDetail / 2);
		BufferedImage img = new BufferedImage(w * detail, h * detail, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.scale(detail, detail);
		paintCard(g, new Rectangle(0, 0, w, h), tier, front, name, artDesign);
		g.dispose();
		// Same picture, near black: drawn over the top (by alpha) to shade it,
		// respecting the card's own shape and transparency.
		BufferedImage dark = new RescaleOp(new float[]{0.15f, 0.12f, 0.18f, 1f}, new float[4], null)
			.filter(img, null);
		return new BufferedImage[]{img, dark};
	}

	// The full ChunkBlazer logo, for the backs of the themed designs.
	private static final BufferedImage LOGO = ImageUtil.loadImageResource(CardArt.class, "Task_Cards/logo.png");

	/**
	 * One card in {@code b}: btwinnn's art (CardArt) in the ChunkBlazer theme, CardFace's
	 * design for the others.
	 */
	static void paintCard(Graphics2D g, Rectangle b, TaskCardTier tier, boolean front, String name, Theme design)
	{
		if (design == null || design == Theme.ORIGINAL)
		{
			CardArt.paint(g, b, tier, front, name);
			return;
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if (front)
		{
			CardFace.paint(g, b, design, tier.getAccent(), tier.getDisplayName(), tier.ordinal() + 1, name,
				FontManager.getRunescapeBoldFont());
		}
		else
		{
			CardFace.paintBack(g, b, design, tier.getAccent(), tier.ordinal() + 1, LOGO);
		}
	}

	/**
	 * Draw card art centred on (cx, cy), turned and scaled, at the given opacity,
	 * with {@code darken} (0..1) of the dark copy laid over it.
	 */
	private void drawArt(Graphics2D graphics, BufferedImage[] art, double cx, double cy, double angle,
		double sx, double sy, float alpha, float darken)
	{
		if (alpha <= 0.01f || sx <= 0 || sy <= 0)
		{
			return;
		}
		BufferedImage img = art[0];
		AffineTransform before = graphics.getTransform();
		Composite oldComposite = graphics.getComposite();
		// The slow, smoothest scaling only for the card lying face up to be read; moving and
		// piled cards (many, every frame) use the quick one.
		boolean reading = angle == 0 && sx >= FACE_UP_SCALE - 0.001;
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, reading
			? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.translate(cx, cy);
		graphics.rotate(angle);
		graphics.scale(sx, sy);
		double unit = artUnit(img);
		graphics.scale(unit, unit);
		graphics.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
		if (alpha < 1f)
		{
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		graphics.drawImage(img, 0, 0, null);
		if (darken > 0.01f)
		{
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, darken * alpha)));
			graphics.drawImage(art[1], 0, 0, null);
		}
		graphics.setComposite(oldComposite);
		graphics.setTransform(before);
	}

	/** Card-size pixels per pixel of {@code img} (faces are drawn larger than the card; see renderArt). */
	private double artUnit(BufferedImage img)
	{
		return artWidth > 0 && img.getWidth() > 0 ? artWidth / (double) img.getWidth() : 1;
	}

	/** A soft shadow under a card, so the pile reads as a pile. */
	private static void drawShadow(Graphics2D graphics, double cx, double cy, double angle, double scale,
		Rectangle rest, double strength)
	{
		AffineTransform before = graphics.getTransform();
		graphics.translate(cx + 4, cy + 5);
		graphics.rotate(angle);
		graphics.scale(scale, scale);
		graphics.setColor(new Color(0, 0, 0, (int) Math.round(Math.min(1, strength) * 90)));
		graphics.fillRoundRect(-rest.width / 2, -rest.height / 2, rest.width, rest.height, 10, 10);
		graphics.setTransform(before);
	}

	// ---------------------------------------------------------------------
	// Prompt and buttons
	// ---------------------------------------------------------------------

	private void drawPrompt(Graphics2D graphics, Rectangle viewport, Rectangle rest, Card current)
	{
		int total = pile().size();
		String text = null;
		if (current.isFaceDown())
		{
			text = total > 1
				? "Click to reveal. " + total + " cards to open"
				: "Click the card to reveal your task";
		}
		else if (current.isFaceUp())
		{
			// Nothing is waiting on this — the task is already yours. Say so, so the
			// card doesn't read as something still to be completed.
			int left = total - 1;
			text = left > 0
				? "Task added. Click to send it to your list (" + left + " left)"
				: "Task added. Click to send it to your list";
		}

		// No text mid-flip, but the buttons below stay.
		if (text != null)
		{
			graphics.setFont(Skin.bold());
			drawCentered(graphics, text, viewport.x + viewport.width / 2, current.bounds.y - 14,
				new Color(255, 255, 0));
		}

		// Batch controls: only meaningful with a pile to work through. They stay up, in the
		// same spot, while the front card flips, lifts and sits face up: below where the
		// lifted card reaches, not under the card itself (which grows as it lifts), so they
		// never move or vanish from under the mouse and let a click through to the game.
		if (total > 1)
		{
			// These send tasks straight to the list without flipping each one, so the
			// labels say exactly that. Stacked (not side by side) because the labels are
			// long enough that two columns would overlap on a narrow viewport.
			int cx = viewport.x + viewport.width / 2;
			Rectangle lifted = scaled(rest, FACE_UP_SCALE);
			int by = lifted.y + lifted.height + 10;
			if (total > 5)
			{
				nextFiveBounds = drawButton(graphics, "Send 5 To Task List", cx, by);
				nextAllBounds = drawButton(graphics, "Send All To Task List (" + total + ")",
					cx, by + nextFiveBounds.height + 6);
			}
			else
			{
				nextAllBounds = drawButton(graphics, "Send All To Task List (" + total + ")", cx, by);
			}
		}
	}

	private Rectangle drawButton(Graphics2D graphics, String text, int centreX, int top)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int padX = 10;
		int padY = 4;
		int w = fm.stringWidth(text) + padX * 2;
		int h = fm.getHeight() + padY * 2;
		int x = centreX - w / 2;
		Rectangle bounds = new Rectangle(x, top, w, h);

		Skin.button(graphics, bounds, false, false);

		int tx = centreX - fm.stringWidth(text) / 2;
		int ty = top + padY + fm.getAscent();
		Skin.text(graphics, text, tx, ty, PROMPT);
		return bounds;
	}

	private void drawCentered(Graphics2D graphics, String text, int centreX, int y, Color colour)
	{
		FontMetrics fm = graphics.getFontMetrics();
		int x = centreX - fm.stringWidth(text) / 2;
		Skin.text(graphics, text, x, y, colour);
	}

	private Rectangle viewportBounds()
	{
		int w = client.getViewportWidth();
		int h = client.getViewportHeight();
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		return new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), w, h);
	}

	@Override
	public void clear()
	{
		cards.clear();
		// Force the next sync to rebuild rather than trust the cached text.
		lastPendingRaw = null;
		// Drop any pending batch request and stale button bounds so they can't fire
		// into the next session.
		batchRevealRequest = 0;
		sendCurrentRequest = false;
		revealFiveBounds = null;
		revealAllBounds = null;
		impacts.clear();
	}

	// ---------------------------------------------------------------------
	// Easing
	// ---------------------------------------------------------------------

	private static double easeOutCubic(double t)
	{
		double u = 1 - t;
		return 1 - u * u * u;
	}

	private static double easeOutQuart(double t)
	{
		double u = 1 - t;
		return 1 - u * u * u * u;
	}

	private static double easeInOutSine(double t)
	{
		return -(Math.cos(Math.PI * t) - 1) / 2;
	}

	private static double smoothstep(double a, double b, double x)
	{
		double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
