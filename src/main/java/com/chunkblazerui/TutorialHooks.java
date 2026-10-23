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

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What ChunkBlazer asks the new-player tutorial (part of ChunkBlazer UI): its Tutorial Island
 * preview and the practice tasks that stand in for real ones there. Without ChunkBlazer UI
 * there is no tutorial: {@link #NONE}.
 */
public interface TutorialHooks
{
	/** No tutorial. */
	TutorialHooks NONE = new TutorialHooks()
	{
	};

	/** A Tutorial Island chunk unlocked as practice: shown unlocked, never saved. */
	default boolean isPracticeUnlocked(int regionId)
	{
		return false;
	}

	/** The tutorial's locked view of Tutorial Island is up. */
	default boolean isPreview()
	{
		return false;
	}

	default List<NuzlockeTask> practiceActive()
	{
		return Collections.emptyList();
	}

	/** Practice tasks still on a card. */
	default List<String> practiceCards()
	{
		return Collections.emptyList();
	}

	default Set<String> practiceCompleted()
	{
		return Collections.emptySet();
	}

	default NuzlockeTask practiceTask(String id)
	{
		return null;
	}

	/** What a practice task outlines in game ("npc:..." or "object:..."), or null. */
	default String practiceTarget(String id)
	{
		return null;
	}

	default void revealPractice(String id)
	{
	}

	default boolean isOnIsland()
	{
		return false;
	}

	/** The tutorial holds the first unlock back until it's explained. */
	default boolean holdsUnlock()
	{
		return false;
	}

	/** The tutorial is talking the player through something: hold other prompts. */
	default boolean isQuiet()
	{
		return false;
	}
}
