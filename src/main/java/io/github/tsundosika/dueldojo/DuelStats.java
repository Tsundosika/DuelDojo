package io.github.tsundosika.dueldojo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Record per player and tier, stored locally in config/dueldojo-stats.json: wins, losses, and your hits,
 * swings and crits summed over those duels. Nothing is ever sent anywhere.
 */
final class DuelStats {
	static final int WINS = 0;
	static final int LOSSES = 1;
	static final int HITS = 2;
	static final int SWINGS = 3;
	static final int CRITS = 4;
	private static final int SIZE = 5;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, Map<String, int[]>>>() {}.getType();
	private static Map<String, Map<String, int[]>> data;

	private DuelStats() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("dueldojo-stats.json");
	}

	private static Map<String, Map<String, int[]>> data() {
		if (data == null) {
			data = new HashMap<>();
			Path file = file();
			if (Files.exists(file)) {
				try {
					Map<String, Map<String, int[]>> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
					if (loaded != null) data = loaded;
				} catch (IOException | RuntimeException e) {
					DuelDojo.LOGGER.warn("Could not read {}, starting fresh", file, e);
				}
			}
		}
		return data;
	}

	/** Returns {wins, losses, hits, swings, crits} for this player and tier (older files only had wins/losses). */
	static int[] get(UUID player, Tier tier) {
		int[] row = data().getOrDefault(player.toString(), Map.of()).get(tier.id);
		return row == null ? new int[SIZE] : Arrays.copyOf(row, SIZE);
	}

	static void record(UUID player, Tier tier, boolean won, FightStats you) {
		Map<String, int[]> rows = data().computeIfAbsent(player.toString(), k -> new HashMap<>());
		int[] row = Arrays.copyOf(rows.getOrDefault(tier.id, new int[SIZE]), SIZE);
		row[won ? WINS : LOSSES]++;
		row[HITS] += you.hits;
		row[SWINGS] += you.swings;
		row[CRITS] += you.crits;
		rows.put(tier.id, row);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data, TYPE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not save {}", file(), e);
		}
	}
}
