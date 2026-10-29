package io.github.tsundosika.dueldojo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/**
 * Crash safety. While a duel runs, what it has to put back (your items, game mode and position, and the arena to
 * take down) is also kept in the world folder (dueldojo/&lt;uuid&gt;.dat), and you carry a marker tag. A duel that
 * ends normally removes both. If the game closes in the middle of one instead, the next time you join:
 * <ul>
 *   <li>you still carry the tag, so the world was saved during the duel: everything is put back from the file;</li>
 *   <li>no tag, so your saved state is from before the duel: nothing of yours is touched.</li>
 * </ul>
 * Either way a leftover arena is taken down.
 */
final class DuelRecovery {
	private static final String TAG = "dueldojo_in_duel";

	private DuelRecovery() {
	}

	static void begin(ServerPlayer player, PlayerSnapshot snapshot, DuelArena arena) {
		player.addTag(TAG);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
		snapshot.save(out.child("player"));
		if (arena != null) arena.save(out.child("arena"));
		Path file = file(player);
		try {
			Files.createDirectories(file.getParent());
			NbtIo.writeCompressed(out.buildResult(), file);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not write {}; a crash during this duel couldn't be undone", file, e);
		}
	}

	static void end(ServerPlayer player) {
		player.removeTag(TAG);
		delete(file(player));
	}

	/** On join: undoes a duel that the game closed in the middle of. */
	static void onJoin(ServerPlayer player) {
		boolean savedDuringDuel = player.removeTag(TAG);
		Path file = file(player);
		if (!Files.exists(file)) return;
		try {
			CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag);
			if (savedDuringDuel) {
				DuelManager.freeze(player, false);
				PlayerSnapshot.load(in.childOrEmpty("player"), player.getInventory().getContainerSize()).restore(player);
				player.sendSystemMessage(Component.literal("Your last duel was cut off when the game closed. Your items and position are back.")
						.withStyle(ChatFormatting.GRAY));
			}
			in.child("arena").ifPresent(arena -> DuelArena.load(arena).remove(player.getServer()));
		} catch (IOException | RuntimeException e) {
			DuelDojo.LOGGER.warn("Could not undo the interrupted duel from {}", file, e);
		}
		delete(file);
	}

	static Path file(ServerPlayer player) {
		return player.getServer().getWorldPath(LevelResource.ROOT).resolve("dueldojo").resolve(player.getUUID() + ".dat");
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not delete {}", file, e);
		}
	}
}
