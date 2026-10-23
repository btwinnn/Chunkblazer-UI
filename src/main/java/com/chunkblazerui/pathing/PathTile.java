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

import net.runelite.api.coords.WorldPoint;

/** A tile packed into one int: x in bits 0-13, y in bits 14-28, plane in bits 29-30. */
public final class PathTile
{
	private PathTile()
	{
	}

	public static int pack(int x, int y, int z)
	{
		return (x & 0x3FFF) | ((y & 0x7FFF) << 14) | ((z & 3) << 29);
	}

	public static int pack(WorldPoint point)
	{
		return pack(point.getX(), point.getY(), point.getPlane());
	}

	public static int x(int tile)
	{
		return tile & 0x3FFF;
	}

	public static int y(int tile)
	{
		return (tile >>> 14) & 0x7FFF;
	}

	public static int z(int tile)
	{
		return (tile >>> 29) & 3;
	}

	public static int region(int tile)
	{
		return CollisionMap.regionOf(x(tile), y(tile));
	}

	/** Tiles apart, the way you walk (diagonals count as one); planes far apart count as far. */
	public static int distance(int a, int b)
	{
		int d = Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
		return z(a) == z(b) ? d : d + 1000;
	}
}
