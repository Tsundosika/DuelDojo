package io.github.tsundosika.dueldojo;

/**
 * Places the ten tiers on one skill scale using real data: how many tested sword players sit in each tier
 * of a large public LT5–HT1 tier list (snapshot 2026-10-29, 50,883 players).
 *
 * <p>Method: a tier's middle player is at some percentile of the population; that percentile is turned into a
 * z-score (normal latent skill), and the z-scores are rescaled so LT5 = 0 and HT1 = 1. Strength should be linear
 * in z (Thurstone: who beats whom depends on the skill difference), so each tier's bot is then placed on the
 * settings scale where its simulated strength (bot-vs-bot Elo) has that same proportion: {@link #mechanics()}.
 *
 * <p>Tiers 2 and 1 hold only 13 players in the data, too few to place one by one: LT2 is put where tier 2
 * begins, HT1 at the expected best player of the whole population, and HT2/LT1 evenly in between.
 */
final class Calibration {
	/** Tested players per tier, LT5 … HT1. */
	static final int[] PLAYERS = {4019, 15338, 15366, 7281, 7741, 1125, 6, 6, 1, 0};

	private Calibration() {
	}

	/**
	 * Measured strength of the bot's mechanics: Elo (Bradley-Terry) per settings position, fitted jointly over two
	 * bot-vs-bot ladder runs with the tiers at different positions (gametest, 2026-10-29, v1.5 bots in the walled
	 * arena; each run every tier against the next one and the one after that, 120 rounds per pairing), then made
	 * monotone (isotonic regression). Strength is not linear in the settings, so {@link #mechanics()} inverts this curve.
	 */
	static final float[] CURVE_POSITION = {0.0F, 0.088F, 0.105F, 0.204F, 0.215F, 0.285F, 0.319F, 0.395F, 0.558F, 0.587F,
			0.861F, 0.923F, 0.943F, 0.967F, 1.0F};
	static final float[] CURVE_ELO = {0.0F, 157.0F, 241.0F, 372.0F, 381.0F, 457.0F, 541.0F, 632.0F, 837.0F, 840.0F,
			1107.0F, 1146.0F, 1175.0F, 1197.0F, 1202.0F};

	/**
	 * Mechanics position 0..1 per tier: where on the settings scale a bot must sit so its simulated strength
	 * (Elo) stands in the same proportion as the tier's real position from {@link #skills()}.
	 */
	static float[] mechanics() {
		float[] target = skills();
		float top = CURVE_ELO[CURVE_ELO.length - 1];
		float[] out = new float[target.length];
		for (int t = 0; t < target.length; t++) {
			float elo = target[t] * top;
			int i = 1;
			while (i < CURVE_ELO.length - 1 && CURVE_ELO[i] < elo) i++;
			float f = (elo - CURVE_ELO[i - 1]) / (CURVE_ELO[i] - CURVE_ELO[i - 1]);
			out[t] = Math.round((CURVE_POSITION[i - 1] + f * (CURVE_POSITION[i] - CURVE_POSITION[i - 1])) * 1000.0F) / 1000.0F;
		}
		return out;
	}

	/** Data position 0..1 per tier in {@link Tier} order (LT5 = 0, HT1 = 1). */
	static float[] skills() {
		int total = 0;
		for (int n : PLAYERS) total += n;
		double[] z = new double[PLAYERS.length];
		int below = 0;
		for (int i = 0; i <= 5; i++) {
			z[i] = probit((below + PLAYERS[i] / 2.0) / total);
			below += PLAYERS[i];
		}
		z[6] = probit(1.0 - (total - below) / (double) total);
		z[9] = probit(1.0 - 0.5 / total);
		z[7] = z[6] + (z[9] - z[6]) / 3.0;
		z[8] = z[6] + (z[9] - z[6]) * 2.0 / 3.0;

		float[] skill = new float[z.length];
		for (int i = 0; i < z.length; i++) {
			skill[i] = Math.round((z[i] - z[0]) / (z[9] - z[0]) * 1000.0) / 1000.0F;
		}
		return skill;
	}

	/** Inverse of the standard normal CDF (Acklam's rational approximation, error below 1.2e-9). */
	static double probit(double p) {
		final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
		final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
		final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
		final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
		if (p < 0.02425) {
			double q = Math.sqrt(-2.0 * Math.log(p));
			return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		if (p > 1.0 - 0.02425) {
			double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
			return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
		}
		double q = p - 0.5;
		double r = q * q;
		return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
	}
}
