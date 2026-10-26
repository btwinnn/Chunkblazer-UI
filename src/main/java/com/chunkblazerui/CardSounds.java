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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.config.ConfigManager;

/**
 * Varlamore's sounds for the task cards and the task complete popup: real card sounds as
 * they're dealt and turned, then voices singing to the sun as each lands, thunder for Master. Other themes stay silent. At the volume of
 * ChunkBlazer's task sound; while they're on, the popup's sound plays instead of the area jingle.
 */
@Slf4j
@Singleton
public class CardSounds
{
	private static final String FOLDER = "Sounds/Varlamore/";
	/** The Test's run through a set: when each sound starts, ms, and which. */
	private static final long[] PREVIEW_AT = {0, 507, 1014, 1900, 1973, 2046, 2119, 2192, 2265, 3000, 6600};
	private static final String[] PREVIEW = {"deal_0", "deal_1", "deal_2", "cascade_0", "cascade_1", "cascade_2",
		"cascade_3", "cascade_4", "cascade_5", "flip_3", "complete_4"};
	private static final int DEALS = 3;
	private static final int CASCADES = 6;
	private static final int TIERS = 5;

	private final AudioPlayer audio;
	private final ConfigManager configManager;
	private final ChunkBlazerUiConfig config;
	private final ScheduledExecutorService executor;
	private long lastComplete;

	@Inject
	CardSounds(AudioPlayer audio, ConfigManager configManager, ChunkBlazerUiConfig config, ScheduledExecutorService executor)
	{
		this.audio = audio;
		this.configManager = configManager;
		this.config = config;
		this.executor = executor;
	}

	/** A card dealt onto the pile ({@code seed} picks one of a few, so a deal isn't the same knock each time). */
	void deal(int seed)
	{
		play("deal_" + Math.floorMod(seed, DEALS));
	}

	/** Card {@code index} of a cascade dropping onto the deck: each one a note higher. */
	void cascade(int index)
	{
		play("cascade_" + Math.max(0, Math.min(CASCADES - 1, index)));
	}

	/** A card turned over: the drag, the turn, the landing and the sun rising, timed to the tier's flip. */
	void flip(int tier)
	{
		play("flip_" + Math.max(0, Math.min(TIERS - 1, tier)));
	}

	/** The task complete popup: the card dropping in and turning, then stamped COMPLETE (bigger each tier). */
	void complete(int tier)
	{
		// Several tasks completing at once: one sound, like the jingle.
		long now = System.currentTimeMillis();
		if (now - lastComplete < 1500)
		{
			return;
		}
		lastComplete = now;
		play("complete_" + Math.max(0, Math.min(TIERS - 1, tier)));
	}

	/**
	 * The sounds run through, whatever the theme (the Settings page's Test): three cards
	 * dealt, a cascade, an Elite flip and a Master popup.
	 */
	void preview()
	{
		float gain = gain();
		String folder = FOLDER;
		for (int i = 0; i < PREVIEW.length; i++)
		{
			String name = PREVIEW[i];
			executor.schedule(() -> playNow(folder, name, gain), PREVIEW_AT[i], TimeUnit.MILLISECONDS);
		}
	}

	/** Whether this theme's sounds play (Varlamore, with Theme sounds on). */
	boolean on()
	{
		return config.themeSounds() && Skin.theme() == Theme.ROYAL;
	}

	private void play(String name)
	{
		if (!on())
		{
			return;
		}
		float gain = gain();
		String folder = FOLDER;
		// Opening a sound line can take a moment: not on the client or mouse thread.
		executor.execute(() -> playNow(folder, name, gain));
	}

	private void playNow(String folder, String name, float gain)
	{
		try
		{
			audio.play(CardSounds.class, folder + name + ".wav", gain);
		}
		catch (Exception e)
		{
			log.warn("Couldn't play {}", name, e);
		}
	}

	/** ChunkBlazer's Task Sound Volume (0 to 100) as decibels, 100 being 0 dB. */
	private float gain()
	{
		Integer volume = configManager.getConfiguration("chunkblazer", "taskCompletionSoundVolume", Integer.class);
		float share = Math.max(0.001f, Math.min(1f, (volume == null ? 3 : volume) / 100f));
		return (float) (Math.log10(share) * 20);
	}
}
