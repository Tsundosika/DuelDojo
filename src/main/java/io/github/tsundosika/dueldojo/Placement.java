package io.github.tsundosika.dueldojo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * /dojo placement: finds which bot tier you play like with a binary search over the tiers: up to four first-to-3
 * matches, each against the middle of the tiers still possible. Tips are shown once at the end, for all matches.
 */
final class Placement {
	static final int FIRST_TO = 3;
	/** Enough to narrow ten tiers (eleven outcomes, counting "below LT5") down to one. */
	static final int MAX_MATCHES = 4;
	/** Pause between placement matches, in ticks. */
	private static final int BREAK = 100;

	private static final class State {
		/** Highest tier beaten so far (-1 = none) and lowest tier lost to (10 = none). */
		int lo = -1;
		int hi = Tier.values().length;
		int played;
		Tier current;
		int startAt = -1;
		final FightStats you = new FightStats();
		final FightStats bot = new FightStats();
	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();

	private Placement() {
	}

	/** The tier to play next: the middle of what's still possible (LT3 to start with). */
	static int nextTier(int lo, int hi) {
		return Math.floorDiv(lo + hi, 2);
	}

	static boolean active(ServerPlayer player) {
		return ACTIVE.containsKey(player.getUUID());
	}

	static void start(ServerPlayer player) {
		if (DuelManager.inDuel(player) || active(player) || Watch.watching(player)) {
			player.sendSystemMessage(Component.literal("Finish or /dojo stop your current duel first.").withStyle(ChatFormatting.RED));
			return;
		}
		State state = new State();
		ACTIVE.put(player.getUUID(), state);
		player.sendSystemMessage(Component.literal("Finding your level: up to " + MAX_MATCHES + " first-to-" + FIRST_TO
				+ " matches against different tiers. /dojo stop cancels.").withStyle(ChatFormatting.GOLD));
		startNext(player, state);
	}

	private static void startNext(ServerPlayer player, State state) {
		state.current = Tier.values()[nextTier(state.lo, state.hi)];
		state.startAt = -1;
		player.sendSystemMessage(Component.literal("Placement match " + (state.played + 1) + ": ").withStyle(ChatFormatting.GRAY)
				.append(DuelManager.tierName(state.current)));
		DuelManager.start(player, state.current, FIRST_TO, true);
		if (!DuelManager.inDuel(player)) ACTIVE.remove(player.getUUID()); // couldn't start (no room for the arena)
	}

	/** Called when a placement match has finished. */
	static void onFinished(ServerPlayer player, Tier tier, boolean won, FightStats you, FightStats bot) {
		State state = ACTIVE.get(player.getUUID());
		if (state == null || tier != state.current) return;
		state.you.add(you);
		state.bot.add(bot);
		state.played++;
		if (won) state.lo = tier.ordinal();
		else state.hi = tier.ordinal();
		if (state.hi - state.lo <= 1 || state.played >= MAX_MATCHES) {
			finish(player, state);
		} else {
			state.startAt = player.level().getServer().getTickCount() + BREAK;
			player.sendSystemMessage(Component.literal("Next placement match in " + BREAK / 20 + " s.").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void finish(ServerPlayer player, State state) {
		ACTIVE.remove(player.getUUID());
		MutableComponent result;
		if (state.lo < 0) {
			result = Component.literal("You didn't beat the ").append(DuelManager.tierName(Tier.LT5))
					.append(" bot yet. Drill a bit and try again.");
			PlayerData.get(player.getUUID()).level = "below";
		} else {
			Tier level = Tier.values()[state.lo];
			result = Component.literal("You play like the ").append(DuelManager.tierName(level)).append(" bot");
			if (state.hi < Tier.values().length) {
				result.append(" (beat " + level.id + ", lost to " + Tier.values()[state.hi].id + ").");
			} else {
				result.append(": as good as the bots get.");
			}
			PlayerData.get(player.getUUID()).level = level.id;
		}
		PlayerData.save();
		player.sendSystemMessage(Component.literal("Placement done. ").withStyle(ChatFormatting.GOLD).append(result.withStyle(ChatFormatting.WHITE)));
		DuelManager.sendTips(player, state.you, state.bot, state.lo < 0 ? Tier.LT5 : Tier.values()[state.lo], state.lo >= 0);
	}

	static void cancel(ServerPlayer player) {
		if (ACTIVE.remove(player.getUUID()) != null) {
			player.sendSystemMessage(Component.literal("Placement cancelled.").withStyle(ChatFormatting.GRAY));
		}
	}

	/** Starts the next match once the break is over. */
	static void tick(MinecraftServer server) {
		for (Map.Entry<UUID, State> entry : Map.copyOf(ACTIVE).entrySet()) {
			State state = entry.getValue();
			if (state.startAt < 0 || server.getTickCount() < state.startAt) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				ACTIVE.remove(entry.getKey());
			} else {
				startNext(player, state);
			}
		}
	}
}
