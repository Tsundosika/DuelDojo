package io.github.tsundosika.dueldojo;

import net.minecraft.world.entity.LivingEntity;

/** Notices each new arm swing of an entity (player or bot), the same thing you see on screen. */
final class SwingWatcher {
	private LivingEntity.SwingDescription last;

	/** Call once per tick; true on the tick a new swing is first visible (every swing starts a new description). */
	boolean update(LivingEntity entity) {
		LivingEntity.SwingDescription current = entity.getCurrentSwing();
		boolean fresh = entity.isSwinging() && current != null && current != last;
		last = current;
		return fresh;
	}
}
