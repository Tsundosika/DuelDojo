package io.github.tsundosika.dueldojo;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The practice bot. It is a mob for rendering/saving purposes, but everything that matters in a fight
 * follows player rules: player movement speed and air control, 3-block reach checked along its actual
 * line of sight, the 1.9+ attack cooldown, crits, sprint knockback and hunger-based regeneration.
 */
public class BotEntity extends PathfinderMob {
	/** Same as the player's entity interaction range. */
	public static final double REACH = 3.0;

	private Tier tier = Tier.LT5;
	/** Set for a practice drill: changes some settings and when it may attack. */
	private Drill drill;
	private LivingEntity opponent;
	private boolean active;
	private BotBrain brain;
	/** Mirrors Player#attackStrengthTicker. */
	int attackStrengthTicker;
	final BotFood food = new BotFood();
	/** Behaviour counts over all rounds, for the simulation report; never reset. */
	final BotBrain.Counts counts = new BotBrain.Counts();
	/** What it has noticed about its opponent; kept across rounds, so it can adapt over a series. */
	final BotBrain.Habits habits = new BotBrain.Habits();
	/** The tick of its last jump, so another bot can see it jump. */
	int lastJumpTick = -1000;
	private float lastHealth = -1;
	private Vec3 lastPos;

	public BotEntity(EntityType<? extends BotEntity> type, Level level) {
		super(type, level);
		// The duel brain drives the bot directly, so the vanilla controllers must not overwrite its inputs.
		this.moveControl = new MoveControl(this) {
			@Override
			public void tick() {
			}
		};
		this.jumpControl = new JumpControl(this) {
			@Override
			public void tick() {
			}
		};
		this.lookControl = new LookControl(this) {
			@Override
			public void tick() {
			}
		};
		this.setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		// Player base values; the sword's own modifiers bring these to 7 damage / 1.6 attack speed.
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.1)
				.add(Attributes.ATTACK_DAMAGE, 1.0)
				.add(Attributes.ATTACK_SPEED, 4.0);
	}

	/** Called once right after spawning. */
	public void setup(Tier tier, LivingEntity opponent) {
		this.tier = tier;
		this.opponent = opponent;
		this.brain = newBrain();
		this.setCustomName(PlayerRanks.prefix(tier).append(Component.literal("Dojo Bot")));
		this.setCustomNameVisible(true);
		equipKit();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			this.setDropChance(slot, 0.0F);
		}
	}

	/** The same kit as yours, fresh (full durability). */
	private void equipKit() {
		this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		this.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Tier tier() {
		return tier;
	}

	/** Fresh start for the next round: full health and food, a fresh charged sword and armor, new brain. */
	void resetForRound() {
		equipKit();
		this.setHealth(this.getMaxHealth());
		this.removeAllEffects();
		this.clearFire();
		this.setDeltaMovement(Vec3.ZERO);
		this.fallDistance = 0;
		this.setInvulnerableTime(0);
		this.hurtTime = 0;
		this.food.reset();
		this.attackStrengthTicker = 100;
		this.lastHealth = -1;
		this.lastPos = null;
		this.brain = newBrain();
	}

	/** Turns this bot into a drill partner (call before the first round). */
	void setDrill(Drill drill) {
		this.drill = drill;
		this.brain = newBrain();
	}

	/** Plays with these settings instead of its tier's, for tests. */
	void useProfile(Profile profile) {
		this.brain = new BotBrain(this, profile);
	}

	private BotBrain newBrain() {
		Profile profile = TierConfig.profile(tier);
		return drill == null ? new BotBrain(this, profile) : new BotBrain(this, drill.profile(profile), drill.attacks);
	}

	@Override
	protected void registerGoals() {
		// No vanilla AI goals: see BotBrain.
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		// A duel never survives a save/reload, so neither should its bot.
		return false;
	}

	@Override
	protected float getFlyingSpeed() {
		// Player air control (Player#getFlyingSpeed) instead of the mob default.
		return this.isSprinting() ? 0.025999999F : 0.02F;
	}

	@Override
	public void jumpFromGround() {
		super.jumpFromGround();
		lastJumpTick = this.tickCount;
		food.exhaust(this.isSprinting() ? 0.2F : 0.05F);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) return;
		this.attackStrengthTicker++;

		// Hunger like a player: sprinting, jumping, attacking and taking damage cost exhaustion.
		if (lastPos != null && this.isSprinting() && this.onGround()) {
			double dx = this.getX() - lastPos.x;
			double dz = this.getZ() - lastPos.z;
			food.exhaust(0.1F * (float) Math.sqrt(dx * dx + dz * dz));
		}
		lastPos = this.position();
		if (lastHealth >= 0 && this.getHealth() < lastHealth) food.exhaust(0.1F);
		lastHealth = this.getHealth();
		if (this.level() instanceof ServerLevel serverLevel) {
			food.tick(this, serverLevel.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION));
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (brain != null && opponent != null && active && opponent.isAlive() && this.isAlive()) {
			brain.tick(level, opponent);
		} else {
			this.xxa = 0;
			this.zza = 0;
			this.setJumping(false);
			this.setSprinting(false);
			if (brain != null && opponent != null) brain.faceInstantly(opponent);
		}
		// The mob movement speed is what travel() uses; mirror the (sprint-modified) attribute like a player.
		// Mob#setSpeed also overwrites the forward input (that's how vanilla mobs walk), so keep the brain's.
		float forward = this.zza;
		this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
		this.zza = forward;
	}

	/** Attack-cooldown charge 0..1, same formula as Player#getAttackStrengthScale(0.5F). */
	public float attackCharge() {
		return attackCharge(0.5F);
	}

	public float attackCharge(float extraTicks) {
		return Mth.clamp((this.attackStrengthTicker + extraTicks) / cooldownTicks(), 0.0F, 1.0F);
	}

	/** Full cooldown length in ticks (12.5 with the diamond sword), like Player#getCurrentItemAttackStrengthDelay. */
	public float cooldownTicks() {
		return (float) (20.0 / this.getAttributeValue(Attributes.ATTACK_SPEED));
	}

	/** Ticks until the cooldown reaches {@code target} charge. */
	public int ticksUntilCharge(float target) {
		double delay = 20.0 / this.getAttributeValue(Attributes.ATTACK_SPEED);
		return Math.max(0, (int) Math.ceil(target * delay - 0.5 - this.attackStrengthTicker));
	}

	/** True if the bot's crosshair (its real view direction) is on the target within reach. */
	public boolean crosshairOn(LivingEntity target) {
		return crosshairOn(target, target.position());
	}

	/** Same, but with the target's hitbox standing at {@code feet}: where the bot sees it, not where it really is. */
	public boolean crosshairOn(LivingEntity target, Vec3 feet) {
		Vec3 eye = this.getEyePosition();
		AABB box = target.getBoundingBox().move(feet.subtract(target.position()));
		if (box.contains(eye)) return true;
		Vec3 end = eye.add(this.getViewVector(1.0F).scale(REACH));
		return box.clip(eye, end).isPresent() && this.hasLineOfSight(target);
	}

	/** Result of a swing, so the brain can decide on W-taps. */
	public enum SwingResult { MISS, HIT, SPRINT_HIT, CRIT }

	/** Swings exactly like a player would (Player#attack), including resetting the cooldown on a miss. */
	public SwingResult swingAt(ServerLevel level, LivingEntity target) {
		this.swing(InteractionHand.MAIN_HAND, this.getMainHandItem().getAttackAnimation(), false);
		if (!crosshairOn(target)) {
			this.attackStrengthTicker = 0;
			return SwingResult.MISS;
		}
		float charge = this.attackCharge();
		float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.2F + charge * charge * 0.8F);
		this.attackStrengthTicker = 0;

		boolean strong = charge > 0.9F;
		boolean sprintHit = strong && this.isSprinting();
		boolean crit = strong && this.fallDistance > 0 && !this.onGround() && !this.onClimbable() && !this.isInWater()
				&& !this.hasEffect(MobEffects.BLINDNESS) && !this.isPassenger() && !this.isSprinting();
		if (crit) damage *= 1.5F;

		DamageSource source = this.damageSources().mobAttack(this);
		Vec3 targetMovement = target.getDeltaMovement();
		boolean hurt = target.hurtServer(level, source, damage);
		if (!hurt) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PLAYER_ATTACK_NODAMAGE, this.getSoundSource(), 1.0F, 1.0F);
			return SwingResult.MISS;
		}
		food.exhaust(0.1F);

		if (sprintHit) {
			// The player's own extra knockback: 0.5 on top, then the attacker slows to 60% (Player#attack does the same).
			this.causeExtraKnockback(target, 0.5F, targetMovement, source, damage, true);
			this.setSprinting(false);
			level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, this.getSoundSource(), 1.0F, 1.0F);
		}
		if (crit) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, this.getSoundSource(), 1.0F, 1.0F);
			level.getChunkSource().sendToTrackingPlayersAndSelf(target, new ClientboundAnimatePacket(target, ClientboundAnimatePacket.CRITICAL_HIT));
			return SwingResult.CRIT;
		}
		if (!sprintHit) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(),
					strong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK, this.getSoundSource(), 1.0F, 1.0F);
		}
		return sprintHit ? SwingResult.SPRINT_HIT : SwingResult.HIT;
	}
}
