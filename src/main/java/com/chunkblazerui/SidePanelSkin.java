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
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.function.BiPredicate;
import javax.inject.Singleton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;

/**
 * ChunkBlazer's side panel in this plugin's theme. ChunkBlazer keeps only its own look and
 * asks this to draw each part when ChunkBlazer UI is on (its SidePaint): the Swing parts
 * restyled, and the headings, cards, points badges and stat boxes drawn here.
 *
 * <p>Old School: stone behind everything, framed sections as dark stone in a bevelled black
 * frame, stone buttons, sunken fields, the game's orange for grey text. Varlamore: the same
 * in sandstone, as a temple: a centred banner with a sun each side, gold brackets at every
 * box's corners, the stats on one strip, and headings as a sun and a gold rule.
 */
@Singleton
public class SidePanelSkin implements BiPredicate<String, Object[]>
{
	// What ChunkBlazer keeps on each part as built: {background, foreground, border, opaque}.
	private static final String ORIGINAL = "chunkblazer.panelStyle.original";
	private static final Color OS_FIELD = new Color(30, 26, 21);
	private static final Color OS_ORANGE = new Color(255, 152, 31);

	@Override
	public boolean test(String part, Object[] a)
	{
		ThemePalette p = Skin.palette();
		switch (part)
		{
			case "restyle":
				restyle((Container) a[0], p);
				return true;
			case "background":
			{
				Graphics2D g = (Graphics2D) a[0];
				g.setPaint(!p.log && (p.theme == Theme.OLD_SCHOOL || p.tile != null) ? Skin.stone(false) : p.panelBg);
				g.fillRect(0, 0, (Integer) a[1], (Integer) a[2]);
				return true;
			}
			case "header":
				header((Graphics2D) a[0], (Rectangle) a[1], (String) a[2], (String) a[3], (Boolean) a[4], (Boolean) a[5],
					(Font) a[6], (Font) a[7], (Color) a[8], p);
				return true;
			case "card":
				card((Graphics2D) a[0], (Integer) a[1], (Integer) a[2], (Color) a[3], (Boolean) a[4], (Boolean) a[5],
					(Boolean) a[6], (Boolean) a[7], (Integer) a[8], (Integer) a[9], (Font) a[10], p);
				return true;
			case "badge":
				badge((Graphics2D) a[0], (Integer) a[1], (Integer) a[2], (Color) a[3], (Integer) a[4], p);
				return true;
			case "stats":
				if (p.brackets)
				{
					// One strip: the numbers parted by thin gold lines.
					Graphics g = (Graphics) a[0];
					JComponent row = (JComponent) a[1];
					g.setColor(alpha(p.frameInner, 140));
					for (int i = 1; i < row.getComponentCount(); i++)
					{
						int x = row.getComponent(i).getX() - 2;
						g.drawLine(x, 4, x, row.getHeight() - 5);
					}
				}
				return true;
			case "stat":
				// On the strip each stat has no box of its own.
				return p.brackets;
			default:
				return false;
		}
	}

	// --- The Swing parts ---------------------------------------------------------------

	/** A bevelled edge in the theme's colours; Varlamore's gold brackets instead, with suns on the banner. */
	private static final class StoneBorder extends AbstractBorder
	{
		private final Insets padding;

		StoneBorder(Insets padding)
		{
			this.padding = padding;
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height)
		{
			ThemePalette p = Skin.palette();
			Graphics2D g2 = (Graphics2D) g;
			g.setColor(p.theme == Theme.OLD_SCHOOL ? Color.BLACK : p.panelBorder);
			g.drawRect(x, y, width - 1, height - 1);
			if (!p.brackets)
			{
				Skin.bevel(g2, new Rectangle(x + 1, y + 1, width - 2, height - 2), true);
				return;
			}
			Skin.trim(g2, new Rectangle(x, y, width, height));
			if (c instanceof JComponent && ((JComponent) c).getClientProperty("chunkblazer.banner") != null)
			{
				Skin.sun(g2, x + 12, y + height * 2 / 3, p.frameInner);
				Skin.sun(g2, x + width - 13, y + height * 2 / 3, p.frameInner);
			}
		}

		@Override
		public Insets getBorderInsets(Component c, Insets insets)
		{
			insets.set(3 + padding.top, 3 + padding.left, 3 + padding.bottom, 3 + padding.right);
			return insets;
		}
	}

	private static void restyle(Container root, ThemePalette p)
	{
		walk(root, p);
		// The scroll pane round the panel shows below it.
		if (root.getParent() instanceof JViewport)
		{
			root.getParent().setBackground(p.panelBg);
		}
	}

	private static void walk(Container root, ThemePalette p)
	{
		if (root instanceof JComponent)
		{
			style((JComponent) root, p);
		}
		for (Component child : root.getComponents())
		{
			if (child instanceof Container)
			{
				walk((Container) child, p);
			}
		}
	}

	/** The part as ChunkBlazer built it (it keeps that on each part the first time). */
	private static Object[] original(JComponent c)
	{
		Object o = c.getClientProperty(ORIGINAL);
		if (!(o instanceof Object[]))
		{
			o = new Object[]{c.getBackground(), c.getForeground(), c.getBorder(), c.isOpaque()};
			c.putClientProperty(ORIGINAL, o);
		}
		return (Object[]) o;
	}

	private static void style(JComponent c, ThemePalette p)
	{
		if (c.getClientProperty("chunkblazer.centred") != null)
		{
			// The banner's title and account line sit in the middle in Varlamore.
			if (c instanceof JLabel)
			{
				((JLabel) c).setHorizontalAlignment(p.brackets ? JLabel.CENTER : JLabel.LEADING);
			}
			else if (c.getLayout() instanceof FlowLayout)
			{
				((FlowLayout) c.getLayout()).setAlignment(p.brackets ? FlowLayout.CENTER : FlowLayout.LEFT);
				c.revalidate();
			}
		}
		Object[] o = original(c);
		Color background = (Color) o[0];
		Color foreground = (Color) o[1];
		if (c instanceof IconTextField)
		{
			c.setBackground(p.panelField);
			((IconTextField) c).setHoverBackgroundColor(p.panelField.brighter());
		}
		else if (c instanceof JPanel)
		{
			panel((JPanel) c, background, (Border) o[2], p);
		}
		else if (c instanceof JComboBox || c instanceof JTextField)
		{
			if (c instanceof JComboBox)
			{
				comboArrow((JComboBox<?>) c, p);
			}
			// A plain dark edge, like the game's boxes.
			Color edge = p.theme == Theme.OLD_SCHOOL ? Color.BLACK : p.panelBorder;
			c.setBorder(c instanceof JTextField ? new CompoundBorder(new LineBorder(edge), new EmptyBorder(2, 4, 2, 4))
				: new LineBorder(edge));
			c.setBackground(p.panelField);
			c.setForeground(c instanceof JTextField ? Color.WHITE : p.panelAccent);
			if (c instanceof JTextField)
			{
				((JTextField) c).setCaretColor(Color.WHITE);
			}
		}
		else if (c instanceof JRadioButton || c instanceof JList)
		{
			c.setOpaque(false);
			c.setForeground(Color.WHITE);
		}
		else if (c instanceof JButton)
		{
			JButton b = (JButton) c;
			if (b.isContentAreaFilled())
			{
				b.setBackground(p.panelButton);
				b.setBorder(new StoneBorder(new Insets(3, 8, 3, 8)));
				b.setFocusPainted(false);
			}
			// Text links stay red when they warn; everything else takes the accent.
			b.setForeground(foreground != null && foreground.getRed() > foreground.getGreen() + 30 ? new Color(255, 90, 70) : p.panelAccent);
		}
		else if (c instanceof JLabel || c instanceof JTextArea)
		{
			c.setForeground(text(foreground, p));
			if ((Boolean) o[3] && OS_FIELD.equals(background))
			{
				c.setBackground(p.panelField);
			}
		}
	}

	/** The plain combo, with a themed arrow button, drops the look-and-feel's blue ring. */
	private static void comboArrow(JComboBox<?> box, ThemePalette p)
	{
		box.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				return new BasicArrowButton(BasicArrowButton.SOUTH, p.panelButton, Color.BLACK, p.panelAccent,
					p.panelButton.brighter());
			}
		});
	}

	/** Plain backing panels show the material; framed sections become its darker shade in a bevelled frame. */
	private static void panel(JPanel c, Color bg, Border border, ThemePalette p)
	{
		Border outer = border instanceof CompoundBorder ? ((CompoundBorder) border).getOutsideBorder() : border;
		Border inner = border instanceof CompoundBorder ? ((CompoundBorder) border).getInsideBorder() : null;
		if (outer instanceof LineBorder)
		{
			c.setBorder(new StoneBorder(inner instanceof EmptyBorder ? ((EmptyBorder) inner).getBorderInsets() : new Insets(4, 4, 4, 4)));
			c.setBackground(p.panelSection);
			c.setOpaque(true);
		}
		else if (bg == null || bg.equals(ColorScheme.DARK_GRAY_COLOR) || bg.getAlpha() < 255)
		{
			c.setOpaque(false);
		}
		else if (bg.equals(ColorScheme.DARKER_GRAY_COLOR))
		{
			c.setBackground(p.panelSection);
		}
		else if (OS_FIELD.equals(bg))
		{
			c.setBackground(p.panelField);
		}
	}

	/** Grey text becomes the theme's accent (the game's orange in Old School); colours that mean something stay. */
	private static Color text(Color c, ThemePalette p)
	{
		if (c == null || OS_ORANGE.equals(c))
		{
			return p.panelAccent;
		}
		int spread = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue())) - Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
		return spread < 24 && c.getRed() < 235 && c.getRed() > 90 ? p.panelAccent : c;
	}

	// --- Drawn parts ---------------------------------------------------------------------

	/**
	 * A list heading. Old School: light raised stone in a black edge, the name in yellow, its
	 * count in a dark box. Varlamore: no bar, the name in gold, a sun, a gold rule to the count.
	 */
	private static void header(Graphics2D g, Rectangle bar, String name, String count, boolean folded, boolean hover,
		Font small, Font font, Color fg, ThemePalette p)
	{
		int cy = bar.y + bar.height / 2;
		Color gold = p.frameInner;
		if (!p.brackets)
		{
			g.setPaint(p.log ? p.baseDark : Skin.stone(false));
			g.fillRect(bar.x, bar.y, bar.width, bar.height);
			if (hover)
			{
				g.setColor(new Color(255, 255, 255, 30));
				g.fillRect(bar.x, bar.y, bar.width, bar.height);
			}
			g.setColor(Color.BLACK);
			g.drawRect(bar.x, bar.y, bar.width - 1, bar.height - 1);
			Skin.bevel(g, new Rectangle(bar.x + 1, bar.y + 1, bar.width - 2, bar.height - 2), true);
		}
		// Drawn, not a text arrow, as the game's fonts have no arrow glyphs.
		g.setColor(p.brackets ? (hover ? Color.WHITE : gold) : fg);
		g.fillPolygon(folded ? new int[]{9, 9, 15} : new int[]{8, 16, 12}, folded ? new int[]{cy - 4, cy + 4, cy}
			: new int[]{cy - 2, cy - 2, cy + 3}, 3);
		g.setFont(font);
		FontMetrics nm = g.getFontMetrics();
		Color nameColour = p.brackets && !name.startsWith("Selected") ? gold : p.log && !name.startsWith("Selected") ? p.title : fg;
		Skin.text(g, name, 22, cy + nm.getAscent() / 2 - 1, nameColour);
		g.setFont(small);
		FontMetrics fm = g.getFontMetrics();
		if (p.brackets)
		{
			int x = 22 + nm.stringWidth(name) + 8;
			Skin.sun(g, x, cy, gold);
			int countX = bar.x + bar.width - fm.stringWidth(count) - 6;
			g.setColor(alpha(gold, 160));
			g.drawLine(x + 8, cy, countX - 6, cy);
			Skin.text(g, count, countX, cy + fm.getAscent() / 2 - 1, gold);
			return;
		}
		Rectangle chip = new Rectangle(bar.x + bar.width - fm.stringWidth(count) - 16, bar.y + 4,
			fm.stringWidth(count) + 10, bar.height - 8);
		if (p.log)
		{
			// The collection log: just the count, in its green.
			Skin.text(g, count, bar.x + bar.width - fm.stringWidth(count) - 7, cy + fm.getAscent() / 2 - 1, Skin.LOG_COUNT);
			return;
		}
		g.setColor(p.theme == Theme.OLD_SCHOOL ? OS_FIELD : p.panelField);
		g.fillRect(chip.x, chip.y, chip.width, chip.height);
		g.setColor(Color.BLACK);
		g.drawRect(chip.x, chip.y, chip.width - 1, chip.height - 1);
		g.setColor(p.panelAccent);
		g.drawString(count, chip.x + 5, chip.y + (chip.height + fm.getAscent()) / 2 - 1);
	}

	/** A card's background: stone, a tier stripe, the progress bar; Varlamore's gold brackets. */
	private static void card(Graphics2D g2, int w, int h, Color tier, boolean selected, boolean hover,
		boolean saved, boolean bar, int progress, int target, Font font, ThemePalette p)
	{
		Rectangle box = new Rectangle(0, 0, w, h);
		g2.setPaint(p.log ? Skin.LOG_ROW : Skin.stone(true));
		g2.fillRect(0, 0, w, h);
		if (p.theme == Theme.OLD_SCHOOL && !p.log)
		{
			// Old School's cards are near black, the stone only just showing through.
			g2.setColor(new Color(0, 0, 0, 108));
			g2.fillRect(0, 0, w, h);
		}
		// Saved: a warm gold wash over the whole card, easy to spot down the list.
		if (saved)
		{
			g2.setColor(new Color(255, 200, 40, 34));
			g2.fillRect(0, 0, w, h);
		}
		if (selected || hover)
		{
			g2.setColor(selected ? new Color(255, 152, 31, 40) : new Color(255, 255, 255, 22));
			g2.fillRect(0, 0, w, h);
		}
		Skin.bevel(g2, box, true);
		Skin.trim(g2, box);
		g2.setColor(tier);
		if (p.brackets)
		{
			g2.fillRect(5, 6, 3, h - 12);
		}
		else
		{
			g2.fillRect(3, 3, 3, h - 6);
		}
		if (selected)
		{
			g2.setColor(p.panelAccent);
			g2.drawRect(0, 0, w - 1, h - 1);
		}
		if (bar)
		{
			String count = progress + "/" + target;
			g2.setFont(font);
			int textWidth = g2.getFontMetrics().stringWidth(count);
			int barWidth = w - 32 - textWidth;
			Skin.bar(g2, 13, h - 15, barWidth, 7, (float) (progress / (double) Math.max(1, target)), null, 0, false);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(Color.BLACK);
			g2.drawString(count, w - 11 - textWidth, h - 6);
			g2.setColor(new Color(255, 255, 0));
			g2.drawString(count, w - 12 - textWidth, h - 7);
		}
	}

	/** The task's points on a badge in the tier's colour: a shield with a gold chief (Varlamore), a hanging banner (Old School). */
	static void badge(Graphics2D g, int x, int y, Color tier, int points, ThemePalette p)
	{
		int w = 14;
		int h = 18;
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		double centreX = x + w / 2.0;
		double centreY;
		if (p.log)
		{
			// The collection log: the points in a small dark box rimmed in the tier's colour.
			g.setColor(new Color(24, 20, 15));
			g.fillRect(x - 2, y + 1, 18, 16);
			g.setColor(tier);
			g.drawRect(x - 2, y + 1, 17, 15);
			Skin.boxed(g, String.valueOf(points), new Rectangle(x - 2, y + 1, 18, 16), tier);
			return;
		}
		if (p.theme == Theme.ROYAL)
		{
			java.awt.geom.Path2D shield = new java.awt.geom.Path2D.Double();
			shield.moveTo(x, y);
			shield.lineTo(x + w, y);
			shield.lineTo(x + w, y + h * 0.5);
			shield.quadTo(x + w, y + h * 0.85, x + w / 2.0, y + h);
			shield.quadTo(x, y + h * 0.85, x, y + h * 0.5);
			shield.closePath();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(tier);
			g.fill(shield);
			// The gold chief across the top, as on Varlamore's crest.
			g.setColor(new Color(211, 174, 84));
			g.fillRect(x + 1, y + 1, w - 1, 3);
			g.setColor(new Color(60, 40, 20));
			g.draw(shield);
			centreY = y + 4 + (h - 4) * 0.42;
		}
		else
		{
			Polygon flag = new Polygon(new int[]{x, x + w, x + w, x + w / 2, x}, new int[]{y, y, y + h, y + h - 5, y + h}, 5);
			g.setColor(tier);
			g.fillPolygon(flag);
			g.setColor(new Color(255, 255, 255, 60));
			g.fillRect(x + 1, y + 1, w - 2, 2);
			g.setColor(Color.BLACK);
			g.drawPolygon(flag);
			centreY = y + (h - 5) / 2.0 + 1;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
		// Bold, and centred on the badge by the digits' own drawn outline, not the font's box.
		// Two-digit numbers too wide for the badge in bold drop to the small font.
		String text = String.valueOf(points);
		Font font = Skin.bold();
		java.awt.geom.Rectangle2D ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		if (ink.getWidth() > w - 3)
		{
			font = Skin.small();
			ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		}
		g.setFont(font);
		int tx = (int) Math.round(centreX - ink.getWidth() / 2 - ink.getX());
		int ty = (int) Math.round(centreY - ink.getHeight() / 2 - ink.getY());
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(Color.BLACK);
		g.drawString(text, tx + 1, ty + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
	}

	private static Color alpha(Color c, int a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}
}
