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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import net.runelite.api.Skill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The extras' tables, in the shape of the catalog's _Target_Extras.json sidecar (ChunkBlazer
 * passes it over): read the way the plugin does, so a shape change (enum keys, id sets) fails
 * here instead of silently leaving the outlines and menus without them.
 */
class TaskTargetExtrasTest
{
	private static final String SIDECAR = "{"
		+ "\"npcs\": {\"cook_blurberry_special\": [6531]},"
		+ "\"objects\": {\"agility_level_26_underwall_tunnel\": [16528]},"
		+ "\"requirements\": {\"build_spirit_tree\": {\"FARMING\": 83, \"CONSTRUCTION\": 70}}"
		+ "}";

	@AfterEach
	void reset()
	{
		TaskTargetExtras.load(null, new Gson());
	}

	private static NuzlockeTask task(String id)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setTaskId(id);
		task.setName("Test task");
		return task;
	}

	@Test
	void sidecarFillsEveryTable()
	{
		TaskTargetExtras.load(SIDECAR, new Gson());

		assertEquals(Integer.valueOf(83), TaskTargetExtras.requirements(task("build_spirit_tree")).get(Skill.FARMING));
		assertEquals(Integer.valueOf(70), TaskTargetExtras.requirements(task("build_spirit_tree")).get(Skill.CONSTRUCTION));
		assertTrue(TaskTargetExtras.npcIds(task("cook_blurberry_special")).contains(6531));
		assertTrue(TaskTargetExtras.objectIds(task("agility_level_26_underwall_tunnel")).contains(16528));
	}

	@Test
	void missingOrBadSidecarLeavesExtrasEmpty()
	{
		TaskTargetExtras.load("{not json", new Gson());
		assertTrue(TaskTargetExtras.npcIds(task("cook_blurberry_special")).isEmpty());
		TaskTargetExtras.load(null, new Gson());
		assertTrue(TaskTargetExtras.objectIds(task("agility_level_26_underwall_tunnel")).isEmpty());
	}
}
