package io.github.tsundosika.dueldojo;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Best of N, typed as real commands: it stops once it's decided, and an even number can end level. The test player
 * only ever wins a round when the test kills the bot. Skip with environment variable DUELDOJO_BESTOF_TEST=0.
 */
public class BestOfClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if ("0".equals(System.getenv("DUELDOJO_BESTOF_TEST"))) return;
		check(DuelManager.roundsToWin(100) == 51 && DuelManager.roundsToWin(99) == 50 && DuelManager.roundsToWin(3) == 2
				&& DuelManager.roundsToWin(2) == 2 && DuelManager.roundsToWin(1) == 1, "rounds needed for a best of");
		check("/dojo lt3 bo 100 here".equals(DuelManager.command(Tier.LT3, 51, 100, true)) && "/dojo lt3 10".equals(DuelManager.command(Tier.LT3, 10, false)),
				"the rematch button repeats the same series");
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			// Other tests may already have played this tier in this run, so these look at what's added.
			int[] before = server.computeOnServer(s -> DuelStats.get(player(s).getUUID(), Tier.HT1).clone());
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				player.getInventory().clearContent();
				player.getInventory().setItem(0, new ItemStack(Items.APPLE, 7));
				run(s, "dojo ht1 bo 101");
				check(!DuelManager.inDuel(player), "best of 101 is refused");
				run(s, "dojo ht1 100");
				check(!DuelManager.inDuel(player), "first to 100 points you to best of instead of starting");
				run(s, "dojo ht1 21");
				check(!DuelManager.inDuel(player), "first to 21 does too");
				run(s, "dojo ht1 bo 3");
				check(DuelManager.inDuel(player), "best of 3 starts");
			});
			waitUntil(context, server, s -> !DuelManager.inDuel(player(s)), 20 * 90, "the best of 3 to end");
			server.runOnServer(s -> {
				int[] after = DuelStats.get(player(s).getUUID(), Tier.HT1);
				check(after[DuelStats.LOSSES] - before[DuelStats.LOSSES] == 2 && after[DuelStats.WINS] == before[DuelStats.WINS],
						"a best of 3 stops at 0-2, without the third round");
				checkItemsBack(player(s));
			});
			int matchesBefore = server.computeOnServer(s -> PlayerData.get(player(s).getUUID()).matches.size());
			server.runOnServer(s -> {
				run(s, "dojo ht1 bo 2");
				check(DuelManager.inDuel(player(s)), "best of 2 starts");
			});
			context.waitTicks(80); // the countdown
			server.runOnServer(s -> {
				ServerLevel level = (ServerLevel) player(s).level();
				BotEntity opponent = level.getEntities(DuelDojo.BOT, e -> true).get(0);
				opponent.hurtServer(level, level.damageSources().generic(), 1000.0F);
			});
			waitUntil(context, server, s -> !DuelManager.inDuel(player(s)), 20 * 60, "the best of 2 to end");
			server.runOnServer(s -> {
				int[] after = DuelStats.get(player(s).getUUID(), Tier.HT1);
				check(after[DuelStats.WINS] - before[DuelStats.WINS] == 1 && after[DuelStats.LOSSES] - before[DuelStats.LOSSES] == 3,
						"a best of 2 ends after 1-1, without a third round");
				List<PlayerData.Match> played = PlayerData.get(player(s).getUUID()).matches;
				check(played.size() == matchesBefore + 1 && !played.get(played.size() - 1).won, "a draw isn't saved as a win");
				checkItemsBack(player(s));
				run(s, "dojo lt5 20");
				check(DuelManager.inDuel(player(s)), "first to 20 still starts");
				DuelManager.stop(player(s));
			});
			DuelDojo.LOGGER.info("[best of test] all checks passed");
		}
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(player(server).createCommandSourceStack(), command);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void checkItemsBack(ServerPlayer player) {
		ItemStack first = player.getInventory().getItem(0);
		check(first.is(Items.APPLE) && first.getCount() == 7, "your own items are back");
	}

	private static void waitUntil(ClientGameTestContext context, TestServerContext server, Predicate<MinecraftServer> condition, int maxTicks, String what) {
		for (int waited = 0; waited < maxTicks; waited += 20) {
			if (server.computeOnServer(condition::test)) return;
			context.waitTicks(20);
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("Failed: " + what);
		DuelDojo.LOGGER.info("[best of test] ok: {}", what);
	}
}
