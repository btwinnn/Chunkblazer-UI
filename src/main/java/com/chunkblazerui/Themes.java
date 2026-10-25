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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The palettes of every {@link Theme} but ChunkBlazer's own (ThemePalette.original): their
 * colours and the small material tiles they're drawn with. Royal's colours are taken from
 * the game's own models: the Varlamore crest and sunfire fanatic armour.
 */
public final class Themes
{
	private Themes()
	{
	}

	/** The palette for {@code theme} (Old School when null). */
	public static ThemePalette palette(Theme theme)
	{
		if (theme == Theme.ORIGINAL)
		{
			return ThemePalette.original();
		}
		ThemePalette p = new ThemePalette(theme == null ? Theme.OLD_SCHOOL : theme);
		switch (p.theme)
		{
			case ROYAL:
				// Colours from the Varlamore crest and the sunfire fanatic armour: carved sandstone,
				// the gold sun of Ralos, and its red-pink rays.
				p.base = new Color(96, 76, 56);
				p.baseDark = new Color(72, 56, 42);
				p.tile = sandstone(p.base, 9);
				p.tileDark = sandstone(p.baseDark, 10);
				p.radius = 2;
				p.frameOuter = new Color(53, 38, 32);
				p.frameInner = new Color(211, 174, 84);
				p.frameWidth = 2;
				p.flourishes = true;
				p.brackets = true;
				p.button = new Color(118, 94, 70);
				p.buttonActive = new Color(157, 114, 40);
				p.edge = new Color(53, 38, 32);
				p.bevelLight = new Color(150, 124, 95);
				p.bevelDark = new Color(53, 38, 32);
				p.dividerDark = new Color(53, 38, 32);
				p.dividerLight = new Color(150, 124, 95);
				p.field = new Color(60, 46, 36);
				p.barFill = new Color(211, 174, 84);
				p.title = new Color(240, 200, 100);
				p.subtext = new Color(236, 218, 186);
				p.label = new Color(190, 168, 136);
				p.dark = new Color(58, 44, 34);
				p.tracked = new Color(211, 174, 84, 50);
				p.sidebar = new Color(0, 0, 0, 40);
				p.itemSlot = new Color(130, 106, 80);
				p.rowAlt = new Color(0, 0, 0, 26);
				p.panelBg = new Color(92, 72, 54);
				p.panelSection = new Color(70, 54, 40);
				p.panelBorder = new Color(53, 38, 32);
				p.panelField = new Color(60, 46, 36);
				p.panelAccent = new Color(240, 200, 100);
				p.panelButton = new Color(118, 94, 70);
				break;
			case OLD_SCHOOL:
			default:
				// The collection log: flat dark brown pages in thin bronze rims, orange titles,
				// green and yellow counts (Skin draws the rims; no stone images).
				p.log = true;
				p.base = new Color(42, 36, 28);
				p.baseDark = new Color(54, 46, 35);
				p.button = new Color(58, 49, 37);
				p.buttonActive = new Color(70, 56, 34);
				p.edge = Skin.LOG_RIM;
				p.dividerDark = new Color(30, 26, 20);
				p.dividerLight = Skin.LOG_RIM;
				p.field = new Color(30, 26, 20);
				p.barFill = new Color(66, 160, 70);
				p.title = new Color(255, 152, 31);
				p.subtext = new Color(230, 220, 200);
				p.label = new Color(175, 160, 135);
				p.dark = new Color(28, 24, 19);
				p.tracked = new Color(255, 152, 31, 46);
				p.sidebar = new Color(0, 0, 0, 44);
				p.itemSlot = new Color(52, 45, 36);
				p.rowAlt = new Color(0, 0, 0, 30);
				p.panelBg = new Color(34, 29, 23);
				p.panelSection = new Color(42, 36, 28);
				p.panelBorder = Skin.LOG_RIM;
				p.panelField = new Color(30, 26, 20);
				p.panelAccent = new Color(255, 152, 31);
				p.panelButton = new Color(58, 49, 37);
				break;
		}
		return p;
	}

	// --- Material tiles ------------------------------------------------------------

	private static final int TILE = 64;

	private static int clamp(int v)
	{
		return Math.max(0, Math.min(255, v));
	}

	/** Carved sandstone, like the Varlamore crest: soft lighter and darker blotches and grit. */
	private static BufferedImage sandstone(Color base, long seed)
	{
		BufferedImage img = blank(base);
		Random r = new Random(seed);
		Color light = new Color(clamp(base.getRed() + 14), clamp(base.getGreen() + 12), clamp(base.getBlue() + 10));
		Color dark = new Color(clamp(base.getRed() - 12), clamp(base.getGreen() - 10), clamp(base.getBlue() - 8));
		for (int i = 0; i < 70; i++)
		{
			Color c = r.nextBoolean() ? light : dark;
			int cx = r.nextInt(TILE);
			int cy = r.nextInt(TILE);
			int rad = 2 + r.nextInt(4);
			for (int dx = -rad; dx <= rad; dx++)
			{
				for (int dy = -rad; dy <= rad; dy++)
				{
					if (dx * dx + dy * dy <= rad * rad)
					{
						blend(img, Math.floorMod(cx + dx, TILE), Math.floorMod(cy + dy, TILE), c, 30);
					}
				}
			}
		}
		for (int i = 0; i < 300; i++)
		{
			blend(img, r.nextInt(TILE), r.nextInt(TILE), r.nextBoolean() ? Color.BLACK : light, 8 + r.nextInt(14));
		}
		return img;
	}

	private static BufferedImage blank(Color base)
	{
		BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(base);
		g.fillRect(0, 0, TILE, TILE);
		g.dispose();
		return img;
	}

	private static void blend(BufferedImage img, int x, int y, Color c, int alpha)
	{
		int p = img.getRGB(x, y);
		float t = alpha / 255f;
		int r = (int) (((p >> 16) & 255) * (1 - t) + c.getRed() * t);
		int gr = (int) (((p >> 8) & 255) * (1 - t) + c.getGreen() * t);
		int b = (int) ((p & 255) * (1 - t) + c.getBlue() * t);
		img.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (gr << 8) | b);
	}
}
