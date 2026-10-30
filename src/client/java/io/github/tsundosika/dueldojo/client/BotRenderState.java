package io.github.tsundosika.dueldojo.client;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** What the bot renderer needs for one frame: the usual humanoid state plus the bot's health for its health line. */
public class BotRenderState extends HumanoidRenderState {
	public float health;
}
