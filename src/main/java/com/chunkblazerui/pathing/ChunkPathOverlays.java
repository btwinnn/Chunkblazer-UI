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

import com.chunkblazerui.Skin;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
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

	/** The path to draw: none while its line is hidden (the tracked task box's path button). */
	private int[] drawnPath()
	{
		return pathing.lineShown() ? pathing.path() : null;
	}

	/** The next far-off transport the path takes (a charter, glider, teleport), or null. */
	private Transports.Link nextTravel(int[] path)
	{
		int i = nextTravelAt(path);
		return i < 0 ? null : pathing.link(path[i], path[i + 1]);
	}

	/** Where along the path that is (its index), or -1. */
	private int nextTravelAt(int[] path)
	{
		int from = pathing.progress();
		for (int i : pathing.steps())
		{
			if (i >= from && i < path.length - 1)
			{
				Transports.Link link = pathing.link(path[i], path[i + 1]);
				if (link != null && (link.kind == Transports.TRAVEL || link.kind == Transports.TELEPORT))
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** The path as a line along the ground through each tile's middle (no square at its end). */
	private final class SceneOverlay extends Overlay
	{
		SceneOverlay()
		{
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_SCENE);
		}

		public Dimension render(Graphics2D graphics)
		{
			int[] path = drawnPath();
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
			Object aa = Skin.smooth(graphics);
			Stroke oldStroke = graphics.getStroke();
			// A dark edge under it, so it reads on grass, sand and snow alike.
			graphics.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.setColor(new Color(0, 0, 0, 110));
			graphics.draw(line);
			graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			graphics.setColor(PATH);
			graphics.draw(line);
			stepIcons(graphics, view, plane, path);
			graphics.setStroke(oldStroke);
			nextTransport(graphics, view, plane, path);
			Skin.restore(graphics, aa);
			return null;
		}

		/**
		 * Over each stair, ladder and door the path takes from here, a sign: an arrow up or down,
		 * or a door. The first few only, so a long path stays tidy.
		 */
		private void stepIcons(Graphics2D graphics, WorldView view, int plane, int[] path)
		{
			int shown = 0;
			int start = pathing.progress();
			for (int i : pathing.steps())
			{
				if (i < start || i >= path.length - 1 || shown >= 6)
				{
					continue;
				}
				int from = path[i];
				if (PathTile.z(from) != plane)
				{
					continue;
				}
				Transports.Link link = pathing.link(from, path[i + 1]);
				if (link == null || link.kind == Transports.OTHER || link.kind == Transports.TRAVEL || link.kind == Transports.TELEPORT)
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

		/**
		 * A round sign at (cx, cy) big enough to read from a distance: a bold arrow up or down
		 * for stairs and ladders, a door for doors and gates, with its word underneath.
		 */
		private void stepIcon(Graphics2D graphics, int cx, int cy, int kind)
		{
			int r = 12;
			graphics.setColor(new Color(20, 16, 12, 215));
			graphics.fillOval(cx - r, cy - r, r * 2, r * 2);
			graphics.setStroke(new BasicStroke(1.5f));
			graphics.setColor(PATH);
			graphics.drawOval(cx - r, cy - r, r * 2, r * 2);
			graphics.setColor(TARGET);
			String word;
			if (kind == Transports.DOOR)
			{
				// A door, its handle on the right.
				java.awt.geom.RoundRectangle2D door = new java.awt.geom.RoundRectangle2D.Double(cx - 5, cy - 8, 10, 16, 6, 6);
				graphics.fill(door);
				graphics.setColor(new Color(20, 16, 12));
				graphics.fillOval(cx + 1, cy, 3, 3);
				word = "Door";
			}
			else
			{
				// One bold arrow: its head and a thick shaft.
				int dir = kind == Transports.UP ? -1 : 1;
				int tip = cy + dir * 8;
				int base = cy + dir * 1;
				graphics.fillPolygon(new int[]{cx - 7, cx + 7, cx}, new int[]{base, base, tip}, 3);
				graphics.fillRect(cx - 2, Math.min(cy - dir * 8, base), 5, Math.abs(base - (cy - dir * 8)));
				word = kind == Transports.UP ? "Up" : "Down";
			}
			graphics.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics metrics = graphics.getFontMetrics();
			int tx = cx - metrics.stringWidth(word) / 2;
			int ty = cy + r + metrics.getAscent() + 1;
			graphics.setColor(Color.BLACK);
			graphics.drawString(word, tx + 1, ty + 1);
			graphics.setColor(TARGET);
			graphics.drawString(word, tx, ty);
		}

		/** A small tag over the next transport the path takes, saying where to take it: "To Port Sarim". */
		private void nextTransport(Graphics2D graphics, WorldView view, int plane, int[] path)
		{
			int i = nextTravelAt(path);
			Transports.Link link = i < 0 ? null : pathing.link(path[i], path[i + 1]);
			// Where it's taken: its own tile, or for a teleport, the tile you cast it from.
			int from = i < 0 ? -1 : path[i];
			if (link == null || PathTile.z(from) != plane)
			{
				return;
			}
			LocalPoint local = LocalPoint.fromWorld(view, PathTile.x(from), PathTile.y(from));
			Point at = local == null ? null : Perspective.localToCanvas(client, local, plane, 150);
			if (at == null)
			{
				return;
			}
			String text = (link.kind == Transports.TELEPORT ? "Teleport: " : "To ") + link.place;
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
		InterfaceID.CHARTERING_MENU_SIDE, InterfaceID.SAILING_MENU, InterfaceID.GLIDERMAP, 874, 187, 219, InterfaceID.MAGIC_SPELLBOOK};

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
			int[] path = drawnPath();
			Transports.Link link = path == null ? null : nextTravel(path);
			if (link == null)
			{
				return null;
			}
			String place = link.place.toLowerCase();
			Widget best = null;
			for (int group : TRAVEL_MENUS)
			{
				// Only an open one is searched: a closed interface has no root.
				Widget root = client.getWidget(group, 0);
				if (root == null || root.isHidden())
				{
					continue;
				}
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
			Object aa = Skin.smooth(graphics);
			Stroke oldStroke = graphics.getStroke();
			graphics.setColor(new Color(TARGET.getRed(), TARGET.getGreen(), TARGET.getBlue(), 50));
			graphics.fillRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 6, 6);
			graphics.setStroke(new BasicStroke(2f));
			graphics.setColor(TARGET);
			graphics.drawRoundRect(box.x - 3, box.y - 2, box.width + 6, box.height + 4, 6, 6);
			graphics.setStroke(oldStroke);
			Skin.restore(graphics, aa);
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
			int[] path = drawnPath();
			if (path == null)
			{
				return null;
			}
			WorldView view = client.getTopLevelWorldView();
			int plane = view.getPlane();
			int last = path.length - 1;
			Object aa = Skin.smooth(graphics);
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
			Skin.restore(graphics, aa);
			return null;
		}
	}

	// Underground areas (dungeons, caves) sit this far north of the surface in game coordinates.
	private static final int UNDERGROUND = 6400;
	// A transport that moves you further than this (in tiles) gets map links instead of a line.
	private static final int FAR = 8;
	// The world map's transport links, from the last frame: {where on screen, the tile it goes to}.
	private volatile List<Object[]> mapLinks = new ArrayList<>();

	/** Where a transport comes out, for its link: the table's name for it, else the chunk's. */
	private String place(Transports.Link link, int to)
	{
		return link != null && link.place != null && !link.place.isEmpty() ? link.place : pathing.chunkName(to);
	}

	/**
	 * A world map link at (x, y): a round badge with the transport's symbol (a sparkle for a
	 * teleport, an up or down arrow for stairs and ladders, a forward arrow for anything you ride
	 * or walk through), its name in a tag only while the mouse is on it. Clicking it pans the map
	 * to {@code goes}. Returns {its bounds, goes}.
	 */
	private Object[] mapLink(Graphics2D graphics, int kind, String name, int x, int y, int goes, net.runelite.api.Point mouse,
		Rectangle clip)
	{
		int r = 8;
		Rectangle box = new Rectangle(x - r, y - r, 2 * r, 2 * r);
		boolean hover = mouse != null && box.contains(mouse.getX(), mouse.getY()) && clip.contains(mouse.getX(), mouse.getY());
		// In the theme's own colours: its dark fill, its rim (Varlamore's gold, the others' bronze)
		// with a dark line round it so it stands off the map, the symbol in its title colour.
		com.chunkblazerui.ThemePalette theme = Skin.palette();
		Color fill = theme.baseDark;
		graphics.setColor(theme.dark);
		graphics.fillOval(box.x - 2, box.y - 2, box.width + 4, box.height + 4);
		graphics.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 235));
		graphics.fillOval(box.x, box.y, box.width, box.height);
		graphics.setStroke(new BasicStroke(2f));
		graphics.setColor(theme.frameInner != null ? theme.frameInner : theme.edge);
		graphics.drawOval(box.x, box.y, box.width, box.height);
		Color colour = hover ? theme.title.brighter() : theme.title;
		graphics.setColor(colour);
		graphics.setStroke(new BasicStroke(1.5f));
		if (kind == Transports.TELEPORT)
		{
			// A four-pointed sparkle.
			graphics.fillPolygon(new int[]{x, x + 1, x + 6, x + 1, x, x - 1, x - 6, x - 1},
				new int[]{y - 6, y - 1, y, y + 1, y + 6, y + 1, y, y - 1}, 8);
		}
		else if (kind == Transports.UP || kind == Transports.DOWN)
		{
			// An arrow: its head up for going up, down for going down.
			int d = kind == Transports.UP ? -1 : 1;
			graphics.fillPolygon(new int[]{x - 4, x + 4, x}, new int[]{y + d, y + d, y + 5 * d}, 3);
			graphics.drawLine(x, y - 4 * d, x, y + d);
		}
		else
		{
			graphics.fillPolygon(new int[]{x - 1, x + 4, x - 1}, new int[]{y - 4, y, y + 4}, 3);
			graphics.drawLine(x - 4, y, x - 1, y);
		}
		if (hover)
		{
			// The name in the theme's own tooltip, above the badge.
			graphics.setFont(FontManager.getRunescapeSmallFont());
			int width = graphics.getFontMetrics().stringWidth(name) + 10;
			Skin.tooltip(graphics, new Rectangle(x - width / 2, box.y - 21, width, 17), name);
		}
		return new Object[]{box, goes};
	}

	/** The path's end on the world map: a map pin, gold where it gets there, red where it stops short. */
	private static void pin(Graphics2D graphics, int x, int y, Color colour)
	{
		int[] px = {x, x - 6, x - 6, x, x + 6, x + 6};
		int[] py = {y, y - 9, y - 14, y - 18, y - 14, y - 9};
		graphics.setColor(new Color(0, 0, 0, 160));
		graphics.fillPolygon(new int[]{x + 1, x - 5, x - 5, x + 1, x + 7, x + 7}, new int[]{y + 1, y - 8, y - 13, y - 17, y - 13, y - 8}, 6);
		graphics.setColor(colour);
		graphics.fillPolygon(px, py, 6);
		// Outlined and dotted in the theme's dark.
		graphics.setColor(Skin.palette().dark);
		graphics.drawPolygon(px, py, 6);
		graphics.fillOval(x - 2, y - 14, 5, 5);
	}

	/** The tile a world map transport link at this point goes to, or -1 if there isn't one there. */
	int mapLinkAt(int x, int y)
	{
		Object[] link = linkAt(x, y);
		return link == null ? -1 : (int) link[1];
	}

	/** Where the game's own link a dungeon link of ours at this point stands for is on screen, or null. */
	int[] gameLinkAt(int x, int y)
	{
		Object[] link = linkAt(x, y);
		return link == null || link.length < 3 ? null : (int[]) link[2];
	}

	private Object[] linkAt(int x, int y)
	{
		for (Object[] link : mapLinks)
		{
			if (((Rectangle) link[0]).contains(x, y))
			{
				return link;
			}
		}
		return null;
	}


	// The game's own clickable links into another map: the map function sprites of its down and
	// up arrows. Which map element types use one, found once each.
	private static final int[] LINK_SPRITES = {86, 87};
	private final java.util.Map<Integer, Boolean> linkTypes = new java.util.HashMap<>();

	/**
	 * Where on screen the game's own link into another map nearest {@code from} (within 10 tiles)
	 * is drawn, from its own icon's bounds, or null if there isn't one in view.
	 */
	private int[] gameLinkNear(int from, Rectangle bounds)
	{
		net.runelite.api.worldmap.WorldMapRenderer renderer = client.getWorldMap().getWorldMapRenderer();
		if (renderer == null || !renderer.isLoaded() || renderer.getMapRegions() == null)
		{
			return null;
		}
		int[] best = null;
		int bestDistance = 11;
		for (net.runelite.api.worldmap.WorldMapRegion[] column : renderer.getMapRegions())
		{
			for (net.runelite.api.worldmap.WorldMapRegion region : column)
			{
				if (region == null)
				{
					continue;
				}
				for (net.runelite.api.worldmap.WorldMapIcon icon : region.getMapIcons())
				{
					net.runelite.api.coords.WorldPoint at = icon.getCoordinate();
					if (at == null || !isGameLink(icon.getType()))
					{
						continue;
					}
					int distance = Math.max(Math.abs(at.getX() - PathTile.x(from)), Math.abs(at.getY() - PathTile.y(from)));
					if (distance < bestDistance)
					{
						bestDistance = distance;
						best = mapPoint(at.getX(), at.getY(), bounds);
					}
				}
			}
		}
		return best;
	}

	/** A tile's spot on the world map on screen, worked out the way RuneLite places its own map icons. */
	private int[] mapPoint(int x, int y, Rectangle bounds)
	{
		WorldMap map = client.getWorldMap();
		float perTile = map.getWorldMapZoom();
		Point position = map.getWorldMapPosition();
		int wide = (int) Math.ceil(bounds.getWidth() / perTile);
		int high = (int) Math.ceil(bounds.getHeight() / perTile);
		int yOffset = (position.getY() - high / 2 - y - 1) * -1;
		int xOffset = x + wide / 2 - position.getX();
		int gx = (int) (xOffset * perTile);
		int gy = (int) (yOffset * perTile);
		gy -= perTile - Math.ceil(perTile / 2);
		gx += perTile - Math.ceil(perTile / 2);
		return new int[]{gx + bounds.x, bounds.height - gy + bounds.y};
	}

	private boolean isGameLink(int type)
	{
		return linkTypes.computeIfAbsent(type, t ->
		{
			net.runelite.api.worldmap.MapElementConfig config = client.getMapElementConfig(t);
			net.runelite.api.SpritePixels sprite = config == null ? null : config.getMapIcon(false);
			net.runelite.api.SpritePixels[] functions = client.getMapIcons();
			if (sprite == null || functions == null)
			{
				return false;
			}
			for (int index : LINK_SPRITES)
			{
				net.runelite.api.SpritePixels link = index < functions.length ? functions[index] : null;
				if (link != null && (link == sprite || link.getWidth() == sprite.getWidth() && link.getHeight() == sprite.getHeight()
					&& java.util.Arrays.equals(link.getPixels(), sprite.getPixels())))
				{
					return true;
				}
			}
			return false;
		});
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
			int[] path = drawnPath();
			Widget widget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
			WorldMap map = client.getWorldMap();
			if (path == null || widget == null || map == null || map.getWorldMapPosition() == null)
			{
				mapLinks = new ArrayList<>();
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
			Skin.smooth(graphics);
			Stroke solid = new BasicStroke(Math.max(2f, zoom * 0.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
			int start = Math.max(0, pathing.progress());
			List<Object[]> links = new ArrayList<>();
			net.runelite.api.Point mouse = client.getMouseCanvasPosition();
			for (int i = start + 1; i < path.length; i++)
			{
				int a = path[i - 1];
				int b = path[i];
				int ax = midX + (int) ((PathTile.x(a) - centre.getX() + 0.5f) * zoom);
				int ay = midY - (int) ((PathTile.y(a) - centre.getY() + 0.5f) * zoom);
				int bx = midX + (int) ((PathTile.x(b) - centre.getX() + 0.5f) * zoom);
				int by = midY - (int) ((PathTile.y(b) - centre.getY() + 0.5f) * zoom);
				if (PathTile.distance(a, b) > 1)
				{
					// A transport: no line flying across the map, but a link where it's taken that
					// shows where it comes out.
					if (Math.max(Math.abs(PathTile.x(a) - PathTile.x(b)), Math.abs(PathTile.y(a) - PathTile.y(b))) > FAR)
					{
						Transports.Link link = pathing.link(a, b);
						String to = place(link, b);
						int kind = link == null ? Transports.TRAVEL : link.kind;
						int lx = ax;
						int ly = ay;
						int[] game = null;
						if (kind != Transports.TELEPORT && PathTile.y(b) >= UNDERGROUND && PathTile.y(a) < UNDERGROUND)
						{
							// Into a dungeon: on the game's own link there (its up or down arrow). A click on
							// ours is passed on to the game's, at the spot it's drawn, so its dungeon map opens.
							game = gameLinkNear(a, bounds);
							if (game != null)
							{
								lx = game[0];
								ly = game[1];
							}
						}
						Object[] made = mapLink(graphics, kind, (kind == Transports.TELEPORT ? "Teleport: " : "To ") + to,
							lx, ly, b, mouse, bounds);
						links.add(new Object[]{made[0], made[1], game});
					}
					continue;
				}
				graphics.setStroke(solid);
				graphics.setColor(PATH);
				graphics.drawLine(ax, ay, bx, by);
			}
			mapLinks = links;
			int end = path[path.length - 1];
			int ex = midX + (int) ((PathTile.x(end) - centre.getX() + 0.5f) * zoom);
			int ey = midY - (int) ((PathTile.y(end) - centre.getY() + 0.5f) * zoom);
			pin(graphics, ex, ey, pathing.reached() ? TARGET : STOPPED);
			graphics.setStroke(oldStroke);
			graphics.setClip(oldClip);
			return null;
		}
	}
}
