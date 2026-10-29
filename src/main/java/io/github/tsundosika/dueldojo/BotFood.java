package io.github.tsundosika.dueldojo;

/**
 * A small copy of the player's hunger rules (FoodData), so the bot regenerates exactly like you do:
 * fast saturation healing at a full food bar, slow healing from 18 food, no sprinting at 6 food or less.
 */
final class BotFood {
	int foodLevel = 20;
	float saturation = 5.0F;
	private float exhaustion;
	private int tickTimer;

	void reset() {
		foodLevel = 20;
		saturation = 5.0F;
		exhaustion = 0.0F;
		tickTimer = 0;
	}

	void exhaust(float amount) {
		exhaustion = Math.min(exhaustion + amount, 40.0F);
	}

	boolean canSprint() {
		return foodLevel > 6;
	}

	void tick(BotEntity bot, boolean naturalRegeneration) {
		if (exhaustion > 4.0F) {
			exhaustion -= 4.0F;
			if (saturation > 0.0F) {
				saturation = Math.max(saturation - 1.0F, 0.0F);
			} else {
				foodLevel = Math.max(foodLevel - 1, 0);
			}
		}
		boolean hurt = bot.getHealth() < bot.getMaxHealth();
		if (naturalRegeneration && saturation > 0.0F && hurt && foodLevel >= 20) {
			if (++tickTimer >= 10) {
				float amount = Math.min(saturation, 6.0F);
				bot.heal(amount / 6.0F);
				exhaust(amount);
				tickTimer = 0;
			}
		} else if (naturalRegeneration && foodLevel >= 18 && hurt) {
			if (++tickTimer >= 80) {
				bot.heal(1.0F);
				exhaust(6.0F);
				tickTimer = 0;
			}
		} else {
			tickTimer = 0;
		}
	}
}
