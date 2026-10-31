package io.github.tsundosika.dueldojo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/dueldojo-tiers.json: each tier's position on the settings scale (0 = LT5 … 1 = HT1, from {@link Calibration}) and the two
 * {@link Profile} anchors every tier is interpolated between. Written with the defaults on first use, so
 * anyone can retune the bots without rebuilding. Delete the file to get the defaults back.
 */
final class TierConfig {
	static final int VERSION = 8;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<Tier, Profile> PROFILES = new EnumMap<>(Tier.class);
	private static Data data;

	private TierConfig() {
	}

	static final class Data {
		String about = "Duel Dojo bot tuning. 'skill' places each tier between the anchors (0 = 'skill0', 1 = 'skill1');"
				+ " the defaults are chosen so the simulated strength of the tiers is spaced like a real sword tier list. Every bot setting is interpolated linearly"
				+ " between the two anchors and then clamped (negative chances mean never). /dojo reload applies changes."
				+ " Delete this file to restore the defaults.";
		int version = VERSION;
		Map<String, Float> skill = new LinkedHashMap<>();
		Profile skill0 = Profile.beginner();
		Profile skill1 = Profile.elite();
	}

	static float skill(Tier tier) {
		Float s = data().skill.get(tier.id);
		return s == null ? (float) tier.ordinal() / (Tier.values().length - 1) : s;
	}

	static Profile profile(Tier tier) {
		return PROFILES.computeIfAbsent(tier, t -> data().skill0.lerp(data().skill1, skill(t)));
	}

	/** Re-reads the file (used by /dojo reload). */
	static void reload() {
		data = null;
		PROFILES.clear();
		data();
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("dueldojo-tiers.json");
	}

	static Data defaults() {
		Data d = new Data();
		float[] skills = Calibration.mechanics();
		for (Tier tier : Tier.values()) d.skill.put(tier.id, skills[tier.ordinal()]);
		return d;
	}

	private static Data data() {
		if (data != null) return data;
		Path file = file();
		if (Files.exists(file)) {
			try {
				Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (loaded != null && loaded.version == VERSION && loaded.skill0 != null && loaded.skill1 != null && loaded.skill != null) {
					data = loaded;
					return data;
				}
				// Older or broken file: keep it next to the new defaults instead of silently losing edits.
				Files.copy(file, file.resolveSibling("dueldojo-tiers.json.old"), StandardCopyOption.REPLACE_EXISTING);
				DuelDojo.LOGGER.warn("{} is outdated, saved it as dueldojo-tiers.json.old and wrote new defaults", file);
			} catch (IOException | RuntimeException e) {
				DuelDojo.LOGGER.warn("Could not read {}, using defaults", file, e);
				data = defaults();
				return data;
			}
		}
		data = defaults();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (IOException e) {
			DuelDojo.LOGGER.warn("Could not write {}", file, e);
		}
		return data;
	}
}
