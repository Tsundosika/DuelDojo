package io.github.tsundosika.dueldojo.client;

import io.github.tsundosika.dueldojo.BotEntity;
import io.github.tsundosika.dueldojo.DuelDojo;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.ResourceLocation;

/** Draws the bot as a player-shaped humanoid with its own (original) skin, armor and held sword. */
public class BotRenderer extends HumanoidMobRenderer<BotEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final ResourceLocation TEXTURE =
			ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "textures/entity/dojo_bot.png");

	public BotRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this,
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
				new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
				context.getEquipmentRenderer()));
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public ResourceLocation getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
