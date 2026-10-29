package io.github.tsundosika.dueldojo;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The duel flow end to end in a flat test world: the arena goes up, you're moved in, kitted and frozen for the
 * countdown, a first-to-2 is played (the bot against a player who doesn't move), and afterwards everything is
 * back. Then the game "closes" mid-duel, with and without a save made during the duel, and {@link DuelRecovery}
 * has to clean up. Skip with environment variable DUELDOJO_ARENA_TEST=0.
 */
public class ArenaClientGameTest implements FabricClientGameTest {
	private static final String TAG = "dueldojo_in_duel";

	@Override
	public void runTest(ClientGameTestContext context) {
		if ("0".equals(System.getenv("DUELDOJO_ARENA_TEST"))) return;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			// 1. A normal first-to-2 duel.
			Vec3 start = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				player.setGameMode(GameType.SURVIVAL);
				player.getInventory().clearContent();
				player.getInventory().setItem(0, new ItemStack(Items.APPLE, 7));
				Vec3 position = player.position();
				DuelManager.start(player, Tier.HT1, 2, true);
				return position;
			});
			BlockPos floor = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				check(DuelManager.inDuel(player), "the duel started");
				check(player.getY() > 300, "you were moved up into the arena (y = " + player.getY() + ")");
				check(player.level().getBlockState(player.blockPosition()).getBlock() instanceof SlabBlock, "you stand on the mat floor");
				check(player.getMainHandItem().is(Items.DIAMOND_SWORD), "you got the kit");
				check(player.getAttributeValue(Attributes.MOVEMENT_SPEED) == 0.0, "you are frozen during the countdown");
				check(player.getTags().contains(TAG) && Files.exists(DuelRecovery.file(player)), "the crash-recovery file and tag exist");
				return player.blockPosition();
			});
			context.waitTicks(70);
			server.runOnServer(s -> check(player(s).getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0, "you can move after FIGHT!"));
			waitUntil(context, server, s -> !DuelManager.inDuel(player(s)), 20 * 150, "the first-to-2 to end");
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(player.position().distanceTo(start) < 0.5, "you are back where you started");
				checkOwnItemsBack(player);
				check(player.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0, "you are not frozen");
				check(player.level().getBlockState(floor).isAir(), "the arena was taken down");
				check(!player.getTags().contains(TAG) && !Files.exists(DuelRecovery.file(player)), "the crash-recovery file and tag are gone");
				check(((ServerLevel) player.level()).getEntities(DuelDojo.BOT, e -> true).isEmpty(), "the bot is gone");
				check(DuelStats.get(player.getUUID(), Tier.HT1)[DuelStats.LOSSES] >= 2, "both lost rounds were recorded");
			});

			// 2. The game closes mid-duel after a save made during it: on the next join everything comes back.
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				DuelManager.start(player, Tier.LT5, 20, true);
				BlockPos arenaFloor = player.blockPosition();
				crash(s);
				DuelRecovery.onJoin(player);
				check(player.position().distanceTo(start) < 0.5, "recovery: you are back where you started");
				checkOwnItemsBack(player);
				check(player.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0, "recovery: you are not frozen");
				check(player.level().getBlockState(arenaFloor).isAir(), "recovery: the arena was taken down");
				check(!player.getTags().contains(TAG) && !Files.exists(DuelRecovery.file(player)), "recovery: file and tag are gone");
			});

			// 3. Same, but the last save was from before the duel (no tag): your saved state is left alone.
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				DuelManager.start(player, Tier.LT5, 20, true);
				BlockPos arenaFloor = player.blockPosition();
				crash(s);
				player.removeTag(TAG);
				DuelRecovery.onJoin(player);
				check(player.getMainHandItem().is(Items.DIAMOND_SWORD), "old save: your items were left alone");
				check(player.level().getBlockState(arenaFloor).isAir(), "old save: the arena was taken down");
				check(!Files.exists(DuelRecovery.file(player)), "old save: the file is gone");
				DuelManager.freeze(player, false);
				player.setGameMode(GameType.CREATIVE);
				player.teleportTo(start.x, start.y, start.z);
			});
			DuelDojo.LOGGER.info("[arena test] all checks passed");
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void checkOwnItemsBack(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		check(inventory.getItem(0).is(Items.APPLE) && inventory.getItem(0).getCount() == 7, "your own items are back");
		boolean kitLeft = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			kitLeft |= inventory.getItem(i).is(Items.DIAMOND_SWORD) || inventory.getItem(i).is(Items.DIAMOND_CHESTPLATE);
		}
		check(!kitLeft, "the duel kit is gone");
	}

	/** Like the game closing mid-duel: the duel is forgotten and the bot (never saved) is gone, nothing is put back. */
	private static void crash(MinecraftServer server) {
		try {
			Field duels = DuelManager.class.getDeclaredField("DUELS");
			duels.setAccessible(true);
			((Map<?, ?>) duels.get(null)).clear();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		for (ServerLevel level : server.getAllLevels()) {
			level.getEntities(DuelDojo.BOT, e -> true).forEach(Entity::discard);
		}
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
		DuelDojo.LOGGER.info("[arena test] ok: {}", what);
	}
}
