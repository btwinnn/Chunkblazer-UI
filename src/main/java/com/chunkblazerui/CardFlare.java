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
 * <li>Old School: an aura: the card's edge glowing in its tier's colour, sparks flickering up
 * off it, in blocky pixels.</li>
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
				oldSchool(g, b, accent, tier, t, seed, behind);
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

	// --- Old School -----------------------------------------------------------------------


	private static void oldSchool(Graphics2D g, Rectangle b, Color accent, int tier, float t, long seed, boolean behind)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		Random r = new Random(seed);
		aura(g, b, tier, t, swell(t), accent, r, behind);
		// Elite and Master open with a flash over everything.
		if (!behind && tier >= 3 && t < 0.12f)
		{
			glow(g, b.getCenterX(), b.getCenterY(), b.height * 1.5, Color.WHITE, (1 - t / 0.12f) * (tier >= 4 ? 0.9f : 0.55f));
		}
	}

	/** How strongly the show is up: in fast, held, then out over the last part. */
	private static float swell(float t)
	{
		return Math.min(1, t / 0.12f) * (t < 0.65f ? 1 : 1 - (t - 0.65f) / 0.35f);
	}

	/** A soft round glow of {@code c} centred on (cx, cy). */
	private static void glow(Graphics2D g, double cx, double cy, double radius, Color c, float a)
	{
		alpha(g, a);
		g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) Math.max(1, radius), new float[]{0, 1},
			new Color[]{c, Tints.alpha(c, 0)}));
		g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
	}

	/**
	 * No beam: the card's edge glows in its tier's colour, sparks flickering up off its top edge
	 * like heat, brighter each tier; Master's glow pulses.
	 */
	private static void aura(Graphics2D g, Rectangle b, int tier, float t, float s, Color c, Random r, boolean behind)
	{
		int px = px(b);
		if (behind)
		{
			float pulse = tier >= 4 ? 0.75f + 0.25f * (float) Math.sin(t * 24) : 1;
			for (int ring = 3; ring >= 1; ring--)
			{
				int grow = px * (2 + ring * (2 + tier));
				alpha(g, s * pulse * (0.18f + 0.05f * tier) * (4 - ring) / 3f);
				g.setColor(c);
				g.fillRoundRect(b.x - grow, b.y - grow, b.width + grow * 2, b.height + grow * 2, grow * 2, grow * 2);
			}
			return;
		}
		alpha(g, s * 0.9f);
		g.setColor(c);
		g.setStroke(new BasicStroke(px));
		g.drawRect(b.x, b.y, b.width, b.height);
		for (int i = 0; i < 12 + tier * 10; i++)
		{
			double phase = (r.nextDouble() + t * (1.4 + r.nextDouble())) % 1;
			int x = b.x + (int) (r.nextDouble() * b.width);
			int y = b.y - (int) (phase * b.height * (0.35 + 0.1 * tier));
			alpha(g, s * (float) (1 - phase));
			g.setColor(i % 3 == 0 ? Color.WHITE : c);
			g.fillRect(x, y, px, px * 2);
		}
		if (t < 0.35f)
		{
			sweep(g, b, t / 0.35f, c, 0.5f);
		}
	}

	/** A blocky pixel's size for this card: bigger cards, bigger pixels. */
	private static int px(Rectangle b)
	{
		return Math.max(2, b.width / 70);
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
