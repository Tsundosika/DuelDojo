package io.github.tsundosika.dueldojo;

import java.util.ArrayDeque;
import net.minecraft.server.level.ServerLevel;
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
 * </ul>
 */
final class BotBrain {
	private static final int HISTORY = 40;
	/** Ground covered per tick while sprinting. */
	private static final double SPRINT_PER_TICK = 0.28;

	private final BotEntity bot;
	private final Profile p;
	private final RandomSource random;
	private final SwingWatcher opponentSwings = new SwingWatcher();

	private final Vec3[] history = new Vec3[HISTORY];
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

	BotBrain(BotEntity bot, Profile profile) {
		this.bot = bot;
		this.p = profile;
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

		void add(Counts o) {
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

	void faceInstantly(LivingEntity target) {
		float[] angles = anglesTo(aimPoint(target.position(), target));
		apply(angles[0], angles[1]);
	}

	void tick(ServerLevel level, LivingEntity target) {
		age++;
		// The sword's attack speed only applies after the bot's first tick, so the first cycle is planned here.
		if (swingAtTicks < 0) newCycle();
		record(target);
		if (opponentSwings.update(target)) unseenSwings.add(new int[] {age + reactionTicks(), age});
		while (!unseenSwings.isEmpty() && unseenSwings.peek()[0] <= age) opponentSwingTick = unseenSwings.poll()[1];

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
		if (gotHit && random.nextFloat() < p.panicClick) panicTick = age + reactionTicks();
		float behind = (target.getHealth() - bot.getHealth()) / target.getMaxHealth();
		float spacing = p.spacing + (behind > 0.2F ? 0.3F * p.adaptHealth : 0.0F);

		// Readers step in the moment the opponent has swung and can't answer in time (punish); otherwise everyone
		// goes in when their own swing is about due, a bit sooner when ahead on health.
		boolean punish = readsThisCycle && opponentReadyIn > closeTicks + 1 && ownReadyIn <= closeTicks + 1;
		float eager = behind < -0.2F ? 2.0F * p.adaptHealth : 0.0F;
		boolean engage = punish || ownReadyIn <= 2.0F + eager;
		// 3. Movement.
		float forward;
		boolean sprint = false;
		boolean jump = false;
		if (dist > 4.5) {
			forward = 1.0F;
			sprint = true;
		} else if (critPlan) {
			forward = dist > 2.2 ? 1.0F : 0.3F;
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

		if (dist < 6.0) {
			if (--strafeTimer <= 0) {
				if (random.nextFloat() < 0.6F) strafeDir = -strafeDir;
				strafeInput = random.nextFloat() < p.strafe ? strafeDir : 0.0F;
				strafeTimer = Math.max(3, Math.round(p.strafeSwitch * (0.5F + random.nextFloat())));
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

		// Crit: jump about 6 ticks early so the fall lines up with the charge, and only if the opponent will be in
		// reach while it comes down (7 to 12 ticks after the jump). Both keep moving meanwhile: the opponent as the bot
		// sees it, the bot with its own momentum (air drag keeps about 6.5 ticks' worth) plus a little air control.
		// From further out it sprint-jumps (the jump's sprint boost, about 1.3 blocks more) and lets go of sprint once
		// it falls, since crits don't count while sprinting.
		// A reader only jumps when it won't be hit out of the air: right after the opponent swung, or while the
		// opponent is still flying from our hit. (Jumping into a ready opponent was tested and loses the first hit.)
		boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0.0 && bot.fallDistance > 0.0;
		double toX = dx / Math.max(dist, 1.0E-4);
		double toZ = dz / Math.max(dist, 1.0E-4);
		Vec3 own = bot.getDeltaMovement();
		boolean sprintJump = dist > 2.6 && bot.food.canSprint();
		double ownAdvance = (own.x * toX + own.z * toZ) * 6.5 + (dist > 2.2 ? 0.6 : 0.2) + (sprintJump ? 1.3 : 0.0);
		// An opponent coming in stops at striking distance (about 2.4 blocks) instead of running through.
		double theirAdvance = -(seenVelocity.x * toX + seenVelocity.z * toZ) * 9.0;
		double gapAtFall = Math.max(dist - theirAdvance, Math.min(dist, 2.4)) - ownAdvance;
		boolean inReachAtFall = dist < 5.0 && gapAtFall < 2.8;
		boolean safeToJump = !readsThisCycle || opponentReadyIn > 8.0F || age - lastLandedTick < 10;
		if (!critPlan && !critDecided && bot.onGround() && inReachAtFall && ownReadyIn <= 7.0F && safeToJump) {
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
		if (critPlan) sprint = critSprint && !falling; // crits don't count while sprinting
		if (gotHit && bot.onGround() && random.nextFloat() < p.jumpReset) {
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
		if (critPlan) {
			// Falling for real: a crit needs fall distance, which only builds once it has actually moved down (the
			// tick at the top of the jump already has a downward speed but no fall yet).
			swing = falling && bot.attackCharge() > 0.9F && looksOnTarget;
			if (swing) bot.counts.critSwings++;
			if (!swing && (--critTimer <= 0 || (bot.onGround() && critTimer < 14))) {
				critPlan = false;
				bot.counts.critsAbandoned++;
			}
		} else if (looksOnTarget && bot.attackStrengthTicker >= swingAtTicks) {
			swing = true;
		} else {
			swing = age == panicTick && dist < 4.0; // clicks straight back after getting hit, ready or not
			if (swing) bot.counts.panicSwings++;
		}

		if (swing) {
			float charge = bot.attackCharge();
			BotEntity.SwingResult result = bot.swingAt(level, target);
			count(charge, result);
			if (result != BotEntity.SwingResult.MISS) lastLandedTick = age;
			newCycle();
			if (result != BotEntity.SwingResult.MISS && random.nextFloat() < p.wtap) {
				wtapPause = 2 + random.nextInt(3);
				bot.counts.wtaps++;
			}
		}
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
		int whole = (int) ticks;
		if (random.nextDouble() < ticks - whole) whole++;
		return Math.max(1, whole);
	}

	private void record(LivingEntity target) {
		history[head] = target.position();
		head = (head + 1) % HISTORY;
		if (historySize < HISTORY) historySize++;
	}

	private Vec3 past(int ticksAgo) {
		int n = Math.min(ticksAgo, historySize - 1);
		return history[Math.floorMod(head - 1 - n, HISTORY)];
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
