package io.github.tsundosika.dueldojo;

import java.util.Locale;

/**
 * Practice drills, one per kind of mistake the after-match tips point out. A drill is a 45-second session in the
 * arena against a bot of a chosen tier whose settings and attacks are changed so you get that one thing over and
 * over: nobody wins rounds, the score is how well you did it. Each tip has a [Practice] button for its drill.
 */
public enum Drill {
	TIMING("Timing", "Only full-charge hits count. The bot stays in reach and doesn't hit back.", BotBrain.Attacks.NEVER),
	AIM("Aim", "The bot strafes and jukes but never attacks. Land as many swings as you can.", BotBrain.Attacks.NEVER),
	REACH("Reach", "The bot hovers at the edge of your reach and baits. It only hits back when you whiff.", BotBrain.Attacks.AFTER_WHIFF),
	COMBO("Combo escape", "The bot goes for combos. Jump right as its hit lands (jump reset) to get out.", BotBrain.Attacks.ALWAYS),
	WTAP("W-tap", "The bot keeps walking back in. Let go of W after each hit so every hit is a sprint hit.", BotBrain.Attacks.NEVER),
	CRIT("Crits", "The bot keeps coming back after your hits. Jump and hit on the way down.", BotBrain.Attacks.NEVER),
	SPACING("Spacing", "The bot punishes you whenever you're in its reach while your sword recharges. Step back after you swing.",
			BotBrain.Attacks.ALWAYS),
	DODGE("Dodge", "You don't need to hit. Strafe and keep your distance so the bot's swings miss.", BotBrain.Attacks.ALWAYS);

	static final int SECONDS = 45;
	/** Below this much to judge, a drill gives no score. */
	private static final int MIN_TRIES = 5;

	public final String title;
	final String description;
	final BotBrain.Attacks attacks;

	Drill(String title, String description, BotBrain.Attacks attacks) {
		this.title = title;
		this.description = description;
		this.attacks = attacks;
	}

	String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	static Drill byId(String id) {
		for (Drill drill : values()) {
			if (drill.id().equalsIgnoreCase(id)) return drill;
		}
		return null;
	}

	/** The bot's settings for this drill: its tier's, with the few that make the drill work changed. */
	Profile profile(Profile tier) {
		Profile p = tier.lerp(tier, 0.0F); // a copy
		switch (this) {
			case TIMING -> {
				p.spacing = 2.4F; // stays in your reach
				p.strafe = Math.max(p.strafe, 0.5F);
			}
			case AIM -> {
				p.spacing = 2.8F;
				p.strafe = 1.0F;
				p.strafeSwitch = Math.min(p.strafeSwitch, 12.0F);
				p.aimReact = Math.max(p.aimReact, 0.7F);
			}
			case REACH -> {
				p.spacing = 3.5F; // right at the edge of your reach
				p.bait = 0.9F;
				p.readCooldown = 1.0F;
				p.hitSelect = 0.9F;
			}
			case COMBO -> {
				p.spacing = 2.6F;
				p.wtap = Math.max(p.wtap, 0.9F);
			}
			case WTAP, CRIT -> {
				p.spacing = 2.2F; // walks straight back in after every hit
				p.strafe = Math.min(p.strafe, 0.3F);
				p.jumpReset = 0.0F; // takes the full knockback
			}
			case SPACING -> {
				p.readCooldown = 1.0F; // always knows when your sword is down, and steps in to punish
				p.bait = 0.0F;
				p.hitSelect = 0.0F;
			}
			case DODGE -> {
				p.spacing = 2.6F; // keeps coming at you
				p.hitSelect = 0.0F;
				p.bait = 0.0F;
			}
		}
		return p.clamped();
	}

	/** The score in percent (higher is better), or -1 if there was too little to judge. */
	int score(FightStats you, FightStats bot) {
		return switch (this) {
			case TIMING -> you.swings < MIN_TRIES ? -1 : percent(you.swings - you.early, you.swings);
			case AIM, REACH -> you.swings < MIN_TRIES ? -1 : percent(you.hits, you.swings);
			case COMBO -> you.groundHitsTaken < 3 ? -1 : percent(you.jumpResets, you.groundHitsTaken);
			case WTAP -> you.hits < 3 ? -1 : percent(you.sprintHits, you.hits);
			case CRIT -> you.hits < 3 ? -1 : percent(you.crits, you.hits);
			case SPACING -> you.swings < MIN_TRIES ? -1 : percent(you.swings - you.swingPunished, you.swings);
			case DODGE -> bot.swings < MIN_TRIES ? -1 : percent(bot.swings - bot.hits, bot.swings);
		};
	}

	/** What the score counts, e.g. "14/18 full charge" (for the live bar and the result line). */
	String progress(FightStats you, FightStats bot) {
		return switch (this) {
			case TIMING -> (you.swings - you.early) + "/" + you.swings + " full charge, best streak " + you.bestStreak;
			case AIM -> you.hits + "/" + you.swings + " hits";
			case REACH -> you.hits + " hits, " + (you.swings - you.hits) + " whiffs";
			case COMBO -> you.jumpResets + "/" + you.groundHitsTaken + " jump resets in time";
			case WTAP -> you.sprintHits + "/" + you.hits + " sprint hits";
			case CRIT -> you.crits + "/" + you.hits + " crits";
			case SPACING -> (you.swings - you.swingPunished) + "/" + you.swings + " swings unpunished";
			case DODGE -> (bot.swings - bot.hits) + "/" + bot.swings + " of its swings missed";
		};
	}

	private static int percent(int part, int whole) {
		return whole == 0 ? 0 : Math.round(100.0F * part / whole);
	}
}
