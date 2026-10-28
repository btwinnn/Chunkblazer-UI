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
package com.chunkblazerui.pathing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The exact tile each task is done at, chunk by chunk, from task_tiles.json (filled in by hand
 * from the task tiles sheet): {taskId: {regionId: [x, y, plane]}}. A path to a task goes here
 * before its chunk.
 */
@Slf4j
public final class TaskTiles
{
	private static final String RESOURCE = "/com/chunkblazerui/task_tiles.json";
	private static volatile Map<String, Map<String, int[]>> tiles = Collections.emptyMap();

	private TaskTiles()
	{
	}

	/** Reads task_tiles.json. Missing or broken data just means paths go to chunks. */
	public static void load(Gson gson)
	{
		Map<String, Map<String, int[]>> loaded = null;
		try (InputStream in = TaskTiles.class.getResourceAsStream(RESOURCE))
		{
			if (in != null)
			{
				Type type = new TypeToken<Map<String, Map<String, int[]>>>()
				{
				}.getType();
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
				{
					loaded = gson.fromJson(reader, type);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Could not read {}", RESOURCE, e);
		}
		tiles = loaded == null ? Collections.emptyMap() : loaded;
	}

	/** The task's tile in this region (or its only one), packed, or -1. */
	public static int tile(String taskId, int region)
	{
		Map<String, int[]> byRegion = tiles.get(taskId);
		if (byRegion == null || byRegion.isEmpty())
		{
			return -1;
		}
		int[] at = byRegion.get(String.valueOf(region));
		if (at == null && byRegion.size() == 1)
		{
			at = byRegion.values().iterator().next();
		}
		return at == null || at.length < 3 ? -1 : PathTile.pack(at[0], at[1], at[2]);
	}
}
