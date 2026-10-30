package io.github.tsundosika.dueldojo;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tsundosika.dueldojo.client.DrillTutorialScreen;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
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

			// 4. Your rank goes on your name through a team, and comes off again.
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				PlayerRanks.set(player, Tier.HT3);
				check(player.getTeam() != null && player.getTeam().getName().equals("dueldojo.ht3"), "your rank puts you on its team");
				check(player.getDisplayName().getString().startsWith(PlayerRanks.SWORD_ICON + " HT3 | "), "your name shows the rank tag");
				PlayerRanks.set(player, Tier.LT2);
				check(player.getTeam() != null && player.getTeam().getName().equals("dueldojo.lt2"), "a new rank moves you to its team");
				PlayerRanks.set(player, null);
				check(player.getTeam() == null && PlayerRanks.get(player.getUUID()) == null, "clearing the rank takes it off");
			});

			// 5. Tips come from your numbers, most important first, at most three.
			FightStats you = new FightStats();
			you.swings = 20;
			you.early = 10;
			you.hits = 10;
			you.sprintHits = 1;
			you.punished = 3;
			FightStats bot = new FightStats();
			bot.bestCombo = 4;
			List<Coach.Tip> tips = Coach.tips(you, bot, Tier.LT3, false);
			check(tips.size() == 3, "three tips at most");
			check(tips.get(0).line().startsWith("50% early swings"), "the early-swing tip comes first");
			check(tips.get(1).line().startsWith("50% hit rate"), "then accuracy");
			check(tips.get(2).line().startsWith("Hit 3x after missing"), "then getting punished after a miss");
			check(tips.stream().allMatch(t -> t.line().length() <= 50 && !t.detail().isEmpty()), "each tip is one short line, details on hover");
			check(Coach.tips(new FightStats(), new FightStats(), Tier.LT3, true).get(0).line().startsWith("Clean match"),
					"a clean win gets a short note instead");
			check(tips.get(0).drill() == Drill.TIMING && tips.get(1).drill() == Drill.AIM && tips.get(2).drill() == Drill.REACH,
					"each tip points to its drill");

			// 6. Keys: a jump right as a hit lands is a jump reset, a later one is late; letting go of W after a hit is a W-tap.
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				FightStats keys = new FightStats();
				FightStats other = new FightStats();
				List<String> notes = new ArrayList<>();
				keys.coach = note -> notes.add(note.getString());
				Input idle = new Input(false, false, false, false, false, false, false);
				Input jump = new Input(false, false, false, false, true, false, false);
				Input walk = new Input(true, false, false, false, false, false, false);
				player.setLastClientInput(idle);
				keys.tick(player);
				other.landed(false, false, 1.0F, keys);
				player.setLastClientInput(jump);
				keys.tick(player);
				check(keys.jumpResets == 1 && keys.hitsTaken == 1 && keys.groundHitsTaken == 1 && notes.contains("Jump reset ✓"), "a jump right as you're hit is a jump reset");
				player.setLastClientInput(idle);
				keys.tick(player);
				other.landed(false, false, 1.0F, keys);
				keys.tick(player);
				keys.tick(player);
				player.setLastClientInput(jump);
				keys.tick(player);
				check(keys.jumpResets == 2, "a jump 2 ticks after the hit still counts: that's a press right when you see it");
				player.setLastClientInput(idle);
				keys.tick(player);
				other.landed(false, false, 1.0F, keys);
				keys.tick(player);
				keys.tick(player);
				keys.tick(player);
				player.setLastClientInput(jump);
				keys.tick(player);
				check(keys.lateJumps == 1 && notes.contains("Jump reset 1 tick late"), "a jump 3 ticks after the hit is late");
				player.setLastClientInput(idle);
				player.setOnGround(false);
				keys.tick(player);
				other.landed(false, false, 1.0F, keys);
				player.setLastClientInput(jump);
				keys.tick(player);
				player.setOnGround(true);
				check(keys.groundHitsTaken == 3 && keys.hitsTaken == 4 && keys.jumpResets == 2, "a hit in the air doesn't count for or against you");
				player.setLastClientInput(walk);
				keys.tick(player);
				keys.landed(false, true, 1.0F, other);
				player.setLastClientInput(idle);
				keys.tick(player);
				check(keys.wTaps == 1, "letting go of W right after your hit is a W-tap");
				player.setLastClientInput(idle);
			});

			// 7. Drill scores, placement steps and the progress trend.
			FightStats drilled = new FightStats();
			drilled.swings = 10;
			drilled.early = 2;
			check(Drill.TIMING.score(drilled, new FightStats()) == 80 && Drill.AIM.score(new FightStats(), new FightStats()) == -1, "drill scores (and none without enough tries)");
			drilled.swingPunished = 4;
			FightStats attacker = new FightStats();
			attacker.swings = 10;
			attacker.hits = 3;
			check(Drill.SPACING.score(drilled, attacker) == 60 && Drill.DODGE.score(drilled, attacker) == 70, "spacing and dodge drill scores");
			check(Placement.nextTier(-1, 10) == 4 && Placement.nextTier(4, 10) == 7 && Placement.nextTier(4, 7) == 5
					&& Placement.nextTier(5, 7) == 6, "placement halves the possible tiers each match");
			List<PlayerData.Match> matches = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				PlayerData.Match m = new PlayerData.Match();
				m.swings = 10;
				m.hits = i < 2 ? 3 : 6;
				matches.add(m);
			}
			List<Component> trend = Progress.trend(matches);
			check(trend.size() == 6 && trend.get(1).getString().equals(" Hit rate 60% (was 30%) ↑"), "the trend compares recent matches with earlier ones");

			// 8. A whole timing drill: no rounds, the bot never hits you, it ends by itself, and everything comes back.
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				player.setGameMode(GameType.SURVIVAL);
				player.getInventory().clearContent();
				player.getInventory().setItem(0, new ItemStack(Items.APPLE, 7));
				DuelManager.startDrill(player, Drill.TIMING, Tier.HT1, false);
			});
			context.waitTicks(20 * 15);
			server.runOnServer(s -> {
				check(DuelManager.inDuel(player(s)), "the drill is running");
				check(player(s).getHealth() == player(s).getMaxHealth(), "the timing-drill bot never hits you");
			});
			waitUntil(context, server, s -> !DuelManager.inDuel(player(s)), 20 * (Drill.SECONDS + 10), "the drill to end by itself");
			server.runOnServer(s -> {
				checkOwnItemsBack(player(s));
				check(((ServerLevel) player(s).level()).getEntities(DuelDojo.BOT, e -> true).isEmpty(), "the drill bot is gone");
			});

			// 9. A whole placement. The test player never fights back, so it loses at LT3, then HT5, then LT5.
			server.runOnServer(s -> Placement.start(player(s)));
			server.runOnServer(s -> check(Placement.active(player(s)) && DuelManager.inDuel(player(s)), "placement starts with a match"));
			waitUntil(context, server, s -> !Placement.active(player(s)), 20 * 60 * 6, "the placement to finish");
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(!DuelManager.inDuel(player) && "below".equals(PlayerData.get(player.getUUID()).level),
						"placement ends after three losses with \"below LT5\" saved");
				checkOwnItemsBack(player);
			});

			// 10. Watching two bots: you spectate, the game slows down, and stopping puts everything back.
			Vec3 before = server.computeOnServer(s -> player(s).position());
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				Watch.start(player, Tier.HT1, Tier.HT2, 0.5F);
				check(Watch.watching(player) && player.isSpectator(), "watching makes you a spectator");
				check(s.tickRateManager().tickrate() == 10.0F, "the game runs at half speed");
				check(((ServerLevel) player.level()).getEntities(DuelDojo.BOT, e -> true).size() == 2, "two bots fight");
			});
			context.waitTicks(100);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				Watch.stop(player, null);
				check(!Watch.watching(player) && !player.isSpectator() && s.tickRateManager().tickrate() == 20.0F,
						"stopping gives back your game mode and normal speed");
				check(player.position().distanceTo(before) < 0.5, "and your place");
				check(((ServerLevel) player.level()).getEntities(DuelDojo.BOT, e -> true).isEmpty(), "and the bots are gone");
				checkOwnItemsBack(player);
			});
			// 11. A drill's tutorial: the window holds everything still and closing it starts "try it"; doing what it
			// asks starts the real drill. This runs with real key presses, so it also shows they can be done in time.
			for (Drill drill : Drill.values()) {
				DrillGuide guide = DrillGuide.of(drill);
				check(!guide.pages.isEmpty() && guide.goal > 0 && !guide.hints.isEmpty() && !guide.objective.isEmpty(), drill.title + " has a tutorial");
			}
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.TIMING, Tier.LT5, true));
			context.waitFor(client -> client.screen instanceof DrillTutorialScreen);
			server.runOnServer(s -> check("WINDOW".equals(DuelManager.stage(player(s))), "the window comes first"));
			context.runOnClient(client -> {
				DrillTutorialScreen screen = (DrillTutorialScreen) client.screen;
				screen.keyPressed(InputConstants.KEY_RETURN, 0, 0);
				check(screen.page() == 1, "enter goes to the next page");
				screen.keyPressed(InputConstants.KEY_RETURN, 0, 0);
				check(client.screen == null, "on the last page it closes the window");
			});
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			for (int waited = 0; waited < 20 * 30 && server.computeOnServer(s -> "TRY".equals(DuelManager.stage(player(s)))); waited += 3) {
				ShowcaseClientGameTest.aimAndSwing(context);
				context.waitTicks(3);
			}
			server.runOnServer(s -> check("RUN".equals(DuelManager.stage(player(s))), "three full-charge swings finish try it"));
			server.runOnServer(s -> DuelManager.stop(player(s)));

			// A jump pressed as the hit lands has to count, in the slowed-down "try it" of the combo drill.
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.COMBO, Tier.HT1, true));
			context.waitFor(client -> client.screen instanceof DrillTutorialScreen);
			context.runOnClient(client -> client.screen.onClose());
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			server.runOnServer(s -> check(s.tickRateManager().tickrate() == 10.0F, "the combo try-it runs at half speed"));
			int presses = 0;
			int lastHurt = 0;
			for (int waited = 0; waited < 20 * 90 && server.computeOnServer(s -> "TRY".equals(DuelManager.stage(player(s)))); waited++) {
				int hurt = server.computeOnServer(s -> player(s).hurtTime);
				if (hurt > lastHurt) {
					// As long as a real key press: a press and release can fall in one server tick, which it can't see.
					context.getInput().holdKey(options -> options.keyJump);
					context.waitTicks(3);
					context.getInput().releaseKey(options -> options.keyJump);
					waited += 3;
					presses++;
				} else {
					context.waitTick();
				}
				lastHurt = hurt;
			}
			int pressed = presses;
			server.runOnServer(s -> {
				check("RUN".equals(DuelManager.stage(player(s))), "jumps as the hit shows up are jump resets (" + pressed + " presses for 2 resets)");
				check(s.tickRateManager().tickrate() == 20.0F, "the speed is back for the real drill");
				DuelManager.stop(player(s));
			});

			// Skipping: holding Shift in "try it", and the window's Skip button.
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.REACH, Tier.LT5, true));
			context.waitFor(client -> client.screen instanceof DrillTutorialScreen);
			context.runOnClient(client -> client.screen.onClose());
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			context.getInput().holdKey(options -> options.keyShift);
			waitUntil(context, server, s -> "RUN".equals(DuelManager.stage(player(s))), 100, "holding shift to skip try it");
			context.getInput().releaseKey(options -> options.keyShift);
			server.runOnServer(s -> DuelManager.stop(player(s)));
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.DODGE, Tier.LT5, true));
			context.waitFor(client -> client.screen instanceof DrillTutorialScreen);
			context.runOnClient(client -> ((DrillTutorialScreen) client.screen).skip());
			waitUntil(context, server, s -> "RUN".equals(DuelManager.stage(player(s))), 100, "the window's skip button");
			server.runOnServer(s -> {
				DuelManager.stop(player(s));
				checkOwnItemsBack(player(s));
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
