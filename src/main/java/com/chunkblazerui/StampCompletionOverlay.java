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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;

/**
 * The task complete popup for the Old School and Varlamore themes: the task's card, in the
 * theme's design, drops in face down near the top of the screen, flips over with a glow in
 * the tier's colour, then gets stamped COMPLETE; the card jolts and sparks fly as the stamp
 * lands. The points and chunk sit under it. ChunkBlazer's own theme (and the core plugin)
 * keep the original popup, TaskCompletionAnimationOverlay.
 */
@Singleton
public class StampCompletionOverlay extends Overlay
{
	public static final long TOTAL = 5000;
	private static final BufferedImage LOGO = ImageUtil.loadImageResource(CardArt.class, "Task_Cards/logo.png");
	private static final Color GOLD = new Color(255, 200, 100);
	private static final Color INK = new Color(200, 40, 20);
	private static final Color INK_TEXT = new Color(225, 55, 25);
	private static final int CARD_WIDTH = 200;
	/** The stamp and its sparks were sized for a card this wide: scaled with the card. */
	private static final double DESIGN_WIDTH = 300.0;
	/** How many times finer than shown the popup's card is painted. */
	private static final int DETAIL = 3;
	private static final double ASPECT = 200.0 / 356.0;

	private final ChunkBlazerCore core;
	private final Client client;
	private final CardSounds sounds;
	private volatile long startTime = -1;
	// Tasks completed while a popup showed: {task, points, region}, each shown after.
	private final java.util.Deque<Object[]> queue = new java.util.ArrayDeque<>();
	private static final int QUEUE_MAX = 10;
	// A test from the task window's Settings: shown even with the popup setting off.
	private boolean test;
	// This popup's card, back and face, drawn once (null until the first frame).
	private BufferedImage back;
	private BufferedImage face;
	private String taskName = "";
	private String detail = "";
	private TaskCardTier tier = TaskCardTier.EASY;

	@Inject
	StampCompletionOverlay(ChunkBlazerCore core, Client client, CardSounds sounds)
	{
		this.core = core;
		this.client = client;
		this.sounds = sounds;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGH);
	}

	/**
	 * A task completed: its popup, or if one's still showing, after it (each plays out in
	 * turn, so several completing at once are all seen and heard).
	 */
	public void showTaskCompletion(NuzlockeTask task, int points, String region)
	{
		if (task == null || !core.showTaskCompletionPopup())
		{
			return;
		}
		synchronized (queue)
		{
			if (startTime != -1 && System.currentTimeMillis() - startTime <= TOTAL)
			{
				if (queue.size() < QUEUE_MAX)
				{
					queue.add(new Object[]{task, points, region});
				}
				return;
			}
		}
		start(task, points, region);
	}

	/** A popup up, or one waiting its turn. */
	boolean isShowing()
	{
		long start = startTime;
		if (start != -1 && System.currentTimeMillis() - start <= TOTAL)
		{
			return true;
		}
		synchronized (queue)
		{
			return !queue.isEmpty();
		}
	}

	private void start(NuzlockeTask task, int points, String region)
	{
		int awarded = points > 0 ? points : task.getBasePoints();
		String chunk = region == null ? "" : region.replaceAll("\\s*\\(.*", "").trim();
		taskName = task.getName() == null ? "" : task.getName();
		tier = TaskCardTier.fromTask(task);
		detail = "+" + awarded + (awarded == 1 ? " point" : " points") + (chunk.isEmpty() ? "" : "  ·  " + chunk);
		test = false;
		back = null;
		face = null;
		startTime = System.currentTimeMillis();
		sounds.complete(tier.ordinal());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (startTime == -1 || !test && !core.showTaskCompletionPopup())
		{
			return null;
		}
		long t = System.currentTimeMillis() - startTime;
		if (t > TOTAL)
		{
			startTime = -1;
			// The next task that completed while this one showed.
			Object[] next;
			synchronized (queue)
			{
				next = queue.poll();
			}
			if (next != null)
			{
				start((NuzlockeTask) next[0], (Integer) next[1], (String) next[2]);
				test = next.length > 3;
			}
			return null;
		}
		Graphics2D g = (Graphics2D) graphics.create();
		Skin.smooth(g);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// Fades in over 0.3s and out over the last 0.5s.
		float a = clamp(Math.min(t / 300f, (TOTAL - t) / 500f));
		alpha(g, a);
		Theme design = Skin.theme() == Theme.ROYAL ? Theme.ROYAL : Theme.OLD_SCHOOL;
		Color accent = tier.getAccent();
		Font bold = FontManager.getRunescapeBoldFont();
		int cw = CARD_WIDTH;
		int ch = (int) Math.round(cw * ASPECT);
		int jolt = t > 1220 ? (int) (4 * Math.sin(t / 12.0) * (1 - ease(t, 1220, 250))) : 0;
		// Centred over the game view, near its top: in the fixed-size client that's the game
		// window itself, not the whole client with the chatbox and side panel.
		int x = client.getViewportXOffset() + client.getViewportWidth() / 2 + jolt;
		int top = client.isResized() ? client.getCanvasHeight() / 8 : client.getViewportYOffset() + 14;
		int y = top - (int) (40 * (1 - ease(t, 0, 300)));

		// The flip, with a glow in the tier's colour while it's edge-on.
		float flip = ease(t, 300, 450);
		float glow = (float) Math.sin(flip * Math.PI);
		if (glow > 0)
		{
			alpha(g, a * glow);
			g.setPaint(new RadialGradientPaint(x, y + ch / 2f, cw * 0.7f, new float[]{0, 1},
				new Color[]{accent, new Color(accent.getRGB() & 0xFFFFFF, true)}));
			g.fillRect(x - cw, y - ch / 2, cw * 2, ch * 2);
			alpha(g, a);
		}
		// Squashed sideways to turn it: the back until it's edge-on, the face after.
		Graphics2D turn = (Graphics2D) g.create();
		turn.translate(x, y + ch / 2);
		turn.scale(Math.max(0.01, Math.abs(Math.cos(flip * Math.PI))), 1);
		// Drawn once, flat, then squashed as a picture: drawing it straight into the squashed
		// view re-fitted the text every frame, so the lines jumped about as it turned.
		if (back == null || face == null || back.getWidth() != cw * DETAIL)
		{
			back = render(cw, ch, c -> CardFace.paintBack(c, new Rectangle(0, 0, cw, ch), design, accent, tier.ordinal() + 1, LOGO));
			face = render(cw, ch, c -> CardFace.paint(c, new Rectangle(0, 0, cw, ch), design, accent, tier.getDisplayName(),
				tier.ordinal() + 1, taskName, bold));
		}
		turn.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		turn.drawImage(flip < 0.5f ? back : face, -cw / 2, -ch / 2, cw, ch, null);
		turn.dispose();

		// The points and chunk under the card.
		alpha(g, a * ease(t, 750, 250));
		Font small = FontManager.getRunescapeFont();
		PixelText.crisp(g);
		g.setFont(small);
		FontMetrics fm = g.getFontMetrics();
		int dy = y + ch + 20;
		g.setColor(Color.BLACK);
		g.drawString(detail, x - fm.stringWidth(detail) / 2 + 1, dy + 1);
		g.setColor(GOLD);
		g.drawString(detail, x - fm.stringWidth(detail) / 2, dy);

		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// The stamp shrinks down onto the card's lower corner, and the card jolts as it lands.
		float s = ease(t, 1000, 220);
		if (s > 0)
		{
			Graphics2D stamp = (Graphics2D) g.create();
			alpha(stamp, a * s);
			stamp.translate(x + cw / 4, y + (int) (ch * 0.86));
			stamp.rotate(-0.18);
			double k = (2.4 - 1.4 * s) * cw / DESIGN_WIDTH;
			stamp.scale(k, k);
			Font big = bold.deriveFont(32f);
			stamp.setFont(big);
			int sw = stamp.getFontMetrics().stringWidth("COMPLETE");
			stamp.setColor(INK);
			stamp.setStroke(new BasicStroke(3));
			stamp.drawRoundRect(-sw / 2 - 8, -20, sw + 16, 38, 8, 8);
			stamp.setColor(new Color(0, 0, 0, 90));
			stamp.drawString("COMPLETE", -sw / 2 + 1, 11);
			stamp.setColor(INK_TEXT);
			stamp.drawString("COMPLETE", -sw / 2, 10);
			stamp.dispose();
		}
		// Sparks thrown out from where it hit.
		float b = (t - 1220) / 900f;
		for (int i = 0; b > 0 && b < 1 && i < 18; i++)
		{
			double angle = i * 0.35 + i % 3 * 0.1;
			double speed = (50 + i * 37 % 60) * cw / DESIGN_WIDTH;
			alpha(g, a * (1 - b));
			g.setColor(new Color(255, 120 + i * 7, 30));
			g.fillOval(x + cw / 4 + (int) (Math.cos(angle) * speed * b),
				y + (int) (ch * 0.86) + (int) (Math.sin(angle) * speed * b + 40 * b * b), 3, 3);
		}
		g.dispose();
		return null;
	}

	/** A card side drawn into a picture of its own. */
	/** Laid out at w x h, painted DETAIL times finer, so the small card's words stay crisp drawn down to size. */
	private static BufferedImage render(int w, int h, java.util.function.Consumer<Graphics2D> draw)
	{
		BufferedImage image = new BufferedImage(w * DETAIL, h * DETAIL, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		Skin.smooth(g);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.scale(DETAIL, DETAIL);
		draw.accept(g);
		g.dispose();
		return image;
	}

	private static void alpha(Graphics2D g, float a)
	{
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp(a)));
	}

	private static float clamp(float v)
	{
		return Math.max(0f, Math.min(1f, v));
	}

	/** 0 before {@code start}, easing up to 1 over {@code length} ms. */
	private static float ease(long t, long start, long length)
	{
		float p = clamp((t - start) / (float) length);
		return 1 - (1 - p) * (1 - p) * (1 - p);
	}
}
