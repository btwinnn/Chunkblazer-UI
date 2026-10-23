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
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.util.ImageUtil;

/**
 * The Old School theme's cards and chunk banner, in the game's own stone interface: its
 * stone texture and frame, an orange title on the darker stone, white text in a sunken box,
 * and the tier's colour as cut gems.
 */
final class OldSchoolCard
{
	private static final Color OSRS_ORANGE = new Color(255, 152, 31);
	private static final Color OSRS_YELLOW = new Color(255, 255, 0);
	private static final BufferedImage STONE_TILE = image("stone");
	private static final BufferedImage STONE_DARK_TILE = image("stone_dark");
	private static final BufferedImage FRAME = image("frame");

	private OldSchoolCard()
	{
	}

	static void paint(Graphics2D g, Rectangle b, Color accent, String tier, int stars, String name, Font font)
	{
		quality(g);
		stone(g, b, accent, tier, stars, name, font);
	}

	static void paintBack(Graphics2D g, Rectangle b, Color accent, int stars, Image logo)
	{
		quality(g);
		stoneBack(g, b, accent, stars, logo);
	}

	/**
	 * The chunk name banner to match: the stone panel, the name in orange on a sunken strip
	 * of darker stone between two gems in the chunk's colour, and its status under it.
	 * Returns nothing; {@code r} is the whole banner.
	 */
	static void banner(Graphics2D g, Rectangle r, String title, Font titleFont, String status, Font statusFont,
		Color accent, Color statusColor)
	{
		Object antialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		shadow(g, r, r);
		tile(g, r, STONE_TILE, new Color(62, 53, 41));
		if (FRAME != null)
		{
			nine(g, FRAME, r, 6, 1);
		}
		FontMetrics tm = g.getFontMetrics(titleFont);
		Rectangle strip = new Rectangle(r.x + 6, r.y + 6, r.width - 12, tm.getAscent() + 8);
		tile(g, strip, STONE_DARK_TILE, new Color(50, 44, 35));
		sunken(g, strip);
		int gem = Math.max(3, strip.height / 4);
		gem(g, strip.x + gem + 4, (int) strip.getCenterY(), gem, accent);
		gem(g, strip.x + strip.width - gem - 5, (int) strip.getCenterY(), gem, accent);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias);
		PixelText.crisp(g);
		text(g, titleFont, title, strip, OSRS_ORANGE, Color.BLACK);
		Rectangle below = new Rectangle(r.x, strip.y + strip.height, r.width, r.y + r.height - 6 - strip.y - strip.height);
		text(g, statusFont, status, below, statusColor, Color.BLACK);
	}

	/** The game's own stone panels and frame: an orange title on the darker stone, white name in a sunken box. */
	private static void stone(Graphics2D g, Rectangle b, Color accent, String tier, int stars, String name, Font font)
	{
		stonePanel(g, b);
		int pad = px(b, 0.07);
		Rectangle head = new Rectangle(b.x + pad, b.y + pad, b.width - 2 * pad, px(b, 0.2));
		tile(g, head, STONE_DARK_TILE, new Color(50, 44, 35));
		sunken(g, head);
		text(g, PixelText.at(font, b.height * 0.12f), tier + " Task", head, OSRS_ORANGE, Color.BLACK);
		// The tier's colour as a cut gem at each end of the title.
		int gem = px(b, 0.05);
		gem(g, head.x + gem + px(b, 0.02), (int) head.getCenterY(), gem, accent);
		gem(g, head.x + head.width - gem - px(b, 0.02), (int) head.getCenterY(), gem, accent);
		Rectangle body = new Rectangle(head.x, head.y + head.height + px(b, 0.05), head.width,
			b.y + b.height - pad - head.y - head.height - px(b, 0.05) - px(b, 0.1));
		g.setColor(new Color(0, 0, 0, 60));
		g.fill(body);
		sunken(g, body);
		name(g, inset(body, px(b, 0.03)), name, font, Color.WHITE, Color.BLACK);
		starsRow(g, b.getCenterX(), b.y + b.height - pad - px(b, 0.045), px(b, 0.04), stars, OSRS_YELLOW, Color.BLACK);
	}

	private static void stoneBack(Graphics2D g, Rectangle b, Color accent, int stars, Image logo)
	{
		stonePanel(g, b);
		int pad = px(b, 0.07);
		Rectangle inner = new Rectangle(b.x + pad, b.y + pad, b.width - 2 * pad, b.height - 2 * pad);
		tile(g, inner, STONE_DARK_TILE, new Color(50, 44, 35));
		sunken(g, inner);
		// The chunk grid, faintly, under the logo.
		Shape clip = g.getClip();
		g.clip(inner);
		g.setColor(new Color(0, 0, 0, 55));
		g.setStroke(new BasicStroke(1f));
		double step = inner.height / 4.0;
		for (double x = inner.x + step; x < inner.x + inner.width; x += step)
		{
			g.drawLine((int) x, inner.y, (int) x, inner.y + inner.height);
		}
		for (double y = inner.y + step; y < inner.y + inner.height; y += step)
		{
			g.drawLine(inner.x, (int) y, inner.x + inner.width, (int) y);
		}
		g.setClip(clip);
		int gem = px(b, 0.045);
		gem(g, inner.x + gem + 3, inner.y + gem + 3, gem, accent);
		gem(g, inner.x + inner.width - gem - 4, inner.y + gem + 3, gem, accent);
		gem(g, inner.x + gem + 3, inner.y + inner.height - gem - 4, gem, accent);
		gem(g, inner.x + inner.width - gem - 4, inner.y + inner.height - gem - 4, gem, accent);
		logo(g, b, logo, 0.7, false);
	}

	private static void stonePanel(Graphics2D g, Rectangle b)
	{
		shadow(g, b, b);
		tile(g, b, STONE_TILE, new Color(62, 53, 41));
		// The game's frame, drawn twice its size so it reads at card scale.
		if (FRAME != null)
		{
			nine(g, FRAME, b, 6, 2);
		}
		else
		{
			g.setColor(Color.BLACK);
			g.drawRect(b.x, b.y, b.width - 1, b.height - 1);
		}
	}

	/** A small cut gem: lit facet top-left, shaded bottom-right. */
	private static void gem(Graphics2D g, int cx, int cy, int r, Color c)
	{
		Polygon outline = new Polygon(new int[]{cx, cx + r, cx, cx - r}, new int[]{cy - r, cy, cy + r, cy}, 4);
		g.setColor(darker(c, 0.45));
		g.fill(outline);
		g.setColor(brighter(c, 0.25));
		g.fill(new Polygon(new int[]{cx, cx, cx - r}, new int[]{cy - r, cy, cy}, 3));
		g.setColor(c);
		g.fill(new Polygon(new int[]{cx, cx + r, cx}, new int[]{cy - r, cy, cy}, 3));
		g.setColor(darker(c, 0.7));
		g.fill(new Polygon(new int[]{cx - r, cx, cx}, new int[]{cy, cy, cy + r}, 3));
		g.setColor(new Color(255, 255, 255, 170));
		g.fillOval(cx - r / 2, cy - r / 2 - 1, Math.max(2, r / 3), Math.max(2, r / 3));
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1f));
		g.draw(outline);
	}

	private static int px(Rectangle b, double fraction)
	{
		return (int) Math.round(b.height * fraction);
	}

	private static Rectangle inset(Rectangle r, int d)
	{
		return new Rectangle(r.x + d, r.y + d, r.width - 2 * d, r.height - 2 * d);
	}

	private static void shadow(Graphics2D g, Shape s, Rectangle b)
	{
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
		g.setColor(Color.BLACK);
		g.translate(3, 3);
		g.fill(s);
		g.translate(-3, -3);
		g.setComposite(old);
	}

	private static void tile(Graphics2D g, Rectangle r, BufferedImage img, Color fallback)
	{
		if (img == null)
		{
			g.setColor(fallback);
		}
		else
		{
			g.setPaint(new TexturePaint(img, new Rectangle(r.x, r.y, img.getWidth(), img.getHeight())));
		}
		g.fill(r);
	}

	private static void sunken(Graphics2D g, Rectangle r)
	{
		g.setStroke(new BasicStroke(1f));
		g.setColor(Color.BLACK);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setColor(new Color(0, 0, 0, 90));
		g.drawLine(r.x + 1, r.y + 1, r.x + r.width - 2, r.y + 1);
		g.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + r.height - 2);
		g.setColor(new Color(255, 255, 255, 30));
		g.drawLine(r.x + 1, r.y + r.height - 2, r.x + r.width - 2, r.y + r.height - 2);
		g.drawLine(r.x + r.width - 2, r.y + 1, r.x + r.width - 2, r.y + r.height - 2);
	}

	/** An image's border round {@code r} at {@code scale}: corners as they are, edges stretched. */
	private static void nine(Graphics2D g, BufferedImage img, Rectangle r, int e, int scale)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int de = e * scale;
		int[] dx = {r.x, r.x + de, r.x + r.width - de, r.x + r.width};
		int[] dy = {r.y, r.y + de, r.y + r.height - de, r.y + r.height};
		int[] sx = {0, e, w - e, w};
		int[] sy = {0, e, h - e, h};
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (int i = 0; i < 3; i++)
		{
			for (int j = 0; j < 3; j++)
			{
				if (i == 1 && j == 1)
				{
					continue;
				}
				g.drawImage(img, dx[i], dy[j], dx[i + 1], dy[j + 1], sx[i], sy[j], sx[i + 1], sy[j + 1], null);
			}
		}
		if (interpolation != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
	}

	/** The logo centred, {@code height} of the card tall; {@code stamped} prints it faded, like ink on paper. */
	private static void logo(Graphics2D g, Rectangle b, Image logo, double height, boolean stamped)
	{
		if (logo == null || logo.getWidth(null) <= 0)
		{
			return;
		}
		int lw = logo.getWidth(null);
		int lh = logo.getHeight(null);
		int h = (int) Math.round(b.height * height);
		int w = (int) Math.round(h * lw / (double) lh);
		if (w > b.width * 0.8)
		{
			w = (int) (b.width * 0.8);
			h = (int) Math.round(w * lh / (double) lw);
		}
		int x = (int) Math.round(b.getCenterX() - w / 2.0);
		int y = (int) Math.round(b.getCenterY() - h / 2.0);
		Composite old = g.getComposite();
		if (stamped)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
		}
		else
		{
			g.setPaint(new RadialGradientPaint((float) b.getCenterX(), (float) b.getCenterY(), w * 0.55f,
				new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 100), new Color(0, 0, 0, 0)}));
			g.fill(new Ellipse2D.Double(b.getCenterX() - w * 0.55, b.getCenterY() - h * 0.5, w * 1.1, h * 1.1));
		}
		g.drawImage(logo, x, y, w, h, null);
		g.setComposite(old);
	}

	/** {@code count} small five-point stars in a row centred on (cx, cy). */
	private static void starsRow(Graphics2D g, double cx, double cy, double r, int count, Color fill, Color outline)
	{
		double gap = r * 2.3;
		for (int i = 0; i < count; i++)
		{
			double x = cx + (i - (count - 1) / 2.0) * gap;
			Path2D star = new Path2D.Double();
			for (int k = 0; k < 10; k++)
			{
				double a = -Math.PI / 2 + k * Math.PI / 5;
				double rr = k % 2 == 0 ? r : r * 0.45;
				double px = x + Math.cos(a) * rr;
				double py = cy + Math.sin(a) * rr;
				if (k == 0)
				{
					star.moveTo(px, py);
				}
				else
				{
					star.lineTo(px, py);
				}
			}
			star.closePath();
			g.setColor(fill);
			g.fill(star);
			if (outline != null)
			{
				g.setColor(outline);
				g.setStroke(new BasicStroke(1f));
				g.draw(star);
			}
		}
	}

	private static void text(Graphics2D g, Font font, String s, Rectangle r, Color color, Color shadow)
	{
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int x = r.x + (r.width - fm.stringWidth(s)) / 2;
		int y = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
		if (shadow != null)
		{
			g.setColor(shadow);
			g.drawString(s, x + 1, y + 1);
		}
		g.setColor(color);
		g.drawString(s, x, y);
	}

	/** The task's name, as big as fits in up to three lines, centred. Leaves the font set. */
	private static void name(Graphics2D g, Rectangle panel, String text, Font font, Color color, Color shadow)
	{
		List<String> lines = fit(g, panel, text, font);
		FontMetrics fm = g.getFontMetrics();
		int y = panel.y + (panel.height - lines.size() * fm.getHeight()) / 2 + fm.getAscent();
		for (String line : lines)
		{
			int x = panel.x + (panel.width - fm.stringWidth(line)) / 2;
			if (shadow != null)
			{
				g.setColor(shadow);
				// The drop shadow grows with the text: a pixel per 16.
				int d = Math.max(1, Math.round(g.getFont().getSize2D() / 16f));
				g.drawString(line, x + d, y + d);
			}
			g.setColor(color);
			g.drawString(line, x, y);
			y += fm.getHeight();
		}
	}

	private static List<String> fit(Graphics2D g, Rectangle panel, String text, Font font)
	{
		List<String> lines = new ArrayList<>();
		for (float size : PixelText.sizes(panel.height * 0.45f))
		{
			g.setFont(font.deriveFont(size));
			FontMetrics fm = g.getFontMetrics();
			lines = wrap(fm, text, panel.width);
			int widest = 0;
			for (String line : lines)
			{
				widest = Math.max(widest, fm.stringWidth(line));
			}
			if (lines.size() <= 3 && lines.size() * fm.getHeight() <= panel.height && widest <= panel.width)
			{
				break;
			}
		}
		return lines;
	}

	private static List<String> wrap(FontMetrics fm, String text, int width)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : (text == null ? "" : text).split(" "))
		{
			if (line.length() > 0 && fm.stringWidth(line + " " + word) > width)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			line.append(line.length() == 0 ? "" : " ").append(word);
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	private static Color darker(Color c, double k)
	{
		return new Color((int) (c.getRed() * k), (int) (c.getGreen() * k), (int) (c.getBlue() * k), c.getAlpha());
	}

	private static Color brighter(Color c, double k)
	{
		return new Color((int) (c.getRed() + (255 - c.getRed()) * k), (int) (c.getGreen() + (255 - c.getGreen()) * k),
			(int) (c.getBlue() + (255 - c.getBlue()) * k), c.getAlpha());
	}

	private static BufferedImage image(String name)
	{
		try
		{
			return ImageUtil.loadImageResource(com.chunkblazerui.Skin.class, "ui/" + name + ".png");
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static void quality(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		PixelText.crisp(g);
	}
}
