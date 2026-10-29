/*
 * Copyright (c) 2026, btwinnn
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

import com.chunkblazerui.pathing.Transports;
import lombok.RequiredArgsConstructor;

/** How Shortest path picks its way (Settings > Assists > Route): the extra ticks it counts a transport as. */
@RequiredArgsConstructor
public enum RouteStyle
{
	/** Quickest there, but a teleport only when the walk is over about 80 steps. */
	FASTEST("Fastest"),
	/** Walks wherever it can: teleports, boats and the like only when there's no walking there. */
	SCENIC("Scenic");

	private final String name;

	/** Extra ticks to count a transport as, on top of its own time. */
	public int extra(Transports.Link link)
	{
		if (this == SCENIC)
		{
			boolean far = link.kind == Transports.TELEPORT || link.kind == Transports.TRAVEL;
			return (far ? 3000 : 0) + link.coins / 5;
		}
		// With its cast and the 20 every teleport has, about 80 steps of walking.
		return link.kind == Transports.TELEPORT ? 60 : 0;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
