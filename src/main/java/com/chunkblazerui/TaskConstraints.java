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

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
public class TaskConstraints
{
	// ── AUTHORING RULE for RESTRICTED kills (time_in_ticks / equipment) ──────
	// These constraints are verified client-side from the local player's own
	// combat tracking, which enforces "you SOLOED it fast / while restricted",
	// not merely "you landed the killing blow". NPCKillModule requires the
	// fight to be a fresh session (relog gate) AND to have no other-player
	// damage (exclusive-damage gate). Therefore:
	//   • Only put time/equipment constraints on SOLOABLE overworld monsters
	//     a player can guarantee they fight alone.
	//   • NEVER on raid/team bosses (Verzik, Olm, ToA, GWD, Nex, Corp, etc.):
	//     the client can't see damage dealt on other players' clients, so the
	//     "fight" it measures is only your slice — both a false-pass hole and
	//     unfixable client-side. Author those as plain "defeat X" (KC-verified
	//     for tracked bosses).
	//   • Shared/multi-combat spawns are risky even solo (a passer-by's stray
	//     hit voids the attempt) — prefer instanced or low-traffic targets.
	@SerializedName("time_in_ticks")
	private Integer timeInTicks;

	@SerializedName("required_skill")
	private String requiredSkill;

	@SerializedName("required_level")
	private Integer requiredLevel;

	@SerializedName("required_region")
	private Integer requiredRegion;

	@SerializedName("allowed_regions")
	private List<Integer> allowedRegions;

	@SerializedName("min_combat_level")
	private Integer minCombatLevel;

	@SerializedName("max_combat_level")
	private Integer maxCombatLevel;

	@SerializedName("no_prayer")
	private Boolean noPrayer;

	@SerializedName("no_food")
	private Boolean noFood;

	// NOTE: there is deliberately no `solo_only` field. One existed here and was
	// never read by any module, so authoring it bought no enforcement while
	// reading like it did. Solo exclusivity is implied by a time or equipment
	// constraint (see NPCKillModule's contested/startedFresh gates); the inverse
	// — an encounter fought in a team — is the task-level `group_content` flag.

	// Equipment constraints
	@SerializedName("no_equipment")
	private Boolean noEquipment;  // Must have nothing equipped at all

	@SerializedName("required_equipment_ids")
	private List<Integer> requiredEquipmentIds;  // These items MUST be equipped

	@SerializedName("allowed_equipment_ids")
	private List<Integer> allowedEquipmentIds;  // ONLY these items can be equipped (all others fail)

	@SerializedName("forbidden_equipment_ids")
	private List<Integer> forbiddenEquipmentIds;  // These items must NOT be equipped

	// Slot-based equipment constraints (uses EquipmentInventorySlot indices)
	// Slots: HEAD=0, CAPE=1, AMULET=2, WEAPON=3, BODY=4, SHIELD=5, LEGS=7, GLOVES=9, BOOTS=10, RING=12, AMMO=13
	@SerializedName("must_be_empty")
	private List<Integer> mustBeEmptySlots;  // These equipment slots MUST be empty

	@SerializedName("equippable_slots")
	private List<Integer> equippableSlots;  // ONLY these slots can have equipment (all others must be empty)

	@SerializedName("equip_nothing")
	private Boolean equipNothing;  // If true, player must have ZERO equipment (nothing equipped at all)

	// Dropped item constraints (for NPC kill tasks that require specific loot)
	@SerializedName("dropped_item")
	private String droppedItem;  // Name of the required drop (for display)

	@SerializedName("dropped_item_id")
	private List<Integer> droppedItemIds;  // Item IDs that count as the required drop

	@SerializedName("quantity")
	private Integer droppedItemQuantity;  // Required quantity of the dropped item

	// Varbit constraints (e.g., prohibit cannon use during timed tasks)
	@SerializedName("prohibited_active_varbits")
	private List<VarbitConstraint> prohibitedActiveVarbits;

	// net.runelite.api.Quest enum constant name for QUEST_CHECK tasks, e.g.
	// "DRAGON_SLAYER_II". No varp id or expected value is stored alongside it:
	// Quest.getState() resolves the per-quest varp and its finished threshold
	// internally, so the constant name is the whole configuration.
	@SerializedName("quest")
	private String quest;

	// Direct varbit/varp ID for VARBIT_CHECK and VARP_CHECK tasks
	@SerializedName("varbit_id")
	private Integer varbitId;

	@SerializedName("varp_id")
	private Integer varpId;

	// Expected value for VARBIT_CHECK / VARP_CHECK exact-match tasks.
	// Moved into constraints from a top-level NuzlockeTask field so all
	// varbit-related schema lives in one place. NuzlockeTask still exposes
	// a deprecated top-level mirror for legacy JSON that hasn't been migrated.
	@SerializedName("varbit_boolean")
	private Integer varbitBoolean;

	// Bit position to test inside a bitmap varbit (e.g. ACTIVE_PRAYERS 4101).
	// Same provenance / fallback story as varbitBoolean above.
	@SerializedName("varbit_bit")
	private Integer varbitBit;

	/**
	 * Represents a varbit constraint that must be checked during task verification.
	 */
	@Data
	public static class VarbitConstraint
	{
		@SerializedName("varbit_id")
		private int varbitId;

		@SerializedName("must_be_value")
		private int mustBeValue;

		@SerializedName("fail_message")
		private String failMessage;
	}

	public int getRequiredLevel()
	{
		return requiredLevel != null ? requiredLevel : 1;
	}
}
