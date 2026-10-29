package io.github.tsundosika.dueldojo;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Test hook for calibration runs: puts the tiers at chosen settings positions by writing the config file (the
 * gametest run folder is recreated for every run, so editing the file by hand beforehand doesn't stick).
 */
public final class TestTiers {
	private TestTiers() {
	}

	/** {@code positions}: one value per tier, LT5 … HT1, separated by commas. */
	public static void apply(String positions) {
		String[] parts = positions.split(",");
		if (parts.length != Tier.values().length) {
			throw new IllegalArgumentException("Need " + Tier.values().length + " positions, got " + parts.length);
		}
		TierConfig.Data data = TierConfig.defaults();
		for (Tier tier : Tier.values()) data.skill.put(tier.id, Float.parseFloat(parts[tier.ordinal()].trim()));
		Path file = FabricLoader.getInstance().getConfigDir().resolve("dueldojo-tiers.json");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(data), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		TierConfig.reload();
		DuelDojo.LOGGER.info("[ladder] tier positions: {}", data.skill);
	}
}
