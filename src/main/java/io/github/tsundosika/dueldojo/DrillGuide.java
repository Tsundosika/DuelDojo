package io.github.tsundosika.dueldojo;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a drill's tutorial says and checks: the pages of its window (shown by the client), and for the "try it" part
 * after that the thing to do and the key prompts for it (worked out on the server from what you're doing right now).
 */
public final class DrillGuide {
	public enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, ATTACK }

	/** The little scene playing on a page; client/DrillDemo has the script for each. */
	public enum Demo {
		TIMING_FULL, TIMING_EARLY, AIM_FOLLOW, AIM_WHIFF, REACH_STEP_IN, REACH_WHIFF, COMBO_NO_JUMP, COMBO_JUMP,
		WTAP_NO_TAP, WTAP_TAP, CRIT_FALLING, CRIT_RISING, SPACING_STAY, SPACING_BACK, DODGE_STRAFE
	}

	public record Page(String title, String text, Demo demo) {
	}

	public record Hint(String text, Key... keys) {
	}

	public final List<Page> pages;
	public final String objective;
	public final int goal;
	public final List<Hint> hints;
	/** "Try it" runs at half speed: the timing is too hard to get a feel for at full speed. */
	final boolean slowTry;

	private DrillGuide(List<Page> pages, String objective, int goal, boolean slowTry, Hint... hints) {
		this.pages = pages;
		this.objective = objective;
		this.goal = goal;
		this.slowTry = slowTry;
		this.hints = List.of(hints);
	}

	public static DrillGuide of(Drill drill) {
		return switch (drill) {
			case TIMING -> TIMING;
			case AIM -> AIM;
			case REACH -> REACH;
			case COMBO -> COMBO;
			case WTAP -> WTAP;
			case CRIT -> CRIT;
			case SPACING -> SPACING;
			case DODGE -> DODGE;
		};
	}

	private static final DrillGuide TIMING = new DrillGuide(List.of(
			new Page("Wait for full charge", "Your sword recharges after every swing. Swing when the charge bar is full: that's a strong hit.", Demo.TIMING_FULL),
			new Page("Too early is weak", "Swing early and the hit barely hurts. Your score is how many of your swings were at full charge.", Demo.TIMING_EARLY)),
			"Full-charge swings in a row", 3, false,
			new Hint("Wait for full charge"),
			new Hint("Full charge: click now", Key.ATTACK));

	private static final DrillGuide AIM = new DrillGuide(List.of(
			new Page("Keep your crosshair on it", "The bot strafes left and right and never hits back. Follow it with your mouse and click when you're on it.", Demo.AIM_FOLLOW),
			new Page("Don't click at air", "It changes direction when your aim catches up. Your score is how many of your swings hit.", Demo.AIM_WHIFF)),
			"Land hits", 3, false,
			new Hint("Crosshair on the bot, then click", Key.ATTACK));

	private static final DrillGuide REACH = new DrillGuide(List.of(
			new Page("Your reach is 3 blocks", "The bot waits just outside it. Step in until it's in reach, then hit it.", Demo.REACH_STEP_IN),
			new Page("A miss gets punished", "Swing at air and the bot rushes in and hits you. Your score is how many of your swings land.", Demo.REACH_WHIFF)),
			"Hits in a row, no misses", 3, false,
			new Hint("Too far: step closer, don't swing", Key.FORWARD),
			new Hint("In reach: hit!", Key.ATTACK));

	private static final DrillGuide COMBO = new DrillGuide(List.of(
			new Page("What a combo is", "Every hit knocks you back, and the bot walks right back in for the next one.", Demo.COMBO_NO_JUMP),
			new Page("Jump as the hit lands", "Press jump the moment its sword hits you. You fly back less and can hit back. That's a jump reset.", Demo.COMBO_JUMP)),
			"Jump resets", 2, true,
			new Hint("Jump as its sword hits you", Key.JUMP),
			new Hint("In the air: wait until you land"),
			new Hint("Too late: jump a bit sooner", Key.JUMP),
			new Hint("Too early: wait for the hit", Key.JUMP));

	private static final DrillGuide WTAP = new DrillGuide(List.of(
			new Page("Sprint hits knock back further", "But every sprint hit ends your sprint, so your next hit is a weak one.", Demo.WTAP_NO_TAP),
			new Page("Tap W after each hit", "Let go of W for a moment, then hold it again. You sprint again, so your next hit is a sprint hit too.", Demo.WTAP_TAP)),
			"W-tap, then a sprint hit", 3, false,
			new Hint("Sprint in and hit", Key.FORWARD, Key.ATTACK),
			new Hint("Let go of W", Key.FORWARD),
			new Hint("Hold W again, then hit", Key.FORWARD, Key.ATTACK));

	private static final DrillGuide CRIT = new DrillGuide(List.of(
			new Page("Hit while falling", "Jump, and hit on the way down. A crit does 50% more damage. Look for the stars.", Demo.CRIT_FALLING),
			new Page("Not on the way up", "A hit on the way up is a plain hit. Sprinting stops crits too, so let go of W: the bot comes to you.", Demo.CRIT_RISING)),
			"Land crits", 3, false,
			new Hint("Jump", Key.JUMP),
			new Hint("Going up: wait"),
			new Hint("Falling: hit now!", Key.ATTACK),
			new Hint("Let go of W: no crits while sprinting", Key.FORWARD));

	private static final DrillGuide SPACING = new DrillGuide(List.of(
			new Page("Your sword needs time", "After a swing your sword is weak for about half a second. If you stay close, the bot hits you for free.", Demo.SPACING_STAY),
			new Page("Step back after you swing", "Hit, back off with S, and come back when your sword is charged. Your score is the swings you didn't get hit after.", Demo.SPACING_BACK)),
			"Hit, then get away unhit", 3, false,
			new Hint("Step in and hit", Key.FORWARD, Key.ATTACK),
			new Hint("Step back!", Key.BACK));

	private static final DrillGuide DODGE = new DrillGuide(List.of(
			new Page("You don't need to hit", "Just make its swings miss. Strafe with A and D, and back off with S when it gets close. Your score is how many of its swings miss.", Demo.DODGE_STRAFE)),
			"Make its swings miss", 3, false,
			new Hint("Strafe left and right", Key.LEFT, Key.RIGHT),
			new Hint("Too close: back off", Key.BACK));

	/** How far along "try it" is, out of {@link #goal}. In-a-row goals can fall back to 0. */
	int done(Drill drill, FightStats you, FightStats bot) {
		return switch (drill) {
			case TIMING -> you.streak;
			case AIM -> you.hits;
			case REACH -> you.hitStreak;
			case COMBO -> you.jumpResets;
			case WTAP -> you.wTapHits;
			case CRIT -> you.crits;
			case SPACING -> you.cleanHits;
			case DODGE -> bot.swings - bot.hits;
		};
	}

	/** Which of {@link #hints} fits what you're doing right now. */
	int hint(Drill drill, ServerPlayer player, BotEntity bot, FightStats you) {
		double distance = Math.hypot(player.getX() - bot.getX(), player.getZ() - bot.getZ());
		boolean charged = player.getAttackStrengthScale(0.0F) >= 0.9F;
		return switch (drill) {
			case TIMING -> charged ? 1 : 0;
			case AIM -> 0;
			case REACH -> distance <= BotBrain.REACH_EDGE ? 1 : 0;
			case COMBO -> !player.onGround() ? 1 : you.jumpedLateJustNow() ? 2 : you.jumpedEarlyJustNow() ? 3 : 0;
			case WTAP -> you.wTapDue() ? 1 : !player.getLastClientInput().forward() ? 2 : 0;
			case CRIT -> !player.onGround() ? (player.fallDistance > 0.0 ? 2 : 1) : player.isSprinting() ? 3 : 0;
			case SPACING -> charged ? 0 : 1;
			case DODGE -> distance < BotBrain.REACH_EDGE ? 1 : 0;
		};
	}
}
