package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Starts, runs and ends duels. One duel per player, played as "first to N" rounds (N = 1 is a single fight).
 * All state lives here; on disk there are only the stats and, while a duel runs, {@link DuelRecovery}'s file.
 */
public final class DuelManager {
	private static final int COUNTDOWN_TICKS = 60;
	/** Between rounds, the round result stays on screen this long before the next countdown starts. */
	private static final int ROUND_PAUSE_TICKS = 30;
	private static final double MAX_DISTANCE = 48.0;
	/** Fighting where you stand: the bot starts this far in front of you. */
	private static final double HERE_DISTANCE = 6.0;
	/** Holding the skip key (Shift) this long skips "try it". */
	private static final int SKIP_HOLD_TICKS = 20;
	private static final Identifier FREEZE = Identifier.fromNamespaceAndPath(DuelDojo.MOD_ID, "countdown_freeze");
	private static final Map<UUID, Duel> DUELS = new HashMap<>();

	private DuelManager() {
	}

	/** A drill with a tutorial goes through the window (nothing moves) and "try it" (no timer, nothing scored) first. */
	private enum Stage { WINDOW, TRY, RUN }

	private static final class Duel {
		final ServerPlayer player;
		final BotEntity bot;
		final Tier tier;
		final int firstTo;
		final PlayerSnapshot snapshot;
		/** Null when fighting where the player stood. */
		final DuelArena arena;
		final Vec3 playerSpawn;
		final float playerYaw;
		final Vec3 botSpawn;
		final float botYaw;
		/** This round's numbers, and all rounds' together. */
		FightStats you = new FightStats();
		FightStats botStats = new FightStats();
		final FightStats youTotal = new FightStats();
		final FightStats botTotal = new FightStats();
		int yourRounds;
		int botRounds;
		int countdown = COUNTDOWN_TICKS;
		int fightTicks;
		int totalTicks;
		/** Round result waiting for the end of the tick: 1 = you won the round, 2 = the bot did, 0 = none. */
		int pending;
		boolean finished;
		/** Set for a practice drill: no rounds, it runs for {@link Drill#SECONDS} with a live score in a boss bar. */
		final Drill drill;
		int drillTicks;
		ServerBossEvent bar;
		Stage stage = Stage.RUN;
		int skipHold;
		TutorialNet.Hud lastHud;
		/** The tick rate to go back to after a slowed-down "try it"; 0 = not slowed. */
		float normalTickRate;

		Duel(ServerPlayer player, BotEntity bot, Tier tier, int firstTo, Drill drill, PlayerSnapshot snapshot, DuelArena arena,
				Vec3 playerSpawn, float playerYaw, Vec3 botSpawn, float botYaw) {
			this.player = player;
			this.bot = bot;
			this.tier = tier;
			this.firstTo = firstTo;
			this.drill = drill;
			this.snapshot = snapshot;
			this.arena = arena;
			this.playerSpawn = playerSpawn;
			this.playerYaw = playerYaw;
			this.botSpawn = botSpawn;
			this.botYaw = botYaw;
		}
	}

	public static boolean inDuel(ServerPlayer player) {
		return DUELS.containsKey(player.getUUID());
	}

	/**
	 * @param firstTo rounds needed to win (1 = a single fight)
	 * @param inArena true: in a {@link DuelArena} built above you; false: right where you stand
	 */
	public static void start(ServerPlayer player, Tier tier, int firstTo, boolean inArena) {
		start(player, tier, firstTo, inArena, null, false);
	}

	/**
	 * A practice drill against a bot of this tier, in the arena.
	 *
	 * @param tutorial show its tutorial first (needs the mod on the player's client, which it almost always is)
	 */
	static void startDrill(ServerPlayer player, Drill drill, Tier tier, boolean tutorial) {
		start(player, tier, 1, true, drill, tutorial);
	}

	private static void start(ServerPlayer player, Tier tier, int firstTo, boolean inArena, Drill drill, boolean tutorial) {
		if (inDuel(player) || Watch.watching(player)) {
			player.sendSystemMessage(Component.literal("You're already in a duel or watching one. Use /dojo stop first.").withStyle(ChatFormatting.RED));
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		PlayerSnapshot snapshot = PlayerSnapshot.capture(player);

		DuelArena arena = null;
		Vec3 playerSpawn;
		float playerYaw;
		Vec3 botSpawn;
		float botYaw;
		if (inArena) {
			arena = DuelArena.build(level, player.blockPosition());
			if (arena == null) {
				player.sendSystemMessage(Component.literal("There's no free space for the arena near the top of the world above you. Use /dojo "
						+ tier.lowerId() + " here to fight where you stand.").withStyle(ChatFormatting.RED));
				return;
			}
			playerSpawn = arena.playerSpawn();
			playerYaw = 0.0F;
			botSpawn = arena.botSpawn();
			botYaw = 180.0F;
		} else {
			Vec3 look = player.getLookAngle();
			Vec3 dir = new Vec3(look.x, 0, look.z);
			dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : dir.normalize();
			double x = player.getX() + dir.x * HERE_DISTANCE;
			double z = player.getZ() + dir.z * HERE_DISTANCE;
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
			double y = Math.abs(ground - player.getY()) <= 4 ? ground : player.getY();
			botYaw = (float) (Mth.atan2(-dir.z, -dir.x) * Mth.RAD_TO_DEG) - 90.0F;
			botSpawn = new Vec3(x, y, z);
			playerYaw = Mth.wrapDegrees(botYaw + 180.0F);
			playerSpawn = player.position();
		}

		BotEntity bot = new BotEntity(DuelDojo.BOT, level);
		bot.setup(tier, player);
		if (drill != null) bot.setDrill(drill);
		bot.snapTo(botSpawn.x, botSpawn.y, botSpawn.z, botYaw, 0.0F);
		level.addFreshEntity(bot);

		Duel duel = new Duel(player, bot, tier, firstTo, drill, snapshot, arena, playerSpawn, playerYaw, botSpawn, botYaw);
		duel.you = playerStats(duel);
		DUELS.put(player.getUUID(), duel);
		DuelRecovery.begin(player, snapshot, arena);
		resetFighters(duel);

		if (drill != null) {
			duel.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(drill.title + " drill"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
			if (tutorial && ServerPlayNetworking.canSend(player, TutorialNet.Open.TYPE)) {
				duel.stage = Stage.WINDOW;
				ServerPlayNetworking.send(player, new TutorialNet.Open(drill.ordinal()));
			} else {
				announceDrill(duel);
			}
			return;
		}
		int[] wl = DuelStats.get(player.getUUID(), tier);
		Tier rank = PlayerRanks.get(player.getUUID());
		MutableComponent you = rank == null ? Component.empty() : Component.literal("you: ").append(tierName(rank)).append(", ");
		player.sendSystemMessage(Component.literal("Duel vs ").append(tierName(tier))
				.append(Component.literal(" bot" + (firstTo > 1 ? ", first to " + firstTo : "") + " (").append(you)
						.append(Component.literal("your record: " + wl[0] + "W / " + wl[1] + "L). Your items come back afterwards."
								+ " /dojo stop to give up."))
						.withStyle(ChatFormatting.GRAY)));
	}

	/** The drill proper is about to start: the score bar appears, and its one-line reminder in chat. */
	private static void announceDrill(Duel duel) {
		duel.bar.addPlayer(duel.player);
		duel.player.sendSystemMessage(Component.literal(duel.drill.title + " drill vs ").withStyle(ChatFormatting.GOLD).append(tierName(duel.tier))
				.append(Component.literal(" bot, " + Drill.SECONDS + " s: ").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(duel.drill.description).withStyle(ChatFormatting.GRAY)));
	}

	/** The stage of the player's drill (WINDOW, TRY or RUN), or null if they're not in a duel. */
	static String stage(ServerPlayer player) {
		Duel duel = DUELS.get(player.getUUID());
		return duel == null ? null : duel.stage.name();
	}

	/** The tutorial window was closed: on to "try it", or with skip right to the drill's countdown. */
	static void tutorialClosed(ServerPlayer player, boolean skip) {
		Duel duel = DUELS.get(player.getUUID());
		if (duel == null || duel.stage != Stage.WINDOW) return;
		if (skip) {
			duel.stage = Stage.RUN;
			announceDrill(duel);
			return;
		}
		duel.stage = Stage.TRY;
		duel.countdown = 0;
		freeze(player, false);
		duel.bot.setActive(true);
		MinecraftServer server = player.level().getServer();
		// It slows the whole server, so only when no one else is on it.
		if (DrillGuide.of(duel.drill).slowTry && server != null && server.getPlayerCount() == 1) {
			duel.normalTickRate = server.tickRateManager().tickrate();
			server.tickRateManager().setTickRate(duel.normalTickRate * 0.5F);
		}
		player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
	}

	/** One tick of "try it": the on-screen objective and prompt; done (or skipped) goes on to the drill's countdown. */
	private static void tutorialTick(Duel duel) {
		ServerPlayer player = duel.player;
		DrillGuide guide = DrillGuide.of(duel.drill);
		int done = Math.max(0, Math.min(guide.done(duel.drill, duel.you, duel.botStats), guide.goal));
		duel.skipHold = player.getLastClientInput().shift() ? duel.skipHold + 1 : 0;
		boolean skipped = duel.skipHold >= SKIP_HOLD_TICKS;
		if (done >= guide.goal || skipped) {
			endTry(duel);
			title(player, Component.literal(skipped ? "SKIPPED" : "GOT IT!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
					Component.literal("Now the real drill").withStyle(ChatFormatting.GRAY));
			player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
			duel.stage = Stage.RUN;
			duel.you = playerStats(duel);
			duel.botStats = new FightStats();
			duel.fightTicks = 0;
			duel.countdown = COUNTDOWN_TICKS + ROUND_PAUSE_TICKS;
			resetFighters(duel);
			announceDrill(duel);
			return;
		}
		sendHud(duel, new TutorialNet.Hud(duel.drill.ordinal(), done, guide.hint(duel.drill, player, duel.bot, duel.you),
				duel.skipHold * 100 / SKIP_HOLD_TICKS));
	}

	/** "Try it" is over: its prompts go away and the speed comes back. */
	private static void endTry(Duel duel) {
		sendHud(duel, TutorialNet.Hud.HIDDEN);
		MinecraftServer server = duel.player.level().getServer();
		if (duel.normalTickRate > 0.0F && server != null) server.tickRateManager().setTickRate(duel.normalTickRate);
		duel.normalTickRate = 0.0F;
	}

	private static void sendHud(Duel duel, TutorialNet.Hud hud) {
		if (hud.equals(duel.lastHud)) return;
		duel.lastHud = hud;
		ServerPlayNetworking.send(duel.player, hud);
	}

	static void giveKit(ServerPlayer player) {
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
	}

	/** Both fighters back on their marks with a fresh kit and full health; you stay frozen until "FIGHT!". */
	private static void resetFighters(Duel duel) {
		ServerPlayer player = duel.player;
		BotEntity bot = duel.bot;
		player.setGameMode(GameType.SURVIVAL);
		player.getInventory().clearContent();
		giveKit(player);
		player.removeAllEffects();
		player.clearFire();
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5.0F);
		player.teleportTo((ServerLevel) bot.level(), duel.playerSpawn.x, duel.playerSpawn.y, duel.playerSpawn.z, Set.of(), duel.playerYaw, 0.0F, true);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		freeze(player, true);

		bot.resetForRound();
		bot.setActive(false);
		bot.teleportTo(duel.botSpawn.x, duel.botSpawn.y, duel.botSpawn.z);
		bot.setYRot(duel.botYaw);
		bot.setXRot(0.0F);
		bot.yHeadRot = duel.botYaw;
		bot.yBodyRot = duel.botYaw;
	}

	/** Fresh numbers for the player, with live coach notes on the action bar unless they turned the coach off (drills always). */
	private static FightStats playerStats(Duel duel) {
		FightStats stats = new FightStats();
		ServerPlayer player = duel.player;
		if (duel.drill != null || !PlayerData.get(player.getUUID()).coachOff) {
			stats.coach = note -> player.sendOverlayMessage(note);
		}
		return stats;
	}

	/** Holds you in place during the countdown (no walking, no jumping); looking around still works. */
	static void freeze(ServerPlayer player, boolean frozen) {
		for (Holder<Attribute> attribute : List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
			AttributeInstance instance = player.getAttribute(attribute);
			if (instance == null) continue;
			if (frozen) {
				instance.addOrUpdateTransientModifier(new AttributeModifier(FREEZE, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else {
				instance.removeModifier(FREEZE);
			}
		}
	}

	public static void tick(MinecraftServer server) {
		for (Duel duel : new ArrayList<>(DUELS.values())) {
			if (duel.finished) continue;
			ServerPlayer player = duel.player;
			BotEntity bot = duel.bot;

			if (duel.countdown == 0) {
				// Counted before the round can end, so the swing that ends it is in its numbers too.
				duel.fightTicks++;
				duel.you.tick(player);
				duel.botStats.tick(bot);
			}
			if (duel.stage == Stage.TRY) {
				tutorialTick(duel);
			} else if (duel.drill != null) {
				if (duel.countdown == 0 && drillTick(duel)) continue;
			} else if (duel.pending != 0) {
				endRound(duel, duel.pending == 1);
				continue;
			}
			if (!bot.isAlive()) {
				abort(duel, "The bot was removed, so the duel was cancelled.");
				continue;
			}
			if (player.isRemoved() || player.level() != bot.level() || player.distanceTo(bot) > MAX_DISTANCE) {
				abort(duel, "You left the arena, so the duel was cancelled.");
				continue;
			}
			if (duel.countdown > 0 && duel.stage != Stage.WINDOW) {
				if (duel.countdown % 20 == 0 && duel.countdown <= COUNTDOWN_TICKS) {
					int seconds = duel.countdown / 20;
					MutableComponent subtitle = tierName(duel.tier);
					if (duel.drill != null) subtitle.append(Component.literal("  " + duel.drill.title + " drill").withStyle(ChatFormatting.GOLD));
					if (duel.firstTo > 1) subtitle.append(Component.literal("  ")).append(score(duel));
					title(player, Component.literal(String.valueOf(seconds)).withStyle(ChatFormatting.YELLOW), subtitle);
					player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
				}
				duel.countdown--;
				if (duel.countdown == 0) {
					title(player, Component.literal("FIGHT!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty());
					player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
					freeze(player, false);
					bot.setActive(true);
				}
			}
		}
	}

	/** Fabric AFTER_DAMAGE hook: counts hits and crits for the round summary (also the final blow of a round). */
	public static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (blocked) return;
		for (Duel duel : DUELS.values()) {
			if (duel.finished || duel.countdown > 0) continue;
			// Sprint knockback needs a charged hit while sprinting; the attack only stops the sprint after the damage.
			if (entity == duel.bot && source.getEntity() == duel.player) {
				duel.you.landed(FightStats.isCrit(source, baseDamage), duel.player.isSprinting() && duel.you.charged(), damageTaken, duel.botStats);
			} else if (entity == duel.player && source.getEntity() == duel.bot) {
				duel.botStats.landed(FightStats.isCrit(source, baseDamage), duel.bot.isSprinting() && duel.botStats.charged(), damageTaken, duel.you);
			}
		}
	}

	/**
	 * Fabric ALLOW_DEATH hook: in a duel nobody really dies (no drops, no respawn); whoever would have died loses the
	 * round, which is settled at the end of the tick.
	 */
	public static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		for (Duel duel : DUELS.values()) {
			if (duel.finished || (entity != duel.player && entity != duel.bot)) continue;
			entity.setHealth(entity.getMaxHealth());
			// In a drill nobody loses rounds: whoever would have died just carries on with full health.
			if (duel.drill == null && duel.countdown == 0 && duel.pending == 0) duel.pending = entity == duel.bot ? 1 : 2;
			return false;
		}
		return true;
	}

	public static void stop(ServerPlayer player) {
		Duel duel = DUELS.get(player.getUUID());
		if (duel == null) {
			player.sendSystemMessage(Component.literal("You're not in a duel.").withStyle(ChatFormatting.GRAY));
			return;
		}
		abort(duel, "Duel cancelled. Your items are back.");
	}

	public static void onDisconnect(ServerPlayer player) {
		Duel duel = DUELS.get(player.getUUID());
		if (duel != null) abort(duel, null);
	}

	public static void abortAll() {
		for (Duel duel : new ArrayList<>(DUELS.values())) abort(duel, null);
	}

	private static void abort(Duel duel, String message) {
		end(duel);
		if (message != null) duel.player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	/** Puts everything back: the bot goes, you get your items and place back, the arena comes down. */
	private static void end(Duel duel) {
		duel.finished = true;
		DUELS.remove(duel.player.getUUID());
		if (duel.stage == Stage.TRY) endTry(duel);
		if (duel.bar != null) duel.bar.removeAllPlayers();
		MinecraftServer server = duel.player.level().getServer();
		if (duel.bot.isAlive()) duel.bot.discard();
		freeze(duel.player, false);
		duel.snapshot.restore(duel.player);
		if (duel.arena != null && server != null) duel.arena.remove(server);
		DuelRecovery.end(duel.player);
	}

	private static void endRound(Duel duel, boolean won) {
		duel.pending = 0;
		if (won) {
			duel.yourRounds++;
		} else {
			duel.botRounds++;
		}
		DuelStats.record(duel.player.getUUID(), duel.tier, won, duel.you);
		// How close it was: the winner's health, read before anyone is healed for the next round.
		duel.player.sendSystemMessage(won
				? Component.literal("Round won with ").withStyle(ChatFormatting.GREEN)
						.append(hearts(duel.player.getHealth())).append(Component.literal(" left.").withStyle(ChatFormatting.GREEN))
				: Component.literal("Round lost: the ").withStyle(ChatFormatting.RED).append(tierName(duel.tier))
						.append(Component.literal(" bot had ").withStyle(ChatFormatting.RED)).append(hearts(duel.bot.getHealth()))
						.append(Component.literal(" left.").withStyle(ChatFormatting.RED)));
		duel.youTotal.add(duel.you);
		duel.botTotal.add(duel.botStats);
		duel.totalTicks += duel.fightTicks;
		if (duel.yourRounds >= duel.firstTo || duel.botRounds >= duel.firstTo) {
			finish(duel, won);
			return;
		}

		ServerPlayer player = duel.player;
		title(player,
				won ? Component.literal("ROUND WON").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
						: Component.literal("ROUND LOST").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
				score(duel));
		player.level().playSound(null, player.blockPosition(),
				won ? SoundEvents.NOTE_BLOCK_BELL.value() : SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
		duel.you = playerStats(duel);
		duel.botStats = new FightStats();
		duel.fightTicks = 0;
		duel.countdown = COUNTDOWN_TICKS + ROUND_PAUSE_TICKS;
		resetFighters(duel);
	}

	private static void finish(Duel duel, boolean won) {
		end(duel);
		ServerPlayer player = duel.player;
		int[] wl = DuelStats.get(player.getUUID(), duel.tier);
		MutableComponent subtitle = Component.literal("vs ").append(tierName(duel.tier)).append(Component.literal("  "));
		subtitle.append(duel.firstTo > 1 ? score(duel) : Component.literal(wl[0] + "W / " + wl[1] + "L"));

		title(player,
				won ? Component.literal("VICTORY").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
						: Component.literal("DEFEAT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
				subtitle);
		player.level().playSound(null, player.blockPosition(),
				won ? SoundEvents.PLAYER_LEVELUP : SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0F, 1.0F);

		boolean here = duel.arena == null;
		MutableComponent line = Component.literal(won ? "You beat the " : "You lost to the ")
				.withStyle(won ? ChatFormatting.GREEN : ChatFormatting.RED)
				.append(tierName(duel.tier))
				.append(Component.literal(" bot" + (duel.firstTo > 1 ? " " + duel.yourRounds + "–" + duel.botRounds : "") + ". ")
						.withStyle(won ? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(button("[Rematch]", ChatFormatting.YELLOW, command(duel.tier, duel.firstTo, here), "Fight the same tier again"));
		Tier next = duel.tier.next();
		if (won && next != null) {
			line.append(Component.literal(" ")).append(button("[Next: " + next.id + "]", next.color, command(next, duel.firstTo, here), next.description));
		}
		player.sendSystemMessage(line);
		player.sendSystemMessage(Component.literal("You: " + summary(duel.youTotal) + " | Bot: " + summary(duel.botTotal)
				+ " | " + Math.round(duel.totalTicks / 20.0F) + " s").withStyle(ChatFormatting.GRAY));
		PlayerData.recordMatch(player.getUUID(), duel.tier, won, duel.youTotal, duel.botTotal);
		if (Placement.active(player)) {
			Placement.onFinished(player, duel.tier, won, duel.youTotal, duel.botTotal); // tips come at the end of placement
		} else {
			sendTips(player, duel.youTotal, duel.botTotal, duel.tier, won);
		}
	}

	/** Up to three one-line tips (details on hover), each with a button for the drill that practises it. */
	static void sendTips(ServerPlayer player, FightStats you, FightStats bot, Tier tier, boolean won) {
		player.sendSystemMessage(Component.literal("Tips ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal("(hover one for how):").withStyle(ChatFormatting.GRAY)));
		for (Coach.Tip tip : Coach.tips(you, bot, tier, won)) {
			MutableComponent line = Component.literal(" • " + tip.line()).withStyle(style -> style.withColor(ChatFormatting.GRAY)
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(tip.detail()))));
			if (tip.drill() != null) {
				line.append(" ").append(button("[Practice]", ChatFormatting.AQUA, drillCommand(tip.drill(), tier),
						tip.drill().title + " drill, " + Drill.SECONDS + " s: " + tip.drill().description));
			}
			player.sendSystemMessage(line);
		}
	}

	static String drillCommand(Drill drill, Tier tier) {
		return "/dojo drill " + drill.id() + " " + tier.lowerId();
	}

	/** One tick of a running drill: the live score in the boss bar; returns true once the time is up and it ended. */
	private static boolean drillTick(Duel duel) {
		duel.drillTicks++;
		int total = Drill.SECONDS * 20;
		if (duel.drillTicks >= total) {
			endDrill(duel);
			return true;
		}
		if (duel.drillTicks % 5 == 0) {
			int left = (total - duel.drillTicks + 19) / 20;
			duel.bar.setName(Component.literal(duel.drill.title + ": " + duel.drill.progress(duel.you, duel.botStats) + "  "
					+ left / 60 + ":" + String.format("%02d", left % 60)));
			duel.bar.setProgress((total - duel.drillTicks) / (float) total);
		}
		return false;
	}

	private static void endDrill(Duel duel) {
		end(duel);
		ServerPlayer player = duel.player;
		Drill drill = duel.drill;
		int score = drill.score(duel.you, duel.botStats);
		MutableComponent result = Component.literal(drill.title + " drill: ").withStyle(ChatFormatting.GOLD);
		if (score < 0) {
			result.append(Component.literal("too little to score, keep at it next time.").withStyle(ChatFormatting.GRAY));
		} else {
			int before = PlayerData.recordDrill(player.getUUID(), drill, score);
			result.append(Component.literal(score + "%").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
					.append(Component.literal(" (" + drill.progress(duel.you, duel.botStats) + ")").withStyle(ChatFormatting.GRAY));
			if (before < 0) {
				result.append(Component.literal(" First score!").withStyle(ChatFormatting.GREEN));
			} else if (score > before) {
				result.append(Component.literal(" New best! (was " + before + "%)").withStyle(ChatFormatting.GREEN));
			} else {
				result.append(Component.literal(" Best: " + before + "%").withStyle(ChatFormatting.GRAY));
			}
		}
		title(player, Component.literal("DRILL DONE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
				score < 0 ? Component.empty() : Component.literal(score + "%").withStyle(ChatFormatting.WHITE));
		player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
		player.sendSystemMessage(result);
		player.sendSystemMessage(button("[Again]", ChatFormatting.YELLOW, drillCommand(drill, duel.tier) + " quick", "Same drill again, without the tutorial")
				.append(Component.literal(" "))
				.append(button("[Duel " + duel.tier.id + "]", duel.tier.color, command(duel.tier, 1, false), "A normal fight against this tier"))
				.append(Component.literal(" "))
				.append(button("[Other drills]", ChatFormatting.GRAY, "/dojo drill", "All drills")));
	}

	/** Health as hearts, rounded up to half hearts like the health bar: 6.3 health is 3.5 hearts. Also the bots' health line. */
	public static MutableComponent hearts(float health) {
		int halves = (int) Math.ceil(health - 1.0E-3);
		String hearts = halves % 2 == 0 ? String.valueOf(halves / 2) : (halves / 2) + ".5";
		return Component.literal(hearts + " ❤").withStyle(ChatFormatting.RED);
	}

	static String command(Tier tier, int firstTo, boolean here) {
		return "/dojo " + tier.lowerId() + (firstTo > 1 ? " " + firstTo : "") + (here ? " here" : "");
	}

	private static MutableComponent score(Duel duel) {
		return Component.literal("You " + duel.yourRounds).withStyle(ChatFormatting.GREEN)
				.append(Component.literal(" – ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(duel.botRounds + " Bot").withStyle(ChatFormatting.RED))
				.append(Component.literal("  (first to " + duel.firstTo + ")").withStyle(ChatFormatting.GRAY));
	}

	private static String summary(FightStats s) {
		return s.hits + "/" + s.swings + " hits, " + s.crits + " crits";
	}

	static MutableComponent tierName(Tier tier) {
		return Component.literal(tier.id).withColor(tier.color).withStyle(ChatFormatting.BOLD);
	}

	static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
		return clickable(Component.literal(label).withStyle(color), command, hover);
	}

	static MutableComponent button(String label, int color, String command, String hover) {
		return clickable(Component.literal(label).withColor(color), command, hover);
	}

	private static MutableComponent clickable(MutableComponent text, String command, String hover) {
		return text.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 5));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
