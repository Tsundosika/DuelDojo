package io.github.tsundosika.dueldojo;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /dojo                      clickable tier menu
 * /dojo <tier> [n] [here]    duel a bot (lt5 … ht1) in a sky arena; n = first to n rounds (default 1);
 *                            "here" fights where you stand instead
 * /dojo stats                your record per tier (rounds)
 * /dojo stop                 give up / cancel the current duel
 * /dojo ladder [rounds]      bot vs bot: every tier against the next one (operators / world owner)
 * /dojo sim <a> <b> [rounds] bot vs bot: one pairing
 * /dojo sim stop             cancel a simulation
 * /dojo reload               re-read config/dueldojo-tiers.json
 */
final class DojoCommand {
	private static final int DEFAULT_ROUNDS = 20;
	/** Tier tests go up to first to 20. */
	private static final int MAX_FIRST_TO = 20;

	private DojoCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("dojo")
				.executes(ctx -> menu(ctx.getSource().getPlayerOrException()))
				.then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("stop").executes(ctx -> {
					DuelManager.stop(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("reload").requires(DojoCommand::mayTune).executes(ctx -> {
					TierConfig.reload();
					ctx.getSource().sendSystemMessage(Component.literal("Reloaded config/dueldojo-tiers.json; new duels use it.").withStyle(ChatFormatting.GRAY));
					return 1;
				}))
				.then(Commands.literal("ladder").requires(DojoCommand::mayTune)
						.executes(ctx -> ladder(ctx, DEFAULT_ROUNDS))
						.then(Commands.argument("rounds", IntegerArgumentType.integer(1, 1000))
								.executes(ctx -> ladder(ctx, IntegerArgumentType.getInteger(ctx, "rounds")))))
				.then(Commands.literal("sim").requires(DojoCommand::mayTune)
						.then(Commands.literal("stop").executes(ctx -> {
							BotSim.stop(ctx.getSource()::sendSystemMessage);
							return 1;
						}))
						.then(tierArgument("a").then(tierArgument("b")
								.executes(ctx -> sim(ctx, DEFAULT_ROUNDS))
								.then(Commands.argument("rounds", IntegerArgumentType.integer(1, 1000))
										.executes(ctx -> sim(ctx, IntegerArgumentType.getInteger(ctx, "rounds")))))))
				.then(tierArgument("tier")
						.executes(ctx -> startDuel(ctx, 1, true))
						.then(Commands.literal("here").executes(ctx -> startDuel(ctx, 1, false)))
						.then(Commands.argument("firstTo", IntegerArgumentType.integer(1, MAX_FIRST_TO))
								.executes(ctx -> startDuel(ctx, IntegerArgumentType.getInteger(ctx, "firstTo"), true))
								.then(Commands.literal("here")
										.executes(ctx -> startDuel(ctx, IntegerArgumentType.getInteger(ctx, "firstTo"), false))))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> tierArgument(String name) {
		return Commands.argument(name, StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Tier.values()).map(Tier::lowerId), builder));
	}

	/** Simulations spawn many bots and speed up time: operators, or the owner of a single-player world. */
	private static boolean mayTune(CommandSourceStack source) {
		if (source.hasPermission(2)) return true;
		ServerPlayer player = source.getPlayer();
		return player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
	}

	private static Tier tier(CommandContext<CommandSourceStack> ctx, String name) {
		Tier tier = Tier.byId(StringArgumentType.getString(ctx, name));
		if (tier == null) ctx.getSource().sendFailure(Component.literal("Unknown tier. Use one of: lt5 ht5 lt4 ht4 lt3 ht3 lt2 ht2 lt1 ht1"));
		return tier;
	}

	private static int startDuel(CommandContext<CommandSourceStack> ctx, int firstTo, boolean inArena) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Tier tier = tier(ctx, "tier");
		if (tier == null) return 0;
		DuelManager.start(player, tier, firstTo, inArena);
		return 1;
	}

	private static int ladder(CommandContext<CommandSourceStack> ctx, int rounds) {
		CommandSourceStack source = ctx.getSource();
		return BotSim.startLadder(source.getLevel(), source.getPosition(), rounds, 1, source::sendSystemMessage, true) ? 1 : 0;
	}

	private static int sim(CommandContext<CommandSourceStack> ctx, int rounds) {
		Tier a = tier(ctx, "a");
		Tier b = a == null ? null : tier(ctx, "b");
		if (b == null) return 0;
		CommandSourceStack source = ctx.getSource();
		return BotSim.start(source.getLevel(), source.getPosition(), List.<Tier[]>of(new Tier[] {a, b}), rounds, 1, source::sendSystemMessage, true) ? 1 : 0;
	}

	private static int menu(ServerPlayer player) {
		player.sendSystemMessage(Component.literal("Duel Dojo").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(" — pick a bot (weakest → strongest), hover for details:").withStyle(ChatFormatting.GRAY)));
		MutableComponent row = Component.empty();
		for (Tier tier : Tier.values()) {
			row.append(DuelManager.button("[" + tier.id + "]", tier.color, "/dojo " + tier.lowerId(), tier.description))
					.append(Component.literal(" "));
		}
		player.sendSystemMessage(row);
		MutableComponent series = Component.literal("First to 10, like a tier test: ").withStyle(ChatFormatting.GRAY);
		for (Tier tier : Tier.values()) {
			series.append(DuelManager.button(tier.id, tier.color, DuelManager.command(tier, 10, false), "First to 10 vs " + tier.id))
					.append(Component.literal(" "));
		}
		player.sendSystemMessage(series);
		player.sendSystemMessage(Component.literal("Duels are in a sky arena built above you. Add \"here\" to fight where you stand: /dojo lt3 here")
				.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int stats(ServerPlayer player) {
		player.sendSystemMessage(Component.literal("Your Duel Dojo record:").withStyle(ChatFormatting.GOLD));
		for (Tier tier : Tier.values()) {
			int[] row = DuelStats.get(player.getUUID(), tier);
			int duels = row[DuelStats.WINS] + row[DuelStats.LOSSES];
			if (duels == 0) continue;
			String line = "  " + row[DuelStats.WINS] + "W / " + row[DuelStats.LOSSES] + "L  (" + Math.round(100.0F * row[DuelStats.WINS] / duels) + "%)";
			if (row[DuelStats.SWINGS] > 0) {
				line += "  hit rate " + Math.round(100.0F * row[DuelStats.HITS] / row[DuelStats.SWINGS]) + "%";
				if (row[DuelStats.HITS] > 0) line += ", crits " + Math.round(100.0F * row[DuelStats.CRITS] / row[DuelStats.HITS]) + "% of hits";
			}
			player.sendSystemMessage(Component.literal("  ").append(DuelManager.tierName(tier))
					.append(Component.literal(line).withStyle(ChatFormatting.GRAY)));
		}
		return 1;
	}
}
