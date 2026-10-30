package io.github.tsundosika.dueldojo;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;

/**
 * One fighter's numbers for one round (or, added up, a whole duel): swings, hits, crits, damage and combos, plus
 * what the tips, drills and live coach look at: swings before the sword was charged, hits with sprint knockback,
 * hits taken right after a missed swing, and, for a player, their keys: jump resets and W-taps.
 */
final class FightStats {
	/** A hit taken within this many ticks of your own missed swing counts as being punished for it. */
	private static final int PUNISH_WINDOW = 10;
	/** Jump pressed on the tick of the hit or the next one is a jump reset; up to this many ticks later, a late one. */
	private static final int LATE_JUMP_WINDOW = 5;
	/** Letting go of W within this many ticks after your hit counts as a W-tap. */
	private static final int WTAP_WINDOW = 8;

	int swings;
	int hits;
	int crits;
	float damage;
	int combo;
	int bestCombo;
	/** Swings at under 90% charge. */
	int early;
	int sprintHits;
	/** Hits taken right after a missed swing. */
	int punished;
	/** Hits taken right after any of your swings, while your sword recharged (once per swing). */
	int swingPunished;
	/** Full-charge swings in a row, and the longest such run. */
	int streak;
	int bestStreak;
	int hitsTaken;
	int jumpResets;
	int lateJumps;
	int wTaps;
	/** Live notes for the player (the coach); null = none. */
	Consumer<Component> coach;

	private final SwingWatcher watcher = new SwingWatcher();
	private int ticks;
	private float lastCharge = 1.0F;
	private boolean landedThisTick;
	private boolean landedCrit;
	private boolean landedSprint;
	private int lastMissTick = -1000;
	private int lastSwingTick = -1000;
	private int hitTakenTick = -1000;
	private boolean jumpJudged = true;
	private int hitLandedTick = -1000;
	private boolean wTapJudged = true;
	private boolean prevJump;
	private boolean prevForward;

	/** Called once per tick at the end of the server tick, after any hit of that tick has been counted. */
	void tick(LivingEntity self) {
		ticks++;
		if (watcher.update(self)) {
			swings++;
			// The charge at the end of the last tick is what the swing had (it resets the cooldown).
			boolean wasEarly = lastCharge < 0.9F;
			if (wasEarly) {
				early++;
				streak = 0;
			} else {
				bestStreak = Math.max(bestStreak, ++streak);
			}
			if (!landedThisTick) lastMissTick = ticks;
			lastSwingTick = ticks;
			noteSwing(!landedThisTick, wasEarly);
		}
		if (self instanceof ServerPlayer player) keys(player.getLastClientInput());
		landedThisTick = false;
		landedCrit = false;
		landedSprint = false;
		lastCharge = charge(self);
	}

	/** Whether the sword was charged enough for a strong hit (sprint knockback, crits) just before this tick. */
	boolean charged() {
		return lastCharge >= 0.88F;
	}

	void landed(boolean crit, boolean sprintHit, float damageTaken, FightStats victim) {
		hits++;
		if (crit) crits++;
		if (sprintHit) sprintHits++;
		damage += damageTaken;
		bestCombo = Math.max(bestCombo, ++combo);
		landedThisTick = true;
		landedCrit = crit;
		landedSprint = sprintHit;
		hitLandedTick = ticks;
		wTapJudged = false;
		victim.combo = 0;
		victim.hitsTaken++;
		victim.hitTakenTick = victim.ticks;
		victim.jumpJudged = false;
		if (victim.ticks - victim.lastMissTick <= PUNISH_WINDOW) {
			victim.punished++;
			victim.lastMissTick = -1000; // one punish per miss
		}
		if (victim.ticks - victim.lastSwingTick <= PUNISH_WINDOW) {
			victim.swingPunished++;
			victim.lastSwingTick = -1000;
		}
	}

	/** The player's keys this tick: a jump pressed right after being hit is a jump reset; W let go after a hit, a W-tap. */
	private void keys(Input input) {
		boolean jumpPressed = input.jump() && !prevJump;
		boolean forwardReleased = !input.forward() && prevForward;
		prevJump = input.jump();
		prevForward = input.forward();
		int sinceHit = ticks - hitTakenTick - 1; // 0 = pressed on the tick of the hit
		if (jumpPressed && !jumpJudged && sinceHit <= LATE_JUMP_WINDOW) {
			jumpJudged = true;
			if (sinceHit <= 1) {
				jumpResets++;
				note("Jump reset ✓", ChatFormatting.GREEN);
			} else {
				lateJumps++;
				note("Jump reset " + sinceHit + " ticks late", ChatFormatting.YELLOW);
			}
		}
		if (forwardReleased && !wTapJudged && ticks - hitLandedTick <= WTAP_WINDOW) {
			wTapJudged = true;
			wTaps++;
		}
	}

	private void noteSwing(boolean missed, boolean wasEarly) {
		int charge = Math.round(lastCharge * 100.0F);
		if (missed) {
			note(wasEarly ? "Whiff (early, " + charge + "%)" : "Whiff", ChatFormatting.GRAY);
		} else if (wasEarly) {
			note("Early hit (" + charge + "%)", ChatFormatting.YELLOW);
		} else if (landedCrit) {
			note("Crit ✓", ChatFormatting.GREEN);
		} else if (landedSprint) {
			note("Sprint hit ✓", ChatFormatting.GREEN);
		} else {
			note("Hit, no sprint knockback", ChatFormatting.GRAY);
		}
	}

	private void note(String text, ChatFormatting color) {
		if (coach != null) coach.accept(Component.literal(text).withStyle(color));
	}

	/** Adds one round's numbers, for the total of a first-to-N duel. */
	void add(FightStats round) {
		swings += round.swings;
		hits += round.hits;
		crits += round.crits;
		damage += round.damage;
		bestCombo = Math.max(bestCombo, round.bestCombo);
		early += round.early;
		sprintHits += round.sprintHits;
		punished += round.punished;
		swingPunished += round.swingPunished;
		bestStreak = Math.max(bestStreak, round.bestStreak);
		hitsTaken += round.hitsTaken;
		jumpResets += round.jumpResets;
		lateJumps += round.lateJumps;
		wTaps += round.wTaps;
	}

	int accuracy() {
		return swings == 0 ? 0 : Math.round(100.0F * Math.min(hits, swings) / swings);
	}

	private static float charge(LivingEntity entity) {
		if (entity instanceof Player player) return player.getAttackStrengthScale(0.5F);
		if (entity instanceof BotEntity bot) return bot.attackCharge();
		return 1.0F;
	}

	/**
	 * A melee hit was a crit if its damage before armor is above the attacker's full-charge damage: only the
	 * 1.5x crit bonus can push it over (true for the unenchanted duel kit).
	 */
	static boolean isCrit(DamageSource source, float baseDamage) {
		if (!(source.getEntity() instanceof LivingEntity attacker) || source.getDirectEntity() != attacker) return false;
		if (!attacker.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) return false;
		return baseDamage > attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) + 0.01;
	}
}
