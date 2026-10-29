package io.github.tsundosika.dueldojo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Bot-vs-bot simulation, to check that the tiers are ordered and spaced like the real tier list.
 * Every pairing gets a small arena near where it was started, all arenas fight at the same time, and the
 * results are fitted to Elo-style ratings (Bradley-Terry) and compared with the spacing from {@link Calibration}.
 * Best run in a superflat world. Results: chat, the log, and dueldojo-ladder.txt in the game folder.
 */
public final class BotSim {
	private static final int ROUND_LIMIT = 2400; // 2 minutes, then the round is a draw
	private static final int PAUSE = 20;
	private static final double ARENA_SPACING = 20.0;
	private static final double MAX_WANDER = 30.0;

	private static Run run;
	private static volatile boolean running;
	private static volatile String lastReport = "";

	private BotSim() {
	}

	private static final class Arena {
		final Tier a;
		final Tier b;
		final Vec3 center;
		final BotEntity botA;
		final BotEntity botB;
		final FightStats statsA = new FightStats();
		final FightStats statsB = new FightStats();
		int winsA;
		int winsB;
		int draws;
		int pause;
		/** Result waiting to be applied at the end of the tick (1 = A won, 2 = B won), -1 = none. */
		int pending = -1;
		boolean fighting;
		int fightTicks;
		long totalTicks;

		Arena(Tier a, Tier b, Vec3 center, BotEntity botA, BotEntity botB) {
			this.a = a;
			this.b = b;
			this.center = center;
			this.botA = botA;
			this.botB = botB;
		}

		int played() {
			return winsA + winsB + draws;
		}
	}

	private static final class Run {
		final List<Arena> arenas = new ArrayList<>();
		/** Rounds per arena. */
		final int rounds;
		final int copies;
		final Consumer<Component> out;
		final boolean sprint;
		int reportedTenths;

		Run(int rounds, int copies, Consumer<Component> out, boolean sprint) {
			this.rounds = rounds;
			this.copies = copies;
			this.out = out;
			this.sprint = sprint;
		}

		boolean done(Arena arena) {
			return arena.played() >= rounds;
		}
	}

	public static boolean running() {
		return running;
	}

	public static String lastReport() {
		return lastReport;
	}

	/** Every tier against the next one up: LT5–HT5, HT5–LT4, … LT1–HT1. */
	public static boolean startLadder(ServerLevel level, Vec3 center, int rounds, int copies, Consumer<Component> out, boolean sprint) {
		List<Tier[]> pairs = new ArrayList<>();
		for (Tier tier : Tier.values()) {
			if (tier.next() != null) pairs.add(new Tier[] {tier, tier.next()});
		}
		return start(level, center, pairs, rounds, copies, out, sprint);
	}

	/**
	 * Runs {@code rounds} rounds of each pairing, spread over {@code copies} arenas per pairing that fight at the
	 * same time (more arenas = done sooner when time can't be sped up).
	 */
	public static boolean start(ServerLevel level, Vec3 center, List<Tier[]> pairs, int rounds, int copies, Consumer<Component> out, boolean sprint) {
		if (run != null) {
			out.accept(Component.literal("A simulation is already running. /dojo sim stop cancels it.").withStyle(ChatFormatting.RED));
			return false;
		}
		Run next = new Run((rounds + copies - 1) / copies, copies, out, sprint);
		int arenas = pairs.size() * copies;
		int cols = (int) Math.ceil(Math.sqrt(arenas));
		int rows = (int) Math.ceil(arenas / (double) cols);
		for (int i = 0; i < arenas; i++) {
			double x = center.x + (i % cols - (cols - 1) / 2.0) * ARENA_SPACING;
			double z = center.z + (i / cols - (rows - 1) / 2.0) * ARENA_SPACING;
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
			BotEntity botA = new BotEntity(DuelDojo.BOT, level);
			BotEntity botB = new BotEntity(DuelDojo.BOT, level);
			Tier[] pair = pairs.get(i % pairs.size());
			botA.setup(pair[0], botB);
			botB.setup(pair[1], botA);
			Arena arena = new Arena(pair[0], pair[1], new Vec3(x, ground, z), botA, botB);
			place(arena);
			level.addFreshEntity(botA);
			level.addFreshEntity(botB);
			next.arenas.add(arena);
		}
		run = next;
		running = true;
		if (sprint) level.getServer().tickRateManager().requestGameToSprint(Integer.MAX_VALUE / 2);
		out.accept(Component.literal("Simulating " + pairs.size() + " pairing(s) x " + next.rounds * copies + " rounds in " + arenas + " arenas"
				+ (sprint ? ", time sped up" : "") + ". /dojo sim stop cancels.").withStyle(ChatFormatting.GRAY));
		return true;
	}

	/** Puts both bots back at their marks with full health; the round starts after a short pause. */
	private static void place(Arena arena) {
		arena.botA.resetForRound();
		arena.botB.resetForRound();
		double jitter = (arena.botA.getRandom().nextDouble() - 0.5) * 1.0;
		arena.botA.setPos(arena.center.x + jitter, arena.center.y, arena.center.z - 3.0);
		arena.botB.setPos(arena.center.x - jitter, arena.center.y, arena.center.z + 3.0);
		face(arena.botA, 0.0F);
		face(arena.botB, 180.0F);
		arena.botA.setActive(false);
		arena.botB.setActive(false);
		arena.fighting = false;
		arena.fightTicks = 0;
		arena.pause = PAUSE;
	}

	private static void face(BotEntity bot, float yaw) {
		bot.setYRot(yaw);
		bot.setXRot(0.0F);
		bot.yHeadRot = yaw;
		bot.yBodyRot = yaw;
	}

	public static void stop(Consumer<Component> out) {
		if (run == null) {
			out.accept(Component.literal("No simulation is running.").withStyle(ChatFormatting.GRAY));
			return;
		}
		Run old = run;
		cleanUp();
		out.accept(Component.literal("Simulation cancelled.").withStyle(ChatFormatting.GRAY));
		if (old.out != out) old.out.accept(Component.literal("Simulation cancelled.").withStyle(ChatFormatting.GRAY));
	}

	static void stopAll() {
		if (run != null) cleanUp();
	}

	private static void cleanUp() {
		Run old = run;
		run = null;
		running = false;
		for (Arena arena : old.arenas) {
			arena.botA.discard();
			arena.botB.discard();
		}
		if (old.sprint && !old.arenas.isEmpty()) {
			MinecraftServer server = old.arenas.get(0).botA.level().getServer();
			if (server != null) server.tickRateManager().stopSprinting();
		}
	}

	static void tick(MinecraftServer server) {
		if (run == null) return;
		Run current = run;
		int finished = 0;
		for (Arena arena : current.arenas) {
			if (current.done(arena)) {
				finished++;
				continue;
			}
			if (arena.botA.isRemoved() || arena.botB.isRemoved()) {
				current.out.accept(Component.literal("A simulation bot was removed, so the simulation stopped.").withStyle(ChatFormatting.RED));
				cleanUp();
				return;
			}
			if (!arena.fighting) {
				if (--arena.pause <= 0) {
					arena.fighting = true;
					arena.botA.setActive(true);
					arena.botB.setActive(true);
				}
				continue;
			}
			// Count swings first: the round's final swing happened this tick too.
			arena.statsA.tick(arena.botA);
			arena.statsB.tick(arena.botB);
			if (arena.pending >= 0) {
				int result = arena.pending;
				arena.pending = -1;
				endRound(current, arena, result);
				continue;
			}
			arena.fightTicks++;
			if (arena.fightTicks >= ROUND_LIMIT
					|| arena.botA.position().distanceTo(arena.center) > MAX_WANDER
					|| arena.botB.position().distanceTo(arena.center) > MAX_WANDER) {
				endRound(current, arena, 0);
			}
		}
		int tenths = (int) (10L * progress(current) / ((long) current.rounds * current.arenas.size()));
		if (tenths > current.reportedTenths && tenths < 10) {
			current.reportedTenths = tenths;
			long fought = 0;
			for (Arena arena : current.arenas) fought += arena.totalTicks + arena.fightTicks;
			current.out.accept(Component.literal(String.format(Locale.ROOT, "Simulation %d%% done (%.0f min of fighting so far)",
					tenths * 10, fought / 1200.0)).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (finished == current.arenas.size()) {
			String report = report(current);
			lastReport = report;
			cleanUp();
			for (String line : report.split("\n")) {
				current.out.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
				DuelDojo.LOGGER.info("[ladder] {}", line);
			}
			Path file = FabricLoader.getInstance().getGameDir().resolve("dueldojo-ladder.txt");
			try {
				Files.writeString(file, report, StandardCharsets.UTF_8);
			} catch (IOException e) {
				DuelDojo.LOGGER.warn("Could not write {}", file, e);
			}
		}
	}

	private static long progress(Run current) {
		long sum = 0;
		for (Arena arena : current.arenas) sum += Math.min(arena.played(), current.rounds);
		return sum;
	}

	/** result: 1 = A won, 2 = B won, 0 = draw. */
	private static void endRound(Run current, Arena arena, int result) {
		if (result == 1) arena.winsA++;
		else if (result == 2) arena.winsB++;
		else arena.draws++;
		arena.totalTicks += arena.fightTicks;
		if (current.done(arena)) {
			arena.fighting = false;
			arena.botA.setActive(false);
			arena.botB.setActive(false);
		} else {
			place(arena);
		}
	}

	/** Fabric ALLOW_DEATH hook: a simulated bot never dies, its round just ends. */
	public static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (run == null || !(entity instanceof BotEntity)) return true;
		for (Arena arena : run.arenas) {
			if (entity != arena.botA && entity != arena.botB) continue;
			entity.setHealth(entity.getMaxHealth());
			if (arena.fighting && arena.pending < 0) {
				// Applied in tick(), not here in the middle of the attack (its knockback is still to come). The hit
				// itself is counted by AFTER_DAMAGE, which still fires because the death was cancelled.
				arena.pending = entity == arena.botA ? 2 : 1;
			}
			return false;
		}
		return true;
	}

	/** Fabric AFTER_DAMAGE hook: hit and crit counts per bot. */
	public static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (run == null || blocked) return;
		for (Arena arena : run.arenas) {
			if (!arena.fighting) continue;
			if (entity == arena.botA && source.getEntity() == arena.botB) {
				arena.statsB.landed(FightStats.isCrit(source, baseDamage), damageTaken, arena.statsA);
			} else if (entity == arena.botB && source.getEntity() == arena.botA) {
				arena.statsA.landed(FightStats.isCrit(source, baseDamage), damageTaken, arena.statsB);
			}
		}
	}

	private static String report(Run current) {
		StringBuilder sb = new StringBuilder();
		long ticks = 0;
		int tiers = Tier.values().length;
		double[][] wins = new double[tiers][tiers];
		boolean[] used = new boolean[tiers];
		for (Arena arena : current.arenas) ticks += arena.totalTicks;
		sb.append(String.format(Locale.ROOT, "Duel Dojo bot simulation: %d rounds per pairing, %.0f min of fighting simulated%n",
				current.rounds * current.copies, ticks / 1200.0));
		// Arenas of the same pairing are added up: {winsA, winsB, draws, ticks, swingsA, hitsA, critsA, swingsB, hitsB, critsB}.
		Map<String, long[]> byPair = new LinkedHashMap<>();
		Map<String, Arena> firstOf = new LinkedHashMap<>();
		for (Arena arena : current.arenas) {
			String key = arena.a.id + " vs " + arena.b.id;
			firstOf.putIfAbsent(key, arena);
			long[] sum = byPair.computeIfAbsent(key, k -> new long[10]);
			long[] add = {arena.winsA, arena.winsB, arena.draws, arena.totalTicks, arena.statsA.swings, arena.statsA.hits,
					arena.statsA.crits, arena.statsB.swings, arena.statsB.hits, arena.statsB.crits};
			for (int k = 0; k < sum.length; k++) sum[k] += add[k];
		}
		for (Map.Entry<String, long[]> entry : byPair.entrySet()) {
			Arena arena = firstOf.get(entry.getKey());
			long[] s = entry.getValue();
			long played = Math.max(1, s[0] + s[1] + s[2]);
			sb.append(String.format(Locale.ROOT, "%s: %d-%d, %d draws -> %s wins %d%% | hit rate %d%% / %d%% | crits per hit %d%% / %d%% | %.0f s per round%n",
					entry.getKey(), s[0], s[1], s[2], arena.b.id, Math.round(100.0 * (s[1] + s[2] * 0.5) / played),
					percent(s[5], s[4]), percent(s[8], s[7]), percent(s[6], s[5]), percent(s[9], s[8]), s[3] / 20.0 / played));
			int i = arena.a.ordinal();
			int j = arena.b.ordinal();
			wins[i][j] += s[0] + s[2] * 0.5;
			wins[j][i] += s[1] + s[2] * 0.5;
			used[i] = true;
			used[j] = true;
		}
		double[] elo = bradleyTerry(wins, used);
		float[] skill = Calibration.skills(); // where the real tier list puts each tier
		// Scale the data's skill gaps to Elo with one factor fitted to all steps, then compare step by step.
		double sumElo = 0;
		double sumSkill = 0;
		int prev = -1;
		for (int t = 0; t < tiers; t++) {
			if (!used[t]) continue;
			if (prev >= 0) {
				sumElo += elo[t] - elo[prev];
				sumSkill += skill[t] - skill[prev];
			}
			prev = t;
		}
		double scale = sumSkill > 0 ? sumElo / sumSkill : 0;
		sb.append("Ratings (Elo scale) and the gap each step should have if strength follows the real tier spacing:\n");
		prev = -1;
		for (Tier tier : Tier.values()) {
			int t = tier.ordinal();
			if (!used[t]) continue;
			if (prev < 0) {
				sb.append(String.format(Locale.ROOT, "%s %+.0f%n", tier.id, elo[t]));
			} else {
				sb.append(String.format(Locale.ROOT, "%s %+.0f  (step %+.0f, data says %+.0f)%n",
						tier.id, elo[t], elo[t] - elo[prev], (skill[t] - skill[prev]) * scale));
			}
			prev = t;
		}

		// How each tier played, over all its rounds in every pairing it was in.
		BotBrain.Counts[] byTier = new BotBrain.Counts[tiers];
		for (Arena arena : current.arenas) {
			byTier[arena.a.ordinal()] = sum(byTier[arena.a.ordinal()], arena.botA.counts);
			byTier[arena.b.ordinal()] = sum(byTier[arena.b.ordinal()], arena.botB.counts);
		}
		sb.append("Behaviour: full-strength swings (avg charge) | swings that hit | sprint hits, crits per hit | crit jumps per 100 swings,"
				+ " abandoned | W-taps per hit | panic clicks per 100 swings | strafing when close | jump resets per hit taken\n");
		for (Tier tier : Tier.values()) {
			BotBrain.Counts c = byTier[tier.ordinal()];
			if (c == null || c.swings == 0) continue;
			sb.append(String.format(Locale.ROOT, "%s: %d%% (%d%%) | %d%% | %d%%, %d%% | %d, %d%% | %d%% | %d | %d%% | %d%%%n",
					tier.id, percent(c.strong, c.swings), Math.round(100.0F * c.chargeSum / c.swings), percent(c.hits, c.swings),
					percent(c.sprintHits, c.hits), percent(c.crits, c.hits), percent(c.critJumps, c.swings),
					percent(c.critsAbandoned, c.critJumps), percent(c.wtaps, c.hits), percent(c.panicSwings, c.swings),
					percent(c.strafeTicks, c.closeTicks), percent(c.jumpResets, c.hitsTaken)));
		}
		return sb.toString().trim();
	}

	private static BotBrain.Counts sum(BotBrain.Counts total, BotBrain.Counts add) {
		if (total == null) total = new BotBrain.Counts();
		total.add(add);
		return total;
	}

	private static int percent(long part, long whole) {
		return whole == 0 ? 0 : Math.round(100.0F * part / whole);
	}

	/**
	 * Bradley-Terry strengths by the MM algorithm (Hunter 2004), with half a win and half a loss added to every
	 * played pairing so a clean sweep doesn't give an infinite rating. Returned as Elo, lowest used tier = 0.
	 */
	static double[] bradleyTerry(double[][] wins, boolean[] used) {
		int n = wins.length;
		double[][] w = new double[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				boolean played = wins[i][j] + wins[j][i] > 0;
				w[i][j] = wins[i][j] + (played ? 0.5 : 0.0);
			}
		}
		double[] pi = new double[n];
		java.util.Arrays.fill(pi, 1.0);
		for (int iter = 0; iter < 5000; iter++) {
			double[] next = pi.clone();
			for (int i = 0; i < n; i++) {
				if (!used[i]) continue;
				double won = 0;
				double denom = 0;
				for (int j = 0; j < n; j++) {
					double games = w[i][j] + w[j][i];
					if (i == j || games == 0) continue;
					won += w[i][j];
					denom += games / (pi[i] + pi[j]);
				}
				if (denom > 0) next[i] = won / denom;
			}
			pi = next;
		}
		double[] elo = new double[n];
		int base = -1;
		for (int i = 0; i < n; i++) {
			if (used[i] && base < 0) base = i;
		}
		for (int i = 0; i < n; i++) {
			elo[i] = used[i] ? 400.0 * Math.log10(pi[i] / pi[base]) : 0.0;
		}
		return elo;
	}
}
