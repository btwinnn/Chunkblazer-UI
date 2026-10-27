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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The saved-tasks bubble: a round task-list button with a count of your saved (starred)
 * tasks. Clicking it opens the saved list over the side panel, like a quest's
 * requirements (TaskPaneOverlay draws that). Alt + drag to move it. Registered by TaskPaneOverlay.
 */
@Singleton
public class SavedTaskTracker extends Overlay
{
	@javax.inject.Inject
	private MouseShield shield;
	private static final int SIZE = 32;

	private final Client client;
	private final ChunkBlazerUiConfig config;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	// The quest/saved box, which draws the saved list (a Provider: the box also uses this bubble).
	private final Provider<TaskPaneOverlay> browser;
	private final TaskLists lists;
	private volatile boolean hovered;
	// Where the button was drawn last, or null while it's hidden: for the tutorial to point at.
	private volatile Rectangle button;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			// Alt + drag moves the bubble; leave those clicks to RuneLite.
			if (hovered && !event.isAltDown() && event.getButton() == MouseEvent.BUTTON1)
			{
				browser.get().toggleSavedPane();
				event.consume();
			}
			return event;
		}
	};

	@Inject
	public SavedTaskTracker(Client client, ChunkBlazerCore plugin, ChunkBlazerUiConfig config,
		OverlayManager overlayManager, MouseManager mouseManager, Provider<TaskPaneOverlay> browser, TaskLists lists)
	{
		super(plugin.owner());
		this.client = client;
		this.config = config;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.browser = browser;
		this.lists = lists;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setPriority(OverlayPriority.LOW);
		// Over every interface (bank, shops, the world map...), never under one.
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	/** The button on screen, or null while it isn't showing. */
	public Rectangle getButtonBounds()
	{
		return button;
	}

	public void startUp()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouse);
	}

	public void shutDown()
	{
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouse);
		hovered = false;
		button = null;
	}

	public Dimension render(Graphics2D graphics)
	{
		// Not on the "Click here to play" screen either: there's no game view to place it by
		// yet, so it showed in the corner and jumped across once you were in.
		if (!config.showSavedTaskTracker() || client.getGameState() != GameState.LOGGED_IN
			|| client.getLocalPlayer() == null || isWelcomeScreenUp())
		{
			hovered = false;
			button = null;
			return null;
		}
		int d = SIZE;
		int top = d / 6;
		Rectangle bounds = getBounds();
		net.runelite.api.Point m = shield.mouse();
		// It's drawn over every interface, so it takes the click over them too (the click is
		// kept from the interface underneath). Only an open right-click menu goes first.
		boolean covered = client.isMenuOpen();
		hovered = !covered && m != null && bounds != null
			&& new Rectangle(bounds.x, bounds.y + top, d, d).contains(m.getX(), m.getY());
		button = bounds == null ? null : new Rectangle(bounds.x, bounds.y + top, d, d);
		drawSkinned(graphics, d, top);
		return new Dimension(d + 4, d + top);
	}

	private static final java.awt.image.BufferedImage ORB = Skin.image("orb");
	private static final java.awt.image.BufferedImage ORB_HOVER = Skin.image("orb_hover");

	/** A stone orb like the minimap's with a task sheet in it, and the count in yellow game text. */
	private void drawSkinned(Graphics2D g, int d, int top)
	{
		g.drawImage(hovered ? ORB_HOVER : ORB, 0, top, d, d, null);
		int count = lists.savedTasks().size();
		if (count > 0)
		{
			String text = String.valueOf(count);
			g.setFont(Skin.small());
			Skin.text(g, text, d - g.getFontMetrics().stringWidth(text) + 2, 11, new Color(255, 255, 0));
		}
	}

	private boolean isWelcomeScreenUp()
	{
		Widget welcome = client.getWidget(InterfaceID.WelcomeScreen.UNIVERSE);
		return welcome != null && !welcome.isHidden();
	}
}
