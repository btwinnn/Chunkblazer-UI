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
public class NuzlockeTask
{
	private String name;

	@SerializedName("taskID")
	private String taskId;

	private String category;

	/**
	 * Optional human-readable "how to complete this" blurb shown on the task card.
	 * Authored in the catalog; especially useful for raid/boss tasks whose name
	 * alone doesn't spell out the requirement (e.g. "Strapped Sands").
	 */
	private String description;

	/**
	 * The category as it should read in the plugin UI (filters, dropdowns, card
	 * labels). The catalog tags some item-set pools with a "_Set" suffix
	 * ("Herblore_Set", "Obtain_Set") to group authoring, but players just think
	 * of those as the base skill — so we fold "_Set" back onto its parent
	 * ("Herblore", "Obtain") for display and filtering. This is presentation only;
	 * the raw {@link #category} (and all task routing, which keys off
	 * completion_type) is untouched.
	 */
	public static String displayCategory(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return "Task"; // a task with no category in the catalog, rather than "null"
		}
		if (raw.length() > 4 && raw.regionMatches(true, raw.length() - 4, "_Set", 0, 4))
		{
			return raw.substring(0, raw.length() - 4);
		}
		return raw;
	}

	@SerializedName("completion_type")
	private String completionType;

	@SerializedName("base_points")
	private int basePoints;

	@SerializedName("assignment_weight")
	private int assignmentWeight;

	private Integer level;

	/**
	 * Marks a task as GROUP content (raids, Nex, group bosses) — an encounter a
	 * player is expected to fight alongside others.
	 *
	 * <p>Two things follow from it in {@code NPCKillModule}:
	 * <ul>
	 *   <li>The solo-only gates (contested / startedFresh / cannon / relog-grace)
	 *       are skipped. They exist for solo speed-and-gear challenges and are
	 *       unsatisfiable in a team: a teammate's hitsplat sets {@code contested},
	 *       and the boss is only at full health for whoever lands the very first
	 *       hit of the encounter.</li>
	 *   <li>The fight record's idle TTL is extended, so a long mechanic phase
	 *       where you land no hits doesn't discard your stake in the kill.</li>
	 * </ul>
	 *
	 * <p>The always-on {@code damage > 0} check still applies — you must have
	 * personally damaged the NPC that died. This flag widens who may ALSO have
	 * hit it; it never grants credit for a kill you took no part in.
	 *
	 * <p>Authoring a group task WITH a time or equipment constraint is a
	 * contradiction and is rejected at load — see {@link #getGroupContentSchemaError()}.
	 */
	@SerializedName("group_content")
	private Boolean groupContent;

	/**
	 * When true, this task cannot complete while the player is in a Theatre of Blood Entry
	 * Mode raid. The mode is latched from the "You enter the Theatre of Blood (X Mode)" chat
	 * banner (see ChunkBlazer's TobModeTracker); Entry is the learner tier,
	 * so flagged tasks are Normal/Hard only. Null-safe: absent means no gate.
	 */
	@SerializedName("forbid_entry_mode")
	private Boolean forbidEntryMode;

	@SerializedName("is_unlocked")
	private Boolean isUnlocked;

	/**
	 * For COMBAT_ACHIEVEMENT tasks: the Combat Achievement task ids that must ALL
	 * read complete for this task to finish. These are the sparse in-game CA ids
	 * (see CA_Struct_IDs.json), read from the packed CA-completion VarPlayers by
	 * {@code CombatAchievementModule}.
	 */
	@SerializedName("ca_ids")
	private List<Integer> caIds;

	@SerializedName("required_items")
	private List<RequiredItem> requiredItems;

	@SerializedName("required_object")
	private List<RequiredObject> requiredObjects;

	@SerializedName("target_npc")
	private TargetNpc targetNpc;

	// Agility course tasks: the course name in the game's "Your <name> lap count is: N."
	// message, e.g. "Draynor Village Rooftop". Each such message is one finished lap.
	@SerializedName("lap_message")
	private String lapMessage;

	private TaskConstraints constraints;

	// DEPRECATED top-level mirrors of varbit_boolean and varbit_bit. The
	// canonical home is now inside `constraints` (alongside varbit_id) so all
	// varbit-related schema lives in one place. The fields are kept here so
	// any pre-migration JSON that still puts them at the top level continues
	// to work — VarbitCheckModule falls back to these when the constraints
	// version is null. Remove after one release cycle once all task JSON has
	// been migrated.
	@Deprecated
	@SerializedName("varbit_boolean")
	private Integer varbitBoolean;

	@Deprecated
	@SerializedName("varbit_bit")
	private Integer varbitBit;

	// Runtime tracking fields (not from JSON)
	private transient int currentProgress;
	private transient int targetQuantity;
	private transient boolean completed;

	// Set true by the deserializer when the task JSON has a required_object
	// block. We don't model the full RequiredObject yet; AgilityModule needs
	// only "is this a lap-style task" to pick the right XP threshold.
	private transient boolean hasRequiredObject;

	public int getLevelRequirement()
	{
		return level != null ? level : 1;
	}

	/**
	 * EQUIP tasks only: require ALL of the required_items groups to be worn at once (a full
	 * set) rather than the default "any one item completes it". Each required_items entry is
	 * one group (a piece plus its accepted variants); the task completes when one item from
	 * EVERY group is equipped. Null-safe: absent means the default any-one behaviour.
	 */
	@SerializedName("require_all_equipped")
	private Boolean requireAllEquipped;
}
