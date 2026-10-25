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
import java.awt.FontMetrics;
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
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/** The path drawn three ways: on the ground, on the minimap and on the world map. */
final class ChunkPathOverlays
{
	private static final Color PATH = new Color(255, 150, 40);
	private static final Color STOPPED = new Color(255, 80, 80);
	private static final Color TARGET = new Color(255, 215, 90);

	private final ChunkPathing pathing;
	private final Client client;
	private final Overlay scene = new SceneOverlay();
	private final Overlay minimap = new MinimapOverlay();
	private final Overlay worldMap = new WorldMapOverlay();
	private final Overlay travelMenu = new TravelMenuOverlay();

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
		overlays.add(travelMenu);
	}

	void remove(OverlayManager overlays)
	{
		overlays.remove(scene);
		overlays.remove(minimap);
		overlays.remove(worldMap);
		overlays.remove(travelMenu);
	}

	/** The next far-off transport the path takes (a charter, glider), or null. */
	private Transports.Link nextTravel(int[] path)
	{
		for (int i = Math.max(0, pathing.progress()); i < path.length - 1; i++)
		{
			if (PathTile.distance(path[i], path[i + 1]) > 1)
			{
				Transports.Link link = pathing.link(path[i], path[i + 1]);
				if (link != null && link.kind == Transports.TRAVEL)
				{
					return link;
				}
			}
		}
		return null;
	}

	/** The path as a line along the ground through each tile's middle; its end tile outlined (red if it couldn't get there). */
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
			int last = path.length - 1;
			java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
			boolean drawing = false;
			int previous = -1;
			for (int i = Math.max(0, pathing.progress()); i <= last; i++)
			{
				int tile = path[i];
				LocalPoint local = PathTile.z(tile) == plane ? LocalPoint.fromWorld(view, PathTile.x(tile), PathTile.y(tile)) : null;
				Point p = local == null ? null : Perspective.localToCanvas(client, local, plane);
				// A break where it leaves the scene, changes floor, or takes a transport.
				if (p == null || previous >= 0 && PathTile.distance(previous, tile) > 1)
				{
					drawing = false;
				}
				if (p != null)
				{
					if (drawing)
					{
						line.lineTo(p.getX(), p.getY());
					}
					else
					{
						line.moveTo(p.getX(), p.getY());
						drawing = true;
					}
				}
				previous = tile;
			}
			Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Stroke oldStroke = graphics.getStroke();
			// A dark edge under it, so it reads on grass, sand and snow alike.
			graphics.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.setColor(new Color(0, 0, 0, 110));
			graphics.draw(line);
			graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.setColor(PATH);
			graphics.draw(line);
			int end = path[last];
			LocalPoint endLocal = PathTile.z(end) == plane ? LocalPoint.fromWorld(view, PathTile.x(end), PathTile.y(end)) : null;
			Polygon poly = endLocal == null ? null : Perspective.getCanvasTilePoly(client, endLocal);
			if (poly != null)
			{
				Color ring = pathing.reached() ? TARGET : STOPPED;
				graphics.setColor(new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 70));
				graphics.fill(poly);
				graphics.setStroke(new BasicStroke(1.5f));
				graphics.setColor(ring);
				graphics.draw(poly);
			}
			stepIcons(graphics, view, plane, path);
			graphics.setStroke(oldStroke);
			nextTransport(graphics, view, plane, path);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
			return null;
		}

		/**
		 * Over each stair, ladder and door the path takes from here, a little sign: stairs with
		 * an arrow up or down, or a doorway. The first few only, so a long path stays tidy.
		 */
		private void stepIcons(Graphics2D graphics, WorldView view, int plane, int[] path)
		{
			int shown = 0;
			for (int i = Math.max(0, pathing.progress()); i < path.length - 1 && shown < 6; i++)
			{
				int from = path[i];
				if (PathTile.z(from) != plane)
				{
					continue;
				}
				Transports.Link link = pathing.link(from, path[i + 1]);
				if (link == null || link.kind == Transports.OTHER || link.kind == Transports.TRAVEL)
				{
					continue;
				}
				LocalPoint local = LocalPoint.fromWorld(view, PathTile.x(from), PathTile.y(from));
				Point at = local == null ? null : Perspective.localToCanvas(client, local, plane, 120);
				if (at == null)
				{
					continue;
				}
				shown++;
				stepIcon(graphics, at.getX(), at.getY(), link.kind);
			}
		}

		/** A 16px round sign at (cx, cy): stairs and an arrow for {@code UP}/{@code DOWN}, a doorway for {@code DOOR}. */
		private void stepIcon(Graphics2D graphics, int cx, int cy, int kind)
		{
			int x = cx - 9;
			int y = cy - 9;
			graphics.setColor(new Color(20, 16, 12, 200));
			graphics.fillOval(x, y, 18, 18);
			graphics.setStroke(new BasicStroke(1f));
			graphics.setColor(PATH);
			graphics.drawOval(x, y, 18, 18);
			graphics.setColor(TARGET);
			if (kind == Transports.DOOR)
			{
				// An arched doorway, open (dark inside).
				graphics.fillRoundRect(cx - 4, cy - 5, 8, 11, 8, 8);
				graphics.setColor(new Color(20, 16, 12));
				graphics.fillRoundRect(cx - 2, cy - 3, 4, 9, 4, 4);
				return;
			}
			// Three steps rising to the right.
			graphics.fillRect(cx - 6, cy + 2, 3, 3);
			graphics.fillRect(cx - 3, cy - 1, 3, 6);
			graphics.fillRect(cx, cy - 4, 3, 9);
			// The arrow beside them.
			int ax = cx + 5;
			java.awt.Polygon arrow = kind == Transports.UP
				? new java.awt.Polygon(new int[]{ax - 3, ax + 3, ax}, new int[]{cy - 1, cy - 1, cy - 5}, 3)
				: new java.awt.Polygon(new int[]{ax - 3, ax + 3, ax}, new int[]{cy + 1, cy + 1, cy + 5}, 3);
			graphics.fillPolygon(arrow);
			graphics.fillRect(ax - 1, kind == Transports.UP ? cy - 1 : cy - 3, 2, 4);
		}

		/** A small tag over the next transport the path takes, saying where to take it: "To Port Sarim". */
		private void nextTransport(Graphics2D graphics, WorldView view, int plane, int[] path)
		{
			Transports.Link link = nextTravel(path);
			if (link == null || PathTile.z(link.from) != plane)
			{
				return;
			}
			LocalPoint local = LocalPoint.fromWorld(view, PathTile.x(link.from), PathTile.y(link.from));
			Point at = local == null ? null : Perspective.localToCanvas(client, local, plane, 150);
			if (at == null)
			{
				return;
			}
			String text = "To " + link.place;
			graphics.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics metrics = graphics.getFontMetrics();
			int width = metrics.stringWidth(text) + 8;
			int height = metrics.getAscent() + 5;
			int x = at.getX() - width / 2;
			int y = at.getY() - height;
			graphics.setColor(new Color(20, 16, 12, 190));
			graphics.fillRoundRect(x, y, width, height, 4, 4);
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, x + 5, y + metrics.getAscent() + 3);
			graphics.setColor(TARGET);
			graphics.drawString(text, x + 4, y + metrics.getAscent() + 2);
		}
	}

	/** The interfaces travel stops are picked from: charters, gliders, quetzals, spirit trees, chat options. */
	private static final int[] TRAVEL_MENUS = {
		InterfaceID.CHARTERING_MENU_SIDE, InterfaceID.SAILING_MENU, InterfaceID.GLIDERMAP, 874, 187, 219};

	/** With a charter, glider or the like open, the stop the path takes outlined in it. */
	private final class TravelMenuOverlay extends Overlay
	{
		TravelMenuOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
		}

		public Dimension render(Graphics2D graphics)
		{
			int[] path = pathing.path();
			Transports.Link link = path == null ? null : nextTravel(path);
			if (link == null)
			{
				return null;
			}
			String place = link.place.toLowerCase();
			Widget best = null;
			for (int group : TRAVEL_MENUS)
			{
				int misses = 0;
				for (int child = 0; misses < 40; child++)
				{
					Widget widget = client.getWidget(group, child);
					if (widget == null)
					{
						misses++;
						continue;
					}
					misses = 0;
					best = smaller(best, find(widget, place, 0));
				}
			}
			if (best == null)
			{
				return null;
			}
			Rectangle box = best.getBounds();
			Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Stroke oldStroke = graphics.getStroke();
			graphics.setColor(new Color(TARGET.getRed(), TARGET.getGreen(), TARGET.getBlue(), 50));
			graphics.fillRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 6, 6);
			graphics.setStroke(new BasicStroke(2f));
			graphics.setColor(TARGET);
			graphics.drawRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 6, 6);
			graphics.setStroke(oldStroke);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
			return null;
		}

		/** The smallest showing widget at or under {@code widget} that names {@code place}. */
		private Widget find(Widget widget, String place, int depth)
		{
			if (widget == null || widget.isHidden() || depth > 6)
			{
				return null;
			}
			Widget best = null;
			if (names(widget, place))
			{
				best = widget;
			}
			Widget[][] kids = {widget.getDynamicChildren(), widget.getNestedChildren()};
			for (Widget[] list : kids)
			{
				if (list != null)
				{
					for (Widget kid : list)
					{
						best = smaller(best, find(kid, place, depth + 1));
					}
				}
			}
			return best;
		}

		private boolean names(Widget widget, String place)
		{
			String text = widget.getText();
			String name = widget.getName();
			return text != null && net.runelite.client.util.Text.removeTags(text).toLowerCase().contains(place)
				|| name != null && net.runelite.client.util.Text.removeTags(name).toLowerCase().contains(place);
		}

		private Widget smaller(Widget a, Widget b)
		{
			if (a == null || b == null)
			{
				return a == null ? b : a;
			}
			Rectangle ra = a.getBounds();
			Rectangle rb = b.getBounds();
			if (rb.width <= 0 || rb.height <= 0)
			{
				return a;
			}
			if (ra.width <= 0 || ra.height <= 0)
			{
				return b;
			}
			return (long) rb.width * rb.height < (long) ra.width * ra.height ? b : a;
		}
	}

	/** The same line, thin, on the minimap, with a dot at the end. */
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
			Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Stroke oldStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.setColor(PATH);
			Point before = null;
			int previous = -1;
			Point endDot = null;
			for (int i = Math.max(0, pathing.progress()); i <= last; i++)
			{
				int tile = path[i];
				LocalPoint local = PathTile.z(tile) == plane ? LocalPoint.fromWorld(view, PathTile.x(tile), PathTile.y(tile)) : null;
				Point dot = local == null ? null : Perspective.localToMinimap(client, local);
				if (dot != null && before != null && PathTile.distance(previous, tile) <= 1)
				{
					graphics.drawLine(before.getX(), before.getY(), dot.getX(), dot.getY());
				}
				before = dot;
				previous = tile;
				if (i == last)
				{
					endDot = dot;
				}
			}
			if (endDot != null)
			{
				graphics.setColor(pathing.reached() ? TARGET : STOPPED);
				graphics.fillOval(endDot.getX() - 3, endDot.getY() - 3, 6, 6);
			}
			graphics.setStroke(oldStroke);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
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
