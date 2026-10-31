package io.github.tsundosika.dueldojo;

import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Decides, every tick, where the bot looks, how it moves and when it swings. One brain for all tiers: the
 * tier's {@link Profile} only changes the numbers.
 *
 * <p>The model follows how people actually aim and click, not fixed delays:
 * <ul>
 *   <li><b>Perception.</b> Aiming uses where you were one visual-feedback delay ago, partly extrapolated
 *       along your movement. Discrete events (your swing) are noticed after a log-normal reaction time.</li>
 *   <li><b>Aim.</b> Smooth pursuit follows your motion with a gain below 1; the leftover error is fixed by
 *       intermittent corrective flicks with a minimum-jerk speed profile, whose endpoint error grows with the
 *       flick's size, while the intended aim point itself wanders a little.</li>
 *   <li><b>Clicking.</b> The cooldown is timed by anticipation, with a spread proportional to the interval
 *       (Weber's law); low tiers aim for an earlier point and sometimes click straight back when hit. It clicks
 *       when its crosshair looks on you, judged on the delayed view it aims with, so it whiffs if you've moved.</li>
 *   <li><b>Decisions.</b> Approach, hold spacing, strafe, W-tap, crit-jump, jump-reset, and from about LT4 on
 *       read your cooldown: step in right after you swing, and only crit-jump when you can't answer.</li>
 *   <li><b>Reading you</b> (middle tiers up): it sees where you aim, with the same delay as everything else, and
 *       switches strafe direction once your aim catches up (juke); it baits, dipping into your reach and back out
 *       so you swing at air; it waits at the edge of reach for you to step in when you're both charged; and it
 *       plays the walls, circling so its hits knock you into one and getting out when it's pinned.</li>
 *   <li><b>The crit fight:</b> after a hit it answers straight back with a sprint hit if its sword is ready (a crit
 *       deflection or hit select), and otherwise holds S to get out of the combo, unless you're so close that a jump
 *       reset is better. It sprint-jumps in for uppercuts, keeps sprinting onto someone backing off
 *       (falling sprint hit), and catches you at the far end of its reach with an early hit (pick hit).</li>
 *   <li><b>Adapting</b> (over a series): it notices when you jump-reset, crit or back off a lot, and answers with
 *       delayed hits, more deflections and more falling sprint hits.</li>
 * </ul>
 */
final class BotBrain {
	private static final int HISTORY = 40;
	/** Ground covered per tick while sprinting. */
	private static final double SPRINT_PER_TICK = 0.28;
	/** Reach measured centre to centre: 3 blocks from the eyes to the edge of a hitbox about 0.3 wide on each side. */
	static final double REACH_EDGE = 3.3;
	/** Closer than this when hit, it wasn't a perfect stun hit from the edge of reach, so a jump reset beats holding S. */
	private static final double JUMP_RESET_RANGE = 2.9;
	/** How long it goes straight back in to answer a hit, in ticks. */
	private static final int ANSWER_TICKS = 8;
	/** How long it holds S to get out of a combo, and after answering a hit, in ticks. */
	private static final int HOLD_BACK_TICKS = 12;
	private static final int HOLD_AFTER_ANSWER_TICKS = 6;
	/** How long it holds its swing, waiting to be hit so it can answer, in ticks. */
	private static final int MAX_WAIT_TICKS = 14;
	/** How far a hit carries you per tick at first, in blocks: a sprint hit (0.5 knockback on top) and a plain one or crit. */
	private static final double SPRINT_KNOCKBACK_PER_TICK = 0.7;
	private static final double CRIT_KNOCKBACK_PER_TICK = 0.35;
	/**
	 * How much of its answers it plans by holding its swing. That hands them the hit (a counter gets you out of a bad
	 * spot, it doesn't win the exchange), and tested, a lot of it loses more than it gains. So it's the exception: mostly
	 * it would rather hit first, and answers when a hit lands with its sword ready anyway.
	 */
	private static final float WAIT_FOR_CRIT_SHARE = 0.1F;
	private static final float WAIT_FOR_HIT_SHARE = 0.5F;
	/** Someone coming at it this fast, in blocks per tick, can't be walked away from: holding S only loses time. */
	private static final double CHASED_SPEED = 0.2;

	private final BotEntity bot;
	private final Profile p;
	private final RandomSource random;
	private final SwingWatcher opponentSwings = new SwingWatcher();

	private final Vec3[] history = new Vec3[HISTORY];
	/** The opponent's view direction (yaw) at the same moments, to see where they aim. */
	private final float[] yawHistory = new float[HISTORY];
	private int historySize;
	private int head;
	private int age;

	// Aim.
	private float noiseYaw;
	private float noisePitch;
	private final ArrayDeque<float[]> flick = new ArrayDeque<>();
	private int correctionWait;
	private float lastWantYaw = Float.NaN;
	private float lastWantPitch;

	// Clicking: when it means to swing in this cooldown cycle, and whether it reads the opponent this cycle.
	private float swingAtTicks = -1.0F;
	private boolean readsThisCycle;

	// The opponent's swings, each known only after a reaction time: {tick it becomes known, tick it happened}.
	private final ArrayDeque<int[]> unseenSwings = new ArrayDeque<>();
	private int opponentSwingTick = -1000;

	// Movement.
	private int strafeDir = 1;
	private float strafeInput;
	private int strafeTimer;
	private int wtapPause;
	private boolean critPlan;
	/** This crit jump was a sprint-jump: keep sprinting until the fall. */
	private boolean critSprint;
	private boolean critDecided;
	private int critTimer;
	private int lastLandedTick = -1000;
	private int prevHurtTime;
	/** Tick of a reflex click back after getting hit, -1 = none planned. */
	private int panicTick = -1;
	/** The opponent's movement per tick, as the bot sees it (same delay and extrapolation as its aim). */
	private Vec3 seenVelocity = Vec3.ZERO;

	// Reading the opponent.
	private int jukeWait;
	private float lastToBot = Float.NaN;
	private boolean baitDecided;
	private int baitIn;
	private int baitOut;
	private int baitStart = -1000;
	private boolean holdsThisCycle;
	private int holdTicks;

	// The crit fight.
	/** Answering a hit: from answerAt (its reaction time) to answerUntil it goes back in and sprint-hits as soon as it can. */
	private int answerAt = -1;
	private int answerUntil = -1;
	private boolean answeringCrit;
	/** This cycle it means to let a crit or an approaching hit land, so its sword is ready to answer. */
	private boolean deflectsThisCycle;
	private boolean selectsThisCycle;
	private int waitTicks;
	/** It held its swing last tick, waiting to be hit. */
	private boolean waitingForHit;
	/** It holds S up to this tick. */
	private int backUntil = -1;
	private boolean uppercutDecided;
	private boolean fallDecided;
	private boolean fallingSprint;
	private boolean pickDecided;
	/** Up to this tick it watches whether you jump as its hit lands. */
	private int watchJumpUntil = -1;

	// Walls, probed every few ticks at body height: the nearest wall around the opponent (distance and direction),
	// the wall right behind the opponent and right behind the bot (on the line between them), and the bot's room
	// to its left and right.
	private double oppWall = 99.0;
	private double oppWallX;
	private double oppWallZ;
	private double oppBehind = 99.0;
	private double behind = 99.0;
	private double roomLeft = 99.0;
	private double roomRight = 99.0;
	private boolean wallPhase;

	/** When the bot may attack: always (duels), never (most drills), or only to punish a missed swing (reach drill). */
	enum Attacks { ALWAYS, NEVER, AFTER_WHIFF }

	private final Attacks attacks;
	/** In {@link Attacks#AFTER_WHIFF} mode: it may attack until this tick. */
	private int punishUntil = -1000;

	BotBrain(BotEntity bot, Profile profile) {
		this(bot, profile, Attacks.ALWAYS);
	}

	BotBrain(BotEntity bot, Profile profile, Attacks attacks) {
		this.bot = bot;
		this.p = profile;
		this.attacks = attacks;
		this.random = bot.getRandom();
	}

	/** What a bot did, added up over all its rounds: the behaviour part of the simulation report. */
	static final class Counts {
		int swings;
		int panicSwings;
		int critSwings;
		/** Swings at over 90% charge. */
		int strong;
		float chargeSum;
		int misses;
		int hits;
		int sprintHits;
		int crits;
		int critJumps;
		/** Crit jumps that came down without a swing. */
		int critsAbandoned;
		int wtaps;
		int hitsTaken;
		int jumpResets;
		/** Ticks within 6 blocks of the opponent, and how many of them strafing. */
		int closeTicks;
		int strafeTicks;
		int jukes;
		int baits;
		/** Opponent swings during a bait (within 10 ticks of it), and how many of them still hit. */
		int baitSwings;
		int baitHits;
		/** Cycles in which it held at the edge of reach. */
		int holds;
		/** Ticks close with the opponent pinned against a wall behind them, and ticks spent getting out of one itself. */
		int pinTicks;
		int escapeTicks;
		int deflections;
		int hitSelects;
		/** Hits taken after which it held S to get away. */
		int holdBacks;
		int uppercuts;
		int fallingSprints;
		int picks;
		int delayedHits;
		/** Times it held its swing to let them hit first; answers it went for, and how many came to nothing. */
		int waits;
		int answers;
		int answersLost;

		void add(Counts o) {
			waits += o.waits;
			answers += o.answers;
			answersLost += o.answersLost;
			deflections += o.deflections;
			hitSelects += o.hitSelects;
			holdBacks += o.holdBacks;
			uppercuts += o.uppercuts;
			fallingSprints += o.fallingSprints;
			picks += o.picks;
			delayedHits += o.delayedHits;
			jukes += o.jukes;
			baits += o.baits;
			baitSwings += o.baitSwings;
			baitHits += o.baitHits;
			holds += o.holds;
			pinTicks += o.pinTicks;
			escapeTicks += o.escapeTicks;
			swings += o.swings;
			panicSwings += o.panicSwings;
			critSwings += o.critSwings;
			strong += o.strong;
			chargeSum += o.chargeSum;
			misses += o.misses;
			hits += o.hits;
			sprintHits += o.sprintHits;
			crits += o.crits;
			critJumps += o.critJumps;
			critsAbandoned += o.critsAbandoned;
			wtaps += o.wtaps;
			hitsTaken += o.hitsTaken;
			jumpResets += o.jumpResets;
			closeTicks += o.closeTicks;
			strafeTicks += o.strafeTicks;
		}
	}

	/**
	 * What it has noticed about its opponent over a series, the way a person starts to read someone after a few
	 * exchanges. A habit only counts once there's enough of it to go on.
	 */
	static final class Habits {
		private static final int ENOUGH = 6;
		int hitsLanded;
		int jumpedOnHit;
		int hitsTaken;
		int critsTaken;
		int swings;
		int backedOffSwings;
		/** What it actually changed because of this, for the line after the match. */
		boolean adaptedToJumps;
		boolean adaptedToCrits;
		boolean adaptedToDodges;

		boolean jumpsOnHits() {
			return hitsLanded >= ENOUGH && jumpedOnHit * 100 >= hitsLanded * 35;
		}

		boolean crits() {
			return hitsTaken >= ENOUGH && critsTaken * 100 >= hitsTaken * 40;
		}

		boolean dodges() {
			return swings >= ENOUGH && backedOffSwings * 100 >= swings * 25;
		}
	}

	void faceInstantly(LivingEntity target) {
		float[] angles = anglesTo(aimPoint(target.position(), target));
		apply(angles[0], angles[1]);
	}

	void tick(ServerLevel level, LivingEntity target) {
		age++;
		// The sword's attack speed only applies after the bot's first tick, so the first cycle is planned here.
		if (swingAtTicks < 0) newCycle();
		record(target);
		boolean opponentSwung = opponentSwings.update(target);
		if (opponentSwung) unseenSwings.add(new int[] {age + reactionTicks(), age});
		while (!unseenSwings.isEmpty() && unseenSwings.peek()[0] <= age) opponentSwingTick = unseenSwings.poll()[1];
		boolean baiting = age - baitStart <= 10;
		if (opponentSwung && baiting) bot.counts.baitSwings++;
		if (age <= watchJumpUntil && jumping(target)) {
			bot.habits.jumpedOnHit++;
			watchJumpUntil = -1;
		}

		// 1. Perception and aim.
		Vec3 seen = perceivedPosition();
		aim(aimPoint(seen, target));

		// 2. Situation.
		double dx = seen.x - bot.getX();
		double dz = seen.z - bot.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		int closeTicks = (int) Math.ceil(Math.max(0.0, dist - 2.8) / SPRINT_PER_TICK);
		float ownReadyIn = swingAtTicks - bot.attackStrengthTicker;
		float opponentReadyIn = opponentSwingTick + cooldownOf(target) - age;
		boolean gotHit = bot.hurtTime > prevHurtTime;
		prevHurtTime = bot.hurtTime;
		if (gotHit) bot.counts.hitsTaken++;
		if (gotHit && baiting) bot.counts.baitHits++;
		// A swing that didn't hurt it was a miss: in the reach drill that's the only time it may hit back.
		if (opponentSwung && !gotHit) punishUntil = age + 20;
		boolean mayAttack = attacks == Attacks.ALWAYS || (attacks == Attacks.AFTER_WHIFF && age <= punishUntil);
		if (gotHit && random.nextFloat() < p.panicClick) panicTick = age + reactionTicks();
		double toX = dx / Math.max(dist, 1.0E-4);
		double toZ = dz / Math.max(dist, 1.0E-4);
		double approach = -(seenVelocity.x * toX + seenVelocity.z * toZ);
		if (gotHit) reactToHit(target, dist, approach, mayAttack);
		if (answerUntil >= 0 && age > answerUntil) {
			answerUntil = -1;
			bot.counts.answersLost++;
		}
		boolean answering = age >= answerAt && age <= answerUntil;
		// Back off while the sword recharges, like an S-tap; once it's about ready, it goes forward again for the sprint hit.
		boolean backing = age <= backUntil && !answering && ownReadyIn > 2.0F;
		// Letting them hit first, on purpose. Its sword is only ever ready to answer a hit if it didn't just swing, so with
		// its sword ready and them about to hit, it may hold its swing: coming down on it for a crit, that sets up a
		// deflection; close by mid-trade with their sword ready, a hit select. (From further out a hit select is a bad
		// idea: a well-spaced first hit stuns you before you can answer.)
		boolean inAir = !target.onGround();
		boolean waiting = mayAttack && !answering && !backing && !critPlan && waitTicks < MAX_WAIT_TICKS
				&& bot.attackCharge() > 0.9F && answerAt <= age
				&& (inAir ? deflectsThisCycle && dist < 4.5 : selectsThisCycle && readsThisCycle && opponentReadyIn <= 2.0F && dist < REACH_EDGE);
		if (waiting) {
			if (waitTicks == 0) bot.counts.waits++;
			waitTicks++;
		}
		waitingForHit = waiting;
		float healthGap = (target.getHealth() - bot.getHealth()) / target.getMaxHealth();
		float spacing = p.spacing + (healthGap > 0.2F ? 0.3F * p.adaptHealth : 0.0F);
		if (age % 4 == 0) probeWalls(level, seen, toX, toZ);
		boolean pinned = oppBehind < 2.0 && dist < 4.5; // a wall right behind the opponent: their knockback stops there
		if (pinned) bot.counts.pinTicks++;

		// Readers step in the moment the opponent has swung and can't answer in time (punish); otherwise everyone
		// goes in when their own swing is about due, a bit sooner when ahead on health or when the opponent is
		// pinned against a wall and can't back off.
		boolean punish = readsThisCycle && opponentReadyIn > closeTicks + 1 && ownReadyIn <= closeTicks + 1;
		float eager = (healthGap < -0.2F ? 2.0F * p.adaptHealth : 0.0F) + (pinned ? 3.0F * p.walls : 0.0F);
		boolean engage = punish || ownReadyIn <= 2.0F + eager;
		// Holding at the edge: when both swords are charged and the opponent is just out of reach, it doesn't walk into
		// their hit; it waits (for up to a second) so they step into its reach and it hits first.
		boolean holding = false;
		if (engage && !punish && holdsThisCycle && readsThisCycle && opponentReadyIn <= 0.0F && dist > REACH_EDGE && dist < 4.5
				&& holdTicks < 20) {
			if (holdTicks++ == 0) bot.counts.holds++;
			engage = false;
			holding = true;
		}
		// 3. Movement.
		float forward;
		boolean sprint = false;
		boolean jump = false;
		if (dist > 4.5) {
			forward = 1.0F;
			sprint = true;
		} else if (critPlan) {
			forward = dist > 2.2 ? 1.0F : 0.3F;
		} else if (holding) {
			forward = dist > REACH_EDGE + 0.5 ? 0.6F : 0.0F; // stays just outside reach
		} else if (engage) {
			forward = dist > 2.5 ? 1.0F : 0.4F;
			sprint = dist > 2.0;
		} else if (dist < spacing - 0.3) {
			forward = -1.0F; // back off while recharging (S-tap)
		} else if (dist > spacing + 0.4) {
			forward = 1.0F;
			sprint = dist > 3.2;
		} else {
			forward = 0.0F;
		}
		if (dist < 0.9) forward = -1.0F;

		// Bait: a reader that sees you're charged and waiting (not coming in) while it isn't charged may fake an
		// approach: it steps in from outside your reach and turns back just before entering it. Read by its movement
		// it looks like it's coming in, so a swing at that moment hits air, and then it punishes. If you start coming
		// in yourself, it drops the bait and backs off.
		if (!baitDecided && readsThisCycle && !engage && !holding && !critPlan && opponentReadyIn <= 0.0F && ownReadyIn > 6.0F
				&& approach < 0.04 && dist > REACH_EDGE + 0.6 && dist < 4.8 && bot.onGround()) {
			baitDecided = true;
			if (random.nextFloat() < p.bait) {
				baitIn = 6;
				baitStart = age;
				bot.counts.baits++;
			}
		}
		if (engage || critPlan) {
			baitIn = 0;
			baitOut = 0;
		}
		if (baitIn > 0) {
			forward = 1.0F;
			sprint = false;
			// It turns half a block early: its momentum still carries it about 0.3 blocks further in.
			if (--baitIn == 0 || dist < REACH_EDGE + 0.5 || approach > 0.08) {
				baitIn = 0;
				baitOut = 5;
			}
		} else if (baitOut > 0) {
			forward = -1.0F;
			sprint = false;
			baitOut--;
		}

		if (dist < 6.0) {
			if (--strafeTimer <= 0) {
				if (random.nextFloat() < 0.6F) strafeDir = -strafeDir;
				// Wall play: when the opponent is near a wall, circle round them so the bot's hits knock them into it.
				wallPhase = random.nextFloat() < p.walls;
				boolean circle = wallPhase && oppWall < 5.0 && toX * oppWallX + toZ * oppWallZ < 0.9;
				if (circle) strafeDir = toX * oppWallZ - toZ * oppWallX > 0.0 ? 1 : -1;
				strafeInput = circle || random.nextFloat() < p.strafe ? strafeDir : 0.0F;
				strafeTimer = Math.max(3, Math.round(p.strafeSwitch * (0.5F + random.nextFloat())));
			}
			if (wallPhase && behind < 2.5 && Math.max(roomLeft, roomRight) > 2.0) {
				// Pinned itself: slide out along the wall to the open side instead of backing into it.
				strafeInput = roomLeft > roomRight ? 1.0F : -1.0F;
				if (forward < 0.0F) forward = 0.0F;
				bot.counts.escapeTicks++;
			} else {
				juke(seen, dist);
			}
			bot.counts.closeTicks++;
			if (strafeInput != 0.0F) bot.counts.strafeTicks++;
		} else {
			strafeInput = 0.0F;
		}

		if (wtapPause > 0) {
			wtapPause--;
			forward = Math.min(forward, 0.0F);
			sprint = false;
		}
		if (answering) {
			forward = 1.0F;
			sprint = true;
		} else if (backing) {
			forward = -1.0F;
			sprint = false;
		} else if (waiting) {
			// Holding its swing, not its ground: sprinting at them takes less knockback and keeps them in reach for the answer.
			forward = dist > 2.4 ? 1.0F : 0.0F;
			sprint = dist > 2.4;
		}

		// Uppercut: from further out than walking in would allow, it sprint-jumps and hits on the way up.
		if (mayAttack && engage && sprint && !critPlan && !uppercutDecided && !answering && !backing && !waiting && bot.onGround()
				&& dist > 3.4 && dist < 4.6 && ownReadyIn <= 3.0F) {
			uppercutDecided = true;
			if (random.nextFloat() < p.uppercut) {
				jump = true;
				critDecided = true; // no crit from this jump: it stays on sprint for the hit
				bot.counts.uppercuts++;
			}
		}

		// Crit: jump about 6 ticks early so the fall lines up with the charge, and only if the opponent will be in
		// reach while it comes down (7 to 12 ticks after the jump). Both keep moving meanwhile: the opponent as the bot
		// sees it, the bot with its own momentum (air drag keeps about 6.5 ticks' worth) plus a little air control.
		// From further out it sprint-jumps (the jump's sprint boost, about 1.3 blocks more) and lets go of sprint once
		// it falls, since crits don't count while sprinting.
		// A reader only jumps when it won't be hit out of the air: right after the opponent swung, or while the
		// opponent is still flying from our hit. (Jumping into a ready opponent was tested and loses the first hit.)
		boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0.0 && bot.fallDistance > 0.0;
		Vec3 own = bot.getDeltaMovement();
		boolean sprintJump = dist > 2.6 && bot.food.canSprint();
		double ownAdvance = (own.x * toX + own.z * toZ) * 6.5 + (dist > 2.2 ? 0.6 : 0.2) + (sprintJump ? 1.3 : 0.0);
		// An opponent coming in stops at striking distance (about 2.4 blocks) instead of running through.
		double theirAdvance = -(seenVelocity.x * toX + seenVelocity.z * toZ) * 9.0;
		double gapAtFall = Math.max(dist - theirAdvance, Math.min(dist, 2.4)) - ownAdvance;
		boolean inReachAtFall = dist < 5.0 && gapAtFall < 2.8;
		boolean safeToJump = !readsThisCycle || opponentReadyIn > 8.0F || age - lastLandedTick < 10;
		if (mayAttack && !critPlan && !critDecided && !answering && !backing && !waiting && bot.onGround() && inReachAtFall && ownReadyIn <= 7.0F && safeToJump) {
			critDecided = true;
			if (random.nextFloat() < p.crit) {
				critPlan = true;
				critSprint = sprintJump;
				critTimer = 18;
				jump = true;
				forward = dist > 2.2 ? 1.0F : 0.3F; // as for the rest of the crit, from this tick on
				bot.counts.critJumps++;
			}
		}
		// Coming down onto someone backing off, a crit would fall short: it may keep sprinting for a falling sprint hit.
		if (critPlan && falling && !fallDecided) {
			fallDecided = true;
			boolean dodger = bot.habits.dodges();
			float chance = p.fallingSprint + (dodger ? p.adapt * 0.4F : 0.0F);
			fallingSprint = approach < -0.03 && bot.food.canSprint() && random.nextFloat() < chance;
			if (fallingSprint && dodger && p.adapt > 0.0F) bot.habits.adaptedToDodges = true;
		}
		if (critPlan) sprint = fallingSprint || (critSprint && !falling); // crits don't count while sprinting
		if (fallingSprint && critPlan) forward = 1.0F;
		// A jump reset is for when the opponent is too close to stun it; further out it holds S instead (see reactToHit).
		if (gotHit && !answering && !backing && age > answerUntil && bot.onGround() && dist < JUMP_RESET_RANGE && random.nextFloat() < p.jumpReset) {
			jump = true;
			bot.counts.jumpResets++;
		}
		if (bot.horizontalCollision && bot.onGround() && forward > 0.0F) jump = true;

		bot.zza = forward;
		bot.xxa = strafeInput;
		bot.setJumping(jump);
		bot.setSprinting(sprint && forward > 0.5F && bot.food.canSprint());

		// 4. Attack. It clicks when its crosshair looks on you within reach, judged on where it sees you; the swing
		// only connects if the real crosshair is on you within 3 blocks, so if you've moved since, it whiffs.
		boolean looksOnTarget = bot.crosshairOn(target, seen);
		boolean swing;
		if (answering && bot.attackCharge() > 0.9F && looksOnTarget) {
			swing = true;
		} else if (critPlan) {
			// Falling for real: a crit needs fall distance, which only builds once it has actually moved down (the
			// tick at the top of the jump already has a downward speed but no fall yet).
			swing = falling && bot.attackCharge() > 0.9F && looksOnTarget;
			if (swing) bot.counts.critSwings++;
			if (!swing && (--critTimer <= 0 || (bot.onGround() && critTimer < 14))) {
				critPlan = false;
				bot.counts.critsAbandoned++;
			}
		} else if (looksOnTarget && bot.attackStrengthTicker >= swingAtTicks && !waiting) {
			swing = true;
		} else if (mayAttack && !pickDecided && looksOnTarget && approach < -0.1 && dist >= 2.6 && age - lastLandedTick >= 10 && age - lastLandedTick <= 16) {
			// You're flying out of its reach from its last hit (10 ticks: the hit before can't be followed sooner) and its
			// sword isn't full yet: an early hit stops your knockback right at the end of its reach.
			pickDecided = true;
			swing = random.nextFloat() < p.pick;
			if (swing) bot.counts.picks++;
		} else {
			swing = age == panicTick && dist < 4.0; // clicks straight back after getting hit, ready or not
			if (swing) bot.counts.panicSwings++;
		}
		if (!mayAttack) swing = false;

		if (swing) {
			float charge = bot.attackCharge();
			boolean grounded = target.onGround();
			BotEntity.SwingResult result = bot.swingAt(level, target);
			count(charge, result);
			boolean landed = result != BotEntity.SwingResult.MISS;
			if (landed) lastLandedTick = age;
			noteSwing(result, grounded, approach);
			if (result == BotEntity.SwingResult.SPRINT_HIT) {
				if (answering) {
					if (answeringCrit) {
						bot.counts.deflections++;
					} else {
						bot.counts.hitSelects++;
					}
				} else if (fallingSprint) {
					bot.counts.fallingSprints++;
				}
			}
			if (answering) {
				// It stepped in to answer, so it backs straight off again to keep them from hitting back.
				answerUntil = -1;
				if (landed && random.nextFloat() < p.holdBack) backUntil = age + HOLD_AFTER_ANSWER_TICKS;
			}
			newCycle();
			if (landed && !answering && random.nextFloat() < p.wtap) {
				wtapPause = 2 + random.nextInt(3);
				bot.counts.wtaps++;
			}
		}
	}

	/**
	 * It just got hit. If it was waiting for that, or its sword happens to be ready by the time it reacts, it may answer
	 * straight back with a sprint hit: after a crit that's a deflection, after anything else a hit select. If not, it may
	 * hold S to get out of the combo, unless it's on the ground with the opponent so close that a jump reset is better.
	 */
	private void reactToHit(LivingEntity target, double dist, double approach, boolean mayAttack) {
		Habits habits = bot.habits;
		boolean crit = !target.onGround() && target.fallDistance > 0.0;
		habits.hitsTaken++;
		if (crit) habits.critsTaken++;
		// Waiting for the hit, it sees it coming and times its answer as well as it predicts anything, right on the hit at
		// the very top; caught out, it's a full reaction. By then the knockback may have carried it out of reach.
		int reaction = waitingForHit ? roundRandomly(p.trackDelayMs / 50.0 * (1.0 - p.prediction)) : reactionTicks();
		boolean stillInReach = dist + reaction * (crit ? CRIT_KNOCKBACK_PER_TICK : SPRINT_KNOCKBACK_PER_TICK) <= REACH_EDGE;
		float chance = waitingForHit ? 1.0F : crit ? deflectChance() : p.hitSelect;
		if (mayAttack && stillInReach && bot.attackCharge(reaction) > 0.9F && random.nextFloat() < chance) {
			// An earlier answer that never got its swing in is gone by now.
			if (answerUntil >= 0) bot.counts.answersLost++;
			bot.counts.answers++;
			answerAt = age + reaction;
			answerUntil = answerAt + ANSWER_TICKS;
			answeringCrit = crit;
			critPlan = false;
			wtapPause = 0;
			if (crit && habits.crits() && p.adapt > 0.0F) habits.adaptedToCrits = true;
		} else if ((dist >= JUMP_RESET_RANGE || !bot.onGround()) && approach < CHASED_SPEED && random.nextFloat() < p.holdBack) {
			backUntil = age + HOLD_BACK_TICKS;
			critPlan = false;
			bot.counts.holdBacks++;
		}
	}

	/** Against someone who crits a lot, it goes for deflections more. */
	private float deflectChance() {
		return p.deflect + (bot.habits.crits() ? p.adapt * 0.3F : 0.0F);
	}

	/** What this swing says about the opponent's habits: did it hit them on the ground, and did they back off from it? */
	private void noteSwing(BotEntity.SwingResult result, boolean grounded, double approach) {
		Habits habits = bot.habits;
		habits.swings++;
		if (result == BotEntity.SwingResult.MISS) {
			if (approach < -0.05) habits.backedOffSwings++;
		} else if (grounded) {
			habits.hitsLanded++;
			watchJumpUntil = age + 3;
		}
	}

	/** Whether this fighter is jumping right now: you see a person jump, and a player's jump key says the same. */
	private static boolean jumping(LivingEntity entity) {
		if (entity instanceof ServerPlayer player) return player.getLastClientInput().jump();
		return entity instanceof BotEntity other && other.tickCount - other.lastJumpTick <= 1;
	}

	private void count(float charge, BotEntity.SwingResult result) {
		BotBrain.Counts c = bot.counts;
		c.swings++;
		c.chargeSum += charge;
		if (charge > 0.9F) c.strong++;
		switch (result) {
			case MISS -> c.misses++;
			case HIT -> c.hits++;
			case SPRINT_HIT -> {
				c.hits++;
				c.sprintHits++;
			}
			case CRIT -> {
				c.hits++;
				c.crits++;
			}
		}
	}

	private void newCycle() {
		float cooldown = bot.cooldownTicks();
		swingAtTicks = Math.max(1.0F, cooldown * (p.chargeTarget + (float) random.nextGaussian() * p.timingCv) - 0.5F);
		readsThisCycle = random.nextFloat() < p.readCooldown;
		critPlan = false;
		critDecided = false;
		baitDecided = false;
		holdsThisCycle = random.nextFloat() < p.edgeHold;
		holdTicks = 0;
		uppercutDecided = false;
		fallDecided = false;
		fallingSprint = false;
		pickDecided = false;
		deflectsThisCycle = random.nextFloat() < deflectChance() * WAIT_FOR_CRIT_SHARE;
		selectsThisCycle = random.nextFloat() < p.hitSelect * WAIT_FOR_HIT_SHARE;
		waitTicks = 0;
		// Against someone who jumps as hits land, a hit a little later than usual makes that jump come too early.
		if (bot.habits.jumpsOnHits() && random.nextFloat() < p.adapt) {
			swingAtTicks += 2 + random.nextInt(3);
			bot.habits.adaptedToJumps = true;
			bot.counts.delayedHits++;
		}
	}

	/**
	 * Juke: it sees where you aim (as delayed as the rest of its view). While it strafes and your aim has caught up
	 * with it, on it or already ahead of it in the direction it's moving, it may switch direction. After deciding it
	 * needs a reaction time before it can do so again.
	 */
	private void juke(Vec3 seen, double dist) {
		float toBot = yawFrom(seen, bot.position()); // where you'd have to look to aim straight at it
		float orbit = Float.isNaN(lastToBot) ? 0.0F : Mth.wrapDegrees(toBot - lastToBot);
		lastToBot = toBot;
		if (strafeInput == 0.0F || dist > 4.5 || --jukeWait > 0 || Math.abs(orbit) < 0.3F) return;
		float aimOff = Mth.wrapDegrees(pastYaw(Math.round(p.trackDelayMs / 50.0F)) - toBot);
		if (Math.abs(aimOff) > 4.0F && Math.signum(aimOff) != Math.signum(orbit)) return; // your aim still trails it
		jukeWait = reactionTicks() + 2;
		if (random.nextFloat() < p.aimReact) {
			strafeDir = -strafeDir;
			strafeInput = strafeDir;
			strafeTimer = Math.max(3, Math.round(p.strafeSwitch * (0.5F + random.nextFloat())));
			bot.counts.jukes++;
		}
	}

	private void probeWalls(ServerLevel level, Vec3 seen, double toX, double toZ) {
		oppWall = 99.0;
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4.0;
			double d = room(level, seen, Math.cos(a), Math.sin(a), 6.0);
			if (d < oppWall) {
				oppWall = d;
				oppWallX = Math.cos(a);
				oppWallZ = Math.sin(a);
			}
		}
		oppBehind = room(level, seen, toX, toZ, 6.0);
		Vec3 self = bot.position();
		behind = room(level, self, -toX, -toZ, 6.0);
		roomLeft = room(level, self, toZ, -toX, 6.0); // left of the bot as it faces the opponent
		roomRight = room(level, self, -toZ, toX, 6.0);
	}

	/** Free distance from {@code from} along (x, z) at body height, in half-block steps, up to {@code max}. */
	private static double room(ServerLevel level, Vec3 from, double x, double z, double max) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (double d = 0.5; d <= max; d += 0.5) {
			pos.set(Mth.floor(from.x + x * d), Mth.floor(from.y + 0.6), Mth.floor(from.z + z * d));
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return d;
		}
		return max;
	}

	/** The view yaw that looks from {@code from} straight at {@code to}. */
	private static float yawFrom(Vec3 from, Vec3 to) {
		return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
	}

	private static float cooldownOf(LivingEntity entity) {
		return entity.getAttributes().hasAttribute(Attributes.ATTACK_SPEED)
				? (float) (20.0 / entity.getAttributeValue(Attributes.ATTACK_SPEED))
				: 12.5F;
	}

	/** A log-normal reaction time, in ticks. */
	private int reactionTicks() {
		return stochasticRound(p.reactMs * Math.exp(random.nextGaussian() * p.reactSpread) / 50.0);
	}

	/** Rounds up with probability equal to the fraction, so 2.4 ticks means 2 or 3 ticks, 2.4 on average. */
	private int stochasticRound(double ticks) {
		return Math.max(1, roundRandomly(ticks));
	}

	private int roundRandomly(double ticks) {
		int whole = (int) ticks;
		if (random.nextDouble() < ticks - whole) whole++;
		return whole;
	}

	private void record(LivingEntity target) {
		history[head] = target.position();
		yawHistory[head] = target.getYRot();
		head = (head + 1) % HISTORY;
		if (historySize < HISTORY) historySize++;
	}

	private Vec3 past(int ticksAgo) {
		int n = Math.min(ticksAgo, historySize - 1);
		return history[Math.floorMod(head - 1 - n, HISTORY)];
	}

	private float pastYaw(int ticksAgo) {
		int n = Math.min(ticksAgo, historySize - 1);
		return yawHistory[Math.floorMod(head - 1 - n, HISTORY)];
	}

	private Vec3 pastAt(double ticksAgo) {
		int whole = (int) Math.floor(ticksAgo);
		Vec3 a = past(whole);
		return a.add(past(whole + 1).subtract(a).scale(ticksAgo - whole));
	}

	private Vec3 perceivedPosition() {
		double delay = p.trackDelayMs / 50.0;
		Vec3 seen = pastAt(delay);
		Vec3 velocity = seen.subtract(pastAt(delay + 1.0));
		seenVelocity = velocity;
		return seen.add(velocity.scale(delay * p.prediction));
	}

	private static Vec3 aimPoint(Vec3 feet, LivingEntity target) {
		return feet.add(0, target.getBbHeight() * 0.65, 0);
	}

	private void aim(Vec3 point) {
		float[] want = anglesTo(point);
		// The intended aim point wanders (Ornstein-Uhlenbeck, ~5-tick memory); its spread is p.aimNoise degrees.
		noiseYaw = noiseYaw * 0.8F + (float) random.nextGaussian() * p.aimNoise * 0.6F;
		noisePitch = noisePitch * 0.8F + (float) random.nextGaussian() * p.aimNoise * 0.3F;
		float goalYaw = want[0] + noiseYaw;
		float goalPitch = want[1] + noisePitch;

		// Smooth pursuit: follow the target's angular motion, but not fully.
		float stepYaw = 0.0F;
		float stepPitch = 0.0F;
		if (!Float.isNaN(lastWantYaw)) {
			stepYaw = Mth.wrapDegrees(want[0] - lastWantYaw) * p.pursuit;
			stepPitch = (want[1] - lastWantPitch) * p.pursuit;
		}
		lastWantYaw = want[0];
		lastWantPitch = want[1];

		// Corrective flicks, one at a time: the next is planned only after seeing where this one landed.
		if (flick.isEmpty() && --correctionWait <= 0) {
			float errYaw = Mth.wrapDegrees(goalYaw - bot.getYRot() - stepYaw);
			float errPitch = goalPitch - bot.getXRot() - stepPitch;
			float deadZone = 0.3F + p.aimNoise * 0.3F;
			if (Math.abs(errYaw) > deadZone || Math.abs(errPitch) > deadZone) {
				planFlick(errYaw, errPitch);
				correctionWait = flick.size() + stochasticRound(p.trackDelayMs / 50.0);
			} else {
				correctionWait = 1;
			}
		}
		if (!flick.isEmpty()) {
			float[] step = flick.poll();
			stepYaw += step[0];
			stepPitch += step[1];
		}
		stepYaw = Mth.clamp(stepYaw, -p.maxTurn, p.maxTurn);
		stepPitch = Mth.clamp(stepPitch, -p.maxTurn, p.maxTurn);
		apply(bot.getYRot() + stepYaw, Mth.clamp(bot.getXRot() + stepPitch, -90.0F, 90.0F));
	}

	private void planFlick(float errYaw, float errPitch) {
		// Signal-dependent noise: the bigger the flick, the bigger its endpoint error.
		float yaw = errYaw * (1.0F + (float) random.nextGaussian() * p.flickError);
		float pitch = errPitch * (1.0F + (float) random.nextGaussian() * p.flickError);
		int ticks = Mth.clamp((int) Math.ceil(Math.max(Math.abs(yaw), Math.abs(pitch)) / (p.maxTurn * 0.6F)), 1, 6);
		float done = 0.0F;
		for (int i = 1; i <= ticks; i++) {
			float t = (float) i / ticks;
			float pos = t * t * t * (10.0F - 15.0F * t + 6.0F * t * t); // minimum-jerk position profile
			flick.add(new float[] {yaw * (pos - done), pitch * (pos - done)});
			done = pos;
		}
	}

	private float[] anglesTo(Vec3 point) {
		Vec3 eye = bot.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
		return new float[] {yaw, pitch};
	}

	private void apply(float yaw, float pitch) {
		bot.setYRot(yaw);
		bot.setXRot(pitch);
		bot.yHeadRot = yaw;
		bot.yBodyRot = yaw;
	}
}
