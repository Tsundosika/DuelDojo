package io.github.tsundosika.dueldojo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Per-player data in config/dueldojo-players.json: your recent matches (for the progress trend in /dojo stats),
 * your best drill scores, your level from the last placement, and whether the live coach is on. Local only.
 */
final class PlayerData {
	/** Matches kept per player; the trend compares the last few with the ones before. */
	private static final int KEEP_MATCHES = 200;

	static final class Entry {
		List<Match> matches = new ArrayList<>();
		Map<String, Integer> drillBest = new HashMap<>();
		/** Tier id from the last placement, or "below" / null. */
		String level;
		boolean coachOff;
	}

	/** One finished duel (all its rounds together). */
	static final class Match {
		long time;
		String tier;
		boolean won;
		int swings;
		int hits;
		int early;
		int sprintHits;
		int crits;
		int punished;
		int hitsTaken;
		int jumpResets;
		/** The bot's longest combo on you. */
		int comboTaken;
		int firstHits;
		/** First hits from either side. */
		int exchanges;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<Map<String, Entry>>() {}.getType();
	private static Map<String, Entry> data;

	private PlayerData() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("dueldojo-players.json");
	}

	static Entry get(UUID player) {
		if (data == null) {
			data = new HashMap<>();
			Path file = file();
			if (Files.exists(file)) {
				try {
					Map<String, Entry> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
					if (loaded != null) data = loaded;
				} catch (IOException | RuntimeException e) {
					DuelDojo.LOGGER.warn("Could not read {}, starting fresh", file, e);
				}
			}
		}
		return data.computeIfAbsent(player.toString(), k -> new Entry());
	}

	static void save() {
		if (data == null) return;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data, TYPE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not save {}", file(), e);
		}
	}

	static void recordMatch(UUID player, Tier tier, boolean won, FightStats you, FightStats bot) {
		Match m = new Match();
		m.time = System.currentTimeMillis();
		m.tier = tier.id;
		m.won = won;
		m.swings = you.swings;
		m.hits = you.hits;
		m.early = you.earlyMistakes();
		m.sprintHits = you.sprintHits;
		m.crits = you.crits;
		m.punished = you.punished;
		m.hitsTaken = you.hitsTaken;
		m.jumpResets = you.jumpResets;
		m.comboTaken = bot.bestCombo;
		m.firstHits = you.firstHits;
		m.exchanges = you.firstHits + you.firstHitsLost;
		List<Match> matches = get(player).matches;
		matches.add(m);
		if (matches.size() > KEEP_MATCHES) matches.subList(0, matches.size() - KEEP_MATCHES).clear();
		save();
	}

	/** Saves a drill score; returns the best before this one (-1 if none). */
	static int recordDrill(UUID player, Drill drill, int score) {
		Map<String, Integer> best = get(player).drillBest;
		int before = best.getOrDefault(drill.id(), -1);
		if (score > before) {
			best.put(drill.id(), score);
			save();
		}
		return before;
	}
}
