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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Pokemon-style area sign: when the player walks into a different chunk, a small
 * banner with the chunk's name slides down from the top of the game view, holds for a
 * moment, then slides back up. A second line says whether the chunk is unlocked or,
 * if not, what it costs. Its own overlay on the always-on-top layer, so game windows,
 * the chatbox and other overlays never cover it; registered by TaskPaneOverlay.startUp.
 *
 * In a chunk you haven't unlocked, the banner stays up for as long as you're there.
 * It only goes once you unlock the chunk (it flashes "Unlocked!" first) or walk out of
 * it, even into a chunk that gets no banner of its own; when one banner replaces another, the old one slides away quickly
 * before the new one comes in.
 *
 * An unlocked chunk among the last few you were in doesn't get its banner again, so
 * running laps across chunk borders (agility courses, herbiboar, a bank and a skilling
 * spot) stays quiet however long you train, while somewhere new always gets one. A
 * dungeon counts as the same place as the land above it, so going down and back up
 * stays quiet too; coming out in a different chunk doesn't. Locked chunks always get
 * their banner.
 */
@Singleton
public class ChunkNameBanner extends Overlay
{
	private static final long SLIDE_MS = 350;
	// Quicker exit when another banner is waiting to come in.
	private static final long SWAP_SLIDE_MS = 220;
	private static final long HOLD_MS = 2500;
	private static final long UNLOCKED_HOLD_MS = 2000;
	private static final long FLASH_MS = 700;
	private static final int TOP_MARGIN = 12;
	private static final int PAD_X = 18;
	private static final int PAD_Y = 8;
	// How many recent places stay quiet: enough for a course or loop over 3-4 chunks.
	private static final int RECENT_PLACES = 4;
	// Dungeons sit 6400 tiles (100 regions) north of the land above them.
	private static final int UNDERGROUND_REGION_Y = 100;

	// ChunkBlazer GPU's unlock: after the lava sweep the sign falls to where the cards land, turns
	// from Unlock to Unlocked, and the cards come in two seconds later.
	private static final long FALL_MS = 650;
	private static final long TURN_AT = 900;
	private static final long TURN_MS = 600;
	/** From the sweep ending to the cards coming in. */
	public static final long CARDS_AFTER_SWEEP_MS = TURN_AT + TURN_MS + 2000;
	private static final long DROP_FADE_MS = 350;
	private static final double DROP_SCALE = 1.35;

	private enum Phase
	{
		HIDDEN, ENTER, HOLD, LEAVE
	}

	/** What one banner says, and whether it waits for the chunk to be unlocked. */
	private static final class Banner
	{
		final String title;
		final String subtitle;
		final Color color;
		final int regionId;
		final boolean sticky;

		Banner(String title, String subtitle, Color color, int regionId, boolean sticky)
		{
			this.title = title;
			this.subtitle = subtitle;
			this.color = color;
			this.regionId = regionId;
			this.sticky = sticky;
		}
	}

	private final Client client;
	private final ChunkBlazerCore plugin;
	private final ChunkBlazerUiConfig config;

	private int lastRegionId = -1;
	private String lastName;
	// The last few unlocked places you were in (dungeons count as the land above them),
	// oldest first.
	private final Map<Integer, Boolean> recent = new LinkedHashMap<Integer, Boolean>(8, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest)
		{
			return size() > RECENT_PLACES;
		}
	};

	// The banner on screen and where it is in its animation.
	private Phase phase = Phase.HIDDEN;
	private long phaseStart;
	private long holdMs;
	private long leaveMs;
	private double leaveFrom;
	private long flashAt = -1;
	private String title;
	private String subtitle;
	private Color subtitleColor;
	private int bannerRegion;
	private boolean sticky;

	// The next banner, shown once the current one has slid away.
	private Banner pending;

	// The sign falling for an unlock: when (-1: not), what it said, and how far down it was.
	private volatile long dropAt = -1;
	private String dropTitle;
	private String dropFrom;
	private Color dropFromColor;
	private double dropFromShown;
	// A sweep is playing over the chunk the sign is waiting on: it stays put for the drop.
	private volatile boolean heldForSweep;
	// Its unlock sound has played (it plays as the status turns).
	private boolean dropTurned;
	@javax.inject.Inject
	private CardSounds sounds;
	// Drawn at twice the size and scaled down, so the text stays sharp as the sign grows.
	private static final int SHARP = 2;
	private static final int GLOW_PAD = 4;

	@Inject
	public ChunkNameBanner(Client client, ChunkBlazerCore plugin, ChunkBlazerUiConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	/** Called every frame; watches for a chunk change and draws the banner while it's showing. */
	public Dimension render(Graphics2D graphics)
	{
		// Not on Tutorial Island, where the tutorial does the talking.
		if (!config.showChunkNamePopups() || plugin.isOnTutorialIsland())
		{
			phase = Phase.HIDDEN;
			pending = null;
			return null;
		}
		long now = System.currentTimeMillis();
		if (dropAt >= 0)
		{
			drawDrop(graphics, now - dropAt);
			return null;
		}
		checkForNewChunk();
		advance(now);
		if (phase == Phase.HIDDEN)
		{
			return null;
		}
		double flash = flashAt < 0 ? 0 : Math.max(0, 1 - (now - flashAt) / (double) FLASH_MS);
		draw(graphics, shownAmount(now), flash);
		return null;
	}

	/** Move the animation along: finish sliding in, wait, react to an unlock, slide out. */
	private void advance(long now)
	{
		long t = now - phaseStart;
		switch (phase)
		{
			case ENTER:
				if (t >= SLIDE_MS)
				{
					phase = Phase.HOLD;
					phaseStart = now;
				}
				break;
			case HOLD:
				if (sticky)
				{
					// Waiting in a locked chunk: the moment it's unlocked, say so, then leave
					// (or, with ChunkBlazer GPU's sweep playing, wait to fall for it).
					if (!heldForSweep && plugin.isRegionUnlocked(bannerRegion))
					{
						subtitle = "Unlocked!";
						subtitleColor = ChunkUnlockType.UNLOCKED.color;
						sticky = false;
						holdMs = UNLOCKED_HOLD_MS;
						flashAt = now;
						phaseStart = now;
					}
				}
				else if (t >= holdMs)
				{
					leave(now, SLIDE_MS);
				}
				break;
			case LEAVE:
				if (t >= leaveMs)
				{
					phase = Phase.HIDDEN;
					if (pending != null)
					{
						Banner next = pending;
						pending = null;
						show(next, now);
					}
				}
				break;
			default:
				break;
		}
	}

	/** 0 = hidden above the screen, 1 = fully shown. Eases in and out. */
	private double shownAmount(long now)
	{
		double t = now - phaseStart;
		switch (phase)
		{
			case ENTER:
				return ease(Math.min(1, t / SLIDE_MS));
			case LEAVE:
				// Leaves from wherever it was, even part-way through sliding in.
				return leaveFrom * (1 - ease(Math.min(1, t / leaveMs)));
			case HOLD:
				return 1;
			default:
				return 0;
		}
	}

	private void show(Banner banner, long now)
	{
		title = banner.title;
		subtitle = banner.subtitle;
		subtitleColor = banner.color;
		bannerRegion = banner.regionId;
		sticky = banner.sticky;
		holdMs = HOLD_MS;
		flashAt = -1;
		phase = Phase.ENTER;
		phaseStart = now;
	}

	private void leave(long now, long duration)
	{
		leaveFrom = shownAmount(now);
		leaveMs = duration;
		phase = Phase.LEAVE;
		phaseStart = now;
	}

	/** Show a banner now, or after the current one has slid away. */
	private void queue(Banner banner)
	{
		long now = System.currentTimeMillis();
		if (phase == Phase.HIDDEN)
		{
			show(banner, now);
			return;
		}
		pending = banner;
		if (phase != Phase.LEAVE)
		{
			leave(now, SWAP_SLIDE_MS);
		}
	}

	private void checkForNewChunk()
	{
		Player local = client.getLocalPlayer();
		if (local == null || client.isInInstancedRegion())
		{
			return;
		}
		WorldPoint location = local.getWorldLocation();
		if (location == null)
		{
			return;
		}
		int regionId = location.getRegionID();
		if (regionId == lastRegionId)
		{
			return;
		}
		boolean firstLook = lastRegionId == -1;
		lastRegionId = regionId;

		String name = chunkName(regionId);
		// Left the locked chunk the sign is waiting on (declined it, walked back out):
		// let it go, even if where you are now doesn't get a banner of its own.
		if (sticky && (phase == Phase.ENTER || phase == Phase.HOLD) && (name == null || !name.equals(title)))
		{
			sticky = false;
			leave(System.currentTimeMillis(), SLIDE_MS);
		}
		// Nothing for unnamed areas, or when moving between two regions of the same
		// chunk (a surface and its dungeon share a name).
		if (name == null || name.equals(lastName))
		{
			lastName = name;
			return;
		}
		lastName = name;

		boolean unlocked = plugin.isRegionUnlocked(regionId);
		// On login, only a locked chunk gets a sign (it's the one asking to be unlocked).
		// After that, an unlocked chunk only gets one if you haven't been there recently.
		// Locked places aren't remembered, so a chunk you've since unlocked gets one.
		int place = (regionId & 0xFF) >= UNDERGROUND_REGION_Y ? regionId - UNDERGROUND_REGION_Y : regionId;
		if (unlocked && (recent.put(place, true) != null || firstLook))
		{
			return;
		}
		boolean neighbor = !unlocked && plugin.getNeighborRegionIds().contains(regionId);
		ChunkUnlockType type = plugin.unlockType(regionId, unlocked, neighbor);
		// Only a chunk you could unlock right now waits on screen. One you were moved into
		// (a random event's prison, a teleport) just says it's locked and goes.
		queue(new Banner(name, statusText(type, plugin.costLabel(regionId, type)),
			type.color, regionId, type.isUnlockable()));
	}

	/**
	 * ChunkBlazer GPU's lava sweep: while it plays, the sign for that chunk stays where it is;
	 * when it ends, the sign falls to the middle of the screen and turns to Unlocked.
	 */
	@Subscribe
	public void onPluginMessage(PluginMessage message)
	{
		Object region = message.getData().get("regionId");
		if (!"chunkblazer".equals(message.getNamespace()) || !(region instanceof Number))
		{
			return;
		}
		int id = ((Number) region).intValue();
		if ("unlockSweepStarted".equals(message.getName()))
		{
			heldForSweep = phase != Phase.HIDDEN && id == bannerRegion;
		}
		else if ("unlockSweepFinished".equals(message.getName()) && config.showChunkNamePopups() && !plugin.isOnTutorialIsland())
		{
			startDrop(id);
		}
		else if ("unlockSweepSkipped".equals(message.getName()))
		{
			// Unlocked out of sight, so no sweep: the sign falls now, and the cards follow it.
			skippedDrop = dropsForUnlocks() && chunkName(id) != null;
			if (skippedDrop)
			{
				startDrop(id);
			}
			skippedAt = System.currentTimeMillis();
		}
	}

	/** When ChunkBlazer GPU last unlocked a chunk out of sight (no sweep), and whether the sign fell for it. */
	volatile long skippedAt;
	volatile boolean skippedDrop;

	/** Whether an unlock now gets the falling sign (so the cards wait for it). */
	public boolean dropsForUnlocks()
	{
		return config.showChunkNamePopups() && !plugin.isOnTutorialIsland();
	}

	/**
	 * A chunk unlocked with no ChunkBlazer GPU sweep: the sign falls and turns all the same.
	 * True if it does (the cards then wait {@link #CARDS_AFTER_SWEEP_MS} for it).
	 */
	public boolean dropForUnlock(int regionId)
	{
		if (regionId <= 0 || !dropsForUnlocks() || chunkName(regionId) == null)
		{
			return false;
		}
		startDrop(regionId);
		return true;
	}

	private void startDrop(int regionId)
	{
		String name = chunkName(regionId);
		if (name == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean ours = phase != Phase.HIDDEN && regionId == bannerRegion && !"Unlocked!".equals(subtitle);
		dropTitle = name;
		dropFrom = ours ? subtitle : "Unlock";
		dropFromColor = ours ? subtitleColor : ChunkUnlockType.PAID.color;
		dropFromShown = phase == Phase.HIDDEN ? 0 : shownAmount(now);
		phase = Phase.HIDDEN;
		pending = null;
		sticky = false;
		heldForSweep = false;
		lastName = name;
		dropTurned = false;
		dropAt = now;
		sounds.banner("banner_drop");
	}

	/**
	 * The falling sign, {@code e} ms in: dropping from the top (bouncing as it lands, growing),
	 * then its status turning from Unlock (yellow) to Unlocked! (green) with a pop and a flash,
	 * held until the cards come in, then fading as they arrive.
	 */
	private void drawDrop(Graphics2D graphics, long e)
	{
		if (e > CARDS_AFTER_SWEEP_MS + DROP_FADE_MS)
		{
			dropAt = -1;
			return;
		}
		double turn = Math.max(0, Math.min(1, (e - TURN_AT) / (double) TURN_MS));
		if (turn >= 0.5 && !dropTurned)
		{
			dropTurned = true;
			sounds.banner("banner_unlock");
		}
		title = dropTitle;
		subtitle = turn < 0.5 ? dropFrom : "Unlocked!";
		subtitleColor = blend(dropFromColor, ChunkUnlockType.UNLOCKED.color, turn);
		Font titleFont = Skin.bold();
		FontMetrics tm = graphics.getFontMetrics(titleFont);
		FontMetrics sm = graphics.getFontMetrics(FontManager.getRunescapeSmallFont());
		boolean oldSchool = Skin.theme() == Theme.OLD_SCHOOL;
		int width = Math.max(tm.stringWidth(title) + (oldSchool ? 2 * tm.getAscent() : 0),
			Math.max(sm.stringWidth(dropFrom), sm.stringWidth("Unlocked!"))) + PAD_X * 2;
		int height = tm.getAscent() + sm.getHeight() + PAD_Y * 2 + (oldSchool ? 6 : 0);
		int top = client.getViewportYOffset() + TOP_MARGIN;
		double fromY = top - (height + TOP_MARGIN) * (1 - dropFromShown);
		double toY = client.getViewportYOffset() + (client.getViewportHeight() - height) / 2.0;
		double fall = bounce(Math.min(1, e / (double) FALL_MS));
		double y = fromY + (toY - fromY) * fall;
		double scale = 1 + (DROP_SCALE - 1) * Math.min(1, e / (double) FALL_MS) + 0.12 * Math.sin(Math.PI * turn);
		double alpha = e < CARDS_AFTER_SWEEP_MS ? 1 : 1 - (e - CARDS_AFTER_SWEEP_MS) / (double) DROP_FADE_MS;
		double flash = turn <= 0.5 ? 0 : 1 - (turn - 0.5) * 2;
		// Scaling the game's pixel font on screen blurs it into chunky blocks. Instead the sign is
		// drawn at twice its size (the font drawn big, not stretched) and then scaled down smoothly.
		int padW = width + 2 * GLOW_PAD;
		int padH = height + 2 * GLOW_PAD;
		java.awt.image.BufferedImage sign = new java.awt.image.BufferedImage(padW * SHARP, padH * SHARP,
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = sign.createGraphics();
		g.scale(SHARP, SHARP);
		paint(g, GLOW_PAD, GLOW_PAD, width, height, 1, flash);
		g.dispose();
		double cx = client.getViewportXOffset() + client.getViewportWidth() / 2.0;
		double cy = y + height / 2.0;
		double w = padW * scale;
		double h = padH * scale;
		Composite before = graphics.getComposite();
		Object interpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
		graphics.drawImage(sign, (int) Math.round(cx - w / 2), (int) Math.round(cy - h / 2), (int) Math.round(w), (int) Math.round(h), null);
		graphics.setComposite(before);
		if (interpolation != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
	}

	/** Falls and bounces twice as it lands, like a sign dropped on a table. */
	private static double bounce(double t)
	{
		if (t < 0.6)
		{
			return t * t / 0.36;
		}
		if (t < 0.85)
		{
			double k = (t - 0.725) / 0.125;
			return 1 - 0.08 * (1 - k * k);
		}
		double k = (t - 0.925) / 0.075;
		return 1 - 0.02 * (1 - k * k);
	}

	/** "Lumbridge (12850)" -> "Lumbridge"; null for regions without a chunk name. */
	private String chunkName(int regionId)
	{
		String full = plugin.getRegionName(regionId);
		if (full == null || full.startsWith("Unknown Region"))
		{
			return null;
		}
		return full.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	private static String statusText(ChunkUnlockType type, String cost)
	{
		switch (type)
		{
			case UNLOCKED:
				return "Unlocked";
			case LOCKED:
				return "Locked";
			case FREE:
			case CHARTER:
				return "Free to unlock";
			case BOSS:
				return "Unlock with 1 Boss Token";
			default:
				return "Unlock for " + cost;
		}
	}

	private static double ease(double t)
	{
		return 1 - Math.pow(1 - t, 3);
	}

	/** {@code flash} (0 to 1) brightens the border for a moment when the chunk is unlocked. */
	private void draw(Graphics2D graphics, double shown, double flash)
	{
		drawSkinned(graphics, shown, flash);
	}

	/**
	 * A sign in the theme's own look: Old School's matches its stone cards (the name in
	 * orange on a sunken strip between two gems in the chunk's colour); the others use the
	 * theme's window frame with the name in its title colour. The status sits under it.
	 */
	private void drawSkinned(Graphics2D graphics, double shown, double flash)
	{
		boolean oldSchool = Skin.theme() == Theme.OLD_SCHOOL;
		Font titleFont = Skin.bold();
		Font subtitleFont = FontManager.getRunescapeSmallFont();
		FontMetrics tm = graphics.getFontMetrics(titleFont);
		FontMetrics sm = graphics.getFontMetrics(subtitleFont);
		// The stone sign has gems either side of the name and a taller title strip.
		int width = Math.max(tm.stringWidth(title) + (oldSchool ? 2 * tm.getAscent() : 0), sm.stringWidth(subtitle)) + PAD_X * 2;
		int height = tm.getAscent() + sm.getHeight() + PAD_Y * 2 + (oldSchool ? 6 : 0);
		int viewX = client.getViewportXOffset();
		int viewWidth = client.getViewportWidth();
		int x = viewX + (viewWidth - width) / 2;
		int shownY = client.getViewportYOffset() + TOP_MARGIN;
		int y = (int) Math.round(shownY - (height + TOP_MARGIN) * (1 - shown));
		paint(graphics, x, y, width, height, shown, flash);
	}

	/** The sign itself at (x, y), {@code alpha} opaque, its border flashing by {@code flash}. */
	private void paint(Graphics2D graphics, int x, int y, int width, int height, double alpha, double flash)
	{
		boolean oldSchool = Skin.theme() == Theme.OLD_SCHOOL;
		Font titleFont = Skin.bold();
		Font subtitleFont = FontManager.getRunescapeSmallFont();
		FontMetrics tm = graphics.getFontMetrics(titleFont);
		FontMetrics sm = graphics.getFontMetrics(subtitleFont);
		Composite previousComposite = graphics.getComposite();
		Object previousAntialias = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		PixelText.crisp(graphics);
		Color status = flash > 0 ? blend(subtitleColor, ChunkUnlockType.UNLOCKED.color, flash) : subtitleColor;
		if (oldSchool)
		{
			OldSchoolCard.banner(graphics, new Rectangle(x, y, width, height), title, titleFont, subtitle, subtitleFont,
				subtitleColor, status);
		}
		else
		{
			Skin.stoneFrame(graphics, new Rectangle(x, y, width, height), 0);
			int titleY = y + PAD_Y + tm.getAscent() - 3;
			graphics.setFont(titleFont);
			Skin.centred(graphics, title, x + width / 2, titleY, Skin.palette().title);
			graphics.setFont(subtitleFont);
			Skin.centred(graphics, subtitle, x + width / 2, titleY + sm.getAscent() + 3, status);
		}
		if (flash > 0)
		{
			graphics.setColor(new Color(255, 255, 0, (int) (220 * flash)));
			graphics.drawRect(x - 2, y - 2, width + 3, height + 3);
		}
		graphics.setComposite(previousComposite);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialias);
	}

	private static Color blend(Color from, Color to, double amount)
	{
		double k = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * k),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * k),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * k));
	}
}
