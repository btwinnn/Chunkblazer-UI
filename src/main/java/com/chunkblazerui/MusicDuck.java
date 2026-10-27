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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazerui;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * The game's music steps aside for ChunkBlazer's own sounds: a quick fade out as a pack of cards
 * comes in or a task complete popup opens (or any of the plugin's sounds plays), to a tenth of
 * its level, held until the last of it has played, then a slow fade back in to where the player had it. The music's level
 * before is kept in config while it's down, so a crash mid-fade still gets it back next start.
 */
@Slf4j
@Singleton
public class MusicDuck
{
	private static final String GROUP = ChunkBlazerUiConfig.GROUP;
	private static final String SAVED = "musicBeforeDuck";
	// A short fade out, a long fade back in.
	private static final long FADE_OUT_MS = 350;
	private static final long FADE_IN_MS = 3000;
	// After the cards or popup go: the last sound's tail rings on a moment.
	private static final long TAIL_MS = 1200;

	private final Client client;
	private final ConfigManager configManager;
	@Inject
	private Provider<TaskCardOverlay> cards;
	@Inject
	private Provider<StampCompletionOverlay> stamp;
	@Inject
	private Provider<TaskCompletionAnimationOverlay> completion;
	@Inject
	private Provider<CardSounds> sounds;

	// Held until then by a sound playing (any thread).
	private volatile long holdUntil;
	// The rest is client thread only.
	private long heldAt;
	private long lastTick;
	// The music's level when it went down (the client's 0-255), or -1 while it's not down, and
	// the player's music setting then (0-100), to tell them moving the slider from the fade.
	private int base = -1;
	private int setting = -1;
	// 1 = the player's level, down to FLOOR.
	private double level = 1;
	private boolean checkedSaved;
	// Down to a tenth (90% quieter) for the plugin's sounds.
	private static final double FLOOR = 0.1;

	@Inject
	MusicDuck(Client client, ConfigManager configManager)
	{
		this.client = client;
		this.configManager = configManager;
	}

	/** One of the plugin's sounds starts: the music stays down for {@code ms} (and its tail). */
	void hold(long ms)
	{
		long until = System.currentTimeMillis() + ms;
		if (until > holdUntil)
		{
			holdUntil = until;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		long now = System.currentTimeMillis();
		long dt = lastTick == 0 ? 20 : Math.min(200, now - lastTick);
		lastTick = now;
		if (!checkedSaved)
		{
			checkedSaved = true;
			restoreSaved();
		}
		if (showing() || now < holdUntil)
		{
			heldAt = now;
		}
		boolean down = heldAt > 0 && now - heldAt < TAIL_MS;
		int option = client.getVarpValue(VarPlayerID.OPTION_MUSIC);
		if (base < 0)
		{
			if (!down || option <= 0)
			{
				return; // nothing to make room for, or the music's off
			}
			int volume = client.getMusicVolume();
			base = volume > 0 ? volume : (int) Math.round(option * 2.55);
			setting = option;
			level = 1;
			configManager.setConfiguration(GROUP, SAVED, base);
			log.debug("Music down for the plugin's sounds from {} (setting {})", base, option);
		}
		if (option != setting)
		{
			// The player moved the music slider meanwhile: theirs it is.
			forget();
			return;
		}
		level = down ? Math.max(FLOOR, level - dt / (double) FADE_OUT_MS) : Math.min(1, level + dt / (double) FADE_IN_MS);
		if (!down && level >= 1)
		{
			restore();
			return;
		}
		// Set every frame it's down, so the game can't put its own level back in between.
		client.setMusicVolume(Math.max(1, (int) Math.round(base * level)));
	}

	/** Cards on screen or a task complete popup up, with sounds to go with them. */
	private boolean showing()
	{
		boolean themed = sounds.get().on() && (cards.get().isActive() || stamp.get().isShowing());
		return themed || completion.get().isShowing();
	}

	/** The music back where the player had it, straight away (logging out, the plugin stopping). */
	void restore()
	{
		if (base >= 0)
		{
			client.setMusicVolume(base);
		}
		forget();
	}

	private void forget()
	{
		base = -1;
		setting = -1;
		level = 1;
		heldAt = 0;
		configManager.unsetConfiguration(GROUP, SAVED);
	}

	/** Left down by a crash last time: put back. */
	private void restoreSaved()
	{
		Integer saved = configManager.getConfiguration(GROUP, SAVED, Integer.class);
		if (saved != null)
		{
			client.setMusicVolume(Math.max(0, Math.min(255, saved)));
			configManager.unsetConfiguration(GROUP, SAVED);
		}
	}
}
