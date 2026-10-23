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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How a fresh deal of task cards arrives on screen (after ChunkBlazer GPU's unlock
 * sweep, when that's running). Drawn by TaskCardOverlay. Dealt one by one leaves a
 * scruffy pile; the others a neat deck. Flipping and sending cards to the Tasks orb
 * work the same for all of them.
 */
@Getter
@RequiredArgsConstructor
public enum CardEntrance
{
	/**
	 * Dealt one at a time from random spots along the top of the screen into a
	 * scruffy pile, each settling at an angle set by the way it came in.
	 */
	DEALT("Dealt one by one"),
	/** Each card in the pile drops one after another, building the stack. */
	CASCADE("Cascade"),
	/** No entrance: the pile is simply there. */
	OFF("Off");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
