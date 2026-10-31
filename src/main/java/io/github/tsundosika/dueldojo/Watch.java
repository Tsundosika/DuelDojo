package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * /dojo watch: two bots fight round after round in the sky arena while you watch as a spectator, with the game
 * slowed down so you can see what they do. The action bar names their techniques as they use them. /dojo stop
 * ends it, and your items, game mode and position come back (after a crash too, through {@link DuelRecovery}).
 */
final class Watch {
	/** Between rounds, in ticks. */
	private static final int PAUSE = 40;
	/** At most one technique callout per this many ticks, so they can be read. */
	private static final int CALLOUT_GAP = 8;
	/** Technique counters in callout priority, and what the action bar calls them. */
	private static final String[] TECHNIQUES = {"crit deflection", "hit select", "falling sprint hit", "pick hit", "uppercut", "crit", "crit jump",
			"jump reset", "hold S", "delayed hit", "W-tap", "bait", "waits at the edge", "juke", "panic click"};

	private static final class Session {
		final ServerPlayer player;
		final PlayerSnapshot snapshot;
		final DuelArena arena;
		final BotEntity a;
		final BotEntity b;
		final Tier tierA;
		final Tier tierB;
		final float speed;
		final float previousRate;
		final ServerBossEvent bar;
		int winsA;
		int winsB;
		/** 1 = a won the round, 2 = b did, 0 = none yet. */
		int pending;
		int pause = PAUSE;
		int calloutWait;
		int[] lastA = new int[TECHNIQUES.length];
		int[] lastB = new int[TECHNIQUES.length];

		Session(ServerPlayer player, PlayerSnapshot snapshot, DuelArena arena, BotEntity a, BotEntity b, Tier tierA, Tier tierB,
				float speed, float previousRate, ServerBossEvent bar) {
			this.player = player;
			this.snapshot = snapshot;
			this.arena = arena;
			this.a = a;
			this.b = b;
			this.tierA = tierA;
			this.tierB = tierB;
			this.speed = speed;
			this.previousRate = previousRate;
			this.bar = bar;
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private Watch() {
	}

	static boolean watching(ServerPlayer player) {
		return SESSIONS.containsKey(player.getUUID());
	}

	static void start(ServerPlayer player, Tier tierA, Tier tierB, float speed) {
		if (DuelManager.inDuel(player) || watching(player) || Placement.active(player)) {
			player.sendSystemMessage(Component.literal("Finish or /dojo stop what you're doing first.").withStyle(ChatFormatting.RED));
			return;
		}
		MinecraftServer server = player.getServer();
		ServerLevel level = (ServerLevel) player.level();
		DuelArena arena = DuelArena.build(level, player.blockPosition());
		if (arena == null) {
			player.sendSystemMessage(Component.literal("There's no free space for the arena near the top of the world above you.")
					.withStyle(ChatFormatting.RED));
			return;
		}
		PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
		DuelRecovery.begin(player, snapshot, arena);

		BotEntity a = new BotEntity(DuelDojo.BOT, level);
		BotEntity b = new BotEntity(DuelDojo.BOT, level);
		a.setup(tierA, b);
		b.setup(tierB, a);
		ServerBossEvent bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
		float previousRate = server.tickRateManager().tickrate();
		Session session = new Session(player, snapshot, arena, a, b, tierA, tierB, speed, previousRate, bar);
		place(session);
		level.addFreshEntity(a);
		level.addFreshEntity(b);
		SESSIONS.put(player.getUUID(), session);

		// Watch from the side, a little above, looking at the middle of the arena.
		player.setGameMode(GameType.SPECTATOR);
		Vec3 center = Vec3.atBottomCenterOf(arena.center);
		player.teleportTo(level, center.x - 8.0, center.y + 3.5, center.z, Set.of(), -90.0F, 18.0F, false);
		server.tickRateManager().setTickRate(20.0F * speed);
		bar.setProgress(1.0F);
		bar.addPlayer(player);
		updateBar(session);
		player.sendSystemMessage(Component.literal("Watching ").withStyle(ChatFormatting.GOLD).append(DuelManager.tierName(tierA))
				.append(Component.literal(" vs ").withStyle(ChatFormatting.GOLD)).append(DuelManager.tierName(tierB))
				.append(Component.literal(" at " + speedName(speed) + ". ").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("Fly around, or left-click a bot to see through its eyes (Shift gets you out). /dojo stop ends it.")
						.withStyle(ChatFormatting.GRAY)));
	}

	private static String speedName(float speed) {
		return speed == 0.5F ? "half speed" : speed == 0.25F ? "quarter speed" : Math.round(speed * 100.0F) + "% speed";
	}

	/** Both bots back on their marks with full health; the round starts after a short pause. */
	private static void place(Session session) {
		for (BotEntity bot : new BotEntity[] {session.a, session.b}) {
			bot.resetForRound();
			bot.setActive(false);
		}
		Vec3 spawnA = session.arena.playerSpawn();
		Vec3 spawnB = session.arena.botSpawn();
		session.a.snapTo(spawnA.x, spawnA.y, spawnA.z, 0.0F, 0.0F);
		session.b.snapTo(spawnB.x, spawnB.y, spawnB.z, 180.0F, 0.0F);
		session.pause = PAUSE;
	}

	static void tick(MinecraftServer server) {
		for (Session session : new ArrayList<>(SESSIONS.values())) {
			if (session.player.isRemoved() || !session.a.isAlive() || !session.b.isAlive()) {
				stop(session.player, "The bots were removed, so watching ended.");
				continue;
			}
			if (session.pending != 0) {
				endRound(session);
				continue;
			}
			if (session.pause > 0) {
				if (--session.pause == 0) {
					session.a.setActive(true);
					session.b.setActive(true);
				}
				continue;
			}
			callout(session);
		}
	}

	/** Names the newest technique either bot used, on the action bar. */
	private static void callout(Session session) {
		int[] nowA = techniques(session.a.counts);
		int[] nowB = techniques(session.b.counts);
		if (--session.calloutWait <= 0) {
			for (int i = 0; i < TECHNIQUES.length; i++) {
				Tier who = nowA[i] > session.lastA[i] ? session.tierA : nowB[i] > session.lastB[i] ? session.tierB : null;
				if (who == null) continue;
				session.player.displayClientMessage(DuelManager.tierName(who)
						.append(Component.literal(": " + TECHNIQUES[i]).withStyle(ChatFormatting.WHITE)), true);
				session.calloutWait = CALLOUT_GAP;
				break;
			}
		}
		session.lastA = nowA;
		session.lastB = nowB;
	}

	private static int[] techniques(BotBrain.Counts c) {
		return new int[] {c.deflections, c.hitSelects, c.fallingSprints, c.picks, c.uppercuts, c.crits, c.critJumps,
				c.jumpResets, c.holdBacks, c.delayedHits, c.wtaps, c.baits, c.holds, c.jukes, c.panicSwings};
	}

	private static void endRound(Session session) {
		boolean aWon = session.pending == 1;
		session.pending = 0;
		if (aWon) {
			session.winsA++;
		} else {
			session.winsB++;
		}
		BotEntity winner = aWon ? session.a : session.b;
		MutableComponent line = DuelManager.tierName(aWon ? session.tierA : session.tierB)
				.append(Component.literal(" won the round with ").withStyle(ChatFormatting.GRAY))
				.append(DuelManager.hearts(winner.getHealth()))
				.append(Component.literal(" left (").withStyle(ChatFormatting.GRAY))
				.append(score(session))
				.append(Component.literal(")").withStyle(ChatFormatting.GRAY));
		session.player.sendSystemMessage(line);
		place(session);
		updateBar(session);
	}

	private static MutableComponent score(Session session) {
		return DuelManager.tierName(session.tierA).append(Component.literal(" " + session.winsA + " – " + session.winsB + " ")
				.withStyle(ChatFormatting.WHITE)).append(DuelManager.tierName(session.tierB));
	}

	private static void updateBar(Session session) {
		session.bar.setName(score(session).append(Component.literal("  ·  " + speedName(session.speed)).withStyle(ChatFormatting.GRAY)));
	}

	/** Fabric ALLOW_DEATH hook: a watched bot never dies, the round just ends. */
	static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		for (Session session : SESSIONS.values()) {
			if (entity != session.a && entity != session.b) continue;
			entity.setHealth(entity.getMaxHealth());
			if (session.pending == 0 && session.pause == 0) session.pending = entity == session.b ? 1 : 2;
			return false;
		}
		return true;
	}

	/** Ends watching and puts everything back; message may be null. */
	static void stop(ServerPlayer player, String message) {
		Session session = SESSIONS.remove(player.getUUID());
		if (session == null) return;
		session.a.discard();
		session.b.discard();
		session.bar.removeAllPlayers();
		MinecraftServer server = player.getServer();
		if (server != null) {
			server.tickRateManager().setTickRate(session.previousRate);
			session.arena.remove(server);
		}
		session.snapshot.restore(player);
		DuelRecovery.end(player);
		if (message != null) player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	static void stopAll() {
		for (Session session : new ArrayList<>(SESSIONS.values())) stop(session.player, null);
	}

	/** Below a tenth the game gets unresponsive, and above full speed it isn't slow motion any more. */
	static float clampSpeed(float speed) {
		return Mth.clamp(speed, 0.1F, 1.0F);
	}
}
