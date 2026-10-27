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

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.inject.Singleton;
import net.runelite.client.input.MouseAdapter;

/**
 * Over one of ChunkBlazer's own boxes (the tutorial's, the cards, the task box, the saved tasks
 * button), the game is told the mouse has left the screen, so what's behind them (NPCs, items,
 * objects) gets no hover text, tooltip or outline. The boxes still see where the mouse really
 * is through {@link #mouse()}. (The task window does the same for itself.)
 */
@Singleton
public class MouseShield extends MouseAdapter
{
	private final List<Supplier<Rectangle>> areas = new CopyOnWriteArrayList<>();
	private volatile Point real = new Point(-1, -1);

	/** One more box to keep the game's hover off; the supplier gives null while it's not on screen. */
	void add(Supplier<Rectangle> area)
	{
		areas.add(area);
	}

	/** No boxes (the UI stopping). */
	void clear()
	{
		areas.clear();
	}

	/** Where the mouse really is on the game's canvas (the game itself may be told otherwise). */
	net.runelite.api.Point mouse()
	{
		Point at = real;
		return new net.runelite.api.Point(at.x, at.y);
	}

	private boolean over(Point at)
	{
		for (Supplier<Rectangle> area : areas)
		{
			Rectangle r = area.get();
			if (r != null && r.contains(at))
			{
				return true;
			}
		}
		return false;
	}

	private MouseEvent shield(MouseEvent event)
	{
		real = event.getPoint();
		if (!over(event.getPoint()))
		{
			return event;
		}
		return new MouseEvent(event.getComponent(), event.getID(), event.getWhen(), event.getModifiersEx(), -1, -1, 0, false,
			event.getButton());
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		return shield(event);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		return shield(event);
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event)
	{
		real = new Point(-1, -1);
		return event;
	}
}
