package io.github.tsundosika.dueldojo.client;

import io.github.tsundosika.dueldojo.BotEntity;
import io.github.tsundosika.dueldojo.DuelDojo;
import io.github.tsundosika.dueldojo.DuelManager;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * Draws the bot as a player-shaped humanoid with its own (original) skin, armor and held sword, and its health in
 * hearts on a line under its name.
 */
public class BotRenderer extends HumanoidMobRenderer<BotEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(DuelDojo.MOD_ID, "textures/entity/dojo_bot.png");

	public BotRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				ArmorModelSet.<HumanoidModel<HumanoidRenderState>>bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
				context.getEquipmentRenderer()));
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public void extractRenderState(BotEntity bot, HumanoidRenderState state, float partialTick) {
		super.extractRenderState(bot, state, partialTick);
		// The game draws scoreText on a line under the name tag (players use it for below-name scores).
		state.scoreText = state.nameTag == null ? null : DuelManager.hearts(bot.getHealth());
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
