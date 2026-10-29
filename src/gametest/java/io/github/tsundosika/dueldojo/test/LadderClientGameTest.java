package io.github.tsundosika.dueldojo.test;

import io.github.tsundosika.dueldojo.BotSim;
import io.github.tsundosika.dueldojo.DuelDojo;
import io.github.tsundosika.dueldojo.TestTiers;
import io.github.tsundosika.dueldojo.Tier;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Runs the bot-vs-bot ladder (every tier against the next one) in a flat test world and prints the report.
 * Rounds per pairing: environment variable DUELDOJO_LADDER_ROUNDS (default 30; 0 skips the ladder).
 * DUELDOJO_LADDER_WIDE=1 also pairs every tier with the one two above it, for tighter ratings when calibrating.
 * DUELDOJO_POSITIONS (ten comma-separated values, LT5 … HT1) puts the tiers at those settings positions instead of the defaults.
 */
public class LadderClientGameTest implements FabricClientGameTest {
	/** Gametests run at real time (one server tick per client tick), so rounds are spread over parallel arenas. */
	private static final int ARENAS_PER_PAIRING = 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		String env = System.getenv("DUELDOJO_LADDER_ROUNDS");
		int rounds = env == null ? 30 : Integer.parseInt(env.trim());
		if (rounds <= 0) return;
		String positions = System.getenv("DUELDOJO_POSITIONS");
		if (positions != null && !positions.isBlank()) TestTiers.apply(positions);
		// One server tick runs per rendered frame here, so frame caps would slow the simulation down: no vsync,
		// no frame limit, and no idle cap (by default the game drops its frame rate when nobody touches it).
		context.runOnClient(client -> {
			client.options.enableVsync().set(false);
			client.options.framerateLimit().set(260);
			client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
			client.options.renderDistance().set(2);
			client.options.graphicsMode().set(GraphicsStatus.FAST);
			client.options.entityShadows().set(false);
			client.options.particles().set(ParticleStatus.MINIMAL);
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			// The arena grid spans about 90 blocks from the player and chunks beyond the view distance don't tick,
			// so widen it once the world is up (rendering 10 chunks first would exceed the wait timeout).
			context.runOnClient(client -> {
				client.options.renderDistance().set(10);
				client.options.simulationDistance().set(10);
			});
			context.waitTicks(200);
			boolean wide = "1".equals(System.getenv("DUELDOJO_LADDER_WIDE"));
			List<Tier[]> pairs = new ArrayList<>();
			for (Tier tier : Tier.values()) {
				if (tier.next() != null) pairs.add(new Tier[] {tier, tier.next()});
				if (wide && tier.next() != null && tier.next().next() != null) pairs.add(new Tier[] {tier, tier.next().next()});
			}
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				player.setGameMode(GameType.SPECTATOR);
				BotSim.start((ServerLevel) player.level(), player.position(), pairs, rounds, ARENAS_PER_PAIRING,
						message -> DuelDojo.LOGGER.info("[ladder] {}", message.getString()), false);
			});
			context.waitFor(client -> !BotSim.running(), 20 * 60 * 60 * 3);
		}
	}
}
