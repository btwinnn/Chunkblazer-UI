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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.config.ConfigManager;

/**
 * The Varlamore and Old School themes' sounds for the task cards and the task complete popup:
 * real card sounds as they're dealt and turned, then the theme's own as each lands (Old School's
 * Hellfire, Varlamore's Sun flute), bigger each tier, and a fanfare of its own for each tier of task. ChunkBlazer's own theme stays
 * silent. At the volume of ChunkBlazer's task sound; while they're on, the popup's sound plays
 * instead of the area jingle.
 */
@Slf4j
@Singleton
public class CardSounds
{
	private static final String FOLDER = "Sounds/";
	private static final int DEALS = 3;
	private static final int CASCADES = 6;
	private static final int TIERS = 5;

	private final AudioPlayer audio;
	private final ConfigManager configManager;
	private final ChunkBlazerUiConfig config;
	// Sounds get a thread of their own: RuneLite's shared one also saves config and syncs
	// (busy while logging in and on a new account), and a sound queued behind that is lost.
	private ExecutorService executor;

	@Inject
	private MusicDuck music;
	// Each sound's length in ms, read once (for how long the music stays down).
	private final java.util.Map<String, Long> lengths = new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	CardSounds(AudioPlayer audio, ConfigManager configManager, ChunkBlazerUiConfig config)
	{
		this.audio = audio;
		this.configManager = configManager;
		this.config = config;
	}

	private synchronized void run(Runnable sound)
	{
		if (executor == null)
		{
			executor = Executors.newSingleThreadExecutor(r ->
			{
				Thread thread = new Thread(r, "ChunkBlazer card sounds");
				thread.setDaemon(true);
				return thread;
			});
		}
		executor.execute(sound);
	}

	/** The sound thread stops (a new one starts if a sound plays again). */
	synchronized void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
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

	/** A card turned over: the drag, the turn, the landing and the theme's show, timed to the tier's flip. */
	void flip(int tier)
	{
		play(folder(Skin.theme()) + "flip_" + tier(tier));
	}

	/** A theme's folder: Old School's, or Varlamore's (for any other theme asked for). */
	private static String folder(Theme theme)
	{
		return theme == Theme.OLD_SCHOOL ? "OldSchool/" : "Varlamore/";
	}

	private static int tier(int tier)
	{
		return Math.max(0, Math.min(TIERS - 1, tier));
	}

	/** The task complete popup: the card dropping in and turning, then stamped COMPLETE (each tier its own fanfare). */
	void complete(int tier)
	{
		play(folder(Skin.theme()) + "complete_" + tier(tier));
	}

	/** The chunk's sign falling for an unlock ("banner_drop"), or turning to Unlocked ("banner_unlock"). */
	void banner(String name)
	{
		if (config.themeSounds())
		{
			float gain = gain();
			run(() -> playNow(FOLDER, name, gain));
		}
	}

	/** A theme picked in the tutorial: that theme's card turning over (a plain card for ChunkBlazer's own). */
	void preview(Theme theme)
	{
		if (config.themeSounds())
		{
			String name = theme == Theme.ORIGINAL ? "deal_0" : folder(theme) + "flip_0";
			float gain = gain();
			run(() -> playNow(FOLDER, name, gain));
		}
	}

	/** One of the tutorial's picture cards picked: a card put down. */
	void pick()
	{
		play("deal_1");
	}

	/** The tutorial's sound check let go of: a card put down, at the new volume. */
	void tick()
	{
		float gain = gain();
		run(() -> playNow(FOLDER, "deal_2", gain));
	}

	/**
	 * The tutorial's sound check Test: a card turned over, then a task complete fanfare, the
	 * loudest there is, in this theme (Old School's for ChunkBlazer's own, which has none).
	 */
	void test()
	{
		Theme theme = Skin.theme() == Theme.ORIGINAL ? Theme.OLD_SCHOOL : Skin.theme();
		float gain = gain();
		run(() ->
		{
			playNow(FOLDER, folder(theme) + "flip_2", gain);
			try
			{
				Thread.sleep(1800);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
			playNow(FOLDER, folder(theme) + "complete_2", gain);
		});
	}

	/** Whether this theme's sounds play (Varlamore or Old School, with Theme sounds on). */
	boolean on()
	{
		return config.themeSounds() && Skin.theme() != Theme.ORIGINAL;
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
		run(() -> playNow(folder, name, gain));
	}

	private void playNow(String folder, String name, float gain)
	{
		try
		{
			String path = folder + name + ".wav";
			music.hold(lengths.computeIfAbsent(path, CardSounds::length));
			audio.play(CardSounds.class, path, gain);
		}
		catch (Exception e)
		{
			log.warn("Couldn't play {}", name, e);
		}
	}

	/** How long a sound runs, in ms (a second and a half if it can't be read). */
	private static long length(String path)
	{
		try (java.io.InputStream in = CardSounds.class.getResourceAsStream(path))
		{
			if (in != null)
			{
				try (javax.sound.sampled.AudioInputStream audio = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.BufferedInputStream(in)))
				{
					return Math.round(audio.getFrameLength() * 1000.0 / audio.getFormat().getFrameRate());
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Couldn't read {}", path, e);
		}
		return 1500;
	}

	/** ChunkBlazer's Task Sound Volume (0 to 100) as decibels, 100 being 0 dB. */
	private float gain()
	{
		Integer volume = configManager.getConfiguration("chunkblazer", "taskCompletionSoundVolume", Integer.class);
		float share = Math.max(0.001f, Math.min(1f, (volume == null ? 3 : volume) / 100f));
		return (float) (Math.log10(share) * 20);
	}
}
