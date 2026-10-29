package io.github.tsundosika.dueldojo;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** One fighter's numbers for one round (or, added up, a whole duel): swings, hits, crits, damage and combos. */
final class FightStats {
	int swings;
	int hits;
	int crits;
	float damage;
	int combo;
	int bestCombo;
	private final SwingWatcher watcher = new SwingWatcher();

	void tick(LivingEntity self) {
		if (watcher.update(self)) swings++;
	}

	void landed(boolean crit, float damageTaken, FightStats victim) {
		hits++;
		if (crit) crits++;
		damage += damageTaken;
		bestCombo = Math.max(bestCombo, ++combo);
		victim.combo = 0;
	}

	/** Adds one round's numbers, for the total of a first-to-N duel. */
	void add(FightStats round) {
		swings += round.swings;
		hits += round.hits;
		crits += round.crits;
		damage += round.damage;
		bestCombo = Math.max(bestCombo, round.bestCombo);
	}

	int accuracy() {
		return swings == 0 ? 0 : Math.round(100.0F * Math.min(hits, swings) / swings);
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
