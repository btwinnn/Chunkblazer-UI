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

/**
 * How a chunk is shown on the world map: a faint tint for what state it's in and,
 * if it can be unlocked, what it costs. The tint is kept light so the map stays
 * readable underneath; every unlockable chunk can also show its cost as text.
 */
public enum ChunkUnlockType
{
	// No tint: chunks you own show the plain map.
	UNLOCKED("Unlocked", new Color(0, 255, 0), new Color(0, 0, 0, 0)),
	PAID("Unlock with points", new Color(255, 215, 0), new Color(255, 215, 0, 55)),
	FREE("Free: walk in to unlock", new Color(80, 230, 230), new Color(80, 230, 230, 55)),
	CHARTER("Charter port: free", new Color(90, 150, 255), new Color(90, 150, 255, 60)),
	BOSS("Boss chunk: 1 token", new Color(200, 110, 255), new Color(200, 110, 255, 60)),
	LOCKED("Locked", new Color(255, 80, 80), new Color(0, 0, 0, 100));

	public final String legend;
	/** Solid colour for text (region id, cost label, legend). */
	public final Color color;
	/** Faint tint drawn over the whole chunk. */
	final Color fill;

	ChunkUnlockType(String legend, Color color, Color fill)
	{
		this.legend = legend;
		this.color = color;
		this.fill = fill;
	}

	public boolean isUnlockable()
	{
		return this == PAID || this == FREE || this == CHARTER || this == BOSS;
	}

	/** Classify a region. {@code unlocked} and {@code neighbor} are passed in so callers can reuse per-frame lookups. */
	static ChunkUnlockType of(ChunkBlazerCore plugin, int regionId, boolean unlocked, boolean neighbor)
	{
		if (unlocked)
		{
			return UNLOCKED;
		}
		if (plugin.isCharterRegion(regionId))
		{
			return CHARTER;
		}
		if (plugin.isFreeUnlockableRegion(regionId))
		{
			return FREE;
		}
		if (!neighbor)
		{
			return LOCKED;
		}
		if (plugin.isBossRegion(regionId))
		{
			return BOSS;
		}
		return plugin.getRegionUnlockCost(regionId) == 0 ? FREE : PAID;
	}

	/** Short cost text drawn inside the chunk ("FREE", "5 pts", "1 token"), or null if not unlockable. */
	static String costLabel(ChunkBlazerCore plugin, int regionId, ChunkUnlockType type)
	{
		switch (type)
		{
			case FREE:
			case CHARTER:
				return "FREE";
			case BOSS:
				return "1 token";
			case PAID:
				int cost = plugin.getRegionUnlockCost(regionId);
				return cost == 1 ? "1 pt" : cost + " pts";
			default:
				return null;
		}
	}
}
