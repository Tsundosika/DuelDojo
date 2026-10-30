package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.List;

/**
 * Tips after a match, from your own numbers in it. Each rule looks at one habit; the first three that apply, most
 * important first, are shown as one short line each, with the longer explanation on hover.
 */
final class Coach {
	private static final int MAX_TIPS = 3;

	/** {@code line}: the short chat line; {@code detail}: the hover text explaining it; {@code drill}: practice for it, or null. */
	record Tip(String line, String detail, Drill drill) {
	}

	private Coach() {
	}

	static List<Tip> tips(FightStats you, FightStats bot, Tier tier, boolean won) {
		List<Tip> tips = new ArrayList<>();
		if (you.swings >= 8 && you.early * 100 >= you.swings * 25) {
			tips.add(new Tip(percent(you.early, you.swings) + "% early swings: wait for full charge",
					"Those swings came before your sword was charged (under 90%). Early hits do less damage and no sprint knockback.",
					Drill.TIMING));
		}
		if (you.swings >= 8 && you.accuracy() < 70) {
			tips.add(new Tip(you.accuracy() + "% hit rate: click only when aimed",
					"A miss resets your sword too. Only click when the bot is in reach and on your crosshair.", Drill.AIM));
		}
		if (you.punished >= 2) {
			tips.add(new Tip("Hit " + you.punished + "x after missing: don't swing out of reach",
					"The bot hit you right after you missed a swing. When it's out of reach, don't swing: let it come to you.",
					Drill.REACH));
		}
		if (bot.bestCombo >= 3) {
			tips.add(new Tip("Took " + bot.bestCombo + " hits in a row: back off or jump-reset",
					"After you get hit, back off, or jump just as the hit lands (jump reset) to take less knockback, instead"
							+ " of walking straight back in. You jump-reset " + you.jumpResets + " of the " + you.groundHitsTaken
							+ " hits you took on the ground in time" + (you.lateJumps > 0 ? ", " + you.lateJumps + " too late." : "."),
					Drill.COMBO));
		}
		if (you.hits >= 5 && you.sprintHits * 100 < you.hits * 40) {
			tips.add(new Tip(percent(you.sprintHits, you.hits) + "% sprint hits: W-tap between hits",
					"Only those hits had sprint knockback. Let go of W for a moment after each hit (W-tap) so the next one"
							+ " knocks the bot back again.", Drill.WTAP));
		}
		if (you.swingPunished - you.punished >= 2) {
			tips.add(new Tip("Hit " + you.swingPunished + "x while recharging: back off after swinging",
					"After a swing your sword needs a moment. The bot hit you in that moment " + you.swingPunished
							+ " times. Back off out of its reach until you're charged again.", Drill.SPACING));
		}
		if (bot.swings >= 8 && bot.hits * 100 >= bot.swings * 85) {
			tips.add(new Tip("It hit " + Math.round(100.0F * bot.hits / bot.swings) + "% of its swings: strafe so it misses",
					"Moving sideways and keeping your distance makes its swings miss, and every miss leaves it open for a moment.",
					Drill.DODGE));
		}
		if (you.hits >= 8 && tier.ordinal() >= Tier.LT4.ordinal() && you.crits * 10 < you.hits) {
			tips.add(new Tip(you.crits + " crits in " + you.hits + " hits: crit while it flies back",
					"Jump and hit on the way down while the bot is flying back from your hit: crits do 50% more damage.", Drill.CRIT));
		}
		if (tips.isEmpty()) {
			Tier next = tier.next();
			tips.add(won
					? new Tip("Clean match" + (next != null ? ": try /dojo " + next.lowerId() : ""), "Nothing in your numbers stood out.", null)
					: new Tip("No big mistakes, it just played better", "Nothing in your numbers stood out. Keep going.", null));
		}
		return tips.subList(0, Math.min(MAX_TIPS, tips.size()));
	}

	private static int percent(int part, int whole) {
		return whole == 0 ? 0 : Math.round(100.0F * part / whole);
	}
}
