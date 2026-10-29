package io.github.tsundosika.dueldojo.client;

import io.github.tsundosika.dueldojo.DuelDojo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class DuelDojoClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(DuelDojo.BOT, BotRenderer::new);
	}
}
