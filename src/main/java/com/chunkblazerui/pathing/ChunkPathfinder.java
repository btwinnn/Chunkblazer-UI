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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.chunkblazerui.RouteStyle;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/**
 * The quickest way from one tile to another without setting foot in a locked chunk.
 * Dijkstra over tiles: a step costs its length (diagonals a little more), a transport its
 * duration, and any tile or transport landing in a region {@code open} says no to is
 * skipped. If the target can't be reached, the path goes to the reachable tile nearest it.
 */
public final class ChunkPathfinder
{
	// Step costs. In game a diagonal step takes the same tick as a straight one, but costing
	// them alike leaves countless equally short paths, and the one picked zigzags and cuts
	// odd triangles. Costing a diagonal ~1.4 straight steps (its real length) picks the
	// path that looks shortest: straight lines, hugging walls and chunk edges.
	private static final int STRAIGHT = 10;
	private static final int DIAGONAL = 14;

	// Enough for every tile of a few hundred chunks; past it we stop and use the nearest.
	private static final int MAX_TILES = 2_000_000;

	public static final class Result
	{
		/** Tiles from start to end; a jump between non-touching tiles is a transport. */
		public final int[] path;
		/** True if it ends on the target; false if it stops as near as unlocked chunks allow. */
		public final boolean reached;

		Result(int[] path, boolean reached)
		{
			this.path = path;
			this.reached = reached;
		}
	}

	private ChunkPathfinder()
	{
	}

	public static Result find(CollisionMap map, Map<Integer, List<Transports.Link>> links, IntPredicate open,
		int start, int target, RouteStyle style, BooleanSupplier cancelled)
	{
		Visits visits = new Visits();
		ChunkCalculator.LongHeap queue = new ChunkCalculator.LongHeap();
		visits.put(start, 0, start);
		queue.add(start & 0xFFFFFFFFL);
		int closest = start;
		int closestDistance = near(start, target);
		int settled = 0;
		int[] step = new int[8];
		while (!queue.isEmpty())
		{
			long head = queue.poll();
			int tile = (int) head;
			int at = (int) (head >>> 32);
			if (at > visits.cost(tile))
			{
				continue;
			}
			if (tile == target)
			{
				return new Result(visits.trace(start, tile), true);
			}
			int distance = near(tile, target);
			if (distance < closestDistance)
			{
				closest = tile;
				closestDistance = distance;
			}
			if (++settled > MAX_TILES || (settled & 0xFFFF) == 0 && cancelled.getAsBoolean())
			{
				break;
			}
			int x = PathTile.x(tile);
			int y = PathTile.y(tile);
			int z = PathTile.z(tile);
			int n = 0;
			if (map.n(x, y, z))
			{
				step[n++] = PathTile.pack(x, y + 1, z);
			}
			if (map.s(x, y, z))
			{
				step[n++] = PathTile.pack(x, y - 1, z);
			}
			if (map.e(x, y, z))
			{
				step[n++] = PathTile.pack(x + 1, y, z);
			}
			if (map.w(x, y, z))
			{
				step[n++] = PathTile.pack(x - 1, y, z);
			}
			int straight = n;
			if (map.ne(x, y, z) && corner(open, x, y, 1, 1))
			{
				step[n++] = PathTile.pack(x + 1, y + 1, z);
			}
			if (map.nw(x, y, z) && corner(open, x, y, -1, 1))
			{
				step[n++] = PathTile.pack(x - 1, y + 1, z);
			}
			if (map.se(x, y, z) && corner(open, x, y, 1, -1))
			{
				step[n++] = PathTile.pack(x + 1, y - 1, z);
			}
			if (map.sw(x, y, z) && corner(open, x, y, -1, -1))
			{
				step[n++] = PathTile.pack(x - 1, y - 1, z);
			}
			for (int i = 0; i < n; i++)
			{
				relax(queue, visits, open, tile, step[i], at + (i < straight ? STRAIGHT : DIAGONAL));
			}
			List<Transports.Link> out = links.get(tile);
			if (out != null)
			{
				for (Transports.Link link : out)
				{
					relax(queue, visits, open, tile, link.to, at + (link.cost + style.extra(link)) * STRAIGHT);
				}
			}
			// Teleports go from where you start (into an open chunk only, like anything else).
			List<Transports.Link> teleports = tile == start ? links.get(Transports.ANYWHERE) : null;
			if (teleports != null)
			{
				for (Transports.Link link : teleports)
				{
					relax(queue, visits, open, tile, link.to, at + (link.cost + style.extra(link)) * STRAIGHT);
				}
			}
		}
		// A target you can't stand on (a cave mouth, a door, an NPC's tile) counts as reached from
		// right beside it: Quest Helper's "enter the stronghold" step is the entrance itself.
		boolean beside = PathTile.z(closest) == PathTile.z(target) && near(closest, target) <= 2;
		return new Result(visits.trace(start, closest), closest == target || beside);
	}

	/**
	 * How near a tile is to the target, for where a path stops when it can't get there: across
	 * the ground, with a floor's difference worth only a short walk. Otherwise the nearest
	 * tile on the target's floor wins however far off it is: up the pub's stairs when the
	 * target's upstairs in the Heroes' Guild next door, which those stairs don't reach.
	 */
	private static int near(int tile, int target)
	{
		int d = Math.max(Math.abs(PathTile.x(tile) - PathTile.x(target)), Math.abs(PathTile.y(tile) - PathTile.y(target)));
		return d + 16 * Math.abs(PathTile.z(tile) - PathTile.z(target));
	}

	/**
	 * A diagonal step cuts across the two tiles beside it; the game counts that as
	 * stepping on them, so both have to be in open chunks too. Round a locked chunk's
	 * corner, the path goes through the corner tile instead of across it.
	 */
	private static boolean corner(IntPredicate open, int x, int y, int dx, int dy)
	{
		return open.test(CollisionMap.regionOf(x + dx, y)) && open.test(CollisionMap.regionOf(x, y + dy));
	}

	private static void relax(ChunkCalculator.LongHeap queue, Visits visits, IntPredicate open, int from, int to, int at)
	{
		if (at >= visits.cost(to) || !open.test(PathTile.region(to)))
		{
			return;
		}
		visits.put(to, at, from);
		queue.add(((long) at << 32) | (to & 0xFFFFFFFFL));
	}

	/** Best cost and previous tile for every tile reached: an open-addressed hash table of ints. */
	private static final class Visits
	{
		private static final int EMPTY = -1;
		private int[] keys = new int[1 << 16];
		private int[] costs = new int[1 << 16];
		private int[] previous = new int[1 << 16];
		private int size;

		Visits()
		{
			Arrays.fill(keys, EMPTY);
		}

		private int slot(int key)
		{
			int mask = keys.length - 1;
			int i = (key * 0x9E3779B9) >>> 7 & mask;
			while (keys[i] != EMPTY && keys[i] != key)
			{
				i = (i + 1) & mask;
			}
			return i;
		}

		int cost(int key)
		{
			int i = slot(key);
			return keys[i] == EMPTY ? Integer.MAX_VALUE : costs[i];
		}

		int previous(int key)
		{
			return previous[slot(key)];
		}

		void put(int key, int cost, int from)
		{
			int i = slot(key);
			if (keys[i] == EMPTY)
			{
				if (++size * 2 > keys.length)
				{
					grow();
					i = slot(key);
				}
				keys[i] = key;
			}
			costs[i] = cost;
			previous[i] = from;
		}

		private void grow()
		{
			int[] oldKeys = keys;
			int[] oldCosts = costs;
			int[] oldPrevious = previous;
			keys = new int[oldKeys.length * 2];
			costs = new int[keys.length];
			previous = new int[keys.length];
			Arrays.fill(keys, EMPTY);
			for (int j = 0; j < oldKeys.length; j++)
			{
				if (oldKeys[j] != EMPTY)
				{
					int i = slot(oldKeys[j]);
					keys[i] = oldKeys[j];
					costs[i] = oldCosts[j];
					previous[i] = oldPrevious[j];
				}
			}
		}

		int[] trace(int start, int end)
		{
			List<Integer> tiles = new ArrayList<>();
			int tile = end;
			tiles.add(tile);
			while (tile != start)
			{
				tile = previous(tile);
				tiles.add(tile);
			}
			Collections.reverse(tiles);
			return tiles.stream().mapToInt(Integer::intValue).toArray();
		}
	}
}
