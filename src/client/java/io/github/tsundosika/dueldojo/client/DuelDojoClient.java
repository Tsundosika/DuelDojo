package io.github.tsundosika.dueldojo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.tsundosika.dueldojo.Drill;
import io.github.tsundosika.dueldojo.DuelDojo;
import io.github.tsundosika.dueldojo.TutorialNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

public class DuelDojoClient implements ClientModInitializer {
	/** Scoreboard teams the mod uses for ranks (see PlayerRanks). */
	private static final String RANK_TEAM_PREFIX = "dueldojo.";

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(DuelDojo.BOT, BotRenderer::new);
		WorldRenderEvents.AFTER_ENTITIES.register(DuelDojoClient::drawOwnRank);
		SpecialGuiElementRegistry.register(context -> new DemoStage.Renderer(context.vertexConsumers(), context.client().getEntityRenderDispatcher()));
		HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "tutorial"), TutorialHud::extract);
		ClientPlayNetworking.registerGlobalReceiver(TutorialNet.Open.TYPE, (payload, context) ->
				context.client().setScreen(new DrillTutorialScreen(Drill.values()[payload.drill()], true)));
		ClientPlayNetworking.registerGlobalReceiver(TutorialNet.Hud.TYPE, (payload, context) -> TutorialHud.set(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TutorialHud.set(TutorialNet.Hud.HIDDEN));
	}

	/**
	 * The game never draws your own name tag, so in third person your rank ("[HT3] name") is drawn above your head
	 * here, the same way and at the same height as a name tag. Other players see it on your normal name tag.
	 */
	private static void drawOwnRank(WorldRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.options.getCameraType().isFirstPerson() || minecraft.options.hideGui) return;
		PlayerTeam team = player.getTeam();
		if (team == null || !team.getName().startsWith(RANK_TEAM_PREFIX)) return;
		PoseStack pose = context.matrixStack();
		MultiBufferSource buffers = context.consumers();
		if (pose == null || buffers == null) return;

		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		Vec3 camera = context.camera().getPosition();
		Vec3 at = player.getPosition(partialTick).add(0.0, player.getBbHeight() + 0.5, 0.0);
		Component label = player.getDisplayName();
		Font font = minecraft.font;
		float x = -font.width(label) / 2.0F;
		int background = (int) (minecraft.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;

		pose.pushPose();
		pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
		pose.mulPose(context.camera().rotation());
		pose.scale(0.025F, -0.025F, 0.025F);
		font.drawInBatch(label, x, 0.0F, 0x20FFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, background, LightTexture.FULL_BRIGHT);
		font.drawInBatch(label, x, 0.0F, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
		pose.popPose();
	}
}
