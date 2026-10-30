package io.github.tsundosika.dueldojo.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.tsundosika.dueldojo.BotEntity;
import io.github.tsundosika.dueldojo.DuelDojo;
import io.github.tsundosika.dueldojo.DuelManager;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws the bot as a player-shaped humanoid with its own (original) skin, armor and held sword, and its health in
 * hearts on a line under its name.
 */
public class BotRenderer extends HumanoidMobRenderer<BotEntity, BotRenderState, HumanoidModel<BotRenderState>> {
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "textures/entity/dojo_bot.png");
	/** One line of name tag text, in blocks (10 pixels at the name tag's 0.025 scale). */
	private static final float LINE = 0.25F;

	public BotRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
				context.getEquipmentRenderer()));
	}

	@Override
	public BotRenderState createRenderState() {
		return new BotRenderState();
	}

	@Override
	public void extractRenderState(BotEntity bot, BotRenderState state, float partialTick) {
		super.extractRenderState(bot, state, partialTick);
		state.health = bot.getHealth();
	}

	@Override
	public ResourceLocation getTextureLocation(BotRenderState state) {
		return TEXTURE;
	}

	/** The name one line up, and the health where the name tag normally sits. */
	@Override
	protected void renderNameTag(BotRenderState state, Component text, PoseStack pose, MultiBufferSource buffers, int light) {
		pose.pushPose();
		pose.translate(0.0F, LINE, 0.0F);
		super.renderNameTag(state, text, pose, buffers, light);
		pose.popPose();
		super.renderNameTag(state, DuelManager.hearts(state.health), pose, buffers, light);
	}
}
