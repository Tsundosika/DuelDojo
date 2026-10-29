package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Everything a duel changes on the player, so it can be put back exactly afterwards. */
record PlayerSnapshot(List<ItemStack> items, GameType gameMode, float health, int foodLevel, float saturation,
		List<MobEffectInstance> effects, ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {

	static PlayerSnapshot capture(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			items.add(inventory.getItem(i).copy());
		}
		List<MobEffectInstance> effects = new ArrayList<>();
		for (MobEffectInstance effect : player.getActiveEffects()) {
			effects.add(new MobEffectInstance(effect));
		}
		return new PlayerSnapshot(items, player.gameMode.getGameModeForPlayer(), player.getHealth(),
				player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), effects,
				player.level().dimension(), player.position(), player.getYRot(), player.getXRot());
	}

	void restore(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		for (int i = 0; i < items.size() && i < inventory.getContainerSize(); i++) {
			inventory.setItem(i, items.get(i).copy());
		}
		player.setGameMode(gameMode);
		player.removeAllEffects();
		for (MobEffectInstance effect : effects) {
			player.addEffect(new MobEffectInstance(effect));
		}
		player.setHealth(Math.max(1.0F, health));
		player.getFoodData().setFoodLevel(foodLevel);
		player.getFoodData().setSaturation(saturation);
		player.clearFire();
		ServerLevel level = player.getServer().getLevel(dimension);
		if (level != null) player.teleportTo(level, position.x, position.y, position.z, Set.of(), yRot, xRot, true);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
	}

	/** For {@link DuelRecovery}: written to the world folder while the duel runs. */
	void save(ValueOutput out) {
		ValueOutput.TypedOutputList<ItemStackWithSlot> slots = out.list("items", ItemStackWithSlot.CODEC);
		for (int i = 0; i < items.size(); i++) {
			if (!items.get(i).isEmpty()) slots.add(new ItemStackWithSlot(i, items.get(i)));
		}
		out.putInt("gameMode", gameMode.getId());
		out.putFloat("health", health);
		out.putInt("food", foodLevel);
		out.putFloat("saturation", saturation);
		ValueOutput.TypedOutputList<MobEffectInstance> effectList = out.list("effects", MobEffectInstance.CODEC);
		effects.forEach(effectList::add);
		out.putString("dimension", dimension.location().toString());
		out.putDouble("x", position.x);
		out.putDouble("y", position.y);
		out.putDouble("z", position.z);
		out.putFloat("yRot", yRot);
		out.putFloat("xRot", xRot);
	}

	static PlayerSnapshot load(ValueInput in, int inventorySize) {
		List<ItemStack> items = new ArrayList<>(Collections.nCopies(inventorySize, ItemStack.EMPTY));
		for (ItemStackWithSlot slot : in.listOrEmpty("items", ItemStackWithSlot.CODEC)) {
			if (slot.isValidInContainer(inventorySize)) items.set(slot.slot(), slot.stack());
		}
		List<MobEffectInstance> effects = new ArrayList<>();
		in.listOrEmpty("effects", MobEffectInstance.CODEC).forEach(effects::add);
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
				ResourceLocation.parse(in.getStringOr("dimension", Level.OVERWORLD.location().toString())));
		return new PlayerSnapshot(items, GameType.byId(in.getIntOr("gameMode", 0)), in.getFloatOr("health", 20.0F),
				in.getIntOr("food", 20), in.getFloatOr("saturation", 5.0F), effects, dimension,
				new Vec3(in.getDoubleOr("x", 0.0), in.getDoubleOr("y", 0.0), in.getDoubleOr("z", 0.0)),
				in.getFloatOr("yRot", 0.0F), in.getFloatOr("xRot", 0.0F));
	}
}
