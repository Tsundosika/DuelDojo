package io.github.tsundosika.dueldojo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Every number that makes one tier play differently. All bots share player rules; only these differ.
 * A tier's profile is interpolated between two anchors, {@link #beginner()} at skill 0 (LT5) and
 * {@link #elite()} at skill 1 (HT1). Field names are also the keys in config/dueldojo-tiers.json.
 */
public final class Profile {
	/** Median reaction to a discrete event such as the opponent's swing, in ms. */
	public float reactMs;
	/** Spread of that reaction time (log-normal sigma; about the coefficient of variation). */
	public float reactSpread;
	/** Visual feedback delay of continuous aiming: the bot tracks where you were this long ago, in ms. */
	public float trackDelayMs;
	/** How much of that delay it makes up for by extrapolating your movement (0 none … 1 all). */
	public float prediction;
	/** Smooth-pursuit gain: how much of your sideways motion its view follows without a correction. */
	public float pursuit;
	/** Size of its aim-point error while tracking, in degrees (standard deviation, slowly wandering). */
	public float aimNoise;
	/** Endpoint error of a corrective flick, as a fraction of the flick's size. */
	public float flickError;
	/** Fastest it can turn in one tick, in degrees. */
	public float maxTurn;
	/** When it means to swing, as a fraction of the full cooldown (1.0 = exactly at full charge). */
	public float chargeTarget;
	/** Timing spread of that swing, as a fraction of the cooldown (Weber fraction). */
	public float timingCv;
	/** Chance to click straight back after getting hit, whatever the charge (a reflex; a miss resets the cooldown). */
	public float panicClick;
	/** Chance to strafe during each strafe phase. */
	public float strafe;
	/** Average length of a strafe phase, in ticks. */
	public float strafeSwitch;
	/** Chance to release forward for a moment after landing a hit (W-tap), keeping the gap. */
	public float wtap;
	/** Chance per exchange to go for a falling crit. */
	public float crit;
	/** Chance to jump the moment it gets hit (jump reset). */
	public float jumpReset;
	/** Distance it holds while its cooldown recharges, centre to centre (your reach ends at about 3.3). */
	public float spacing;
	/** Chance per exchange to read your cooldown: step in right after you swing, crit-jump only when safe. */
	public float readCooldown;
	/** How much it plays safer when behind on health (0 … 1). */
	public float adaptHealth;
	/** Chance to switch strafe direction once it sees your aim catch up with it (a juke). */
	public float aimReact;
	/** Chance per cooldown cycle to bait: dip into your reach and straight back out so you swing at air. */
	public float bait;
	/** Chance per cooldown cycle to hit-select: when you're both charged it waits at the edge of reach for you to step in. */
	public float hitSelect;
	/** How much it plays the walls: knocks you toward them and gets itself out when it's the one pinned. */
	public float walls;

	/** Skill 0 (LT5). Sources for the human-performance values are in the README. */
	static Profile beginner() {
		Profile p = new Profile();
		p.reactMs = 290.0F;
		p.reactSpread = 0.25F;
		p.trackDelayMs = 150.0F;
		p.prediction = 0.25F;
		p.pursuit = 0.55F;
		p.aimNoise = 5.0F;
		p.flickError = 0.15F;
		p.maxTurn = 30.0F;
		p.chargeTarget = 0.80F; // times its hits, but early
		p.timingCv = 0.12F;
		p.panicClick = 0.35F;
		p.strafe = 0.15F;
		p.strafeSwitch = 28.0F;
		p.wtap = 0.0F;
		p.crit = 0.0F;
		p.jumpReset = 0.0F;
		p.spacing = 1.6F;
		p.readCooldown = -0.2F;
		p.adaptHealth = 0.0F;
		// Advanced reads: nothing at the bottom, they start around the middle tiers (where each value crosses 0).
		p.aimReact = -0.3F;
		p.bait = -0.4F;
		p.hitSelect = -0.3F;
		p.walls = -0.15F;
		return p;
	}

	/** Skill 1 (HT1): the human-elite ceiling, not beyond it. */
	static Profile elite() {
		Profile p = new Profile();
		p.reactMs = 170.0F;
		p.reactSpread = 0.12F;
		p.trackDelayMs = 110.0F;
		p.prediction = 0.95F;
		p.pursuit = 0.95F;
		p.aimNoise = 1.0F;
		p.flickError = 0.06F;
		p.maxTurn = 75.0F;
		p.chargeTarget = 0.91F; // the earliest swing that still counts as a strong hit (charge > 0.9)
		p.timingCv = 0.035F;
		p.panicClick = -1.05F; // gone from skill 0.25 on: only the lowest tiers panic-click
		p.strafe = 1.0F;
		p.strafeSwitch = 9.0F;
		p.wtap = 0.97F;
		p.crit = 0.9F;
		p.jumpReset = 0.85F;
		p.spacing = 3.4F; // just outside your reach
		p.readCooldown = 0.95F;
		p.adaptHealth = 1.0F;
		p.aimReact = 0.9F;
		p.bait = 0.8F;
		p.hitSelect = 0.9F;
		p.walls = 0.9F;
		return p;
	}

	/** Linear interpolation of every field, then {@link #clamped()}. */
	Profile lerp(Profile to, float t) {
		Profile out = new Profile();
		try {
			for (Field f : Profile.class.getFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getType() != float.class) continue;
				float a = f.getFloat(this);
				f.setFloat(out, a + (f.getFloat(to) - a) * t);
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		return out.clamped();
	}

	/** Keeps every value in a range a human could produce (negative chances become "never"). */
	Profile clamped() {
		reactMs = Math.max(reactMs, 100.0F);
		reactSpread = clamp(reactSpread, 0.0F, 1.0F);
		trackDelayMs = Math.max(trackDelayMs, 50.0F);
		prediction = clamp(prediction, 0.0F, 1.0F);
		pursuit = clamp(pursuit, 0.0F, 1.0F);
		aimNoise = Math.max(aimNoise, 0.0F);
		flickError = clamp(flickError, 0.0F, 1.0F);
		maxTurn = clamp(maxTurn, 1.0F, 90.0F);
		chargeTarget = clamp(chargeTarget, 0.1F, 1.5F);
		timingCv = clamp(timingCv, 0.0F, 0.5F);
		panicClick = clamp(panicClick, 0.0F, 1.0F);
		strafe = clamp(strafe, 0.0F, 1.0F);
		strafeSwitch = Math.max(strafeSwitch, 2.0F);
		wtap = clamp(wtap, 0.0F, 1.0F);
		crit = clamp(crit, 0.0F, 1.0F);
		jumpReset = clamp(jumpReset, 0.0F, 1.0F);
		spacing = clamp(spacing, 0.5F, 4.0F);
		readCooldown = clamp(readCooldown, 0.0F, 1.0F);
		adaptHealth = clamp(adaptHealth, 0.0F, 1.0F);
		aimReact = clamp(aimReact, 0.0F, 1.0F);
		bait = clamp(bait, 0.0F, 1.0F);
		hitSelect = clamp(hitSelect, 0.0F, 1.0F);
		walls = clamp(walls, 0.0F, 1.0F);
		return this;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
