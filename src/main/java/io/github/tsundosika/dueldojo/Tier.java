package io.github.tsundosika.dueldojo;

import java.util.Locale;

/**
 * The ten skill tiers, weakest (LT5) to strongest (HT1).
 *
 * <p>Every tier changes <em>behaviour</em>, not stats: all bots have 20 health, the same kit and the
 * same player physics. How a tier plays comes from {@link TierConfig#profile(Tier)}.
 */
public enum Tier {
	LT5("LT5", 0x284775, "Times its hits but often a bit early, panic-clicks back when hit, walks straight in."),
	HT5("HT5", 0x4974B5, "Still swings early and panic-clicks sometimes; rarely strafes, first W-taps and crit jumps."),
	LT4("LT4", 0x739F57, "Steadier timing, starts to strafe, W-taps and crit-jumps now and then."),
	HT4("HT4", 0x9FD27F, "Holds some spacing, W-taps about a third of the time, starts reading your cooldown."),
	LT3("LT3", 0xBD6B2B, "Solid fundamentals: strafes half the time, W-taps, crits, jump resets, backs out of combos."),
	HT3("HT3", 0xEB9138, "Reads your cooldown, punishes early swings, juking your aim, using the walls, first crit deflections."),
	LT2("LT2", 0x858D9C, "Jukes, baits, waits at the edge; hit-selects, deflects crits, uppercuts, starts reading your habits."),
	HT2("HT2", 0x9EB3D1, "Out-spaces you, pushes you into walls, falling sprint hits on your dodges, punishes every early swing."),
	LT1("LT1", 0xC4A34B, "Near-elite reactions and timing, punishes almost every mistake and adapts to what you keep doing."),
	HT1("HT1", 0xFFDA55, "Human-elite ceiling: ~170 ms reactions, tight tracking, full technique every exchange.");

	public final String id;
	/** Text colour (0xRRGGBB), the same as the usual tier-list colours. */
	public final int color;
	public final String description;

	Tier(String id, int color, String description) {
		this.id = id;
		this.color = color;
		this.description = description;
	}

	public static Tier byId(String id) {
		for (Tier t : values()) {
			if (t.id.equalsIgnoreCase(id)) return t;
		}
		return null;
	}

	public String lowerId() {
		return id.toLowerCase(Locale.ROOT);
	}

	public Tier next() {
		return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
	}
}
