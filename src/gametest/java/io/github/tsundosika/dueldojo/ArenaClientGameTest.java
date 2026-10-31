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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
			singleplayer.getConnection().waitForChunksRender();
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
				check(player.entityTags().contains(TAG) && Files.exists(DuelRecovery.file(player)), "the crash-recovery file and tag exist");
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
				check(!player.entityTags().contains(TAG) && !Files.exists(DuelRecovery.file(player)), "the crash-recovery file and tag are gone");
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
				check(!player.entityTags().contains(TAG) && !Files.exists(DuelRecovery.file(player)), "recovery: file and tag are gone");
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
			FightStats picker = new FightStats();
			picker.swings = 20;
			picker.hits = 20;
			picker.sprintHits = 20;
			picker.early = 6;
			picker.picks = 3;
			check(Coach.tips(picker, new FightStats(), Tier.LT5, true).get(0).line().startsWith("Clean match"), "pick hits don't count as early swings");
			picker.picks = 0;
			check(Coach.tips(picker, new FightStats(), Tier.LT5, true).get(0).line().startsWith("30% early swings"), "early swings that aren't picks do");
			FightStats critter = new FightStats();
			critter.hits = 10;
			critter.crits = 5;
			check(Coach.tips(critter, new FightStats(), Tier.LT5, true).stream().noneMatch(t -> t.drill() == Drill.WTAP), "crits count as good hits, no W-tap tip for them");
			critter.crits = 0;
			check(Coach.tips(critter, new FightStats(), Tier.LT5, true).stream().anyMatch(t -> t.drill() == Drill.WTAP), "plain hits still get the W-tap tip");
			FightStats comboed = new FightStats();
			FightStats comboer = new FightStats();
			comboer.bestCombo = 4;
			Coach.Tip comboTip = Coach.tips(comboed, comboer, Tier.LT5, false).get(0);
			check(comboTip.line().equals("Took 4 hits in a row: hold S to get out") && comboTip.line().length() <= 50 && comboTip.drill() == Drill.COMBO, "the combo tip says to hold S");
			FightStats beaten = new FightStats();
			beaten.firstHits = 2;
			beaten.firstHitsLost = 118;
			Coach.Tip firstTip = Coach.tips(beaten, new FightStats(), Tier.LT5, false).get(0);
			check(firstTip.line().startsWith("First hits 2/120") && firstTip.line().length() <= 50 && firstTip.drill() == Drill.FIRST, "losing first hits gets a tip and a drill");
			beaten.firstHits = 60;
			beaten.firstHitsLost = 60;
			check(Coach.tips(beaten, new FightStats(), Tier.LT5, false).stream().noneMatch(t -> t.drill() == Drill.FIRST), "winning half of them doesn't");
			Map<String, Integer> best = new java.util.HashMap<>();
			check(Path.next(best) == Path.STEPS.get(0), "the course starts at its first step");
			best.put(Drill.TIMING.id(), Path.STEPS.get(0).pass() - 1);
			check(Path.next(best) == Path.STEPS.get(0), "just under the pass mark stays on it");
			best.put(Drill.TIMING.id(), Path.STEPS.get(0).pass());
			check(Path.next(best) == Path.STEPS.get(1), "the pass mark moves on");
			for (Path.Step step : Path.STEPS) best.put(step.drill().id(), 100);
			check(Path.next(best) == null, "all passed: the course is done");
			check(java.util.Arrays.stream(Drill.values()).allMatch(d -> Path.STEPS.stream().anyMatch(s -> s.drill() == d)), "every drill is in the course");

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

				// Getting out of a combo: S in time after a hit, and no next hit for a while.
				Input back = new Input(false, true, false, false, false, false, false);
				FightStats out = new FightStats();
				List<String> outNotes = new ArrayList<>();
				out.coach = note -> outNotes.add(note.getString());
				out.tick(player);
				other.landed(false, true, 1.0F, out);
				check(out.justHit(), "a hit is open until it's settled whether you got away");
				player.setLastClientInput(back);
				for (int i = 0; i < 30; i++) out.tick(player);
				check(out.escapes == 1 && !out.justHit() && outNotes.contains("Got away ✓"), "S after a hit, and no next hit, is a hit you got away from");
				player.setLastClientInput(idle);
				other.landed(false, true, 1.0F, out);
				for (int i = 0; i < 30; i++) out.tick(player);
				check(out.escapes == 1, "not doing anything isn't getting away, even if no next hit comes");
				other.landed(false, true, 1.0F, out);
				for (int i = 0; i < 10; i++) out.tick(player);
				other.landed(false, true, 1.0F, out);
				check(outNotes.contains("Hold S to get out"), "a second hit while you did nothing says to hold S");
				for (int i = 0; i < 30; i++) out.tick(player);
				check(out.escapes == 1, "and neither of those two counts");
				other.landed(false, true, 1.0F, out);
				player.setLastClientInput(back);
				for (int i = 0; i < 10; i++) out.tick(player);
				other.landed(false, true, 1.0F, out);
				player.setLastClientInput(idle);
				for (int i = 0; i < 30; i++) out.tick(player);
				check(out.escapes == 1, "S but hit again anyway: not away (and S held too late for the next one doesn't count)");
				other.landed(false, true, 1.0F, out);
				for (int i = 0; i < 7; i++) out.tick(player);
				player.setLastClientInput(back);
				for (int i = 0; i < 30; i++) out.tick(player);
				player.setLastClientInput(idle);
				check(out.escapes == 1, "S more than 6 ticks after the hit is too late");
				FightStats comboScore = new FightStats();
				comboScore.hitsTaken = 2;
				comboScore.escapes = 1;
				check(Drill.COMBO.score(comboScore, new FightStats()) == -1, "the combo drill needs a few hits to score");
				comboScore.hitsTaken = 4;
				comboScore.escapes = 2;
				check(Drill.COMBO.score(comboScore, new FightStats()) == 50, "the combo drill scores hits you got away from");

				// Pick hits: an early hit that landed at the far end of your reach; not in the timing drill, not up close.
				FightStats picks = new FightStats();
				FightStats strict = new FightStats();
				strict.allowPicks = false;
				FightStats close = new FightStats();
				player.resetAttackStrengthTicker();
				picks.tick(player);
				strict.tick(player);
				close.tick(player);
				picks.landed(false, false, 1.0F, other);
				picks.landedAtEdge();
				strict.landed(false, false, 1.0F, other);
				strict.landedAtEdge();
				close.landed(false, false, 1.0F, other);
				player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
				picks.tick(player);
				strict.tick(player);
				close.tick(player);
				check(picks.early == 1 && picks.picks == 1 && picks.earlyMistakes() == 0, "an early hit at the edge of reach is a pick, not a mistake");
				check(strict.early == 1 && strict.picks == 0 && strict.earlyMistakes() == 1, "in the timing drill it still counts as early");
				check(close.early == 1 && close.picks == 0 && close.earlyMistakes() == 1, "an early hit up close is just early");
				check(picks.moves.getOrDefault(Move.PICK, 0) == 1, "and it's named as a move");

				// First hits: the first hit after a quiet spell, from either side; hits in the exchange after it aren't.
				FightStats a = new FightStats();
				FightStats b = new FightStats();
				a.landed(false, true, 1.0F, b);
				check(a.firstHits == 1 && b.firstHitsLost == 1, "the opening hit is a first hit");
				for (int i = 0; i < 13; i++) {
					a.tick(player);
					b.tick(player);
				}
				a.landed(false, true, 1.0F, b);
				for (int i = 0; i < 13; i++) {
					a.tick(player);
					b.tick(player);
				}
				b.landed(false, true, 1.0F, a);
				check(a.firstHits == 1 && b.firstHits == 0, "hits during the exchange aren't");
				for (int i = 0; i < 31; i++) {
					a.tick(player);
					b.tick(player);
				}
				b.landed(false, true, 1.0F, a);
				check(b.firstHits == 1 && a.firstHitsLost == 1, "after a quiet spell the next hit is a first hit again");
				Drill first = Drill.FIRST;
				a.firstHits = 3;
				a.firstHitsLost = 1;
				check(first.score(a, b) == 75, "the first hit drill scores first hits won");
				a.stunHits = 3;
				a.hits = 6;
				check(Drill.STUN.score(a, b) == 50, "the stun combo drill scores spaced sprint hits");

				// Named moves, from what just happened.
				FightStats mover = new FightStats();
				FightStats foe = new FightStats();
				foe.landed(false, true, 1.0F, mover);
				mover.landed(false, true, 1.0F, foe);
				check(mover.moves.getOrDefault(Move.HIT_SELECT, 0) == 1, "a sprint hit straight after being hit is a hit select");
				FightStats deflector = new FightStats();
				foe.landed(true, false, 1.0F, deflector);
				deflector.landed(false, true, 1.0F, foe);
				check(deflector.moves.getOrDefault(Move.DEFLECTION, 0) == 1, "a sprint hit straight after being critted is a deflection");
				FightStats punisher = new FightStats();
				foe.landed(false, true, 1.0F, punisher);
				punisher.landed(true, false, 1.0F, foe);
				check(punisher.moves.getOrDefault(Move.PUNISH_CRIT, 0) == 1, "a crit straight after being hit, without jumping, is a punish crit");
				FightStats plain = new FightStats();
				plain.landed(false, true, 1.0F, foe);
				check(plain.moves.isEmpty(), "a plain opening sprint hit isn't a named move");

				// What a bot notices over a series, and only once there's enough to go on.
				BotBrain.Habits habits = new BotBrain.Habits();
				habits.hitsLanded = 5;
				habits.jumpedOnHit = 5;
				check(!habits.jumpsOnHits(), "five hits aren't enough to read someone");
				habits.hitsLanded = 10;
				habits.jumpedOnHit = 3;
				check(!habits.jumpsOnHits(), "the odd jump isn't a habit");
				habits.jumpedOnHit = 4;
				check(habits.jumpsOnHits(), "jumping on 4 of 10 hits is");
				habits.hitsTaken = 10;
				habits.critsTaken = 4;
				habits.swings = 8;
				habits.backedOffSwings = 2;
				check(habits.crits() && habits.dodges(), "crits and backing off get noticed too");
				check(DuelManager.readLine(new BotBrain.Habits()) == null, "nothing to say when it didn't adapt");
				habits.adaptedToJumps = true;
				check(DuelManager.readLine(habits).equals("It read you: delayed its hits against your jump resets. Change it up."), "it tells you what it read");

				// Drills keep to the one thing they practise, and the moves come in over the tiers.
				for (Drill drill : Drill.values()) {
					Profile drilled2 = drill.profile(TierConfig.profile(Tier.HT1));
					check(drilled2.deflect == 0.0F && drilled2.holdBack == 0.0F && drilled2.adapt == 0.0F, drill.title + " drill bot doesn't use the crit fight");
				}
				check(TierConfig.profile(Tier.LT5).deflect == 0.0F && TierConfig.profile(Tier.LT5).hitSelect == 0.0F && TierConfig.profile(Tier.LT5).adapt == 0.0F, "the lowest tier doesn't use them");
				check(TierConfig.profile(Tier.HT1).deflect > 0.5F && TierConfig.profile(Tier.HT1).holdBack > 0.5F && TierConfig.profile(Tier.HT1).adapt > 0.5F, "the top tier does");

				ServerLevel level = (ServerLevel) player.level();
				BotEntity target = new BotEntity(DuelDojo.BOT, level);
				target.setPos(player.getX() + 3.0, player.getY(), player.getZ());
				target.xo = target.getX() - 0.3;
				target.zo = target.getZ();
				check(FightStats.atEdgeMovingAway(player, target), "3 blocks away and moving away is at the edge");
				target.xo = target.getX() + 0.3;
				check(!FightStats.atEdgeMovingAway(player, target), "coming towards you isn't");
				target.setPos(player.getX() + 2.0, player.getY(), player.getZ());
				target.xo = target.getX() - 0.3;
				target.zo = target.getZ();
				check(!FightStats.atEdgeMovingAway(player, target), "up close isn't either");
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
			check(trend.size() == 7 && trend.get(1).getString().equals(" Hit rate 60% (was 30%) ↑"), "the trend compares recent matches with earlier ones");
			check(trend.get(2).getString().equals(" First hits -"), "old matches without first hits don't break it");

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
			context.waitFor(client -> client.gui.screen() instanceof DrillTutorialScreen);
			server.runOnServer(s -> check("WINDOW".equals(DuelManager.stage(player(s))), "the window comes first"));
			context.runOnClient(client -> {
				DrillTutorialScreen screen = (DrillTutorialScreen) client.gui.screen();
				screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				check(screen.page() == 1, "enter goes to the next page");
				screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
				check(client.gui.screen() == null, "on the last page it closes the window");
			});
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			for (int waited = 0; waited < 20 * 30 && server.computeOnServer(s -> "TRY".equals(DuelManager.stage(player(s)))); waited += 3) {
				ShowcaseClientGameTest.aimAndSwing(context);
				context.waitTicks(3);
			}
			server.runOnServer(s -> check("RUN".equals(DuelManager.stage(player(s))), "three full-charge swings finish try it"));
			server.runOnServer(s -> DuelManager.stop(player(s)));

			// S pressed as the hit shows up has to get you out, in the slowed-down "try it" of the combo drill, even
			// against the best bot, and with the walls in play: three in a row need more room than there is behind you.
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.COMBO, Tier.HT1, true));
			context.waitFor(client -> client.gui.screen() instanceof DrillTutorialScreen);
			context.runOnClient(client -> client.gui.screen().onClose());
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			server.runOnServer(s -> check(s.tickRateManager().tickrate() == 10.0F, "the combo try-it runs at half speed"));
			int presses = 0;
			int lastHurt = 0;
			for (int waited = 0; waited < 20 * 90 && server.computeOnServer(s -> "TRY".equals(DuelManager.stage(player(s)))); waited++) {
				int hurt = server.computeOnServer(s -> player(s).hurtTime);
				if (hurt > lastHurt) {
					context.getInput().holdKey(options -> options.keyDown);
					context.waitTicks(60);
					context.getInput().releaseKey(options -> options.keyDown);
					waited += 60;
					presses++;
				} else {
					context.waitTick();
				}
				lastHurt = hurt;
			}
			int pressed = presses;
			server.runOnServer(s -> {
				check("RUN".equals(DuelManager.stage(player(s))), "holding S as the hit shows up gets you out (" + pressed + " holds for 3)");
				check(s.tickRateManager().tickrate() == 20.0F, "the speed is back for the real drill");
				DuelManager.stop(player(s));
			});

			// The two newest drills run: the bot comes in on the first hit one, and the stun one keeps its bot in the arena.
			for (Drill newDrill : List.of(Drill.FIRST, Drill.STUN)) {
				server.runOnServer(s -> DuelManager.startDrill(player(s), newDrill, Tier.LT3, false));
				context.waitTicks(20 * 8);
				server.runOnServer(s -> {
					check(DuelManager.inDuel(player(s)), newDrill.title + " drill is running");
					DuelManager.stop(player(s));
					checkOwnItemsBack(player(s));
				});
			}

			// Skipping: holding Shift in "try it", and the window's Skip button.
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.REACH, Tier.LT5, true));
			context.waitFor(client -> client.gui.screen() instanceof DrillTutorialScreen);
			context.runOnClient(client -> client.gui.screen().onClose());
			waitUntil(context, server, s -> "TRY".equals(DuelManager.stage(player(s))), 100, "try it to start");
			context.getInput().holdKey(options -> options.keyShift);
			waitUntil(context, server, s -> "RUN".equals(DuelManager.stage(player(s))), 100, "holding shift to skip try it");
			context.getInput().releaseKey(options -> options.keyShift);
			server.runOnServer(s -> DuelManager.stop(player(s)));
			server.runOnServer(s -> DuelManager.startDrill(player(s), Drill.DODGE, Tier.LT5, true));
			context.waitFor(client -> client.gui.screen() instanceof DrillTutorialScreen);
			context.runOnClient(client -> ((DrillTutorialScreen) client.gui.screen()).skip());
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
