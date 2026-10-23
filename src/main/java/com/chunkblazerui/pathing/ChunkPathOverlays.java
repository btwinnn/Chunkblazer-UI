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
package com.chunkblazerui.pathing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/** The path drawn three ways: on the ground, on the minimap and on the world map. */
final class ChunkPathOverlays
{
	private static final Color PATH = new Color(255, 150, 40);
	private static final Color PATH_FILL = new Color(255, 150, 40, 45);
	private static final Color STOPPED = new Color(255, 80, 80);
	private static final Color TARGET = new Color(255, 215, 90);

	private final ChunkPathing pathing;
	private final Client client;
	private final Overlay scene = new SceneOverlay();
	private final Overlay minimap = new MinimapOverlay();
	private final Overlay worldMap = new WorldMapOverlay();

	ChunkPathOverlays(ChunkPathing pathing, Client client)
	{
		this.pathing = pathing;
		this.client = client;
	}

	void add(OverlayManager overlays)
	{
		overlays.add(scene);
		overlays.add(minimap);
		overlays.add(worldMap);
	}

	void remove(OverlayManager overlays)
	{
		overlays.remove(scene);
		overlays.remove(minimap);
		overlays.remove(worldMap);
	}

	/** Each path tile in this scene, outlined; the last one red if the path couldn't get there. */
	private final class SceneOverlay extends Overlay
	{
		SceneOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_SCENE);
		}

		public Dimension render(Graphics2D graphics)
		{
			int[] path = pathing.path();
			if (path == null)
			{
				return null;
			}
			WorldView view = client.getTopLevelWorldView();
			int plane = view.getPlane();
			Stroke oldStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(1.5f));
			int last = path.length - 1;
			for (int i = Math.max(0, pathing.progress()); i <= last; i++)
			{
				int tile = path[i];
				if (PathTile.z(tile) != plane)
				{
					continue;
				}
				LocalPoint local = LocalPoint.fromWorld(view, PathTile.x(tile), PathTile.y(tile));
				Polygon poly = local == null ? null : Perspective.getCanvasTilePoly(client, local);
				if (poly == null)
				{
					continue;
				}
				boolean end = i == last;
				Color line = end ? (pathing.reached() ? TARGET : STOPPED) : PATH;
				graphics.setColor(end ? new Color(line.getRed(), line.getGreen(), line.getBlue(), 70) : PATH_FILL);
				graphics.fill(poly);
				graphics.setColor(new Color(line.getRed(), line.getGreen(), line.getBlue(), end ? 230 : 150));
				graphics.draw(poly);
			}
			graphics.setStroke(oldStroke);
			return null;
		}
	}

	/** Small dots on the minimap. */
	private final class MinimapOverlay extends Overlay
	{
		MinimapOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
		}

		public Dimension render(Graphics2D graphics)
		{
			int[] path = pathing.path();
			if (path == null)
			{
				return null;
			}
			WorldView view = client.getTopLevelWorldView();
			int plane = view.getPlane();
			int last = path.length - 1;
			for (int i = Math.max(0, pathing.progress()); i <= last; i++)
			{
				int tile = path[i];
				if (PathTile.z(tile) != plane)
				{
					continue;
				}
				LocalPoint local = LocalPoint.fromWorld(view, PathTile.x(tile), PathTile.y(tile));
				Point dot = local == null ? null : Perspective.localToMinimap(client, local);
				if (dot == null)
				{
					continue;
				}
				boolean end = i == last;
				graphics.setColor(end ? (pathing.reached() ? TARGET : STOPPED) : PATH);
				int size = end ? 5 : 3;
				graphics.fillOval(dot.getX() - size / 2, dot.getY() - size / 2, size, size);
			}
			return null;
		}
	}

	/** Off the surface map: dungeons and other areas stored outside the overworld's rows. */
	private static boolean underground(int tile)
	{
		int y = PathTile.y(tile);
		return y < 2496 || y >= 4160;
	}

	/** A line on the world map, dashed where it takes a transport, with a ring at the end. */
	private final class WorldMapOverlay extends Overlay
	{
		WorldMapOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setPriority(PRIORITY_HIGHEST + 0.02f);
			setLayer(OverlayLayer.MANUAL);
			drawAfterInterface(InterfaceID.WORLDMAP);
		}

		public Dimension render(Graphics2D graphics)
		{
			int[] path = pathing.path();
			Widget widget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
			WorldMap map = client.getWorldMap();
			if (path == null || widget == null || map == null || map.getWorldMapPosition() == null)
			{
				return null;
			}
			Rectangle bounds = widget.getBounds();
			float zoom = map.getWorldMapZoom();
			Point centre = map.getWorldMapPosition();
			int midX = bounds.x + bounds.width / 2;
			int midY = bounds.y + bounds.height / 2;
			Shape oldClip = graphics.getClip();
			Stroke oldStroke = graphics.getStroke();
			graphics.setClip(bounds);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Stroke solid = new BasicStroke(Math.max(2f, zoom * 0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 5f}, 0f);
			int start = Math.max(0, pathing.progress());
			for (int i = start + 1; i < path.length; i++)
			{
				int a = path[i - 1];
				int b = path[i];
				int ax = midX + (int) ((PathTile.x(a) - centre.getX() + 0.5f) * zoom);
				int ay = midY - (int) ((PathTile.y(a) - centre.getY() + 0.5f) * zoom);
				int bx = midX + (int) ((PathTile.x(b) - centre.getX() + 0.5f) * zoom);
				int by = midY - (int) ((PathTile.y(b) - centre.getY() + 0.5f) * zoom);
				boolean jump = PathTile.distance(a, b) > 1;
				// Underground areas sit far off on the world map, so a line into or out of one
				// would cross the whole map. Surface to surface (a charter ship) still shows.
				if (jump && (underground(a) || underground(b)))
				{
					continue;
				}
				graphics.setStroke(jump ? dashed : solid);
				graphics.setColor(jump ? new Color(255, 230, 160, 200) : PATH);
				graphics.drawLine(ax, ay, bx, by);
			}
			int end = path[path.length - 1];
			int ex = midX + (int) ((PathTile.x(end) - centre.getX() + 0.5f) * zoom);
			int ey = midY - (int) ((PathTile.y(end) - centre.getY() + 0.5f) * zoom);
			graphics.setStroke(new BasicStroke(2f));
			graphics.setColor(pathing.reached() ? TARGET : STOPPED);
			graphics.drawOval(ex - 6, ey - 6, 12, 12);
			graphics.setStroke(oldStroke);
			graphics.setClip(oldClip);
			return null;
		}
	}
}
