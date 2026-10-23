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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.Random;

/**
 * The show as a card turns face up, one of its own per Theme, growing with the tier:
 * <ul>
 * <li>Old School: the game's level-up fireworks, in blocky pixels (Easy just kicks up dust;
 * each tier above adds a firework, and Master gets a 99-style volley).</li>
 * <li>Royal: the sun of Ralos rises behind the card, its rays turning; higher tiers shower gold
 * and red-pink petals, and Master blazes with a full sun and a blinding flash.</li>
 * </ul>
 * ChunkBlazer, the core look, has none. Stateless: everything is worked out from the card's age
 * and a seed, so it can be drawn every frame. Plain AWT only.
 */
final class CardFlare
{
	/** How long a card's show lasts, in ms: longer for rarer tiers. */
	static long life(int tier)
	{
		return 900 + 300L * tier;
	}

	private CardFlare()
	{
	}

	/**
	 * Draw the show for a card {@code age} ms after it turned face up. {@code behind}: the part
	 * drawn under the card; otherwise the part over it.
	 */
	static void draw(Graphics2D g, Theme theme, Rectangle b, Color accent, int tier, long age, long seed, boolean behind)
	{
		long life = life(tier);
		if (theme == Theme.ORIGINAL || age < 0 || age > life)
		{
			return;
		}
		float t = age / (float) life;
		Composite oldComposite = g.getComposite();
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		switch (theme)
		{
			case OLD_SCHOOL:
				fireworks(g, b, tier, t, seed, behind);
				break;
			case ROYAL:
				sunrise(g, b, accent, tier, t, seed, behind);
				break;
			default:
				break;
		}
		g.setComposite(oldComposite);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
		g.setStroke(new BasicStroke(1f));
	}

	// --- Old School: level-up fireworks -------------------------------------------------------

	private static final Color[] FIREWORK = {new Color(255, 40, 40), new Color(255, 230, 40), new Color(60, 220, 60),
		new Color(70, 140, 255), new Color(255, 255, 255), new Color(255, 140, 20)};

	/**
	 * The puff of dust as it slaps down, then (from Medium) fireworks bursting round the card in
	 * square pixels like the game's level-up: one more each tier, and Master a 99-style volley
	 * with rockets streaking up first.
	 */
	private static void fireworks(Graphics2D g, Rectangle b, int tier, float t, long seed, boolean behind)
	{
		if (behind)
		{
			return;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		Random r = new Random(seed);
		// Dust thrown out from under the card.
		float ease = 1 - (1 - t) * (1 - t);
		int dust = 18 + tier * 6;
		for (int i = 0; i < dust; i++)
		{
			double[] from = onEdge(b, r.nextDouble(), r.nextInt(4));
			double out = (10 + r.nextDouble() * 26) * ease;
			int size = 2 + r.nextInt(3);
			int shade = 120 + r.nextInt(60);
			alpha(g, (1 - t) * 0.6f);
			g.setColor(new Color(shade, shade - 14, shade - 34));
			g.fillRect((int) (from[0] + from[2] * out), (int) (from[1] + from[3] * out - 6 * ease), size, size);
		}
		int bursts = tier == 0 ? 0 : tier >= 4 ? 7 : tier;
		for (int k = 0; k < bursts; k++)
		{
			float start = k * (tier >= 4 ? 0.08f : 0.14f);
			float u = (t - start) / 0.55f;
			double ox = b.x + b.width * (0.1 + 0.8 * r.nextDouble());
			double oy = b.y - b.height * (0.15 + 0.5 * r.nextDouble());
			Color c1 = FIREWORK[r.nextInt(FIREWORK.length)];
			Color c2 = FIREWORK[r.nextInt(FIREWORK.length)];
			int sparks = 22 + tier * 6;
			long sparkSeed = r.nextLong();
			if (tier >= 4 && u < 0 && u > -0.4f)
			{
				// Master: the rocket going up first, trailing sparks.
				float up = 1 + u / 0.4f;
				double ry = b.y + b.height - (b.y + b.height - oy) * up;
				for (int j = 0; j < 5; j++)
				{
					alpha(g, 1 - j * 0.18f);
					g.setColor(j == 0 ? Color.WHITE : new Color(255, 200, 80));
					g.fillRect((int) ox - 1, (int) ry + j * 4, 3, 3);
				}
			}
			if (u <= 0 || u >= 1)
			{
				continue;
			}
			Random sr = new Random(sparkSeed);
			double reach = b.width * (0.22 + 0.06 * tier);
			for (int i = 0; i < sparks; i++)
			{
				double ang = Math.PI * 2 * i / sparks + sr.nextDouble() * 0.2;
				double d = reach * (1 - (1 - u) * (1 - u)) * (0.7 + sr.nextDouble() * 0.3);
				double x = ox + Math.cos(ang) * d;
				double y = oy + Math.sin(ang) * d + 30 * u * u;
				int size = u < 0.5 ? 3 : 2;
				alpha(g, 1 - u);
				g.setColor(i % 2 == 0 ? c1 : c2);
				g.fillRect((int) x, (int) y, size, size);
				// A short trail back towards the centre.
				alpha(g, (1 - u) * 0.5f);
				g.fillRect((int) (ox + Math.cos(ang) * d * 0.8), (int) (oy + Math.sin(ang) * d * 0.8 + 30 * u * u), 2, 2);
			}
			if (u < 0.12f)
			{
				// The bang: a bright square flash.
				alpha(g, 1 - u / 0.12f);
				g.setColor(Color.WHITE);
				int f = 6 + tier * 2;
				g.fillRect((int) ox - f / 2, (int) oy - f / 2, f, f);
			}
		}
	}

	// --- Royal: the sun of Ralos rising ------------------------------------------------------

	private static final Color SUN_GOLD = new Color(211, 174, 84);
	private static final Color SUN_RAY = new Color(176, 104, 88);

	/**
	 * The sun of Ralos rises behind the card: gold and red-pink rays turning, longer and more each
	 * tier, with gold glints round the card. From Hard, gold and red-pink petals fall; from Elite
	 * the sun's eight-pointed disc stands behind it; Master opens with a blinding flash and a
	 * second ring of rays turning the other way.
	 */
	private static void sunrise(Graphics2D g, Rectangle b, Color accent, int tier, float t, long seed, boolean behind)
	{
		double cx = b.getCenterX();
		double cy = b.getCenterY();
		float rise = (float) Math.sin(Math.PI * Math.min(1, t * 1.15));
		if (behind)
		{
			int rays = 12 + tier * 2;
			double len = b.width * (0.6 + 0.16 * tier) * (0.7 + 0.3 * rise);
			alpha(g, rise * (0.45f + 0.08f * tier));
			drawRays(g, cx, cy, rays, len, t * (0.5 + 0.2 * tier), 0.42);
			if (tier >= 4)
			{
				alpha(g, rise * 0.35f);
				drawRays(g, cx, cy, rays + 6, len * 1.3, -t * 0.9, 0.25);
			}
			if (tier >= 3)
			{
				// The sun's disc, eight points, standing behind the card.
				double r = b.height * (0.85 + 0.25 * (tier - 3)) * (0.6 + 0.4 * rise);
				alpha(g, rise * 0.8f);
				g.setColor(SUN_RAY);
				g.fill(AffineTransform.getRotateInstance(Math.PI / 8, cx, cy).createTransformedShape(Tints.star(cx, cy, r * 1.15, 8, 0.5)));
				g.setColor(SUN_GOLD);
				g.fill(Tints.star(cx, cy, r, 8, 0.42));
			}
			return;
		}
		if (tier >= 4 && t < 0.15f)
		{
			// Master: a blinding flash as the sun breaks.
			alpha(g, 1 - t / 0.15f);
			double rad = b.width * 1.2;
			g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) rad, new float[]{0f, 0.4f, 1f},
				new Color[]{new Color(255, 255, 240, 255), new Color(255, 230, 150, 200), new Color(255, 220, 120, 0)}));
			g.fill(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
		}
		sweep(g, b, t, SUN_GOLD, 0.6f);
		Random r = new Random(seed);
		int glints = 8 + tier * 6;
		for (int i = 0; i < glints; i++)
		{
			double[] at = onEdge(b, r.nextDouble(), r.nextInt(4));
			double out = 6 + r.nextDouble() * (22 + 8 * tier);
			float start = r.nextFloat() * 0.5f;
			float u = (t - start) / 0.5f;
			if (u <= 0 || u >= 1)
			{
				continue;
			}
			double size = (5 + r.nextDouble() * 6) * Math.sin(u * Math.PI);
			alpha(g, (float) Math.sin(u * Math.PI));
			g.setColor(r.nextInt(3) == 0 ? Color.WHITE : SUN_GOLD);
			g.fill(Tints.star(at[0] + at[2] * out, at[1] + at[3] * out, size, 4, 0.3));
		}
		if (tier >= 2)
		{
			// Gold and red-pink petals drifting down round the card.
			Random p = new Random(seed * 13 + 5);
			int petals = 10 + (tier - 2) * 14;
			for (int i = 0; i < petals; i++)
			{
				double x0 = cx + (p.nextDouble() - 0.5) * b.width * (1.6 + 0.3 * tier);
				double y0 = b.y - b.height * (0.2 + p.nextDouble() * 0.8);
				double fall = b.height * (1.2 + p.nextDouble()) * t;
				double sway = Math.sin(t * 8 + i) * 10;
				AffineTransform before = g.getTransform();
				g.translate(x0 + sway, y0 + fall);
				g.rotate(t * 6 + i);
				alpha(g, (float) Math.min(1, (1 - t) * 1.6));
				g.setColor(p.nextBoolean() ? SUN_GOLD : SUN_RAY);
				g.fill(new Ellipse2D.Double(-4, -2, 8, 4));
				g.setTransform(before);
			}
		}
	}

	/** Rays from (cx, cy), alternating the sun's gold and red-pink, turned by {@code turn}. */
	private static void drawRays(Graphics2D g, double cx, double cy, int rays, double len, double turn, double width)
	{
		for (int i = 0; i < rays; i++)
		{
			double a = Math.PI * 2 * i / rays + turn;
			double half = Math.PI / rays * width;
			Path2D ray = new Path2D.Double();
			ray.moveTo(cx, cy);
			ray.lineTo(cx + Math.cos(a - half) * len, cy + Math.sin(a - half) * len);
			ray.lineTo(cx + Math.cos(a + half) * len, cy + Math.sin(a + half) * len);
			ray.closePath();
			g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) len, new float[]{0f, 1f},
				new Color[]{i % 2 == 0 ? SUN_GOLD : SUN_RAY, Tints.alpha(i % 2 == 0 ? SUN_GOLD : SUN_RAY, 0)}));
			g.fill(ray);
		}
	}

	// --- Helpers -------------------------------------------------------------------

	/** A soft diagonal band of light sliding across the card. */
	private static void sweep(Graphics2D g, Rectangle b, float t, Color c, float strength)
	{
		Shape old = g.getClip();
		g.clip(b);
		double pos = -0.4 + 1.8 * t;
		double x = b.x + b.width * pos;
		alpha(g, strength * (1 - t));
		float band = b.width * 0.18f;
		g.setPaint(new GradientPaint((float) x - band, b.y, Tints.alpha(c, 0), (float) x, b.y, Tints.alpha(c, 200), true));
		Path2D p = new Path2D.Double();
		p.moveTo(x - band, b.y);
		p.lineTo(x + band, b.y);
		p.lineTo(x + band - b.height * 0.6, b.y + b.height);
		p.lineTo(x - band - b.height * 0.6, b.y + b.height);
		p.closePath();
		g.fill(p);
		g.setClip(old);
	}

	/** A point along one side of {@code b} ({@code f} 0..1 along it) and that side's outward direction. */
	private static double[] onEdge(Rectangle b, double f, int side)
	{
		switch (side)
		{
			case 0:
				return new double[]{b.x + f * b.width, b.y, 0, -1};
			case 1:
				return new double[]{b.x + b.width, b.y + f * b.height, 1, 0};
			case 2:
				return new double[]{b.x + f * b.width, b.y + b.height, 0, 1};
			default:
				return new double[]{b.x, b.y + f * b.height, -1, 0};
		}
	}

	private static void alpha(Graphics2D g, float a)
	{
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, a))));
	}
}
