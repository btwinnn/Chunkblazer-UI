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
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The chunk colour key in the bottom-left corner of the world map. Its own overlay on
 * the always-on-top layer, so map icons and other overlays never cover it. Uses the
 * same colours as ChunkBlazer's world map (ChunkBlazerCore.chunkFill). Registered by
 * TaskPaneOverlay. Its X turns it off (the "Colour Legend" setting). In the bottom-right corner,
 * the keys to hold while clicking the map, as they're set (its X: the "Keys Legend" setting).
 */
@Singleton
public class WorldMapLegendOverlay extends Overlay
{
	private static final Color SWATCH_BORDER = new Color(255, 255, 255, 70);
	private static final ChunkUnlockType[] ROWS = {
		ChunkUnlockType.UNLOCKED, ChunkUnlockType.PAID,
		ChunkUnlockType.CHARTER, ChunkUnlockType.BOSS, ChunkUnlockType.LOCKED
	};

	private final Client client;
	private final ChunkBlazerUiConfig config;
	private final ChunkBlazerCore core;
	private final ConfigManager configManager;
	// The legend's X where it was last drawn (null when it isn't showing).
	private volatile Rectangle close;
	// The keys legend's X (null when it isn't showing).
	private volatile Rectangle keysClose;

	/** The X turns the "Colour Legend" setting off; ticking it in the settings brings it back. */
	final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (event.getButton() != MouseEvent.BUTTON1)
			{
				return event;
			}
			Rectangle c = close;
			Rectangle k = keysClose;
			if (c != null && c.contains(event.getPoint()))
			{
				configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, "showChunkLegend", false);
				event.consume();
			}
			else if (k != null && k.contains(event.getPoint()))
			{
				configManager.setConfiguration(ChunkBlazerUiConfig.GROUP, "showMapKeys", false);
				event.consume();
			}
			return event;
		}
	};

	@Inject
	public WorldMapLegendOverlay(Client client, ChunkBlazerUiConfig config, ChunkBlazerCore core,
		ConfigManager configManager)
	{
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.core = core;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	public Dimension render(Graphics2D graphics)
	{
		close = null;
		keysClose = null;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			return null;
		}
		Rectangle worldMapRect = map.getBounds();
		if (worldMapRect == null || worldMapRect.width <= 0)
		{
			return null;
		}
		if (core.showWorldMapChunks() && config.showChunkLegend())
		{
			drawLegend(graphics, worldMapRect);
		}
		if (config.showMapKeys())
		{
			drawKeys(graphics, worldMapRect);
		}
		// The map's hover tooltip goes on top too, over the legend and map icons.
		core.drawMapTooltip(graphics);
		return null;
	}

	private void drawLegend(Graphics2D graphics, Rectangle worldMapRect)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();

		int padding = 6;
		int swatch = 10;
		int lineHeight = Math.max(fm.getHeight(), swatch + 4);
		int textWidth = 0;
		for (ChunkUnlockType row : ROWS)
		{
			textWidth = Math.max(textWidth, fm.stringWidth(row.legend));
		}
		// Room on the right for the X.
		int width = padding * 3 + swatch + textWidth + 14;
		int height = padding * 2 + lineHeight * ROWS.length;
		int x = (int) worldMapRect.getX() + 8;
		int y = (int) (worldMapRect.getY() + worldMapRect.getHeight()) - height - 8;

		Skin.panel(graphics, new Rectangle(x - 2, y - 2, width + 5, height + 5), 0, null);
		Rectangle cross = new Rectangle(x + width - 13, y + 1, 13, 13);
		Point m = client.getMouseCanvasPosition();
		Skin.close(graphics, cross, m != null && cross.contains(m.getX(), m.getY()));
		close = cross;

		int rowY = y + padding;
		for (ChunkUnlockType row : ROWS)
		{
			int sx = x + padding;
			int sy = rowY + (lineHeight - swatch) / 2;
			// The swatch is drawn over a map-like backing so it matches the tint
			// players see on the chunks.
			graphics.setColor(new Color(110, 110, 90));
			graphics.fillRect(sx, sy, swatch, swatch);
			graphics.setColor(core.chunkFill(row));
			graphics.fillRect(sx, sy, swatch, swatch);
			graphics.setColor(SWATCH_BORDER);
			graphics.drawRect(sx, sy, swatch, swatch);

			graphics.setColor(new Color(255, 152, 31));
			Skin.text(graphics, row.legend, sx + swatch + padding, rowY + (lineHeight + fm.getAscent()) / 2 - 2);
			rowY += lineHeight;
		}
	}

	/** The keys to hold while clicking the map, bottom right, named as they're set. */
	private void drawKeys(Graphics2D graphics, Rectangle worldMapRect)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		Keybind tasks = core.worldMapTasksKey();
		if (tasks != Keybind.NOT_SET)
		{
			lines.add(tasks + " + click: view chunk tasks");
		}
		Keybind path = config.pathKey();
		if (config.shortestPath() && path != Keybind.NOT_SET)
		{
			lines.add(path + " + click: set path");
		}
		if (lines.isEmpty())
		{
			return;
		}
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int padding = 6;
		int lineHeight = Math.max(fm.getHeight(), 14);
		int textWidth = 0;
		for (String line : lines)
		{
			textWidth = Math.max(textWidth, fm.stringWidth(line));
		}
		// Room on the right for the X.
		int width = padding * 2 + textWidth + 14;
		int height = padding * 2 + lineHeight * lines.size();
		int x = (int) (worldMapRect.getX() + worldMapRect.getWidth()) - width - 8;
		int y = (int) (worldMapRect.getY() + worldMapRect.getHeight()) - height - 8;

		Skin.panel(graphics, new Rectangle(x - 2, y - 2, width + 5, height + 5), 0, null);
		Rectangle cross = new Rectangle(x + width - 13, y + 1, 13, 13);
		Point m = client.getMouseCanvasPosition();
		Skin.close(graphics, cross, m != null && cross.contains(m.getX(), m.getY()));
		keysClose = cross;

		int rowY = y + padding;
		for (String line : lines)
		{
			graphics.setColor(new Color(255, 152, 31));
			Skin.text(graphics, line, x + padding, rowY + (lineHeight + fm.getAscent()) / 2 - 2);
			rowY += lineHeight;
		}
	}
}
