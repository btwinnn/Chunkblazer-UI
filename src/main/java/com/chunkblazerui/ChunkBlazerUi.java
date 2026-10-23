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

import java.awt.Rectangle;

/**
 * What ChunkBlazer asks of this plugin: the task window and the quest/saved box, the animated
 * cards, the task box, completion popup, outlines, chunk name banner, map legend, themes and
 * tutorial. ChunkBlazer's calls arrive as plugin messages (see ChunkBlazerUiPlugin).
 */
public interface ChunkBlazerUi
{
	/** With the plugin: the tutorial (it runs before a mode is picked) and the theme. */
	void startUp();

	void shutDown();

	/**
	 * With the chunk view (a mode, or the tutorial's Tutorial Island preview): the task cards,
	 * task box, completion popup and target outlines. Hiding drops the cards without
	 * revealing them.
	 */
	void showGameUi(boolean show);

	/** With the orbs: the task window, quest/saved box, saved bubble, item outlines, banner and legend. */
	void startTaskUi();

	void stopTaskUi();

	/** The Points orb's "Tasks" option. */
	void toggleWindow();

	/** Where the Points orb is on screen (null if hidden). */
	void setTaskButtonBounds(Rectangle bounds);

	/** A quest task clicked in the side panel: its requirements (or, without the box, track it). */
	void openTask(NuzlockeTask task);

	/** The task cards (never null). */
	TaskCardShow cards();

	/** The tutorial (TutorialHooks.NONE without it). */
	TutorialHooks tutorial();

	/** A task done: the completion popup, if there is one. */
	void showTaskCompletion(NuzlockeTask task, int points, String region);
}
