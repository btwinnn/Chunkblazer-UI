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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * How every ChunkBlazer window, box, button and bar is drawn: the game's own interfaces,
 * after the Leagues task screens. The stone, frames, bevels and icons are images in
 * {@code ui/} (drawn stretched round the edges, nine-slice style), so this is mostly
 * where to put them. The overlays lay themselves out and say what goes where.
 */
public final class Skin
{
	private static final BufferedImage FRAME = image("frame");
	private static final BufferedImage RAISED = image("raised");
	private static final BufferedImage SUNK = image("sunk");
	/** Antialiasing on; returns what it was, for {@link #restore}. */
	public static Object smooth(Graphics2D g)
	{
		Object was = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return was;
	}

	/** Antialiasing back to {@code was}, from {@link #smooth}. */
	public static void restore(Graphics2D g, Object was)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, was == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : was);
	}

	public static final BufferedImage CROSS = image("cross");
	public static final BufferedImage CROSS_HOVER = image("cross_hover");
	private static final Paint OS_STONE = tile("stone");
	private static final Paint OS_STONE_DARK = tile("stone_dark");
	private static Paint STONE = OS_STONE;
	private static Paint STONE_DARK = OS_STONE_DARK;
	private static Color FIELD = new Color(30, 26, 21);
	public static Color FILL = new Color(66, 160, 70);
	private static volatile ThemePalette palette = ThemePalette.original();

	private Skin()
	{
	}

	public static BufferedImage image(String name)
	{
		return ImageUtil.loadImageResource(Skin.class, "ui/" + name + ".png");
	}

	private static Paint tile(String name)
	{
		return new TexturePaint(image(name), new Rectangle(0, 0, 64, 64));
	}

	// Told whenever the palette changes, so overlays can pick up its text colours.
	private static final List<Consumer<ThemePalette>> RETHEME = new CopyOnWriteArrayList<>();

	/**
	 * Switch every window, box, button and bar to {@code p} (ChunkBlazer's own when null), and
	 * the overlays' own text colours with them (they read them each frame). Any thread.
	 * ChunkBlazer UI's Themes makes the palettes for its themes.
	 */
	public static void setPalette(ThemePalette p)
	{
		if (p == null)
		{
			p = ThemePalette.original();
		}
		boolean os = p.theme == Theme.OLD_SCHOOL && !p.log;
		STONE = os ? OS_STONE : p.tile != null ? new TexturePaint(p.tile, new Rectangle(0, 0, 64, 64)) : p.base;
		STONE_DARK = os ? OS_STONE_DARK : p.tileDark != null ? new TexturePaint(p.tileDark, new Rectangle(0, 0, 64, 64)) : p.baseDark;
		FIELD = p.field;
		FILL = p.barFill;
		palette = p;
		for (Consumer<ThemePalette> listener : RETHEME)
		{
			listener.accept(p);
		}
	}

	/** Call {@code listener} with the palette now and whenever it changes. */
	public static void onRetheme(Consumer<ThemePalette> listener)
	{
		RETHEME.add(listener);
		listener.accept(palette);
	}

	public static ThemePalette palette()
	{
		return palette;
	}

	public static Theme theme()
	{
		return palette.theme;
	}

	private static boolean oldSchool()
	{
		return palette.theme == Theme.OLD_SCHOOL && !palette.log;
	}

	// The collection log's colours (Old School): its bronze rim, header band, a row and the tracked row.
	public static final Color LOG_RIM = new Color(88, 74, 54);
	public static final Color LOG_LINE = new Color(60, 50, 38);
	public static final Color LOG_ROW = new Color(50, 43, 33);
	public static final Color LOG_ROW_ON = new Color(70, 56, 34);
	public static final Color LOG_COUNT = new Color(0, 230, 0);

	/**
	 * A number in the middle of {@code box}, in bold (small if too wide), centred by the digits'
	 * own drawn outline rather than the font's box, which sits them a little high.
	 */
	public static void boxed(Graphics2D g, String text, Rectangle box, Color color)
	{
		Font font = bold();
		java.awt.geom.Rectangle2D ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		if (ink.getWidth() > box.width - 4)
		{
			font = small();
			ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		}
		g.setFont(font);
		int x = (int) Math.round(box.getCenterX() - ink.getWidth() / 2 - ink.getX());
		int y = (int) Math.round(box.getCenterY() - ink.getHeight() / 2 - ink.getY());
		text(g, text, x, y, color);
	}

	/** Whether this is Old School's collection log look. */
	public static boolean log()
	{
		return palette.log;
	}

	/** A collection log page edge: black outside, the bronze rim, then a darker line inside it. */
	public static void logRim(Graphics2D g, Rectangle r)
	{
		g.setColor(Color.BLACK);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setColor(LOG_RIM);
		g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
		if (r.width > 12 && r.height > 12)
		{
			g.setColor(LOG_LINE);
			g.drawRect(r.x + 3, r.y + 3, r.width - 7, r.height - 7);
		}
	}

	public static Font bold()
	{
		return FontManager.getRunescapeBoldFont();
	}

	public static Font regular()
	{
		return FontManager.getRunescapeFont();
	}

	public static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	/** Text the game's way: crisp, with a hard black drop shadow. */
	public static void text(Graphics2D g, String s, int x, int y)
	{
		if (s == null || s.isEmpty())
		{
			return;
		}
		Color color = g.getColor();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(new Color(0, 0, 0, color.getAlpha()));
		g.drawString(s, x + 1, y + 1);
		g.setColor(color);
		g.drawString(s, x, y);
	}

	public static void text(Graphics2D g, String s, int x, int y, Color color)
	{
		g.setColor(color);
		text(g, s, x, y);
	}

	/** The theme's material (its darker shade when {@code dark}), as a paint. */
	public static Paint stone(boolean dark)
	{
		return dark ? STONE_DARK : STONE;
	}

	/**
	 * A material tile lined up with {@code r}'s corner, so a window's texture moves with the
	 * window when it's dragged instead of staying put on the screen. Flat colours pass through.
	 */
	private static Paint at(Paint material, Rectangle r)
	{
		if (!(material instanceof TexturePaint))
		{
			return material;
		}
		TexturePaint t = (TexturePaint) material;
		Rectangle anchor = t.getAnchorRect().getBounds();
		return new TexturePaint(t.getImage(), new Rectangle(r.x, r.y, anchor.width, anchor.height));
	}

	/** Text centred on {@code cx}, baseline {@code y}. */
	public static void centred(Graphics2D g, String s, int cx, int y, Color color)
	{
		g.setColor(color);
		text(g, s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
	}

	/** Draws an image's border round {@code r}: corners as they are, edges stretched (its middle is left alone). */
	public static void nine(Graphics2D g, BufferedImage img, Rectangle r, int e)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int[] dx = {r.x, r.x + e, r.x + r.width - e, r.x + r.width};
		int[] dy = {r.y, r.y + e, r.y + r.height - e, r.y + r.height};
		int[] sx = {0, e, w - e, w};
		int[] sy = {0, e, h - e, h};
		for (int i = 0; i < 3; i++)
		{
			for (int j = 0; j < 3; j++)
			{
				if (i != 1 || j != 1)
				{
					g.drawImage(img, dx[i], dy[j], dx[i + 1], dy[j + 1], sx[i], sy[j], sx[i + 1], sy[j + 1], null);
				}
			}
		}
	}

	/**
	 * Varlamore's temple trim on a box: a faint gold line just inside it and bold gold brackets
	 * at its corners. Nothing in the other themes.
	 */
	public static void trim(Graphics2D g, Rectangle r)
	{
		ThemePalette p = palette;
		if (!p.brackets || p.frameInner == null || r.width < 12 || r.height < 10)
		{
			return;
		}
		Color gold = p.frameInner;
		g.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 70));
		g.drawRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5);
		g.setColor(gold);
		int k = Math.max(3, Math.min(6, Math.min(r.width, r.height) / 5));
		int x1 = r.x + 2;
		int y1 = r.y + 2;
		int x2 = r.x + r.width - 3;
		int y2 = r.y + r.height - 3;
		g.fillRect(x1, y1, k, 2);
		g.fillRect(x1, y1, 2, k);
		g.fillRect(x2 - k + 1, y1, k, 2);
		g.fillRect(x2 - 1, y1, 2, k);
		g.fillRect(x1, y2 - 1, k, 2);
		g.fillRect(x1, y2 - k + 1, 2, k);
		g.fillRect(x2 - k + 1, y2 - 1, k, 2);
		g.fillRect(x2 - 1, y2 - k + 1, 2, k);
	}

	/** A small gold sun (Ralos's), centred on (cx, cy). */
	public static void sun(Graphics2D g, int cx, int cy, Color color)
	{
		g.setColor(color);
		g.fillRect(cx - 2, cy - 2, 5, 5);
		g.fillRect(cx - 1, cy - 3, 3, 7);
		g.fillRect(cx - 3, cy - 1, 7, 3);
		g.fillRect(cx, cy - 5, 1, 1);
		g.fillRect(cx, cy + 5, 1, 1);
		g.fillRect(cx - 5, cy, 1, 1);
		g.fillRect(cx + 5, cy, 1, 1);
		g.fillRect(cx - 4, cy - 4, 1, 1);
		g.fillRect(cx + 4, cy - 4, 1, 1);
		g.fillRect(cx - 4, cy + 4, 1, 1);
		g.fillRect(cx + 4, cy + 4, 1, 1);
	}

	/** A stone bevel: raised (light top-left) or sunk, edged in black, like every button and box in the game. */
	public static void bevel(Graphics2D g, Rectangle r, boolean raised)
	{
		if (palette.log)
		{
			// The log's boxes: a bronze rim; sunk ones a black edge with a dark line inside.
			g.setColor(raised ? LOG_RIM : Color.BLACK);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			if (!raised && r.width > 4 && r.height > 4)
			{
				g.setColor(LOG_LINE);
				g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
			}
			return;
		}
		if (oldSchool())
		{
			nine(g, raised ? RAISED : SUNK, r, 2);
			return;
		}
		ThemePalette p = palette;
		if (p.bevelLight != null)
		{
			// Light and dark edges inside a dark outline, the other way round when sunk.
			g.setColor(raised ? p.bevelLight : p.bevelDark);
			g.drawLine(r.x + 1, r.y + 1, r.x + r.width - 2, r.y + 1);
			g.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + r.height - 2);
			g.setColor(raised ? p.bevelDark : p.bevelLight);
			g.drawLine(r.x + 1, r.y + r.height - 2, r.x + r.width - 2, r.y + r.height - 2);
			g.drawLine(r.x + r.width - 2, r.y + 1, r.x + r.width - 2, r.y + r.height - 2);
		}
		g.setColor(raised ? p.edge : p.dividerDark);
		if (p.radius > 0)
		{
			g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, Math.min(p.radius, r.height / 2), Math.min(p.radius, r.height / 2));
		}
		else
		{
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		}
	}

	/** A smaller box (trackers, the quest pane); {@code accent} tints its top edge, or null. */
	public static void panel(Graphics2D g, Rectangle r, int header, Color accent)
	{
		stoneFrame(g, r, header);
		if (accent != null)
		{
			g.setColor(accent);
			g.fillRect(r.x + 4, r.y + 4, r.width - 8, 2);
		}
	}

	/**
	 * Stone inside a black-edged steel band with studs at the corners, a darker header band of
	 * {@code header} pixels. Big frames (the task window, the quest box) are drawn the same every
	 * frame, so once a size has been asked for twice it's drawn once into an image and that's
	 * copied from then on: filling a large area with the stone texture every frame was a good
	 * part of what the open window cost.
	 */
	public static void stoneFrame(Graphics2D g, Rectangle r, int header)
	{
		if (r.width <= 0 || r.height <= 0)
		{
			return;
		}
		String key = r.width + "x" + r.height + ":" + header + ":" + System.identityHashCode(palette);
		BufferedImage image;
		synchronized (FRAMES)
		{
			image = FRAMES.get(key);
			if (image == null && FRAME_ASKED.merge(key, 1, Integer::sum) >= 2)
			{
				FRAME_ASKED.remove(key);
				// A margin all round for the drop shadow and any glow.
				image = new BufferedImage(r.width + FRAME_MARGIN * 2, r.height + FRAME_MARGIN * 2, BufferedImage.TYPE_INT_ARGB);
				Graphics2D ig = image.createGraphics();
				ig.setRenderingHints(g.getRenderingHints());
				drawFrame(ig, new Rectangle(FRAME_MARGIN, FRAME_MARGIN, r.width, r.height), header);
				ig.dispose();
				FRAMES.put(key, image);
			}
			if (FRAME_ASKED.size() > 64)
			{
				FRAME_ASKED.clear();
			}
		}
		if (image != null)
		{
			g.drawImage(image, r.x - FRAME_MARGIN, r.y - FRAME_MARGIN, null);
		}
		else
		{
			drawFrame(g, r, header);
		}
	}

	// Frames drawn once for their size and look, newest kept (see stoneFrame).
	private static final Map<String, BufferedImage> FRAMES = new LinkedHashMap<String, BufferedImage>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
		{
			return size() > 12;
		}
	};
	private static final Map<String, Integer> FRAME_ASKED = new HashMap<>();
	private static final int FRAME_MARGIN = 6;

	private static void drawFrame(Graphics2D g, Rectangle r, int header)
	{
		if (palette.log)
		{
			g.setColor(new Color(0, 0, 0, 110));
			g.fillRect(r.x + 3, r.y + 3, r.width, r.height);
			g.setColor(palette.base);
			g.fillRect(r.x, r.y, r.width, r.height);
			if (header > 0)
			{
				g.setColor(palette.baseDark);
				g.fillRect(r.x + 4, r.y + 4, r.width - 8, header - 4);
				g.setColor(LOG_RIM);
				g.drawLine(r.x + 4, r.y + header, r.x + r.width - 5, r.y + header);
			}
			logRim(g, r);
			return;
		}
		if (!oldSchool())
		{
			themedFrame(g, r, header);
			return;
		}
		Paint old = g.getPaint();
		g.setColor(new Color(0, 0, 0, 110));
		g.fillRect(r.x + 3, r.y + 3, r.width, r.height);
		g.setPaint(at(STONE, r));
		g.fillRect(r.x, r.y, r.width, r.height);
		if (header > 0)
		{
			g.setPaint(at(STONE_DARK, r));
			g.fillRect(r.x + 3, r.y + 3, r.width - 6, header - 3);
			divider(g, r.x + 3, r.y + header, r.x + r.width - 4);
		}
		nine(g, FRAME, r, 6);
		g.setPaint(old);
	}

	/**
	 * A window or box in a theme other than Old School: its material, a darker header band,
	 * then its frame (one or two lines, rounded or not, glowing or with gold flourishes).
	 */
	private static void themedFrame(Graphics2D g, Rectangle r, int header)
	{
		if (palette.hearth)
		{
			hearthFrame(g, r, header);
			return;
		}
		ThemePalette p = palette;
		Paint old = g.getPaint();
		Object aa = smooth(g);
		int rad = p.radius * 2;
		g.setColor(new Color(0, 0, 0, 110));
		g.fillRoundRect(r.x + 3, r.y + 3, r.width, r.height, rad, rad);
		java.awt.Shape clip = g.getClip();
		java.awt.geom.RoundRectangle2D shape = new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, rad, rad);
		g.clip(shape);
		g.setPaint(at(STONE, r));
		g.fillRect(r.x, r.y, r.width, r.height);
		if (header > 0)
		{
			g.setPaint(at(STONE_DARK, r));
			g.fillRect(r.x, r.y, r.width, header);
			divider(g, r.x + 3, r.y + header, r.x + r.width - 4);
		}
		g.setClip(clip);
		if (p.glow)
		{
			for (int i = 3; i >= 1; i--)
			{
				g.setColor(new Color(p.frameOuter.getRed(), p.frameOuter.getGreen(), p.frameOuter.getBlue(), 28));
				g.setStroke(new java.awt.BasicStroke(i * 2f));
				g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, rad, rad);
			}
		}
		g.setStroke(new java.awt.BasicStroke(p.frameWidth));
		g.setColor(p.frameOuter);
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, rad, rad);
		if (p.frameInner != null)
		{
			int in = p.frameWidth + 1;
			g.setStroke(new java.awt.BasicStroke(1f));
			// The temple look keeps the inner line faint and lets its corner brackets carry the gold.
			g.setColor(p.brackets ? new Color(p.frameInner.getRed(), p.frameInner.getGreen(), p.frameInner.getBlue(), 80) : p.frameInner);
			g.drawRoundRect(r.x + in, r.y + in, r.width - 1 - 2 * in, r.height - 1 - 2 * in, Math.max(0, rad - in), Math.max(0, rad - in));
		}
		if (p.brackets)
		{
			g.setStroke(new java.awt.BasicStroke(1f));
			trim(g, new Rectangle(r.x + p.frameWidth - 1, r.y + p.frameWidth - 1, r.width - 2 * p.frameWidth + 2, r.height - 2 * p.frameWidth + 2));
		}
		else if (p.flourishes)
		{
			// Little gold L-shapes at each corner.
			int c = Math.min(8, Math.min(r.width, r.height) / 8);
			int m = p.frameWidth + 2;
			g.setColor(p.frameInner);
			int[][] corners = {{r.x + m, r.y + m, 1, 1}, {r.x + r.width - 1 - m, r.y + m, -1, 1},
				{r.x + m, r.y + r.height - 1 - m, 1, -1}, {r.x + r.width - 1 - m, r.y + r.height - 1 - m, -1, -1}};
			for (int[] k : corners)
			{
				g.drawLine(k[0], k[1], k[0] + k[2] * c, k[1]);
				g.drawLine(k[0], k[1], k[0], k[1] + k[3] * c);
			}
		}
		g.setStroke(new java.awt.BasicStroke(1f));
		restore(g, aa);
		g.setPaint(old);
	}

	// --- ChunkBlazer's hearth look ---------------------------------------------------------

	private static final Color HEARTH_TOP = new Color(62, 46, 34);
	private static final Color HEARTH_BOTTOM = new Color(30, 25, 21);
	private static final Color HEARTH_EDGE = new Color(14, 11, 9);
	private static final Color HEARTH_BRONZE = new Color(120, 82, 48, 160);
	public static final Color HEARTH_GLOW = new Color(255, 120, 30);

	/** A soft shadow (or a glow, in {@code color}) round a rounded box, {@code spread} pixels out. */
	public static void haze(Graphics2D g, Rectangle r, int radius, Color color, int alpha, int spread, int dy)
	{
		for (int i = spread; i >= 1; i--)
		{
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha / (i + 1)));
			g.fillRoundRect(r.x - i, r.y - i + dy, r.width + 2 * i, r.height + 2 * i, (radius + i) * 2, (radius + i) * 2);
		}
	}

	/** A rounded box filled top to bottom from {@code top} to {@code bottom}, edged dark, with light catching its top edge. */
	public static void lit(Graphics2D g, Rectangle r, int radius, Color top, Color bottom, Color edge)
	{
		Object aa = smooth(g);
		Paint old = g.getPaint();
		g.setPaint(new java.awt.GradientPaint(0, r.y, top, 0, r.y + r.height, bottom));
		g.fillRoundRect(r.x, r.y, r.width, r.height, radius * 2, radius * 2);
		g.setPaint(old);
		g.setColor(edge);
		g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, radius * 2, radius * 2);
		g.setColor(new Color(255, 255, 255, 40));
		g.drawLine(r.x + radius, r.y + 1, r.x + r.width - 1 - radius, r.y + 1);
		restore(g, aa);
	}

	/** A window or box by firelight: warmer toward the top, a soft shadow, a bronze line inside a dark edge. */
	private static void hearthFrame(Graphics2D g, Rectangle r, int header)
	{
		Object aa = smooth(g);
		haze(g, r, 8, Color.BLACK, 150, 5, 3);
		lit(g, r, 8, HEARTH_TOP, HEARTH_BOTTOM, HEARTH_EDGE);
		// The fire's glow on the top of the window.
		Paint old = g.getPaint();
		float glowRadius = Math.max(60, r.width / 3f);
		g.setPaint(new java.awt.RadialGradientPaint(r.x + r.width / 2f, r.y - 20, glowRadius, new float[]{0, 1},
			new Color[]{new Color(255, 140, 40, 46), new Color(255, 140, 40, 0)}));
		java.awt.Shape clip = g.getClip();
		g.clip(new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 16, 16));
		g.fillRect(r.x, r.y, r.width, (int) Math.min(r.height, glowRadius + 20));
		g.setClip(clip);
		g.setPaint(old);
		g.setColor(HEARTH_BRONZE);
		g.drawRoundRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3, 14, 14);
		if (header > 0)
		{
			g.setColor(new Color(255, 150, 60, 70));
			g.drawLine(r.x + 10, r.y + header, r.x + r.width - 11, r.y + header);
		}
		restore(g, aa);
	}

	/** A horizontal groove. */
	public static void divider(Graphics2D g, int x1, int y, int x2)
	{
		ThemePalette p = palette;
		g.setColor(p.dividerDark);
		g.drawLine(x1, y, x2, y);
		g.setColor(p.dividerLight);
		g.drawLine(x1, y + 1, x2, y + 1);
	}

	/** A stone button's face (the caller draws its label). {@code active}: pressed or chosen. */
	public static void button(Graphics2D g, Rectangle r, boolean hover, boolean active)
	{
		if (palette.hearth)
		{
			if (active)
			{
				haze(g, r, 5, HEARTH_GLOW, 90, 3, 0);
				lit(g, r, 5, new Color(120, 70, 30), new Color(80, 46, 22), new Color(255, 160, 70));
			}
			else
			{
				haze(g, r, 5, Color.BLACK, 90, 1, 1);
				lit(g, r, 5, hover ? new Color(100, 78, 56) : new Color(86, 66, 48), new Color(58, 44, 33), HEARTH_EDGE);
			}
			return;
		}
		if (palette.log)
		{
			g.setColor(active ? palette.buttonActive : palette.button);
			g.fillRect(r.x, r.y, r.width, r.height);
			if (hover)
			{
				g.setColor(new Color(255, 255, 255, 22));
				g.fillRect(r.x, r.y, r.width, r.height);
			}
			g.setColor(active ? palette.title : LOG_RIM);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			return;
		}
		if (oldSchool())
		{
			g.setPaint(at(STONE, r));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(active ? new Color(150, 40, 20, 110) : new Color(255, 255, 255, hover ? 28 : 0));
			g.fillRect(r.x, r.y, r.width, r.height);
			bevel(g, r, !active);
			return;
		}
		ThemePalette p = palette;
		Object aa = smooth(g);
		int rad = Math.min(p.radius, r.height / 2) * 2;
		g.setColor(active ? p.buttonActive : p.button);
		g.fillRoundRect(r.x, r.y, r.width, r.height, rad, rad);
		if (hover)
		{
			g.setColor(new Color(255, 255, 255, 26));
			g.fillRoundRect(r.x, r.y, r.width, r.height, rad, rad);
		}
		restore(g, aa);
		bevel(g, r, !active);
		if (active && p.glow)
		{
			g.setColor(p.frameOuter);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		}
	}

	/**
	 * A progress bar filled to {@code fraction}: a dark groove with a soft green fill (or
	 * {@code color} when {@code tinted}, for a countdown). {@code glow} (0 to 1) rims it in yellow.
	 */
	public static void bar(Graphics2D g, int x, int y, int width, int height, float fraction, Color color, float glow, boolean tinted)
	{
		int fill = Math.round(width * Math.max(0, Math.min(1, fraction)));
		if (palette.hearth && !tinted)
		{
			// The hearth: an ember-orange fill in a dark groove.
			g.setColor(Color.BLACK);
			g.fillRect(x - 1, y - 1, width + 2, height + 2);
			g.setColor(new Color(24, 20, 16));
			g.fillRect(x, y, width, height);
			if (fill > 0)
			{
				Paint old = g.getPaint();
				g.setPaint(new java.awt.GradientPaint(0, y, new Color(255, 190, 80), 0, y + height, new Color(220, 90, 20)));
				g.fillRect(x, y, fill, height);
				g.setPaint(old);
			}
			if (glow > 0)
			{
				g.setColor(new Color(255, 255, 0, Math.round(200 * glow)));
				g.drawRect(x - 1, y - 1, width + 1, height + 1);
			}
			return;
		}
		g.setColor(Color.BLACK);
		g.fillRect(x - 1, y - 1, width + 2, height + 2);
		g.setColor(oldSchool() ? new Color(40, 34, 27) : palette.field);
		g.fillRect(x, y, width, height);
		g.setColor(tinted ? color : FILL);
		g.fillRect(x, y, fill, height);
		g.setColor(new Color(255, 255, 255, 60));
		g.fillRect(x, y, fill, Math.max(1, height / 4));
		if (glow > 0)
		{
			g.setColor(new Color(255, 255, 0, Math.round(200 * glow)));
			g.drawRect(x - 1, y - 1, width + 1, height + 1);
		}
	}

	/** A scrollbar down the right of {@code area}: a sunk track and a raised thumb. */
	public static void scrollbar(Graphics2D g, Rectangle area, int thumbY, int thumbHeight, boolean glow)
	{
		Rectangle track = new Rectangle(area.x + area.width - 8, area.y + 1, 7, area.height - 2);
		Rectangle thumb = new Rectangle(track.x, Math.max(track.y, thumbY), track.width, thumbHeight);
		if (palette.brackets)
		{
			// Varlamore: a gold thumb in a dark groove.
			g.setColor(new Color(30, 22, 16));
			g.fillRect(track.x, track.y, track.width, track.height);
			g.setColor(glow ? new Color(255, 236, 160) : palette.frameInner);
			g.fillRect(thumb.x + 1, thumb.y, thumb.width - 2, thumb.height);
			g.setColor(new Color(240, 220, 150));
			g.drawLine(thumb.x + 2, thumb.y + 1, thumb.x + 2, thumb.y + thumb.height - 2);
		}
		else
		{
			g.setColor(FIELD);
			g.fillRect(track.x, track.y, track.width, track.height);
			button(g, thumb, glow, false);
		}
		if (glow)
		{
			// A soft halo round the thumb, fading out.
			for (int i = 1; i <= 3; i++)
			{
				g.setColor(new Color(255, 220, 90, 150 / (i * i)));
				g.drawRect(thumb.x - i, thumb.y - i, thumb.width - 1 + 2 * i, thumb.height - 1 + 2 * i);
			}
		}
	}

	/** A hint by the mouse: the game's yellow examine box. */
	public static void tooltip(Graphics2D g, Rectangle box, String text)
	{
		g.setColor(new Color(255, 255, 160));
		g.fillRect(box.x, box.y, box.width, box.height);
		g.setColor(Color.BLACK);
		g.drawRect(box.x, box.y, box.width, box.height);
		g.drawString(text, box.x + 5, box.y + box.height - 4);
	}

	/** The game's close button: a stone square with a gold cross. */
	public static void close(Graphics2D g, Rectangle r, boolean hover)
	{
		button(g, r, hover, false);
		g.drawImage(hover ? CROSS_HOVER : CROSS, r.x + (r.width - 11) / 2, r.y + (r.height - 11) / 2, null);
	}

	/** Shorten text with "..." so it fits the width. */
	public static String fit(FontMetrics fm, String text, int width)
	{
		if (text == null || width <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + "...") > width)
		{
			end--;
		}
		return text.substring(0, end) + "...";
	}

	static
	{
		// ChunkBlazer's own look until ChunkBlazer UI (if installed) applies the chosen Theme.
		setPalette(ThemePalette.original());
	}
}
