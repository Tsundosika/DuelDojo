package io.github.tsundosika.dueldojo.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Quaternionf;

/**
 * Draws a demo scene's fighters into the tutorial window. The game's own way of showing an entity in a screen does
 * one per frame per kind of picture, so both go in this one picture.
 */
final class DemoStage {
	/** A fighter, with x and y in blocks from the middle of the floor. */
	record Fighter(HumanoidRenderState state, float x, float y) {
	}

	/** @param scale pixels per block in GUI units */
	record State(List<Fighter> fighters, int x0, int y0, int x1, int y1, float scale, float floorDrop, ScreenRectangle scissorArea,
			ScreenRectangle bounds) implements PictureInPictureRenderState {
	}

	static final class Renderer extends PictureInPictureRenderer<State> {
		private final EntityRenderDispatcher dispatcher;

		Renderer(EntityRenderDispatcher dispatcher) {
			this.dispatcher = dispatcher;
		}

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected void renderToTexture(State state, PoseStack pose, SubmitNodeCollector collector) {
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			pose.translate(0.0F, state.floorDrop(), 0.0F);
			pose.rotate(new Quaternionf().rotateZ((float) Math.PI));
			CameraRenderState camera = new CameraRenderState();
			for (Fighter fighter : state.fighters()) {
				// Turned upside down above, so -x is to the right and +y up.
				dispatcher.submit(fighter.state(), camera, -fighter.x(), fighter.y(), 0.0, pose, collector);
			}
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height / 2.0F;
		}

		@Override
		protected String getTextureLabel() {
			return "dueldojo demo";
		}
	}

	private DemoStage() {
	}
}
