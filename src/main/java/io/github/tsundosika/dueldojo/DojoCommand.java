package io.github.tsundosika.dueldojo;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * /dojo rank [tier|clear]    show / set / remove your own tier, shown before your name
 * /dojo drill [name] [tier] [quick]  practice drills, 45 s each, after a tutorial (quick: without it)
 * /dojo coach [on|off]       live notes above the hotbar while you fight
 * /dojo placement            up to 4 first-to-3 matches that find which tier you play like
 * /dojo watch <a> <b> [speed] watch two bots fight in slow motion as a spectator (operators / world owner)
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
				.then(Commands.literal("rank")
						.executes(ctx -> showRank(ctx.getSource().getPlayerOrException()))
						.then(Commands.literal("clear").executes(ctx -> setRank(ctx.getSource().getPlayerOrException(), null)))
						.then(tierArgument("tier").executes(ctx -> {
							Tier tier = tier(ctx, "tier");
							return tier == null ? 0 : setRank(ctx.getSource().getPlayerOrException(), tier);
						})))
				.then(Commands.literal("stop").executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					if (Watch.watching(player)) {
						Watch.stop(player, "Stopped watching.");
					} else if (Placement.active(player) && !DuelManager.inDuel(player)) {
						Placement.cancel(player); // between placement matches
					} else {
						Placement.cancel(player);
						DuelManager.stop(player);
					}
					return 1;
				}))
				.then(Commands.literal("drill")
						.executes(ctx -> drillMenu(ctx.getSource().getPlayerOrException()))
						.then(Commands.argument("drill", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(Drill.values()).map(Drill::id), builder))
								.executes(ctx -> startDrill(ctx, null, true))
								.then(tierArgument("tier").executes(ctx -> {
									Tier tier = tier(ctx, "tier");
									return tier == null ? 0 : startDrill(ctx, tier, true);
								}).then(Commands.literal("quick").executes(ctx -> {
									Tier tier = tier(ctx, "tier");
									return tier == null ? 0 : startDrill(ctx, tier, false);
								})))))
				.then(Commands.literal("coach")
						.executes(ctx -> setCoach(ctx.getSource().getPlayerOrException(), null))
						.then(Commands.literal("on").executes(ctx -> setCoach(ctx.getSource().getPlayerOrException(), true)))
						.then(Commands.literal("off").executes(ctx -> setCoach(ctx.getSource().getPlayerOrException(), false))))
				// Slows the whole server down, so operators / the single-player owner only.
				.then(Commands.literal("watch").requires(DojoCommand::mayTune)
						.then(tierArgument("a").then(tierArgument("b")
								.executes(ctx -> watch(ctx, 0.5F))
								.then(Commands.argument("speed", FloatArgumentType.floatArg(0.1F, 1.0F))
										.executes(ctx -> watch(ctx, FloatArgumentType.getFloat(ctx, "speed")))))))
				.then(Commands.literal("placement").executes(ctx -> {
					Placement.start(ctx.getSource().getPlayerOrException());
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
		player.sendSystemMessage(Component.literal("Practice: ").withStyle(ChatFormatting.GRAY)
				.append(DuelManager.button("[Drills]", ChatFormatting.AQUA, "/dojo drill", "Short drills for one skill each"))
				.append(Component.literal(" "))
				.append(DuelManager.button("[Find my level]", ChatFormatting.GOLD, "/dojo placement", "Up to 4 matches that find which tier you play like"))
				.append(Component.literal(" "))
				.append(DuelManager.button("[Watch HT1 vs HT2]", ChatFormatting.GRAY, "/dojo watch ht1 ht2", "Two bots fight in slow motion while you watch"))
				.append(Component.literal(" "))
				.append(DuelManager.button("[Stats]", ChatFormatting.WHITE, "/dojo stats", "Your record and how you're improving")));
		player.sendSystemMessage(Component.literal("Duels are in a sky arena built above you. Add \"here\" to fight where you stand: /dojo lt3 here")
				.withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int watch(CommandContext<CommandSourceStack> ctx, float speed) throws CommandSyntaxException {
		Tier a = tier(ctx, "a");
		Tier b = a == null ? null : tier(ctx, "b");
		if (b == null) return 0;
		Watch.start(ctx.getSource().getPlayerOrException(), a, b, Watch.clampSpeed(speed));
		return 1;
	}

	/** Drills are against your rank's tier, or LT3 if you haven't set one, unless a tier is given. */
	private static int startDrill(CommandContext<CommandSourceStack> ctx, Tier tier, boolean tutorial) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Drill drill = Drill.byId(StringArgumentType.getString(ctx, "drill"));
		if (drill == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown drill. Use /dojo drill to see them."));
			return 0;
		}
		if (tier == null) {
			Tier rank = PlayerRanks.get(player.getUUID());
			tier = rank != null ? rank : Tier.LT3;
		}
		DuelManager.startDrill(player, drill, tier, tutorial);
		return 1;
	}

	private static int drillMenu(ServerPlayer player) {
		Tier rank = PlayerRanks.get(player.getUUID());
		Tier tier = rank != null ? rank : Tier.LT3;
		Map<String, Integer> best = PlayerData.get(player.getUUID()).drillBest;
		player.sendSystemMessage(Component.literal("Drills (" + Drill.SECONDS + " s each, vs ").withStyle(ChatFormatting.GOLD)
				.append(DuelManager.tierName(tier)).append(Component.literal("; hover for what you practise):").withStyle(ChatFormatting.GOLD)));
		MutableComponent row = Component.empty();
		for (Drill drill : Drill.values()) {
			Integer score = best.get(drill.id());
			row.append(DuelManager.button("[" + drill.title + (score != null ? " " + score + "%" : "") + "]", ChatFormatting.AQUA,
					DuelManager.drillCommand(drill, tier), drill.description)).append(Component.literal(" "));
		}
		player.sendSystemMessage(row);
		player.sendSystemMessage(Component.literal("Another tier: /dojo drill <drill> <tier>, e.g. /dojo drill timing ht3").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	/** null toggles. */
	private static int setCoach(ServerPlayer player, Boolean on) {
		PlayerData.Entry data = PlayerData.get(player.getUUID());
		data.coachOff = on == null ? !data.coachOff : !on;
		PlayerData.save();
		player.sendSystemMessage(Component.literal(data.coachOff
				? "Live coach off. /dojo coach turns it back on (drills always show it)."
				: "Live coach on: short notes above your hotbar while you fight (early swings, whiffs, sprint hits, jump resets).")
				.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int showRank(ServerPlayer player) {
		Tier rank = PlayerRanks.get(player.getUUID());
		player.sendSystemMessage(rank == null
				? Component.literal("No rank set. Set your tier with /dojo rank ht3 (lt5 … ht1).").withStyle(ChatFormatting.GRAY)
				: Component.literal("Your rank: ").withStyle(ChatFormatting.GRAY).append(DuelManager.tierName(rank))
						.append(Component.literal(". Change it with /dojo rank <tier>, remove it with /dojo rank clear.").withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private static int setRank(ServerPlayer player, Tier tier) {
		boolean shown = PlayerRanks.set(player, tier) || tier == null;
		if (tier == null) {
			player.sendSystemMessage(Component.literal("Rank removed.").withStyle(ChatFormatting.GRAY));
		} else if (shown) {
			player.sendSystemMessage(Component.literal("Your rank is now ").withStyle(ChatFormatting.GRAY).append(DuelManager.tierName(tier))
					.append(Component.literal(". It shows before your name in chat and above your head (F5).").withStyle(ChatFormatting.GRAY)));
		} else {
			player.sendSystemMessage(Component.literal("Saved your rank, but you're already on a team in this world, so it can't show on"
					+ " your name. Leave that team (/team leave) and it will.").withStyle(ChatFormatting.YELLOW));
		}
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
		PlayerData.Entry data = PlayerData.get(player.getUUID());
		Tier level = data.level == null ? null : Tier.byId(data.level);
		if (level != null) {
			player.sendSystemMessage(Component.literal("Level from placement: you play like the ").withStyle(ChatFormatting.GRAY)
					.append(DuelManager.tierName(level)).append(Component.literal(" bot.").withStyle(ChatFormatting.GRAY)));
		} else if (data.level == null) {
			player.sendSystemMessage(Component.literal("Level: not placed yet ").withStyle(ChatFormatting.GRAY)
					.append(DuelManager.button("[Find my level]", ChatFormatting.GOLD, "/dojo placement", "Up to 4 matches")));
		}
		for (Component trendLine : Progress.trend(data.matches)) player.sendSystemMessage(trendLine);
		if (!data.drillBest.isEmpty()) {
			MutableComponent bests = Component.literal("Drill bests:").withStyle(ChatFormatting.GRAY);
			for (Drill drill : Drill.values()) {
				Integer score = data.drillBest.get(drill.id());
				if (score != null) bests.append(Component.literal(" " + drill.title + " " + score + "%").withStyle(ChatFormatting.WHITE));
			}
			player.sendSystemMessage(bests);
		}
		return 1;
	}
}
