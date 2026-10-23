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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The task lists' shared look (ChunkBlazer's task window, side panel and quest/saved box):
 * text and highlight colours (following the Theme: see Skin.setPalette), sizes, the arrow
 * images, and small drawing helpers. Used through {@code import static}.
 */
public final class TaskStyle
{
	private TaskStyle()
	{
	}

	public static final String CONFIG_GROUP = "chunkblazer";
	public static final int ROW = 40;
	public static final int DETAIL_LINE = 13;
	private static final int SECTION_TEXT = 11;

	// The game's interface colours (Skin).
	public static Color TITLE = new Color(255, 152, 31);
	public static Color ROW_ALT = new Color(0, 0, 0, 30);
	public static final Color ROW_HOVER = new Color(255, 255, 255, 30);
	public static Color SUBTEXT = new Color(255, 176, 64);
	public static final Color POINTS = new Color(255, 255, 0);
	public static final Color NO_LEVEL = new Color(255, 70, 60);
	private static final BufferedImage[] ARROWS = {Skin.image("arrow_right"), Skin.image("arrow_right_hover"),
		Skin.image("arrow_down"), Skin.image("arrow_down_hover")};
	public static Color DARK = new Color(28, 24, 19);
	public static final Color DETAIL = Color.WHITE;
	public static Color TRACKED_FILL = new Color(255, 152, 31, 46);
	public static Color SIDEBAR_FILL = new Color(0, 0, 0, 44);
	// Behind every task icon: lighter than the stone, so dark items and icons show.
	public static Color ITEM_SLOT = new Color(92, 84, 70);
	public static final Color CARD_HOVER = new Color(255, 255, 255, 34);
	public static final Color HERE = new Color(0, 230, 0);
	public static Color FIELD = new Color(30, 26, 21);
	public static Color LABEL = new Color(170, 150, 112);
	// Wiki links: the light blue of a link.
	public static final Color WIKI_LINK = new Color(110, 180, 255);
	// The where/how note under an Obtain or Equip task's source: a softer grey.
	public static final Color SOURCE_HOW = new Color(200, 195, 180);
	// An item's drop rate: the theme's heading colour, so it stands out from the lines round it.
	public static Color RATE = new Color(255, 152, 31);

	/** A clickable area drawn this frame. Checked in order, so earlier wins. */
	public static final class Hit
	{
		public final Shape area;
		public final Runnable action;

		public Hit(Shape area, Runnable action)
		{
			this.area = area;
			this.action = action;
		}
	}

	/** The theme's colours for this window's text and highlights (see Skin.setPalette). */
	private static void retheme(ThemePalette p)
	{
		TITLE = p.title;
		RATE = p.title;
		SUBTEXT = p.subtext;
		LABEL = p.label;
		DARK = p.dark;
		FIELD = p.field;
		TRACKED_FILL = p.tracked;
		SIDEBAR_FILL = p.sidebar;
		ITEM_SLOT = p.itemSlot;
		ROW_ALT = p.rowAlt;
	}

	/** A requirement's colour in this style: Old School uses the game's own green, red and yellow. */
	public static Color lineColor(Color color)
	{
		if (color == null)
		{
			return color;
		}
		if (color.equals(QuestRequirements.MET))
		{
			return HERE;
		}
		if (color.equals(QuestRequirements.NOT_MET))
		{
			return NO_LEVEL;
		}
		if (color.equals(QuestRequirements.NEXT_TO_YOURS))
		{
			return new Color(255, 255, 0);
		}
		if (color.equals(QuestRequirements.TEXT) || color.equals(DETAIL))
		{
			return DETAIL;
		}
		return color;
	}

	/** Where detail line {@code index} is: from x to the end of its text. */
	public static Rectangle detailLineArea(FontMetrics fm, List<QuestRequirements.Line> detail, int x, int top, int index)
	{
		int y = top;

		for (int i = 0; i <= index; i++)
		{
			y += detail.get(i).gap + (i < index ? DETAIL_LINE : 0);
		}
		QuestRequirements.Line line = detail.get(index);
		return new Rectangle(x, y, lineIndent(line) + fm.stringWidth(line.text), DETAIL_LINE);
	}

	public static int lineIndent(QuestRequirements.Line line)
	{
		return line.header ? SECTION_TEXT : line.indent;
	}

	public static int detailHeight(List<QuestRequirements.Line> lines)
	{
		return lines.isEmpty() ? 0 : 4 + lines.stream().mapToInt(l -> DETAIL_LINE + l.gap).sum();
	}

	// --- Small drawing helpers ------------------------------------------------

	public static void cross(Graphics2D graphics, int cx, int cy, int r, Color color)
	{
		graphics.setColor(color);
		graphics.drawLine(cx - r, cy - r, cx + r, cy + r);
		graphics.drawLine(cx - r, cy + r, cx + r, cy - r);
	}

	/** A small triangle centred on (cx, cy): down when open, else right; white when hovered. */
	public static void arrow(Graphics2D graphics, int cx, int cy, boolean open, boolean hover)
	{
		graphics.drawImage(ARROWS[(open ? 2 : 0) + (hover ? 1 : 0)], cx - 5, cy - 5, null);
	}

	public static void drawCentered(Graphics2D graphics, String text, Rectangle area)
	{
		FontMetrics fm = graphics.getFontMetrics();
		Skin.text(graphics, text, area.x + (area.width - fm.stringWidth(text)) / 2, area.y + (area.height + fm.getAscent()) / 2 - 1);
	}

	public static Runnable firstHit(List<Hit> list, int mx, int my)
	{
		for (Hit hit : list)
		{
			if (hit.area.contains(mx, my))
			{
				return hit.action;
			}
		}
		return null;
	}

	static
	{
		Skin.onRetheme(TaskStyle::retheme);
	}
}
