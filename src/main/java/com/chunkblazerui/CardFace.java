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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Task cards drawn in code, faces and matching backs, one look per {@link Theme}
 * (Original is btwinnn's art, drawn by CardArt). Faces show just the task's name, the tier
 * by colour and label; backs carry the ChunkBlazer logo.
 * Plain AWT only, so the looks can be previewed outside the client.
 */
final class CardFace
{
	// From the Varlamore crest.
	private static final Color SAND_EDGE = new Color(53, 38, 32);
	private static final Color SUN_GOLD = new Color(211, 174, 84);
	private static final Color SUN_HEART = new Color(157, 114, 40);
	private static final Color SUN_RAY = new Color(176, 104, 88);

	private CardFace()
	{
	}

	/**
	 * Paint a face in {@code b}. {@code accent} is the tier's colour, {@code tierName} its
	 * label ("Easy"), {@code stars} 1 to 5 for the tier. {@code font} is the base font, sized here.
	 */
	static void paint(Graphics2D g, Rectangle b, Theme design, Color accent, String tierName, int stars,
		String name, Font font)
	{
		Skin.smooth(g);
		PixelText.crisp(g);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		switch (design)
		{
			case ROYAL:
				royal(g, b, accent, tierName, stars, name, font);
				break;
			case OLD_SCHOOL:
			default:
				OldSchoolCard.paint(g, b, accent, tierName, stars, name, font);
				break;
		}
	}

	/**
	 * Royal: a tablet of the Varlamore crest's carved sandstone with cut corners, a dark band
	 * for the title in gold, and the crest's sun of Ralos with the tier's colour at its heart.
	 */
	private static void royal(Graphics2D g, Rectangle b, Color accent, String tier, int stars, String name, Font font)
	{
		Shape card = cutCorners(b, s(b, 0.05));
		sandstoneFill(g, b, card);
		g.setStroke(new BasicStroke(Math.max(2f, b.width * 0.01f)));
		g.setColor(SAND_EDGE);
		g.draw(card);
		g.setStroke(new BasicStroke(Math.max(1f, b.width * 0.004f)));
		g.setColor(SUN_GOLD);
		g.draw(cutCorners(new Rectangle(b.x + s(b, 0.025), b.y + s(b, 0.025), b.width - 2 * s(b, 0.025), b.height - 2 * s(b, 0.025)),
			s(b, 0.04)));
		Rectangle bar = new Rectangle(b.x + s(b, 0.1), b.y + s(b, 0.08), b.width - 2 * s(b, 0.1), (int) (b.height * 0.17));
		inset(g, bar, new Color(60, 44, 34), SAND_EDGE, new Color(150, 124, 95));
		centredText(g, PixelText.at(font, b.height * 0.1f), tier + " Task", bar, SUN_GOLD, Color.BLACK);
		Rectangle panel = new Rectangle(b.x + s(b, 0.1), (int) (b.y + b.height * 0.32), b.width - 2 * s(b, 0.1), (int) (b.height * 0.42));
		inset(g, panel, new Color(66, 50, 38, 225), SAND_EDGE, new Color(150, 124, 95));
		name(g, panel, name, font, 0.13f, Color.WHITE, Color.BLACK);
		ralosSun(g, b.getCenterX(), b.y + b.height * 0.86, s(b, 0.065), accent);
	}

	/**
	 * Paint a card's back in {@code b}, matching its face design, with the ChunkBlazer
	 * {@code logo} in the middle. {@code accent} and {@code stars} are the tier's.
	 */
	static void paintBack(Graphics2D g, Rectangle b, Theme design, Color accent, int stars, Image logo)
	{
		Skin.smooth(g);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		double logoHeight = 0.8;
		switch (design)
		{
			case ROYAL:
			{
				Shape card = cutCorners(b, s(b, 0.05));
				sandstoneFill(g, b, card);
				ralosSun(g, b.getCenterX(), b.getCenterY(), b.height * 0.46, accent);
				g.setStroke(new BasicStroke(Math.max(2f, b.width * 0.01f)));
				g.setColor(SAND_EDGE);
				g.draw(card);
				g.setStroke(new BasicStroke(Math.max(1f, b.width * 0.004f)));
				g.setColor(SUN_GOLD);
				g.draw(cutCorners(new Rectangle(b.x + s(b, 0.025), b.y + s(b, 0.025), b.width - 2 * s(b, 0.025),
					b.height - 2 * s(b, 0.025)), s(b, 0.04)));
				logoHeight = 0.7;
				break;
			}
			case OLD_SCHOOL:
			default:
				OldSchoolCard.paintBack(g, b, accent, stars, logo);
				return;
		}
		logo(g, b, logo, logoHeight);
	}

	/** The logo centred, {@code height} of the card tall, with a soft shadow. */
	private static void logo(Graphics2D g, Rectangle b, Image logo, double height)
	{
		if (logo == null)
		{
			return;
		}
		int lw = logo.getWidth(null);
		int lh = logo.getHeight(null);
		if (lw <= 0 || lh <= 0)
		{
			return;
		}
		int h = (int) Math.round(b.height * height);
		int w = (int) Math.round(h * lw / (double) lh);
		if (w > b.width * 0.86)
		{
			w = (int) (b.width * 0.86);
			h = (int) Math.round(w * lh / (double) lw);
		}
		int x = (int) Math.round(b.getCenterX() - w / 2.0);
		int y = (int) Math.round(b.getCenterY() - h / 2.0);
		g.setPaint(new RadialGradientPaint((float) b.getCenterX(), (float) b.getCenterY() + h * 0.05f, w * 0.55f,
			new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 110), new Color(0, 0, 0, 0)}));
		g.fill(new Ellipse2D.Double(b.getCenterX() - w * 0.55, b.getCenterY() - h * 0.5, w * 1.1, h * 1.1));
		g.drawImage(logo, x, y, w, h, null);
	}

	// --- Text --------------------------------------------------------------------

	/** The task's name, as big as fits the panel in up to three lines, centred. */
	private static void name(Graphics2D g, Rectangle panel, String text, Font font, float size, Color color, Color shadow)
	{
		List<String> lines = null;
		FontMetrics fm = null;
		for (float px : PixelText.sizes(Math.max(panel.height * size * 2.2f, panel.height * 0.5f)))
		{
			g.setFont(font.deriveFont(px));
			fm = g.getFontMetrics();
			lines = wrap(fm, text, panel.width);
			if (lines.size() <= 3 && lines.size() * fm.getHeight() <= panel.height && widest(fm, lines) <= panel.width)
			{
				break;
			}
		}
		PixelText.centred(g, panel, text, g.getFont(), color, shadow, (m, w) -> wrap(m, text, w));
	}

	private static void centredText(Graphics2D g, Font font, String text, Rectangle r, Color color, Color shadow)
	{
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		String t = text;
		while (t.length() > 1 && fm.stringWidth(t) > r.width - 4)
		{
			t = t.substring(0, t.length() - 1);
		}
		int x = r.x + (r.width - fm.stringWidth(t)) / 2;
		int y = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
		if (shadow != null)
		{
			g.setColor(shadow);
			g.drawString(t, x + 1, y + 1);
		}
		g.setColor(color);
		g.drawString(t, x, y);
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

	private static int widest(FontMetrics fm, List<String> lines)
	{
		int w = 0;
		for (String l : lines)
		{
			w = Math.max(w, fm.stringWidth(l));
		}
		return w;
	}

	// --- Shapes and colour ----------------------------------------------------------

	/** {@code f} of the card's width, in pixels. */
	private static int s(Rectangle b, double f)
	{
		return (int) Math.round(b.width * f);
	}

	/** A recessed box: fill, dark top-left edge, light bottom-right edge. */
	private static void inset(Graphics2D g, Rectangle r, Color fill, Color dark, Color light)
	{
		g.setColor(fill);
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setStroke(new BasicStroke(1f));
		g.setColor(dark);
		g.drawLine(r.x, r.y, r.x + r.width - 1, r.y);
		g.drawLine(r.x, r.y, r.x, r.y + r.height - 1);
		g.setColor(light);
		g.drawLine(r.x, r.y + r.height - 1, r.x + r.width - 1, r.y + r.height - 1);
		g.drawLine(r.x + r.width - 1, r.y, r.x + r.width - 1, r.y + r.height - 1);
	}

	/** Speckle over {@code clip}: {@code density} of the area, dots up to {@code size} px. */
	private static void noise(Graphics2D g, Rectangle b, Shape clip, double density, int seed, Color c, int maxAlpha)
	{
		Shape old = g.getClip();
		g.setClip(clip);
		Random rnd = new Random(seed * 31L + b.width);
		int n = (int) (b.width * b.height * density / 40);
		for (int i = 0; i < n; i++)
		{
			int x = b.x + rnd.nextInt(Math.max(1, b.width));
			int y = b.y + rnd.nextInt(Math.max(1, b.height));
			g.setColor(Tints.alpha(c, rnd.nextInt(maxAlpha + 1)));
			g.fillRect(x, y, 1 + rnd.nextInt(2), 1 + rnd.nextInt(2));
		}
		g.setClip(old);
	}

	/** The Varlamore crest's carved sandstone: tan with lighter and darker blotches and grit. */
	private static void sandstoneFill(Graphics2D g, Rectangle b, Shape card)
	{
		g.setPaint(new GradientPaint(b.x, b.y, new Color(146, 118, 88), b.x, b.y + b.height, new Color(112, 88, 64)));
		g.fill(card);
		Shape old = g.getClip();
		g.clip(card);
		Random r = new Random(b.width * 5L + 1);
		int n = b.width * b.height / 300;
		for (int i = 0; i < n; i++)
		{
			double x = b.x + r.nextDouble() * b.width;
			double y = b.y + r.nextDouble() * b.height;
			double d = b.width * (0.01 + r.nextDouble() * 0.025);
			g.setColor(r.nextBoolean() ? new Color(160, 132, 100, 60) : new Color(83, 67, 49, 60));
			g.fill(new Ellipse2D.Double(x, y, d, d * 0.8));
		}
		g.setClip(old);
		noise(g, b, card, 0.12, b.width + 7, new Color(53, 38, 32), 50);
	}

	/** A rectangle with its corners cut off, like the crest's octagon. */
	private static Shape cutCorners(Rectangle b, int c)
	{
		Path2D p = new Path2D.Double();
		p.moveTo(b.x + c, b.y);
		p.lineTo(b.x + b.width - 1 - c, b.y);
		p.lineTo(b.x + b.width - 1, b.y + c);
		p.lineTo(b.x + b.width - 1, b.y + b.height - 1 - c);
		p.lineTo(b.x + b.width - 1 - c, b.y + b.height - 1);
		p.lineTo(b.x + c, b.y + b.height - 1);
		p.lineTo(b.x, b.y + b.height - 1 - c);
		p.lineTo(b.x, b.y + c);
		p.closePath();
		return p;
	}

	/**
	 * The crest's sun of Ralos: eight gold points with red-pink rays between them, a darker
	 * gold heart, and the tier's colour as the jewel in the middle.
	 */
	private static void ralosSun(Graphics2D g, double x, double y, double r, Color jewel)
	{
		Shape rays = Tints.star(x, y, r * 1.15, 8, 0.5);
		java.awt.geom.AffineTransform turn = java.awt.geom.AffineTransform.getRotateInstance(Math.PI / 8, x, y);
		g.setColor(SUN_RAY);
		g.fill(turn.createTransformedShape(rays));
		g.setColor(SUN_GOLD);
		g.fill(Tints.star(x, y, r, 8, 0.42));
		g.setColor(SAND_EDGE);
		g.setStroke(new BasicStroke(1f));
		g.draw(Tints.star(x, y, r, 8, 0.42));
		g.setColor(SUN_HEART);
		g.fill(new Ellipse2D.Double(x - r * 0.42, y - r * 0.42, r * 0.84, r * 0.84));
		g.setPaint(new RadialGradientPaint((float) (x - r * 0.08), (float) (y - r * 0.08), (float) (r * 0.25), new float[]{0f, 1f},
			new Color[]{Tints.brighter(jewel, 0.45), Tints.darker(jewel, 0.3)}));
		g.fill(new Ellipse2D.Double(x - r * 0.24, y - r * 0.24, r * 0.48, r * 0.48));
	}
}
