package io.github.tsundosika.dueldojo;

import java.util.EnumMap;
import java.util.Map;
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
 * what the tips, drills and live coach look at: swings before the sword was charged, pick hits, hits with sprint
 * knockback, hits taken right after a missed swing, and, for a player, their keys: jump resets, W-taps and getting
 * out of combos.
 */
final class FightStats {
	/** A hit taken within this many ticks of your own missed swing counts as being punished for it. */
	private static final int PUNISH_WINDOW = 10;
	/**
	 * Jump pressed within this many ticks of the hit is a jump reset. The hit takes a tick to reach you and your key
	 * press another to come back, so a press right on the hit reads as 2 here (measured with a real key press).
	 */
	private static final int JUMP_RESET_WINDOW = 2;
	/** Up to this many ticks after the hit, a jump is a late one. */
	private static final int LATE_JUMP_WINDOW = 5;
	/** A jump this many ticks or less before a hit was just too early. */
	private static final int EARLY_JUMP_WINDOW = 6;
	/** Letting go of W within this many ticks after your hit counts as a W-tap. */
	private static final int WTAP_WINDOW = 8;
	/** S held within this many ticks of a hit is in time to get out: you're still flying back from it. */
	private static final int BACK_WINDOW = 6;
	/** Not hit again for this long after a hit means you got out: its next hit (about 13 ticks) didn't reach you. */
	private static final int ESCAPE_TICKS = 25;
	/** A pick hit lands at least this far away, centre to centre (your reach ends at about {@link BotBrain#REACH_EDGE}). */
	private static final double PICK_MIN_DISTANCE = 2.7;
	/** And on someone moving away from you at least this fast, in blocks per tick. */
	private static final double PICK_MIN_SPEED = 0.1;
	/** No hit from either side for this long and the fight is back to neutral: the next hit is a first hit. */
	private static final int NEUTRAL_TICKS = 30;
	/** A sprint hit this soon after you were hit is an answer to that hit: a deflection or hit select. */
	private static final int ANSWER_TICKS = 10;
	/** A crit this soon after you were hit, without having jumped since, is you falling from that knockback. */
	private static final int PUNISH_CRIT_TICKS = 20;
	/** A sprint hit from at least this far away, centre to centre, keeps the target from reaching you: a stun hit. */
	private static final double STUN_MIN_DISTANCE = 2.5;
	/** Moving up or down faster than this per tick, off the ground, is rising or falling. */
	private static final double VERTICAL_SPEED = 0.02;
	/** How long the tutorial's prompt keeps saying that a swing just missed. */
	private static final int MISS_NOTE_MEMORY = 15;

	int swings;
	int hits;
	int crits;
	float damage;
	int combo;
	int bestCombo;
	/** Swings at under 90% charge. */
	int early;
	/** Early swings that were on purpose: they landed at the far end of your reach on someone moving away. */
	int picks;
	/** Whether an early hit can count as a pick; off in the timing drill, where only the charge matters. */
	boolean allowPicks = true;
	int sprintHits;
	/** Hits taken right after a missed swing. */
	int punished;
	/** Hits taken right after any of your swings, while your sword recharged (once per swing). */
	int swingPunished;
	/** Full-charge swings in a row, and the longest such run. */
	int streak;
	int bestStreak;
	int hitsTaken;
	/** Hits taken standing on the ground: the only ones you can jump-reset (you can't jump in the air). */
	int groundHitsTaken;
	int jumpResets;
	int lateJumps;
	/** Hits you got away from: you held S or jump-reset in time, and weren't hit again soon after. */
	int escapes;
	/** First hits you landed when the fight was neutral, and the ones the other side did. */
	int firstHits;
	int firstHitsLost;
	/** Sprint hits landed from far enough away that the target can't reach you. */
	int stunHits;
	/** How often you pulled off each named move. */
	final Map<Move, Integer> moves = new EnumMap<>(Move.class);
	int wTaps;
	/** Sprint hits with a W-tap since your hit before them. */
	int wTapHits;
	/** Hits in a row without a missed swing. */
	int hitStreak;
	/** Hits you got away from: no hit taken in the time right after. */
	int cleanHits;
	/** Live notes for the player (the coach); null = none. */
	Consumer<Component> coach;

	private final SwingWatcher watcher = new SwingWatcher();
	private int ticks;
	private float lastCharge = 1.0F;
	private boolean landedThisTick;
	private boolean landedCrit;
	private boolean landedSprint;
	private boolean landedAtEdge;
	/** This tick's hit was your first hit of an exchange. */
	private boolean landedFirst;
	/** The named move this tick's hit was, or null. */
	private Move landedMove;
	private int lastMissTick = -1000;
	private int lastSwingTick = -1000;
	private int hitTakenTick = -1000;
	private int critTakenTick = -1000;
	/** The last hit from either side. */
	private int lastContactTick = -1000;
	private double lastY = Double.NaN;
	private double verticalSpeed;
	/** The tick your last hit taken counts as got away from, if nothing lands before then; -1 = none open. */
	private int escapeTick = -1;
	/** You did something about your last hit taken in time: S or a jump reset. */
	private boolean gettingOut;
	private boolean jumpJudged = true;
	private int lastJumpPressTick = -1000;
	private int hitLandedTick = -1000;
	private boolean wTapJudged = true;
	private boolean wTappedSinceHit;
	private boolean prevJump;
	private boolean prevForward;
	private boolean onGround = true;
	/** Your last hit, while it could still be punished; -1 = none. */
	private int cleanHitTick = -1;

	/** Called once per tick at the end of the server tick, after any hit of that tick has been counted. */
	void tick(LivingEntity self) {
		ticks++;
		if (watcher.update(self)) {
			swings++;
			// The charge at the end of the last tick is what the swing had (it resets the cooldown).
			boolean wasEarly = lastCharge < 0.9F;
			if (wasEarly && landedThisTick && landedAtEdge && allowPicks) {
				picks++;
				moved(Move.PICK);
			}
			if (wasEarly) {
				early++;
				streak = 0;
			} else {
				bestStreak = Math.max(bestStreak, ++streak);
			}
			if (landedThisTick) {
				cleanHitTick = ticks;
			} else {
				lastMissTick = ticks;
				hitStreak = 0;
			}
			lastSwingTick = ticks;
			noteSwing(!landedThisTick, wasEarly);
		}
		if (self instanceof ServerPlayer player) keys(player.getLastClientInput());
		if (cleanHitTick >= 0 && ticks - cleanHitTick > PUNISH_WINDOW) {
			cleanHits++;
			cleanHitTick = -1;
		}
		if (escapeTick >= 0 && ticks >= escapeTick) {
			escapeTick = -1;
			if (gettingOut) {
				escapes++;
				note("Got away ✓", ChatFormatting.GREEN);
			}
		}
		onGround = self.onGround();
		verticalSpeed = Double.isNaN(lastY) ? 0.0 : self.getY() - lastY;
		lastY = self.getY();
		landedThisTick = false;
		landedCrit = false;
		landedSprint = false;
		landedAtEdge = false;
		landedFirst = false;
		landedMove = null;
		lastCharge = charge(self);
	}

	/** Whether the sword was charged enough for a strong hit (sprint knockback, crits) just before this tick. */
	boolean charged() {
		return lastCharge >= 0.88F;
	}

	void landed(boolean crit, boolean sprintHit, float damageTaken, FightStats victim) {
		hits++;
		if (crit) crits++;
		if (sprintHit) {
			sprintHits++;
			if (wTappedSinceHit) wTapHits++;
		}
		wTappedSinceHit = false;
		hitStreak++;
		damage += damageTaken;
		bestCombo = Math.max(bestCombo, ++combo);
		landedThisTick = true;
		landedCrit = crit;
		landedSprint = sprintHit;
		hitLandedTick = ticks;
		wTapJudged = false;
		if (ticks - lastContactTick > NEUTRAL_TICKS) {
			firstHits++;
			landedFirst = true;
			victim.firstHitsLost++;
			victim.note("It got the first hit", ChatFormatting.YELLOW);
		}
		lastContactTick = ticks;
		victim.lastContactTick = victim.ticks;
		Move move = moveFor(crit, sprintHit);
		if (move != null) moved(move);
		if (crit) victim.critTakenTick = victim.ticks;
		victim.combo = 0;
		victim.hitsTaken++;
		victim.cleanHitTick = -1;
		victim.hitTakenTick = victim.ticks;
		// A second hit while you did nothing about the first: that's a combo starting.
		if (victim.escapeTick >= 0 && !victim.gettingOut) victim.note("Hold S to get out", ChatFormatting.YELLOW);
		victim.escapeTick = victim.ticks + ESCAPE_TICKS;
		victim.gettingOut = false;
		// onGround is from the end of the last tick, so the knockback of this hit hasn't changed it yet.
		if (victim.onGround) victim.groundHitsTaken++;
		victim.jumpJudged = !victim.onGround;
		int early = victim.ticks - victim.lastJumpPressTick + 1;
		if (early <= EARLY_JUMP_WINDOW) {
			victim.note("Jump " + early + (early == 1 ? " tick" : " ticks") + " too early", ChatFormatting.YELLOW);
		}
		if (victim.ticks - victim.lastMissTick <= PUNISH_WINDOW) {
			victim.punished++;
			victim.lastMissTick = -1000; // one punish per miss
		}
		if (victim.ticks - victim.lastSwingTick <= PUNISH_WINDOW) {
			victim.swingPunished++;
			victim.lastSwingTick = -1000;
		}
	}

	/** Which named move a hit landing now is, if any. */
	private Move moveFor(boolean crit, boolean sprintHit) {
		if (sprintHit) {
			if (ticks - hitTakenTick <= ANSWER_TICKS) return ticks - critTakenTick <= ANSWER_TICKS ? Move.DEFLECTION : Move.HIT_SELECT;
			if (!onGround && verticalSpeed > VERTICAL_SPEED) return Move.UPPERCUT;
			if (!onGround && verticalSpeed < -VERTICAL_SPEED) return Move.FALLING_SPRINT;
		}
		if (crit && ticks - hitTakenTick <= PUNISH_CRIT_TICKS && lastJumpPressTick < hitTakenTick) return Move.PUNISH_CRIT;
		return null;
	}

	private void moved(Move move) {
		moves.merge(move, 1, Integer::sum);
		landedMove = move;
	}

	/** This tick's hit landed at the far end of your reach on someone moving away: if it was early too, it's a pick hit. */
	void landedAtEdge() {
		landedAtEdge = true;
	}

	/** A sprint hit from here is far enough away that the target can't reach you. */
	static boolean atStunDistance(LivingEntity attacker, LivingEntity target) {
		return Math.hypot(target.getX() - attacker.getX(), target.getZ() - attacker.getZ()) >= STUN_MIN_DISTANCE;
	}

	static boolean atEdgeMovingAway(LivingEntity attacker, LivingEntity target) {
		double dx = target.getX() - attacker.getX();
		double dz = target.getZ() - attacker.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance < PICK_MIN_DISTANCE) return false;
		// Its movement over the last tick: the speed right now already has this hit's own knockback in it.
		return ((target.getX() - target.xo) * dx + (target.getZ() - target.zo) * dz) / distance > PICK_MIN_SPEED;
	}

	/** The player's keys this tick: a jump pressed right after being hit is a jump reset; W let go after a hit, a W-tap; S after a hit gets you out. */
	private void keys(Input input) {
		boolean jumpPressed = input.jump() && !prevJump;
		boolean forwardReleased = !input.forward() && prevForward;
		prevJump = input.jump();
		prevForward = input.forward();
		if (jumpPressed) lastJumpPressTick = ticks;
		int sinceHit = ticks - hitTakenTick - 1; // 0 = pressed on the tick of the hit
		if (jumpPressed && !jumpJudged && sinceHit <= LATE_JUMP_WINDOW) {
			jumpJudged = true;
			if (sinceHit <= JUMP_RESET_WINDOW) {
				jumpResets++;
				gettingOut = true;
				note("Jump reset ✓", ChatFormatting.GREEN);
			} else {
				lateJumps++;
				note("Jump reset " + (sinceHit - JUMP_RESET_WINDOW) + (sinceHit - JUMP_RESET_WINDOW == 1 ? " tick" : " ticks") + " late", ChatFormatting.YELLOW);
			}
		}
		if (input.backward() && sinceHit <= BACK_WINDOW) gettingOut = true;
		if (forwardReleased && !wTapJudged && ticks - hitLandedTick <= WTAP_WINDOW) {
			wTapJudged = true;
			wTaps++;
			wTappedSinceHit = true;
		}
	}

	/** You just hit something and haven't let go of W yet, but still could for a W-tap. */
	boolean wTapDue() {
		return !wTapJudged && ticks - hitLandedTick <= WTAP_WINDOW;
	}

	/** You were hit and it isn't settled yet whether you got away. */
	boolean justHit() {
		return escapeTick >= 0;
	}

	/** Early swings that weren't on purpose. */
	int earlyMistakes() {
		return early - picks;
	}

	/** A swing of yours just hit nothing. */
	boolean missedJustNow() {
		return ticks - lastMissTick <= MISS_NOTE_MEMORY;
	}

	private void noteSwing(boolean missed, boolean wasEarly) {
		int charge = Math.round(lastCharge * 100.0F);
		if (missed) {
			note(wasEarly ? "Whiff (early, " + charge + "%)" : "Whiff", ChatFormatting.GRAY);
		} else if (landedMove != null) {
			note(landedMove.title + " ✓", ChatFormatting.GREEN);
		} else if (landedFirst) {
			note("First hit ✓", ChatFormatting.GREEN);
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
		picks += round.picks;
		sprintHits += round.sprintHits;
		punished += round.punished;
		swingPunished += round.swingPunished;
		bestStreak = Math.max(bestStreak, round.bestStreak);
		hitsTaken += round.hitsTaken;
		groundHitsTaken += round.groundHitsTaken;
		jumpResets += round.jumpResets;
		lateJumps += round.lateJumps;
		escapes += round.escapes;
		firstHits += round.firstHits;
		firstHitsLost += round.firstHitsLost;
		stunHits += round.stunHits;
		round.moves.forEach((move, count) -> moves.merge(move, count, Integer::sum));
		wTaps += round.wTaps;
		wTapHits += round.wTapHits;
		cleanHits += round.cleanHits;
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
