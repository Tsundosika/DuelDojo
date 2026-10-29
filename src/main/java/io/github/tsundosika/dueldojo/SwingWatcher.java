package io.github.tsundosika.dueldojo;

import net.minecraft.world.entity.LivingEntity;

/** Notices each new arm swing of an entity (player or bot), the same thing you see on screen. */
final class SwingWatcher {
	private boolean wasSwinging;
	private int lastSwingTime;

	/** Call once per tick; true on the tick a new swing is first visible. */
	boolean update(LivingEntity entity) {
		boolean fresh = entity.swinging && (!wasSwinging || entity.swingTime < lastSwingTime);
		wasSwinging = entity.swinging;
		lastSwingTime = entity.swingTime;
		return fresh;
	}
}
