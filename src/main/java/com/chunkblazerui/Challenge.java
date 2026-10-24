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

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Data;

/**
 * The part of a boss challenge task's rules (ChunkBlazer's RaidChallenge) that can be
 * checked from what you're wearing, holding and doing right now. ChunkBlazer judges the
 * attempt; this is only for the task box to say whether you're set up for it.
 */
@Data
public class Challenge
{
	@SerializedName("no_run")
	private Boolean noRun;
	@SerializedName("weapon_ids")
	private List<Integer> weaponIds;
	@SerializedName("forbidden_weapon_ids")
	private List<Integer> forbiddenWeaponIds;
	@SerializedName("empty_slots")
	private List<Integer> emptySlots;
	@SerializedName("forbidden_item_ids")
	private List<Integer> forbiddenItemIds;
	@SerializedName("required_equipped_ids")
	private List<Integer> requiredEquippedIds;
	@SerializedName("required_equipped_groups")
	private List<List<Integer>> requiredEquippedGroups;
	@SerializedName("required_inventory_ids")
	private List<Integer> requiredInventoryIds;
	@SerializedName("required_inventory_groups")
	private List<List<Integer>> requiredInventoryGroups;
	@SerializedName("empty_inventory")
	private Boolean emptyInventory;
	@SerializedName("min_empty_inventory_slots")
	private Integer minEmptyInventorySlots;
	@SerializedName("forbidden_prayer_bits")
	private List<Integer> forbiddenPrayerBits;
	@SerializedName("max_player_hitpoints")
	private Integer maxPlayerHitpoints;
	@SerializedName("max_prayer")
	private Integer maxPrayer;
	@SerializedName("run_varp")
	private Integer runVarp;

	/** Whether any of these rules is set. */
	public boolean any()
	{
		return Boolean.TRUE.equals(noRun) || weaponIds != null || forbiddenWeaponIds != null || emptySlots != null
			|| forbiddenItemIds != null || requiredEquippedIds != null || requiredEquippedGroups != null
			|| requiredInventoryIds != null || requiredInventoryGroups != null || Boolean.TRUE.equals(emptyInventory)
			|| minEmptyInventorySlots != null || forbiddenPrayerBits != null || maxPlayerHitpoints != null
			|| maxPrayer != null;
	}
}
