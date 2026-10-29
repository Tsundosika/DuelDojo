package io.github.tsundosika.dueldojo;

import java.util.Locale;
import net.minecraft.ChatFormatting;

/**
 * The ten skill tiers, weakest (LT5) to strongest (HT1).
 *
 * <p>Every tier changes <em>behaviour</em>, not stats: all bots have 20 health, the same kit and the
 * same player physics. How a tier plays comes from {@link TierConfig#profile(Tier)}.
 */
public enum Tier {
	LT5("LT5", ChatFormatting.GRAY, "Times its hits but often a bit early, panic-clicks back when hit, walks straight in."),
	HT5("HT5", ChatFormatting.WHITE, "Still swings early and panic-clicks sometimes; rarely strafes, first W-taps and crit jumps."),
	LT4("LT4", ChatFormatting.GREEN, "Steadier timing, starts to strafe, W-taps and crit-jumps now and then."),
	HT4("HT4", ChatFormatting.DARK_GREEN, "Holds some spacing, W-taps about a third of the time, starts reading your cooldown."),
	LT3("LT3", ChatFormatting.AQUA, "Solid fundamentals: strafes half the time, W-taps, crits and jump resets more often."),
	HT3("HT3", ChatFormatting.DARK_AQUA, "Reads your cooldown about half the time and punishes early swings."),
	LT2("LT2", ChatFormatting.YELLOW, "Clean tracking, consistent crits and W-taps, rarely wastes a hit."),
	HT2("HT2", ChatFormatting.GOLD, "Out-spaces you and punishes every early swing."),
	LT1("LT1", ChatFormatting.RED, "Near-elite reactions and timing, punishes almost every mistake."),
	HT1("HT1", ChatFormatting.DARK_RED, "Human-elite ceiling: ~170 ms reactions, tight tracking, full technique every exchange.");

	public final String id;
	public final ChatFormatting color;
	public final String description;

	Tier(String id, ChatFormatting color, String description) {
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
