package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The crit fight against someone who plays like a person learning it: they jump in for crits and stay close, or walk in
 * with sprint hits. Bot against bot can't show this, because a good bot backs off right after its crit, so nothing is
 * left to deflect, and the ladder only compares bots with bots: it wouldn't notice if every tier got worse at this.
 * All fights run side by side in their own arenas. Skip with environment variable DUELDOJO_CRITFIGHT_TEST=0;
 * DUELDOJO_CRITFIGHT_MATRIX=1 also tries each move of the crit fight on its own, to see what it does.
 */
public class CritFightClientGameTest implements FabricClientGameTest {
	private static final int SECONDS = 60;
	/** Arenas side by side: far enough apart not to overlap, near enough to tick around the test player. */
	private static final int SPACING = 40;
	private static final int PER_ROW = 5;

	private static final class Fight {
		final String name;
		final Tier tier;
		final Profile testedPlays;
		final Profile attackerPlays;
		BotEntity attacker;
		BotEntity tested;
		DuelArena arena;
		float damageDealt;
		float damageTaken;

		Fight(String name, Tier tier, Profile testedPlays, Profile attackerPlays) {
			this.name = name;
			this.tier = tier;
			this.testedPlays = testedPlays;
			this.attackerPlays = attackerPlays;
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if ("0".equals(System.getenv("DUELDOJO_CRITFIGHT_TEST"))) return;
		// Small until the world is up: rendering 10 chunks first would exceed the wait timeout.
		context.runOnClient(client -> client.options.renderDistance().set(2));
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			context.runOnClient(client -> {
				client.options.renderDistance().set(10);
				client.options.simulationDistance().set(10);
			});
			context.waitTicks(100);
			TestServerContext server = singleplayer.getServer();
			Profile top = TierConfig.profile(Tier.HT1);
			List<Fight> fights = new ArrayList<>();
			if ("2".equals(System.getenv("DUELDOJO_CRITFIGHT_MATRIX"))) {
				// What each move does for the top bot against a strong one: all of them, none, then each one left out.
				Profile strong = TierConfig.profile(Tier.HT2);
				add(fights, "all moves vs HT2", Tier.HT1, null, strong);
				add(fights, "no moves vs HT2", Tier.HT1, without(top), strong);
				add(fights, "no moves vs HT2 with no moves", Tier.HT1, without(top), without(strong));
				addLeftOut(fights, "hold S", top, strong, p -> p.holdBack = 0.0F);
				addLeftOut(fights, "deflect", top, strong, p -> p.deflect = 0.0F);
				addLeftOut(fights, "hit select", top, strong, p -> p.hitSelect = 0.0F);
				addLeftOut(fights, "uppercut", top, strong, p -> p.uppercut = 0.0F);
				addLeftOut(fights, "falling sprint hit", top, strong, p -> p.fallingSprint = 0.0F);
				addLeftOut(fights, "adapting", top, strong, p -> p.adapt = 0.0F);
				run(context, server, fights);
				return;
			}
			Fight high = add(fights, "HT1 vs someone critting", Tier.HT1, null, critter());
			Fight mid = add(fights, "LT3 vs someone critting", Tier.LT3, null, critter());
			Fight low = add(fights, "LT5 vs someone critting", Tier.LT5, null, critter());
			Fight select = add(fights, "HT1 vs someone walking in", Tier.HT1, null, walker());
			add(fights, "HT1 without the crit fight vs someone critting", Tier.HT1, without(top), critter());
			add(fights, "HT1 without the crit fight vs someone walking in", Tier.HT1, without(top), walker());
			if ("1".equals(System.getenv("DUELDOJO_CRITFIGHT_MATRIX"))) {
				addOnly(fights, "hold S", top, p -> p.holdBack = top.holdBack);
				addOnly(fights, "deflect", top, p -> p.deflect = top.deflect);
				addOnly(fights, "hit select", top, p -> p.hitSelect = top.hitSelect);
				addOnly(fights, "uppercut", top, p -> p.uppercut = top.uppercut);
				addOnly(fights, "falling sprint hit", top, p -> p.fallingSprint = top.fallingSprint);
			}
			run(context, server, fights);
			check(high.tested.habits.critsTaken > 5 && high.tested.counts.deflections * 100 >= high.tested.habits.critsTaken * 10, "HT1 deflects some of the crits it takes");
			check(mid.tested.counts.deflections <= high.tested.counts.deflections, "LT3 deflects less than HT1");
			check(low.tested.counts.deflections == 0 && low.tested.counts.hitSelects == 0, "LT5 doesn't deflect or hit-select");
			check(select.tested.counts.hitSelects > 0, "HT1 hit-selects walk-ins");
			check(high.tested.counts.holdBacks > low.tested.counts.holdBacks, "HT1 holds S more than LT5");
			check(high.damageDealt > high.damageTaken * 1.3F, "spamming crits with middling aim doesn't get close to the top bot");
			check(select.damageDealt > select.damageTaken * 1.05F, "and just walking in doesn't beat it");
			DuelDojo.LOGGER.info("[crit fight test] all checks passed");
		}
	}

	private static Fight add(List<Fight> fights, String name, Tier tier, Profile testedPlays, Profile attackerPlays) {
		Fight fight = new Fight(name, tier, testedPlays, attackerPlays);
		fights.add(fight);
		return fight;
	}

	/** The top bot with only this one move of the crit fight, against both opponents. */
	private static void addOnly(List<Fight> fights, String move, Profile top, Consumer<Profile> enable) {
		Profile critting = without(top);
		enable.accept(critting);
		add(fights, "HT1 with only " + move + " vs someone critting", Tier.HT1, critting, critter());
		Profile walking = without(top);
		enable.accept(walking);
		add(fights, "HT1 with only " + move + " vs someone walking in", Tier.HT1, walking, walker());
	}

	private static void addLeftOut(List<Fight> fights, String move, Profile top, Profile opponent, Consumer<Profile> disable) {
		Profile p = top.lerp(top, 0.0F);
		disable.accept(p);
		add(fights, "without " + move + " vs HT2", Tier.HT1, p, opponent);
	}

	/** Jumps in for a crit every time and stays right there. */
	private static Profile critter() {
		Profile p = plain();
		p.crit = 1.0F;
		return p;
	}

	/** Walks in and hits, without jumping. */
	private static Profile walker() {
		Profile p = plain();
		p.crit = 0.0F;
		return p;
	}

	private static Profile without(Profile tier) {
		Profile p = tier.lerp(tier, 0.0F);
		p.deflect = 0.0F;
		p.hitSelect = 0.0F;
		p.holdBack = 0.0F;
		p.uppercut = 0.0F;
		p.fallingSprint = 0.0F;
		p.pick = 0.0F;
		p.adapt = 0.0F;
		return p;
	}

	private static Profile plain() {
		Profile p = without(TierConfig.profile(Tier.LT3));
		p.spacing = 0.5F;
		p.strafe = 0.0F;
		p.wtap = 0.0F;
		p.jumpReset = 0.0F;
		p.readCooldown = 0.0F;
		p.aimReact = 0.0F;
		p.bait = 0.0F;
		p.edgeHold = 0.0F;
		p.walls = 0.0F;
		return p;
	}

	private static void run(ClientGameTestContext context, TestServerContext server, List<Fight> fights) {
		server.runOnServer(s -> {
			ServerPlayer player = player(s);
			ServerLevel level = (ServerLevel) player.level();
			for (int i = 0; i < fights.size(); i++) {
				Fight fight = fights.get(i);
				int x = (i % PER_ROW - PER_ROW / 2) * SPACING;
				int z = (i / PER_ROW - 1) * SPACING;
				BlockPos near = player.blockPosition().offset(x, 0, z);
				fight.arena = DuelArena.build(level, near);
				Vec3 a = fight.arena.playerSpawn();
				Vec3 b = fight.arena.botSpawn();
				fight.attacker = new BotEntity(DuelDojo.BOT, level);
				fight.tested = new BotEntity(DuelDojo.BOT, level);
				fight.attacker.setup(Tier.LT3, fight.tested);
				fight.attacker.useProfile(fight.attackerPlays);
				fight.tested.setup(fight.tier, fight.attacker);
				if (fight.testedPlays != null) fight.tested.useProfile(fight.testedPlays);
				fight.attacker.snapTo(a.x, a.y, a.z, 0.0F, 0.0F);
				fight.tested.snapTo(b.x, b.y, b.z, 180.0F, 0.0F);
				level.addFreshEntity(fight.attacker);
				level.addFreshEntity(fight.tested);
				fight.attacker.setActive(true);
				fight.tested.setActive(true);
			}
		});
		int seconds = "2".equals(System.getenv("DUELDOJO_CRITFIGHT_MATRIX")) ? SECONDS * 3 : SECONDS;
		for (int tick = 0; tick < seconds * 20; tick++) {
			context.waitTick();
			server.runOnServer(s -> {
				for (Fight fight : fights) {
					fight.damageDealt += heal(fight.attacker);
					fight.damageTaken += heal(fight.tested);
				}
			});
		}
		server.runOnServer(s -> {
			for (Fight fight : fights) {
				BotBrain.Counts c = fight.tested.counts;
				DuelDojo.LOGGER.info("[crit fight test] {}: damage dealt {} / taken {} = {}; {} hits taken, {} crits, {} deflections, {} hit selects, held S {}, swings {} (hits {}, sprint {}, crits {}); waits {}, answers {}, lost {}",
						fight.name, Math.round(fight.damageDealt), Math.round(fight.damageTaken), String.format("%.2f", fight.damageDealt / Math.max(1.0F, fight.damageTaken)),
						c.hitsTaken, fight.tested.habits.critsTaken, c.deflections, c.hitSelects, c.holdBacks, c.swings, c.hits, c.sprintHits, c.crits, c.waits, c.answers, c.answersLost);
				fight.attacker.discard();
				fight.tested.discard();
				fight.arena.remove(s);
			}
		});
	}

	/** Puts it back to full health and food, and returns the damage it had taken. */
	private static float heal(BotEntity bot) {
		float lost = bot.getMaxHealth() - bot.getHealth();
		bot.setHealth(bot.getMaxHealth());
		bot.food.reset();
		return lost;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().get(0);
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("Failed: " + what);
		DuelDojo.LOGGER.info("[crit fight test] ok: {}", what);
	}
}
