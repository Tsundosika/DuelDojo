package io.github.tsundosika.dueldojo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.tsundosika.dueldojo.DuelDojo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

public class DuelDojoClient implements ClientModInitializer {
	/** Scoreboard teams the mod uses for ranks (see PlayerRanks). */
	private static final String RANK_TEAM_PREFIX = "dueldojo.";

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(DuelDojo.BOT, BotRenderer::new);
		LevelRenderEvents.COLLECT_SUBMITS.register(DuelDojoClient::drawOwnRank);
	}

	/**
	 * The game never draws your own name tag, so in third person your rank ("HT3 | name") is drawn above your head
	 * here, the same way and at the same height as a name tag. Other players see it on your normal name tag.
	 */
	private static void drawOwnRank(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.options.getCameraType().isFirstPerson() || minecraft.gui.hud.isHidden()) return;
		PlayerTeam team = player.getTeam();
		if (team == null || !team.getName().startsWith(RANK_TEAM_PREFIX)) return;

		CameraRenderState camera = context.levelState().cameraRenderState;
		Vec3 at = player.getPosition(camera.cameraEntityPartialTicks).subtract(camera.pos);
		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(at.x, at.y, at.z);
		context.submitNodeCollector().submitNameTag(pose, new Vec3(0.0, player.getBbHeight(), 0.0), 0, player.getDisplayName(),
				!player.isDiscrete(), LightCoordsUtil.FULL_BRIGHT, camera);
		pose.popPose();
	}
}
