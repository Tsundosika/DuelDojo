package io.github.tsundosika.dueldojo;

import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Takes the screenshots for the README (environment variable DUELDOJO_SHOWCASE=1, skipped otherwise): two bots
 * fighting in the sky arena, then a duel from your own view. They land in build/run/clientGameTest/screenshots,
 * and the best ones are picked by hand.
 */
public class ShowcaseClientGameTest implements FabricClientGameTest {
	private static final int WIDTH = 1600;
	private static final int HEIGHT = 900;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!"1".equals(System.getenv("DUELDOJO_SHOWCASE"))) return;
		context.getInput().resizeWindow(WIDTH, HEIGHT);
		context.runOnClient(client -> {
			client.options.graphicsMode().set(GraphicsStatus.FANCY);
			client.options.cloudStatus().set(CloudStatus.FANCY);
			client.options.particles().set(ParticleStatus.ALL);
			client.options.fov().set(80);
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			// Only now: a bigger view distance before the world is up makes the first chunk wait time out.
			context.runOnClient(client -> client.options.renderDistance().set(8));
			TestServerContext server = singleplayer.getServer();
			Vec3 ground = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, s);
				level.setDayTime(6000);
				level.setWeatherParameters(24000, 0, false, false);
				return player(s).position();
			});

			// 1. Two bots fighting in the arena, watched from outside.
			DuelArena arena = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				ServerLevel level = (ServerLevel) player.level();
				DuelArena built = DuelArena.build(level, player.blockPosition());
				BotEntity a = new BotEntity(DuelDojo.BOT, level);
				BotEntity b = new BotEntity(DuelDojo.BOT, level);
				a.setup(Tier.LT3, b);
				b.setup(Tier.HT3, a);
				double y = built.playerSpawn().y;
				a.snapTo(built.center.getX() + 0.5, y, built.center.getZ() - 2.5, 0.0F, 0.0F);
				b.snapTo(built.center.getX() + 0.5, y, built.center.getZ() + 3.5, 180.0F, 0.0F);
				level.addFreshEntity(a);
				level.addFreshEntity(b);
				a.setActive(true);
				b.setActive(true);
				player.setGameMode(GameType.SPECTATOR);
				return built;
			});
			context.runOnClient(client -> client.options.hideGui = true);
			Vec3 center = Vec3.atBottomCenterOf(arena.center);
			look(server, center.add(-17.0, 11.0, -17.0), center.add(0.0, 1.0, 0.0));
			context.waitTicks(10);
			shot(context, "arena-wide");
			for (int i = 0; i < 16; i++) {
				double angle = Math.toRadians(-135.0 + i * 20.0);
				server.runOnServer(s -> healBots(s));
				Vec3 mid = server.computeOnServer(ShowcaseClientGameTest::botsMiddle);
				look(server, mid.add(Math.cos(angle) * 6.5, 2.2, Math.sin(angle) * 6.5), mid.add(0.0, 1.1, 0.0));
				context.waitTicks(4);
				shot(context, "bots-" + i);
			}
			server.runOnServer(s -> {
				((ServerLevel) player(s).level()).getEntities(DuelDojo.BOT, e -> true).forEach(Entity::discard);
				arena.remove(s);
				ServerPlayer player = player(s);
				player.setGameMode(GameType.SURVIVAL);
				player.teleportTo(ground.x, ground.y, ground.z);
			});

			// 2. A duel from your own view, fighting back whenever the sword is charged and the bot is in reach.
			// Chat and the advancement pop-up for the diamond kit are kept out of the pictures.
			context.runOnClient(client -> {
				client.options.hideGui = false;
				client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
			});
			server.runOnServer(s -> DuelManager.start(player(s), Tier.HT3, 10, true));
			for (int i = 0; i < 60; i++) {
				context.runOnClient(client -> {
					LocalPlayer player = client.player;
					Entity bot = null;
					for (Entity e : client.level.entitiesForRendering()) {
						if (e.getType() == DuelDojo.BOT && (bot == null || e.distanceTo(player) < bot.distanceTo(player))) bot = e;
					}
					if (bot == null) return;
					Vec3 to = bot.position().add(0.0, bot.getBbHeight() * 0.75, 0.0).subtract(player.getEyePosition());
					float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0F;
					float pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG);
					player.setYRot(yaw);
					player.setXRot(pitch);
					player.yRotO = yaw;
					player.xRotO = pitch;
				});
				boolean hit = context.computeOnClient(client -> {
					Entity target = client.crosshairPickEntity;
					return target != null && target.getType() == DuelDojo.BOT && client.player.getAttackStrengthScale(0.0F) > 0.95F;
				});
				if (hit) context.getInput().pressKey(options -> options.keyAttack);
				context.waitTicks(3);
				context.runOnClient(client -> client.getToastManager().clear());
				shot(context, "duel-" + i);
			}
			server.runOnServer(s -> DuelManager.stop(player(s)));
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	/** Puts the (spectating) camera at {@code from}, looking at {@code at}. */
	private static void look(TestServerContext server, Vec3 from, Vec3 at) {
		Vec3 to = at.subtract(from);
		float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG);
		server.runOnServer(s -> {
			ServerPlayer player = player(s);
			player.teleportTo((ServerLevel) player.level(), from.x, from.y, from.z, Set.of(), yaw, pitch, false);
		});
	}

	private static void healBots(MinecraftServer server) {
		((ServerLevel) player(server).level()).getEntities(DuelDojo.BOT, e -> true).forEach(bot -> bot.setHealth(bot.getMaxHealth()));
	}

	private static Vec3 botsMiddle(MinecraftServer server) {
		Vec3 sum = Vec3.ZERO;
		int n = 0;
		for (BotEntity bot : ((ServerLevel) player(server).level()).getEntities(DuelDojo.BOT, e -> true)) {
			sum = sum.add(bot.position());
			n++;
		}
		return n == 0 ? player(server).position() : sum.scale(1.0 / n);
	}

	private static void shot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).withSize(WIDTH, HEIGHT).disableCounterPrefix());
	}
}
