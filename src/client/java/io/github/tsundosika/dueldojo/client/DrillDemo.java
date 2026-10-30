package io.github.tsundosika.dueldojo.client;

import io.github.tsundosika.dueldojo.DrillGuide;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.Mth;

/**
 * The little looping scenes in the tutorial window: two fighters seen from the side (or the bot seen from your eyes),
 * with the keys you'd press lighting up as you'd press them. A scene is a script of times and places; it plays by
 * asking it for the {@link Frame} at a moment in time, so nothing here depends on the game running.
 */
final class DrillDemo {
	static final float SWORD_RECHARGE = 0.625F;
	private static final float SWING_TIME = 0.3F;
	private static final float HURT_TIME = 0.3F;
	private static final int GOOD = 0xFF8CF08C;
	private static final int BAD = 0xFFFF7A7A;
	private static final int NOTE = 0xFFFFE9A8;

	/** One fighter at one moment; x and y in blocks from the middle of the floor. */
	static final class Figure {
		float x;
		float y;
		float walkPosition;
		float walkSpeed;
		float swing;
		boolean hurt;
	}

	record Pop(float x, float y, String text, int color, float age) {
	}

	/** One moment of a scene. */
	static final class Frame {
		final Figure you = new Figure();
		final Figure bot = new Figure();
		/** The bot from your eyes instead of both from the side; crossX is then where your crosshair is. */
		boolean front;
		float crossX;
		/** Your sword's charge, 0 to 1, or -1 not to show it. */
		float charge = -1.0F;
		/** Shows how far you reach. */
		boolean ruler;
		String caption = "";
		int captionColor = NOTE;
		final List<Pop> pops = new ArrayList<>();
		/** Crit stars on the bot, 0 to 1 while they last, 0 = none. */
		float stars;
		final Map<DrillGuide.Key, Boolean> keys = new EnumMap<>(DrillGuide.Key.class);
		/** How much the picture is faded out, for the cut when the scene starts over. */
		float fade;
	}

	/** A value over time: straight lines between (time, value) points. */
	private static final class Track {
		final float[] points;

		Track(float... points) {
			this.points = points;
		}

		float at(float t) {
			if (t <= points[0]) return points[1];
			for (int i = 0; i + 3 < points.length; i += 2) {
				if (t <= points[i + 2]) {
					return Mth.lerp((t - points[i]) / (points[i + 2] - points[i]), points[i + 1], points[i + 3]);
				}
			}
			return points[points.length - 1];
		}

		/** How far it has moved in total by this time: what makes the legs go. */
		float travelled(float t) {
			float sum = 0.0F;
			for (int i = 0; i + 3 < points.length; i += 2) {
				if (t <= points[i]) break;
				float to = Math.min(t, points[i + 2]);
				sum += Math.abs(at(to) - points[i + 1]);
			}
			return sum;
		}
	}

	private record Arc(float start, float time, float height) {
		float at(float t) {
			if (t <= start || t >= start + time) return 0.0F;
			float p = (t - start) / time;
			return 4.0F * height * p * (1.0F - p);
		}
	}

	private record Caption(float from, float to, String text, int color) {
	}

	private record TimedPop(float time, boolean onBot, String text, int color) {
	}

	/** A scene's script; set up with the builder-style methods below. */
	static final class Scene {
		final float length;
		private Track youX = new Track(0.0F, -1.6F);
		private Track botX = new Track(0.0F, 1.4F);
		private Track cross;
		private final List<Arc> youArcs = new ArrayList<>();
		private final List<Arc> botArcs = new ArrayList<>();
		private float[] youSwings = {};
		private float[] botSwings = {};
		private float[] youHurt = {};
		private float[] botHurt = {};
		private final Map<DrillGuide.Key, float[]> keys = new EnumMap<>(DrillGuide.Key.class);
		private final List<Caption> captions = new ArrayList<>();
		private final List<TimedPop> pops = new ArrayList<>();
		private float[] crits = {};
		private boolean showCharge;
		private boolean ruler;
		private boolean front;

		Scene(float length) {
			this.length = length;
		}

		/** The keys this scene shows, in the order to draw them. */
		Iterable<DrillGuide.Key> keys() {
			return keys.keySet();
		}

		Scene you(float... points) {
			youX = new Track(points);
			return this;
		}

		Scene bot(float... points) {
			botX = new Track(points);
			return this;
		}

		Scene cross(float... points) {
			cross = new Track(points);
			front = true;
			return this;
		}

		Scene youJump(float start, float time, float height) {
			youArcs.add(new Arc(start, time, height));
			return this;
		}

		Scene botJump(float start, float time, float height) {
			botArcs.add(new Arc(start, time, height));
			return this;
		}

		Scene youSwings(float... times) {
			youSwings = times;
			return this;
		}

		Scene botSwings(float... times) {
			botSwings = times;
			return this;
		}

		Scene youHurt(float... times) {
			youHurt = times;
			return this;
		}

		Scene botHurt(float... times) {
			botHurt = times;
			return this;
		}

		/** Pairs of press and release times. */
		Scene key(DrillGuide.Key key, float... onOff) {
			keys.put(key, onOff);
			return this;
		}

		Scene caption(float from, float to, String text, int color) {
			captions.add(new Caption(from, to, text, color));
			return this;
		}

		Scene popOnBot(float time, String text, int color) {
			pops.add(new TimedPop(time, true, text, color));
			return this;
		}

		Scene popOnYou(float time, String text, int color) {
			pops.add(new TimedPop(time, false, text, color));
			return this;
		}

		Scene crits(float... times) {
			crits = times;
			return this;
		}

		Scene showCharge() {
			showCharge = true;
			return this;
		}

		Scene showRuler() {
			ruler = true;
			return this;
		}

		Frame at(float time) {
			float t = time % length;
			Frame frame = new Frame();
			frame.front = front;
			frame.ruler = ruler;
			fill(frame.you, youX, youArcs, youSwings, youHurt, t);
			fill(frame.bot, botX, botArcs, botSwings, botHurt, t);
			if (cross != null) frame.crossX = cross.at(t);
			if (showCharge) frame.charge = charge(t);
			for (Map.Entry<DrillGuide.Key, float[]> entry : keys.entrySet()) {
				frame.keys.put(entry.getKey(), isOn(entry.getValue(), t));
			}
			for (Caption caption : captions) {
				if (t >= caption.from && t < caption.to) {
					frame.caption = caption.text;
					frame.captionColor = caption.color;
				}
			}
			for (TimedPop pop : pops) {
				float age = (t - pop.time) / 0.7F;
				if (age < 0.0F || age >= 1.0F) continue;
				Figure who = pop.onBot ? frame.bot : frame.you;
				frame.pops.add(new Pop(who.x, who.y, pop.text, pop.color, age));
			}
			for (float crit : crits) {
				float age = (t - crit) / 0.6F;
				if (age >= 0.0F && age < 1.0F) frame.stars = 1.0F - age;
			}
			frame.fade = Math.max(0.0F, Math.max(1.0F - t / 0.12F, 1.0F - (length - t) / 0.12F));
			return frame;
		}

		private float charge(float t) {
			float last = -1000.0F;
			for (float swing : youSwings) {
				if (swing <= t) last = swing;
			}
			return Math.min(1.0F, (t - last) / SWORD_RECHARGE);
		}

		private static void fill(Figure figure, Track x, List<Arc> arcs, float[] swings, float[] hurts, float t) {
			figure.x = x.at(t);
			for (Arc arc : arcs) figure.y += arc.at(t);
			figure.walkPosition = x.travelled(t) * 4.0F;
			figure.walkSpeed = figure.y > 0.0F ? 0.0F : Math.min(1.0F, Math.abs(x.at(t + 0.05F) - x.at(t)) / 0.05F / 4.5F);
			for (float swing : swings) {
				float p = (t - swing) / SWING_TIME;
				if (p >= 0.0F && p < 1.0F) figure.swing = p;
			}
			for (float hurt : hurts) {
				if (t >= hurt && t < hurt + HURT_TIME) figure.hurt = true;
			}
		}

		private static boolean isOn(float[] onOff, float t) {
			for (int i = 0; i + 1 < onOff.length; i += 2) {
				if (t >= onOff[i] && t < onOff[i + 1]) return true;
			}
			return false;
		}
	}

	private DrillDemo() {
	}

	static Scene scene(DrillGuide.Demo demo) {
		return switch (demo) {
			case TIMING_FULL -> new Scene(3.6F).showCharge()
					.bot(0.0F, 1.4F, 0.35F, 1.4F, 0.65F, 2.8F, 1.2F, 1.4F, 1.55F, 1.4F, 1.85F, 2.8F, 2.4F, 1.4F, 2.75F, 1.4F, 3.05F, 2.8F, 3.6F, 2.0F)
					.botJump(0.35F, 0.3F, 0.3F).botJump(1.55F, 0.3F, 0.3F).botJump(2.75F, 0.3F, 0.3F)
					.youSwings(0.3F, 1.5F, 2.7F).botHurt(0.35F, 1.55F, 2.75F)
					.key(DrillGuide.Key.ATTACK, 0.3F, 0.45F, 1.5F, 1.65F, 2.7F, 2.85F)
					.popOnBot(0.35F, "Strong hit", GOOD).popOnBot(1.55F, "Strong hit", GOOD).popOnBot(2.75F, "Strong hit", GOOD)
					.caption(0.45F, 0.93F, "Charging...", NOTE).caption(0.93F, 1.5F, "Full: hit now", GOOD)
					.caption(1.65F, 2.13F, "Charging...", NOTE).caption(2.13F, 2.7F, "Full: hit now", GOOD);
			case TIMING_EARLY -> new Scene(3.4F).showCharge()
					.bot(0.0F, 1.4F, 0.45F, 1.4F, 0.7F, 2.6F, 0.85F, 2.2F, 1.0F, 2.05F, 1.2F, 2.0F, 1.35F, 2.15F, 1.6F, 2.0F, 2.5F, 1.4F)
					.botJump(0.45F, 0.3F, 0.3F)
					.you(0.0F, -1.6F, 0.7F, -1.6F, 0.85F, -1.0F, 3.0F, -1.0F, 3.3F, -1.6F)
					.youSwings(0.4F, 0.95F, 1.3F, 1.65F).botHurt(0.45F)
					.key(DrillGuide.Key.ATTACK, 0.4F, 0.5F, 0.95F, 1.05F, 1.3F, 1.4F, 1.65F, 1.75F)
					.popOnBot(0.45F, "Strong hit", GOOD).popOnBot(0.97F, "Weak!", BAD).popOnBot(1.32F, "Weak!", BAD).popOnBot(1.67F, "Weak!", BAD)
					.caption(0.95F, 2.2F, "Clicking too early: weak hits", BAD).caption(2.2F, 3.3F, "Wait for the bar instead", NOTE);
			case AIM_FOLLOW -> new Scene(4.0F)
					.bot(0.0F, -3.0F, 1.0F, 3.0F, 1.1F, 3.0F, 2.0F, -2.0F, 2.1F, -2.0F, 3.0F, 2.5F, 3.1F, 2.5F, 4.0F, -3.0F)
					.cross(0.0F, -3.0F, 0.15F, -3.0F, 1.08F, 3.0F, 1.25F, 3.0F, 2.1F, -2.0F, 2.25F, -2.0F, 3.1F, 2.5F, 3.25F, 2.5F, 4.0F, -3.0F)
					.youSwings(1.1F, 2.15F, 3.15F).botHurt(1.12F, 2.17F, 3.17F)
					.key(DrillGuide.Key.ATTACK, 1.1F, 1.25F, 2.15F, 2.3F, 3.15F, 3.3F)
					.popOnBot(1.12F, "Hit", GOOD).popOnBot(2.17F, "Hit", GOOD).popOnBot(3.17F, "Hit", GOOD)
					.caption(0.0F, 3.9F, "Follow it with your mouse", NOTE);
			case AIM_WHIFF -> new Scene(3.4F)
					.bot(0.0F, -3.0F, 0.8F, 1.0F, 1.7F, -2.0F, 1.9F, -2.0F, 3.4F, -3.0F)
					.cross(0.0F, -3.0F, 1.0F, 3.0F, 1.2F, 3.0F, 1.75F, -2.0F, 3.4F, -3.0F)
					.youSwings(1.0F, 1.8F).botHurt(1.82F)
					.key(DrillGuide.Key.ATTACK, 1.0F, 1.15F, 1.8F, 1.95F)
					.caption(0.0F, 1.0F, "It turns when you catch up...", NOTE).caption(1.0F, 1.7F, "Clicked at air: a miss", BAD).caption(1.7F, 3.2F, "On it, then click", GOOD)
					.popOnBot(1.82F, "Hit", GOOD);
			case REACH_STEP_IN -> new Scene(4.4F).showRuler()
					.you(0.0F, -2.9F, 1.1F, -2.9F, 1.6F, -1.7F, 1.85F, -1.7F, 2.4F, -2.9F, 4.4F, -2.9F)
					.bot(0.0F, 1.4F, 1.75F, 1.4F, 2.05F, 2.8F, 3.2F, 2.8F, 4.1F, 1.4F, 4.4F, 1.4F)
					.botJump(1.75F, 0.3F, 0.3F)
					.youSwings(1.7F).botHurt(1.75F)
					.key(DrillGuide.Key.FORWARD, 1.1F, 1.6F).key(DrillGuide.Key.BACK, 1.85F, 2.4F).key(DrillGuide.Key.ATTACK, 1.7F, 1.85F)
					.popOnBot(1.75F, "Hit", GOOD)
					.caption(0.0F, 1.1F, "Too far: don't swing yet", NOTE).caption(1.1F, 1.7F, "Step in", NOTE)
					.caption(1.7F, 2.4F, "In reach: hit!", GOOD).caption(2.4F, 4.3F, "And back out", NOTE);
			case REACH_WHIFF -> new Scene(3.8F).showRuler()
					.you(0.0F, -2.9F, 1.65F, -2.9F, 2.0F, -4.3F, 2.9F, -4.3F, 3.7F, -2.9F)
					.bot(0.0F, 1.4F, 1.05F, 1.4F, 1.55F, -0.7F, 2.9F, -0.7F, 3.7F, 1.4F)
					.youJump(1.65F, 0.4F, 0.4F)
					.youSwings(0.7F).botSwings(1.6F).youHurt(1.65F)
					.key(DrillGuide.Key.ATTACK, 0.7F, 0.85F)
					.popOnYou(0.72F, "Miss", BAD).popOnYou(1.67F, "Punished!", BAD)
					.caption(0.0F, 0.7F, "Out of reach...", NOTE).caption(0.7F, 1.6F, "Swung at air", BAD).caption(1.6F, 2.9F, "It rushes in and hits you", BAD);
			case COMBO_NO_JUMP -> new Scene(3.8F)
					.you(0.0F, 0.6F, 0.55F, 0.6F, 0.95F, -0.9F, 1.35F, -0.9F, 1.75F, -2.4F, 2.15F, -2.4F, 2.55F, -3.9F, 3.8F, -3.9F)
					.bot(0.0F, 3.1F, 0.5F, 3.1F, 1.25F, 1.6F, 1.3F, 1.6F, 2.05F, 0.1F, 2.1F, 0.1F, 2.85F, -1.4F, 3.8F, -1.4F)
					.youJump(0.55F, 0.45F, 0.45F).youJump(1.35F, 0.45F, 0.45F).youJump(2.15F, 0.45F, 0.45F)
					.botSwings(0.5F, 1.3F, 2.1F).youHurt(0.55F, 1.35F, 2.15F)
					.popOnYou(0.57F, "Hit 1", BAD).popOnYou(1.37F, "Hit 2", BAD).popOnYou(2.17F, "Hit 3", BAD)
					.caption(0.55F, 2.15F, "Knocked back, it walks back in", BAD).caption(2.15F, 3.7F, "That's a combo", BAD);
			case COMBO_JUMP -> new Scene(4.2F)
					.you(0.0F, -0.8F, 0.85F, -0.8F, 1.3F, -1.3F, 4.2F, -1.3F)
					.bot(0.0F, 2.3F, 0.85F, 2.3F, 1.7F, 1.6F, 1.95F, 1.6F, 2.25F, 3.0F, 3.6F, 3.0F, 4.1F, 2.3F)
					.youJump(0.85F, 0.6F, 1.05F).botJump(1.95F, 0.3F, 0.3F)
					.botSwings(0.8F).youSwings(1.9F).youHurt(0.85F).botHurt(1.95F)
					.key(DrillGuide.Key.JUMP, 0.82F, 1.0F).key(DrillGuide.Key.ATTACK, 1.9F, 2.05F)
					.popOnYou(0.87F, "Jump reset!", GOOD).popOnBot(1.95F, "Hit back", GOOD)
					.caption(0.0F, 0.8F, "Watch its sword", NOTE).caption(0.8F, 1.5F, "Jump as it hits you", GOOD).caption(1.5F, 3.5F, "Less knockback: hit back", GOOD);
			case WTAP_NO_TAP -> new Scene(4.0F)
					.you(0.0F, -5.0F, 0.8F, -2.2F, 2.15F, -0.2F, 4.0F, -0.2F)
					.bot(0.0F, 0.9F, 0.85F, 0.9F, 1.15F, 3.0F, 1.7F, 2.6F, 2.2F, 2.6F, 2.45F, 3.0F, 4.0F, 3.0F)
					.botJump(0.85F, 0.3F, 0.3F)
					.youSwings(0.8F, 2.15F).botHurt(0.85F, 2.2F)
					.key(DrillGuide.Key.FORWARD, 0.0F, 2.2F).key(DrillGuide.Key.ATTACK, 0.8F, 0.95F, 2.15F, 2.3F)
					.popOnBot(0.85F, "Sprint hit: far", GOOD).popOnBot(2.2F, "No sprint: short", BAD)
					.caption(0.0F, 0.8F, "Sprint in", NOTE).caption(0.8F, 2.15F, "That hit ended your sprint", NOTE).caption(2.15F, 3.8F, "So this one is weak", BAD);
			case WTAP_TAP -> new Scene(4.4F)
					.you(0.0F, -5.0F, 0.8F, -2.2F, 1.3F, -2.2F, 2.2F, 0.3F, 4.4F, 0.3F)
					.bot(0.0F, 0.9F, 0.85F, 0.9F, 1.15F, 3.0F, 1.7F, 3.0F, 2.25F, 3.0F, 2.55F, 4.9F, 4.4F, 4.9F)
					.botJump(0.85F, 0.3F, 0.3F).botJump(2.25F, 0.3F, 0.3F)
					.youSwings(0.8F, 2.2F).botHurt(0.85F, 2.25F)
					.key(DrillGuide.Key.FORWARD, 0.0F, 0.95F, 1.25F, 2.3F).key(DrillGuide.Key.ATTACK, 0.8F, 0.95F, 2.2F, 2.35F)
					.popOnBot(0.85F, "Sprint hit", GOOD).popOnBot(2.25F, "Sprint hit again", GOOD)
					.caption(0.0F, 0.8F, "Sprint in and hit", NOTE).caption(0.95F, 1.25F, "Let go of W", GOOD).caption(1.25F, 2.2F, "Hold W again", GOOD).caption(2.2F, 4.2F, "Far knockback every time", GOOD);
			case CRIT_FALLING -> new Scene(3.4F)
					.bot(0.0F, 1.4F, 1.05F, 1.4F, 1.35F, 2.8F, 2.3F, 2.8F, 3.2F, 1.4F)
					.youJump(0.5F, 0.65F, 1.25F).botJump(1.05F, 0.3F, 0.3F)
					.youSwings(1.0F).botHurt(1.05F).crits(1.05F)
					.key(DrillGuide.Key.JUMP, 0.5F, 0.65F).key(DrillGuide.Key.ATTACK, 1.0F, 1.15F)
					.popOnBot(1.05F, "CRIT!", 0xFFFFD84A)
					.caption(0.5F, 0.82F, "Jump", NOTE).caption(0.82F, 1.05F, "Falling: hit now", GOOD).caption(1.05F, 2.5F, "Crit: 50% more damage", GOOD);
			case CRIT_RISING -> new Scene(3.0F)
					.bot(0.0F, 1.4F, 0.72F, 1.4F, 0.95F, 2.2F, 2.0F, 2.2F, 2.8F, 1.4F)
					.youJump(0.5F, 0.65F, 1.25F)
					.youSwings(0.67F).botHurt(0.72F)
					.key(DrillGuide.Key.JUMP, 0.5F, 0.65F).key(DrillGuide.Key.ATTACK, 0.67F, 0.82F)
					.popOnBot(0.72F, "No crit", BAD)
					.caption(0.5F, 2.2F, "Hit on the way up: plain hit", BAD);
			case SPACING_STAY -> new Scene(3.4F).showCharge()
					.you(0.0F, -1.2F, 1.05F, -1.2F, 1.4F, -2.6F, 2.5F, -2.6F, 3.3F, -1.2F)
					.bot(0.0F, 1.5F, 0.5F, 1.5F, 0.7F, 2.0F, 0.95F, 0.9F, 2.5F, 0.9F, 3.3F, 1.5F)
					.youJump(1.05F, 0.4F, 0.4F)
					.youSwings(0.45F).botSwings(1.0F).botHurt(0.5F).youHurt(1.05F)
					.key(DrillGuide.Key.ATTACK, 0.45F, 0.6F)
					.popOnBot(0.5F, "Hit", GOOD).popOnYou(1.07F, "Free hit!", BAD)
					.caption(0.6F, 1.05F, "Sword still charging", BAD).caption(1.05F, 2.6F, "You stayed close: it hits you", BAD);
			case SPACING_BACK -> new Scene(4.2F).showCharge()
					.you(0.0F, -1.2F, 0.6F, -1.2F, 1.15F, -3.2F, 1.8F, -3.2F, 2.3F, -1.6F, 4.0F, -1.2F)
					.bot(0.0F, 1.5F, 0.5F, 1.5F, 0.7F, 2.0F, 1.2F, 0.5F, 1.8F, 0.5F, 2.4F, 0.5F, 2.7F, 1.9F, 4.0F, 1.5F)
					.botJump(2.4F, 0.3F, 0.3F)
					.youSwings(0.45F, 2.35F).botSwings(1.25F).botHurt(0.5F, 2.4F)
					.key(DrillGuide.Key.ATTACK, 0.45F, 0.6F, 2.35F, 2.5F).key(DrillGuide.Key.BACK, 0.6F, 1.15F).key(DrillGuide.Key.FORWARD, 1.8F, 2.3F)
					.popOnBot(0.5F, "Hit", GOOD).popOnBot(1.27F, "Its swing misses", GOOD).popOnBot(2.4F, "Hit", GOOD)
					.caption(0.6F, 1.15F, "Step back", GOOD).caption(1.15F, 1.8F, "Wait for the charge", NOTE).caption(1.8F, 2.35F, "Charged: back in", GOOD).caption(2.35F, 3.6F, "Hit again", GOOD);
			case DODGE_STRAFE -> new Scene(3.8F)
					.bot(0.0F, 0.0F, 0.6F, 0.0F, 1.05F, -2.6F, 1.5F, -2.6F, 2.0F, 2.6F, 2.4F, 2.6F, 3.0F, 0.0F, 3.8F, 0.0F)
					.cross(0.0F, 100.0F)
					.botSwings(0.95F, 1.9F)
					.key(DrillGuide.Key.LEFT, 1.5F, 2.0F).key(DrillGuide.Key.RIGHT, 0.6F, 1.05F, 2.4F, 3.0F)
					.popOnBot(1.0F, "Miss", GOOD).popOnBot(1.95F, "Miss", GOOD)
					.caption(0.0F, 0.6F, "It comes at you", NOTE).caption(0.6F, 3.0F, "Strafe: its swings miss", GOOD);
		};
	}
}
