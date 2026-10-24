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

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * ChunkBlazer UI's task window, as ChunkBlazer sees it. TaskPaneOverlay draws it (with the
 * quest/saved box over it) and passes it the mouse, wheel and keys it shares with the box.
 * Without ChunkBlazer UI there is no window: the Points orb offers to install it instead.
 */
public interface TaskWindowHook
{
	boolean isOpen();

	/** The Points orb's "Tasks" option. */
	void toggle();

	void close();

	/** The in-game task UI is stopping (logged out of a mode, or the plugin is off). */
	void shutDown();

	/** Each frame, before anything is drawn. */
	void beforeFrame();

	/** Draw the open window; (mx, my) is the mouse. */
	void render(Graphics2D graphics, int mx, int my);

	/** Each frame while the window is shut (its new-task alert on the Points orb). */
	void renderClosed(Graphics2D graphics);

	/** A press, before anything else sees it: true if it was the window's (and is consumed). */
	boolean pressedFirst(MouseEvent event);

	/** A left press over the window: true if it started a drag of the window. */
	boolean startDrag(MouseEvent event);

	void drag(MouseEvent event);

	void release(MouseEvent event);

	/** The task row under the mouse (for right-click to save), or null. */
	String listRowId();

	/** The mouse is over the quest/saved box, which takes the click. */
	void mouseOverPane();

	/** The wheel turned over the window. */
	void scrollBy(int amount);

	void tasksKey(KeyEvent event, boolean down);

	/** A Settings keybind is waiting for its new key: every key goes to it, and none to the game. */
	boolean capturingKey();

	/** Each game tick once logged in a moment. */
	void settledTick();

	void loggedOut();
}
