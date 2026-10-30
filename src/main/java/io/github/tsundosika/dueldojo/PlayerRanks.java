package io.github.tsundosika.dueldojo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Your own tier, set with /dojo rank and saved in config/dueldojo-ranks.json. It is shown as "(sword) HT3 |" before
 * your name through a scoreboard team (one per tier, named dueldojo.ht3 etc.), which puts it in chat and on your name
 * tag; the client also draws it above your own head in third person. Applied again whenever you join a world.
 */
final class PlayerRanks {
	static final String TEAM_PREFIX = "dueldojo.";
	/**
	 * assets/dueldojo/font/icons.json: one character, U+E000, drawn with the game's own diamond sword item texture
	 * (taken from the game at runtime, so it also follows resource packs).
	 */
	private static final FontDescription ICON_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath(DuelDojo.MOD_ID, "icons"));
	static final String SWORD_ICON = "";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, String>>() {}.getType();
	private static Map<String, String> data;

	private PlayerRanks() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("dueldojo-ranks.json");
	}

	private static Map<String, String> data() {
		if (data == null) {
			data = new HashMap<>();
			Path file = file();
			if (Files.exists(file)) {
				try {
					Map<String, String> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
					if (loaded != null) data = loaded;
				} catch (IOException | RuntimeException e) {
					DuelDojo.LOGGER.warn("Could not read {}, starting fresh", file, e);
				}
			}
		}
		return data;
	}

	/** The player's rank, or null if none is set. */
	static Tier get(UUID player) {
		String id = data().get(player.toString());
		return id == null ? null : Tier.byId(id);
	}

	/** Sets (or with null clears) the player's rank, saves it and updates their name; false as in {@link #apply}. */
	static boolean set(ServerPlayer player, Tier tier) {
		if (tier == null) {
			data().remove(player.getUUID().toString());
		} else {
			data().put(player.getUUID().toString(), tier.id);
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data, TYPE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not save {}", file(), e);
		}
		return apply(player);
	}

	/**
	 * The tier tag before a name: a sword icon (a character of the mod's own icon font), the tier in its colour and a
	 * separator, so "HT3 | name". Also used for the bots' names.
	 */
	static MutableComponent prefix(Tier tier) {
		// The icon is a sibling, not the parent: children inherit their parent's font.
		return Component.empty()
				.append(Component.literal(SWORD_ICON).withStyle(style -> style.withFont(ICON_FONT).withColor(ChatFormatting.WHITE)))
				.append(Component.literal(" " + tier.id).withColor(tier.color))
				.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * Puts the player in the team for their rank (or takes them out of ours). Returns false if they're already on a
	 * team that isn't ours: a player can only be on one, so their rank can't show then.
	 */
	static boolean apply(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return true;
		Scoreboard board = server.getScoreboard();
		String name = player.getScoreboardName();
		PlayerTeam current = board.getPlayersTeam(name);
		if (current != null && !current.getName().startsWith(TEAM_PREFIX)) return false;
		if (current != null) board.removePlayerFromTeam(name, current);
		Tier tier = get(player.getUUID());
		if (tier == null) return true;
		PlayerTeam team = board.getPlayerTeam(TEAM_PREFIX + tier.lowerId());
		if (team == null) team = board.addPlayerTeam(TEAM_PREFIX + tier.lowerId());
		team.setPlayerPrefix(prefix(tier));
		board.addPlayerToTeam(name, team);
		return true;
	}
}
