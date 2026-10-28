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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The game's fonts are pixel fonts, drawn for 16 pixels. At whole multiples of that (16, 32,
 * 48) and without smoothing every pixel lands on the screen's grid and they look as they do
 * in game; at sizes in between, or smoothed, the letters come out uneven and blurry.
 */
final class PixelText
{
	private static final float NATIVE = 16f;
	// Below one size up there's no whole multiple left: these are for very small cards only.
	private static final float[] SMALLER = {14f, 12f, 10f, 8f};

	private PixelText()
	{
	}

	/** Crisp text: no smoothing, whole-pixel spacing. */
	static void crisp(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
	}

	/** {@code base} at the biggest whole multiple of its size that's no bigger than {@code px} (at least 16). */
	static Font at(Font base, float px)
	{
		return base.deriveFont(NATIVE * Math.max(1, (int) Math.floor(px / NATIVE)));
	}

	/**
	 * A task name centred in {@code panel}, in {@code font} at the size it was fitted at, with a
	 * drop shadow growing a pixel per 16. A pixel font drawn below its own 16px breaks up into
	 * broken letters, so smaller text is drawn at 16px into a picture and shrunk smoothly into
	 * the panel instead. {@code wrap} splits the text for a width. Leaves {@code font} set.
	 */
	static void centred(Graphics2D g, Rectangle panel, String text, Font font, Color color, Color shadow,
		BiFunction<FontMetrics, Integer, List<String>> wrap)
	{
		float px = font.getSize2D();
		if (px >= NATIVE)
		{
			g.setFont(font);
			lines(g, panel, wrap.apply(g.getFontMetrics(), panel.width), color, shadow);
			return;
		}
		double k = NATIVE / px;
		Rectangle big = new Rectangle(0, 0, (int) Math.ceil(panel.width * k), (int) Math.ceil(panel.height * k));
		BufferedImage picture = new BufferedImage(Math.max(1, big.width), Math.max(1, big.height), BufferedImage.TYPE_INT_ARGB);
		Graphics2D c = picture.createGraphics();
		crisp(c);
		c.setFont(font.deriveFont(NATIVE));
		lines(c, big, wrap.apply(c.getFontMetrics(), big.width), color, shadow);
		c.dispose();
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(picture, panel.x, panel.y, panel.width, panel.height, null);
		if (interpolation != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
		g.setFont(font);
	}

	private static void lines(Graphics2D g, Rectangle panel, List<String> lines, Color color, Color shadow)
	{
		FontMetrics fm = g.getFontMetrics();
		int y = panel.y + (panel.height - lines.size() * fm.getHeight()) / 2 + fm.getAscent();
		for (String line : lines)
		{
			int x = panel.x + (panel.width - fm.stringWidth(line)) / 2;
			if (shadow != null)
			{
				g.setColor(shadow);
				int d = Math.max(1, Math.round(g.getFont().getSize2D() / 16f));
				g.drawString(line, x + d, y + d);
			}
			g.setColor(color);
			g.drawString(line, x, y);
			y += fm.getHeight();
		}
	}

	/** Sizes to try when fitting text, biggest first: whole multiples up to {@code px}, then smaller ones. */
	static float[] sizes(float px)
	{
		int k = Math.max(1, (int) Math.floor(px / NATIVE));
		float[] out = new float[k + SMALLER.length];
		for (int i = 0; i < k; i++)
		{
			out[i] = NATIVE * (k - i);
		}
		System.arraycopy(SMALLER, 0, out, k, SMALLER.length);
		return out;
	}
}
