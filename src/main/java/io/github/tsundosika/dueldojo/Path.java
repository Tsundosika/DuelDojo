package io.github.tsundosika.dueldojo;

import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /dojo path: the drills as a course, basics first, each one to be passed before the next. The drills themselves stay
 * open to anyone at any time; this only says what to work on now, and uses your best score in each.
 */
final class Path {
	record Step(Drill drill, int pass) {
	}

	static final List<Step> STEPS = List.of(
			new Step(Drill.TIMING, 80),
			new Step(Drill.AIM, 65),
			new Step(Drill.REACH, 65),
			new Step(Drill.FIRST, 50),
			new Step(Drill.WTAP, 70),
			new Step(Drill.STUN, 50),
			new Step(Drill.CRIT, 50),
			new Step(Drill.COMBO, 50),
			new Step(Drill.SPACING, 70),
			new Step(Drill.DODGE, 50));

	private Path() {
	}

	/** The first step not passed yet, or null when all of them are. */
	static Step next(Map<String, Integer> best) {
		for (Step step : STEPS) {
			if (best.getOrDefault(step.drill.id(), -1) < step.pass) return step;
		}
		return null;
	}

	/** Course drills are against your rank's tier, else your placement's, else the weakest bot. */
	static Tier tier(ServerPlayer player) {
		Tier rank = PlayerRanks.get(player.getUUID());
		if (rank != null) return rank;
		String level = PlayerData.get(player.getUUID()).level;
		Tier placed = level == null ? null : Tier.byId(level);
		return placed != null ? placed : Tier.LT5;
	}

	static void show(ServerPlayer player) {
		Map<String, Integer> best = PlayerData.get(player.getUUID()).drillBest;
		Step next = next(best);
		Tier tier = tier(player);
		player.sendSystemMessage(Component.literal("Course").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(" (vs ").withStyle(ChatFormatting.GRAY)).append(DuelManager.tierName(tier))
				.append(Component.literal("; pass each drill to move on):").withStyle(ChatFormatting.GRAY)));
		MutableComponent row = Component.empty();
		boolean reached = false;
		for (Step step : STEPS) {
			Integer score = best.get(step.drill.id());
			String label = step.drill.title + (score != null ? " " + score + "%" : "");
			if (step == next) {
				reached = true;
				row.append(DuelManager.button("[" + label + "]", ChatFormatting.GOLD, DuelManager.drillCommand(step.drill, tier),
						"Next up. Pass with " + step.pass + "%: " + step.drill.description));
			} else if (!reached) {
				row.append(DuelManager.button("[✓ " + label + "]", ChatFormatting.GREEN, DuelManager.drillCommand(step.drill, tier),
						"Passed. Again? " + step.drill.description));
			} else {
				row.append(Component.literal("[" + label + "]").withStyle(ChatFormatting.DARK_GRAY));
			}
			row.append(Component.literal(" "));
		}
		player.sendSystemMessage(row);
		player.sendSystemMessage(next == null ? finished(tier) : nextLine(next, tier));
	}

	/** After a drill: if that was the step you're on and you passed it, says so and offers the next one. */
	static void afterDrill(ServerPlayer player, Drill drill, Step before) {
		if (before == null || before.drill != drill) return;
		Step next = next(PlayerData.get(player.getUUID()).drillBest);
		if (next == before) return;
		Tier tier = tier(player);
		player.sendSystemMessage(Component.literal("Course: " + drill.title + " passed! ").withStyle(ChatFormatting.GREEN)
				.append(next == null ? finished(tier) : nextLine(next, tier)));
	}

	private static MutableComponent nextLine(Step next, Tier tier) {
		return Component.literal("Next: " + next.drill.title + ", get " + next.pass + "% ").withStyle(ChatFormatting.GRAY)
				.append(DuelManager.button("[Start]", ChatFormatting.GOLD, DuelManager.drillCommand(next.drill, tier), next.drill.description));
	}

	private static MutableComponent finished(Tier tier) {
		return Component.literal("You passed every drill. Now use it in a fight: ").withStyle(ChatFormatting.GRAY)
				.append(DuelManager.button("[First to 10 vs " + tier.id + "]", tier.color, DuelManager.command(tier, 10, false), "Like a tier test"));
	}
}
