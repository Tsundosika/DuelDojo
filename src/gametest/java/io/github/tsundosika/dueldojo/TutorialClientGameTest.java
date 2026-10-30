package io.github.tsundosika.dueldojo;

import io.github.tsundosika.dueldojo.client.DrillTutorialScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Pictures of every page of every drill's tutorial window at a few moments of its scene (environment variable
 * DUELDOJO_TUTORIAL_SHOTS=1, skipped otherwise): in build/run/clientGameTest/screenshots as tutorial-drill-page-time.
 */
public class TutorialClientGameTest implements FabricClientGameTest {
	private static final int WIDTH = 1600;
	private static final int HEIGHT = 900;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!"1".equals(System.getenv("DUELDOJO_TUTORIAL_SHOTS"))) return;
		context.getInput().resizeWindow(WIDTH, HEIGHT);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			for (Drill drill : Drill.values()) {
				int pages = DrillGuide.of(drill).pages.size();
				for (int page = 0; page < pages; page++) {
					int thePage = page;
					context.runOnClient(client -> {
						DrillTutorialScreen screen = new DrillTutorialScreen(drill, false);
						screen.showPage(thePage);
						client.setScreen(screen);
					});
					for (float time : new float[] {0.3F, 1.0F, 1.8F, 2.6F}) {
						context.runOnClient(client -> ((DrillTutorialScreen) client.screen).freezeAt(time));
						context.waitTick();
						context.takeScreenshot(TestScreenshotOptions.of("tutorial-" + drill.id() + "-" + page + "-" + Math.round(time * 10.0F))
								.withSize(WIDTH, HEIGHT).disableCounterPrefix());
					}
				}
			}
			context.runOnClient(client -> client.setScreen(null));

			// "Try it": the objective at the left and the key prompt.
			for (Drill drill : Drill.values()) {
				singleplayer.getServer().runOnServer(s -> DuelManager.startDrill(s.getPlayerList().getPlayers().get(0), drill, Tier.LT3, true));
				context.waitFor(client -> client.screen instanceof DrillTutorialScreen);
				context.runOnClient(client -> client.screen.onClose());
				context.waitTicks(40);
				context.takeScreenshot(TestScreenshotOptions.of("try-" + drill.id()).withSize(WIDTH, HEIGHT).disableCounterPrefix());
				singleplayer.getServer().runOnServer(s -> DuelManager.stop(s.getPlayerList().getPlayers().get(0)));
				context.waitTicks(5);
			}
		}
	}
}
