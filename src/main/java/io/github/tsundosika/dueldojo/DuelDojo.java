package io.github.tsundosika.dueldojo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DuelDojo implements ModInitializer {
	public static final String MOD_ID = "dueldojo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<EntityType<?>> BOT_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "dojo_bot"));

	public static final EntityType<BotEntity> BOT = Registry.register(BuiltInRegistries.ENTITY_TYPE, BOT_KEY,
			EntityType.Builder.<BotEntity>of(BotEntity::new, MobCategory.MISC)
					.sized(0.6F, 1.8F)
					.eyeHeight(1.62F)
					.clientTrackingRange(10)
					.build(BOT_KEY));

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(BOT, BotEntity.createAttributes());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DojoCommand.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(DuelManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(BotSim::tick);
		ServerLivingEntityEvents.ALLOW_DEATH.register(DuelManager::allowDeath);
		ServerLivingEntityEvents.ALLOW_DEATH.register(BotSim::allowDeath);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(DuelManager::afterDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(BotSim::afterDamage);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DuelManager.onDisconnect(handler.getPlayer()));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> DuelRecovery.onJoin(handler.getPlayer()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DuelManager.abortAll();
			BotSim.stopAll();
		});
		LOGGER.info("Duel Dojo ready: /dojo to pick a bot");
	}
}
