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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Which way you can step from every tile in the game, read from collision-map.zip. One
 * zip entry per 64x64 region ("regionX_regionY"), each a bit set with four flags per tile
 * per plane: north edge open, east edge open, and two wall flags we don't need.
 *
 * <p>The collision map is from the Shortest Path plugin
 * (https://github.com/Skretzo/shortest-path, BSD 2-Clause), as is the way its bits are
 * laid out.
 */
public final class CollisionMap
{
	private static final String RESOURCE = "collision-map.zip";
	private static final int REGION_SIZE = 64;
	private static final int FLAG_NORTH = 0;
	private static final int FLAG_EAST = 1;
	private static final int FLAG_COUNT = 4;
	private static final int BITS_PER_PLANE = REGION_SIZE * REGION_SIZE * FLAG_COUNT;

	// Indexed by region id ((regionX << 8) | regionY); null where there's no data.
	private final long[][] regions = new long[1 << 16][];
	private final byte[] planes = new byte[1 << 16];
	private final Set<Integer> regionIds = new HashSet<>();

	private CollisionMap()
	{
	}

	static CollisionMap load() throws IOException
	{
		CollisionMap map = new CollisionMap();
		InputStream raw = CollisionMap.class.getResourceAsStream(RESOURCE);
		if (raw == null)
		{
			throw new IOException(RESOURCE + " not found");
		}
		try (ZipInputStream in = new ZipInputStream(raw))
		{
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = in.getNextEntry()) != null)
			{
				String[] name = entry.getName().split("_");
				if (name.length != 2)
				{
					continue;
				}
				int regionX = Integer.parseInt(name[0]);
				int regionY = Integer.parseInt(name[1]);
				if (regionX < 0 || regionX > 255 || regionY < 0 || regionY > 255)
				{
					continue;
				}
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				int read;
				while ((read = in.read(buffer)) > 0)
				{
					bytes.write(buffer, 0, read);
				}
				BitSet bits = BitSet.valueOf(bytes.toByteArray());
				int id = (regionX << 8) | regionY;
				map.regions[id] = bits.toLongArray();
				map.planes[id] = (byte) ((bits.size() + BITS_PER_PLANE - 1) / BITS_PER_PLANE);
				map.regionIds.add(id);
			}
		}
		return map;
	}

	/** Every region with collision data. */
	public Set<Integer> regionIds()
	{
		return regionIds;
	}

	public static int regionOf(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	private boolean get(int x, int y, int z, int flag)
	{
		if (x < 0 || y < 0 || z < 0 || z > 3)
		{
			return false;
		}
		int id = regionOf(x, y);
		if (id >= regions.length)
		{
			return false;
		}
		long[] words = regions[id];
		if (words == null || z >= planes[id])
		{
			return false;
		}
		int bit = (z * REGION_SIZE * REGION_SIZE + (y & 63) * REGION_SIZE + (x & 63)) * FLAG_COUNT + flag;
		int word = bit >> 6;
		return word < words.length && (words[word] >>> (bit & 63) & 1L) != 0L;
	}

	public boolean n(int x, int y, int z)
	{
		return get(x, y, z, FLAG_NORTH);
	}

	public boolean s(int x, int y, int z)
	{
		return n(x, y - 1, z);
	}

	public boolean e(int x, int y, int z)
	{
		return get(x, y, z, FLAG_EAST);
	}

	public boolean w(int x, int y, int z)
	{
		return e(x - 1, y, z);
	}

	public boolean ne(int x, int y, int z)
	{
		return n(x, y, z) && e(x, y + 1, z) && e(x, y, z) && n(x + 1, y, z);
	}

	public boolean nw(int x, int y, int z)
	{
		return n(x, y, z) && w(x, y + 1, z) && w(x, y, z) && n(x - 1, y, z);
	}

	public boolean se(int x, int y, int z)
	{
		return s(x, y, z) && e(x, y - 1, z) && e(x, y, z) && s(x + 1, y, z);
	}

	public boolean sw(int x, int y, int z)
	{
		return s(x, y, z) && w(x, y - 1, z) && w(x, y, z) && s(x - 1, y, z);
	}

	/** Some edge of the tile is open, so you can stand on it. */
	public boolean walkable(int x, int y, int z)
	{
		return n(x, y, z) || s(x, y, z) || e(x, y, z) || w(x, y, z);
	}
}
