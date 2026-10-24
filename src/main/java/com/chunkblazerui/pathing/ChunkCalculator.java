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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Which locked chunks a quest still needs: the ones its steps happen in, plus the fewest
 * extra chunks to walk to them from where you are.
 *
 * <p>The map is cut into areas: within one region and plane, the tiles you can walk
 * between without leaving it. Areas link where you can step across a region border, and
 * where a transport you can use goes from one to another (stairs and ladders included,
 * which is how planes and dungeons join up). That's far smaller than the tile map, and
 * unlike plain regions it knows a dungeon or an island isn't joined to everything next to it.
 *
 * <p>Picking the fewest connecting chunks for several targets at once is the Steiner tree
 * problem, so this takes the usual shortcut: reach the nearest target first, count what
 * that unlocked as owned, and repeat.
 */
public final class ChunkCalculator
{
	/** What a quest needs: its own chunks, the ones to get there, and any it couldn't route to. */
	public static final class Result
	{
		public final List<Integer> quest = new ArrayList<>();
		public final List<Integer> connecting = new ArrayList<>();
		public final List<Integer> unreachable = new ArrayList<>();
	}

	private static final int SIZE = 64;

	private final CollisionMap map;
	// Area n is in region nodeRegion[n]; areas of region-plane key (region << 2 | plane)
	// are firstNode.get(key) onwards.
	private int[] nodeRegion = new int[1 << 16];
	private int nodes;
	private final Map<Integer, Integer> firstNode = new HashMap<>();
	private final Map<Integer, List<Integer>> regionNodes = new HashMap<>();
	// Walking links between areas, as an edge list.
	private int[] edgeHead;
	private int[] edgeNext = new int[1 << 16];
	private int[] edgeTo = new int[1 << 16];
	private int edges;
	// Each transport's start and end area, worked out once.
	private final Map<Transports.Link, long[]> linkNodes = new IdentityHashMap<>();

	ChunkCalculator(CollisionMap map, Transports transports)
	{
		this.map = map;
		// Edge labels of every region-plane: south row, north row, west column, east column.
		Map<Integer, short[]> rims = new HashMap<>();
		short[] labels = new short[SIZE * SIZE];
		int[] queue = new int[SIZE * SIZE];
		for (int region : map.regionIds())
		{
			for (int z = 0; z < 4; z++)
			{
				int count = label(region, z, labels, queue);
				if (count == 0)
				{
					continue;
				}
				int key = region << 2 | z;
				firstNode.put(key, nodes);
				for (int i = 0; i < count; i++)
				{
					addNode(region);
				}
				short[] rim = new short[SIZE * 4];
				for (int i = 0; i < SIZE; i++)
				{
					rim[i] = labels[i];                               // south: y = 0
					rim[SIZE + i] = labels[(SIZE - 1) * SIZE + i];    // north: y = 63
					rim[2 * SIZE + i] = labels[i * SIZE];             // west: x = 0
					rim[3 * SIZE + i] = labels[i * SIZE + SIZE - 1];  // east: x = 63
				}
				rims.put(key, rim);
			}
		}
		edgeHead = new int[nodes];
		Arrays.fill(edgeHead, -1);
		for (Map.Entry<Integer, short[]> e : rims.entrySet())
		{
			int key = e.getKey();
			int region = key >> 2;
			int z = key & 3;
			int baseX = (region >> 8) * SIZE;
			int baseY = (region & 0xFF) * SIZE;
			short[] rim = e.getValue();
			short[] east = rims.get((region + (1 << 8)) << 2 | z);
			short[] north = rims.get((region + 1) << 2 | z);
			for (int i = 0; i < SIZE; i++)
			{
				if (east != null && rim[3 * SIZE + i] > 0 && east[2 * SIZE + i] > 0 && map.e(baseX + SIZE - 1, baseY + i, z))
				{
					link(node(key, rim[3 * SIZE + i]), node((region + (1 << 8)) << 2 | z, east[2 * SIZE + i]));
				}
				if (north != null && rim[SIZE + i] > 0 && north[i] > 0 && map.n(baseX + i, baseY + SIZE - 1, z))
				{
					link(node(key, rim[SIZE + i]), node((region + 1) << 2 | z, north[i]));
				}
			}
		}
		Map<Integer, short[]> labelled = new HashMap<>();
		for (Transports.Link link : transports.all())
		{
			int a = cachedNode(link.from, labelled);
			int b = cachedNode(link.to, labelled);
			if (a >= 0 && b >= 0 && a != b)
			{
				linkNodes.put(link, new long[]{a, b});
			}
		}
	}

	/**
	 * Labels the walkable tiles of one region-plane by area, 1 up (0 = can't stand there),
	 * in scan order so the same tile always gets the same label. Returns how many areas.
	 */
	private int label(int region, int z, short[] labels, int[] queue)
	{
		Arrays.fill(labels, (short) 0);
		int baseX = (region >> 8) * SIZE;
		int baseY = (region & 0xFF) * SIZE;
		short next = 0;
		for (int start = 0; start < SIZE * SIZE; start++)
		{
			if (labels[start] != 0 || !map.walkable(baseX + start % SIZE, baseY + start / SIZE, z))
			{
				continue;
			}
			next++;
			labels[start] = next;
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			while (head < tail)
			{
				int at = queue[head++];
				int lx = at % SIZE;
				int ly = at / SIZE;
				int x = baseX + lx;
				int y = baseY + ly;
				if (ly < SIZE - 1 && map.n(x, y, z) && labels[at + SIZE] == 0)
				{
					labels[at + SIZE] = next;
					queue[tail++] = at + SIZE;
				}
				if (ly > 0 && map.s(x, y, z) && labels[at - SIZE] == 0)
				{
					labels[at - SIZE] = next;
					queue[tail++] = at - SIZE;
				}
				if (lx < SIZE - 1 && map.e(x, y, z) && labels[at + 1] == 0)
				{
					labels[at + 1] = next;
					queue[tail++] = at + 1;
				}
				if (lx > 0 && map.w(x, y, z) && labels[at - 1] == 0)
				{
					labels[at - 1] = next;
					queue[tail++] = at - 1;
				}
			}
		}
		return next;
	}

	private void addNode(int region)
	{
		if (nodes == nodeRegion.length)
		{
			nodeRegion = Arrays.copyOf(nodeRegion, nodes * 2);
		}
		nodeRegion[nodes] = region;
		regionNodes.computeIfAbsent(region, k -> new ArrayList<>()).add(nodes);
		nodes++;
	}

	private int node(int key, int label)
	{
		return firstNode.get(key) + label - 1;
	}

	private void link(int a, int b)
	{
		edge(a, b);
		edge(b, a);
	}

	private void edge(int from, int to)
	{
		if (edges == edgeTo.length)
		{
			edgeTo = Arrays.copyOf(edgeTo, edges * 2);
			edgeNext = Arrays.copyOf(edgeNext, edges * 2);
		}
		edgeTo[edges] = to;
		edgeNext[edges] = edgeHead[from];
		edgeHead[from] = edges++;
	}

	/** The area a tile is in, or -1 if you can't stand there. */
	private int nodeAt(int tile, short[] labels, int[] queue)
	{
		int region = PathTile.region(tile);
		int z = PathTile.z(tile);
		Integer first = firstNode.get(region << 2 | z);
		if (first == null)
		{
			return -1;
		}
		label(region, z, labels, queue);
		int label = labels[(PathTile.y(tile) & (SIZE - 1)) * SIZE + (PathTile.x(tile) & (SIZE - 1))];
		return label == 0 ? -1 : first + label - 1;
	}

	/**
	 * @param needed  locked regions the quest's steps are in
	 * @param open    regions you can already walk in
	 * @param weight  what unlocking a region costs: 0 for free and charter chunks, 1 for the rest
	 * @param links   transports you can use, by the tile they start from
	 * @param start   the tile you're on, or -1 to start from every area of your open regions
	 */
	public Result calculate(Set<Integer> needed, IntPredicate open, IntUnaryOperator weight,
		Map<Integer, List<Transports.Link>> links, int start)
	{
		short[] labels = new short[SIZE * SIZE];
		int[] queue = new int[SIZE * SIZE];
		// The transports you can use, between areas.
		Map<Integer, Set<Integer>> jumps = new HashMap<>();
		for (List<Transports.Link> from : links.values())
		{
			for (Transports.Link link : from)
			{
				long[] ends = linkNodes.get(link);
				if (ends != null)
				{
					jumps.computeIfAbsent((int) ends[0], k -> new HashSet<>()).add((int) ends[1]);
				}
			}
		}

		List<Integer> sources = new ArrayList<>();
		int here = start < 0 ? -1 : nodeAt(start, labels, queue);
		if (here >= 0)
		{
			sources.add(here);
		}
		else
		{
			for (int n = 0; n < nodes; n++)
			{
				if (open.test(nodeRegion[n]))
				{
					sources.add(n);
				}
			}
		}

		Result result = new Result();
		Set<Integer> owned = new HashSet<>();
		Set<Integer> targets = new LinkedHashSet<>();
		for (int region : needed)
		{
			// One you can already walk into (free or charter) needs no unlocking or route.
			if (open.test(region))
			{
				continue;
			}
			(regionNodes.containsKey(region) ? targets : result.unreachable).add(region);
		}

		long[] best = new long[nodes];
		int[] previous = new int[nodes];
		while (!targets.isEmpty())
		{
			Arrays.fill(best, Long.MAX_VALUE);
			Arrays.fill(previous, -1);
			LongHeap heap = new LongHeap();
			for (int n : sources)
			{
				best[n] = 0;
				heap.add(n);
			}
			int found = -1;
			while (!heap.isEmpty())
			{
				long head = heap.poll();
				int n = (int) (head & 0xFFFFFFFFL);
				long at = head >>> 32;
				if (at > best[n])
				{
					continue;
				}
				if (targets.contains(nodeRegion[n]))
				{
					found = n;
					break;
				}
				for (int e = edgeHead[n]; e >= 0; e = edgeNext[e])
				{
					relax(heap, best, previous, n, edgeTo[e], at, open, owned, weight);
				}
				Set<Integer> ride = jumps.get(n);
				if (ride != null)
				{
					for (int to : ride)
					{
						relax(heap, best, previous, n, to, at, open, owned, weight);
					}
				}
			}
			if (found < 0)
			{
				result.unreachable.addAll(targets);
				break;
			}
			// Everything locked on the way there becomes owned, listed from your end outwards.
			List<Integer> route = new ArrayList<>();
			for (int n = found; n >= 0; n = previous[n])
			{
				route.add(0, nodeRegion[n]);
			}
			for (int region : route)
			{
				if (open.test(region) || !owned.add(region))
				{
					continue;
				}
				(needed.contains(region) ? result.quest : result.connecting).add(region);
			}
			// The one reached is done either way: without this, a target that never became
			// owned would be found again for ever, and the search thread with it.
			targets.remove(nodeRegion[found]);
			targets.removeIf(owned::contains);
		}
		return result;
	}

	private int cachedNode(int tile, Map<Integer, short[]> labelled)
	{
		int region = PathTile.region(tile);
		int z = PathTile.z(tile);
		int key = region << 2 | z;
		Integer first = firstNode.get(key);
		if (first == null)
		{
			return -1;
		}
		short[] labels = labelled.get(key);
		if (labels == null)
		{
			labels = new short[SIZE * SIZE];
			label(region, z, labels, new int[SIZE * SIZE]);
			labelled.put(key, labels);
		}
		int label = labels[(PathTile.y(tile) & (SIZE - 1)) * SIZE + (PathTile.x(tile) & (SIZE - 1))];
		return label == 0 ? -1 : first + label - 1;
	}

	/** Entering an area: free in your chunks, otherwise mostly the chunk's unlock weight. */
	private void relax(LongHeap heap, long[] best, int[] previous, int from, int to, long at, IntPredicate open,
		Set<Integer> owned, IntUnaryOperator weight)
	{
		int region = nodeRegion[to];
		long step = nodeRegion[from] == region || open.test(region) || owned.contains(region) ? 1
			: 11 + 1000L * weight.applyAsInt(region);
		long cost = at + step;
		if (cost < best[to])
		{
			best[to] = cost;
			previous[to] = from;
			heap.add(cost << 32 | to);
		}
	}

	/** A min-heap of longs, so the search doesn't box every entry. */
	static final class LongHeap
	{
		private long[] items = new long[1024];
		private int size;

		void add(long value)
		{
			if (size == items.length)
			{
				items = Arrays.copyOf(items, size * 2);
			}
			int i = size++;
			while (i > 0)
			{
				int parent = (i - 1) >> 1;
				if (items[parent] <= value)
				{
					break;
				}
				items[i] = items[parent];
				i = parent;
			}
			items[i] = value;
		}

		long poll()
		{
			long top = items[0];
			long last = items[--size];
			int i = 0;
			while (true)
			{
				int child = 2 * i + 1;
				if (child >= size)
				{
					break;
				}
				if (child + 1 < size && items[child + 1] < items[child])
				{
					child++;
				}
				if (items[child] >= last)
				{
					break;
				}
				items[i] = items[child];
				i = child;
			}
			items[i] = last;
			return top;
		}

		boolean isEmpty()
		{
			return size == 0;
		}
	}
}
