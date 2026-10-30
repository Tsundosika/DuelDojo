package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Your trend for /dojo stats: your last matches against the same number of matches before them, one short line per
 * habit, green when it got better and red when it got worse.
 */
final class Progress {
	/** At most this many recent matches are compared with as many before them. */
	private static final int WINDOW = 10;
	/** A change smaller than this many percentage points counts as unchanged. */
	private static final int SAME = 3;

	private Progress() {
	}

	/** Empty when there are fewer than four matches. */
	static List<Component> trend(List<PlayerData.Match> matches) {
		List<Component> lines = new ArrayList<>();
		int n = Math.min(WINDOW, matches.size() / 2);
		if (n < 2) return lines;
		List<PlayerData.Match> recent = matches.subList(matches.size() - n, matches.size());
		List<PlayerData.Match> before = matches.subList(matches.size() - 2 * n, matches.size() - n);
		lines.add(Component.literal("Your last " + n + " matches vs the " + n + " before:").withStyle(ChatFormatting.GRAY));
		lines.add(line("Hit rate", rate(recent, Stat.HITS, Stat.SWINGS), rate(before, Stat.HITS, Stat.SWINGS), true));
		lines.add(line("Early swings", rate(recent, Stat.EARLY, Stat.SWINGS), rate(before, Stat.EARLY, Stat.SWINGS), false));
		lines.add(line("Sprint hits", rate(recent, Stat.SPRINT, Stat.HITS), rate(before, Stat.SPRINT, Stat.HITS), true));
		lines.add(line("Crits", rate(recent, Stat.CRITS, Stat.HITS), rate(before, Stat.CRITS, Stat.HITS), true));
		lines.add(line("Jump resets in time", rate(recent, Stat.JUMP_RESETS, Stat.HITS_TAKEN), rate(before, Stat.JUMP_RESETS, Stat.HITS_TAKEN), true));
		return lines;
	}

	private enum Stat { HITS, SWINGS, EARLY, SPRINT, CRITS, JUMP_RESETS, HITS_TAKEN }

	private static int sum(List<PlayerData.Match> matches, Stat stat) {
		int total = 0;
		for (PlayerData.Match m : matches) {
			total += switch (stat) {
				case HITS -> m.hits;
				case SWINGS -> m.swings;
				case EARLY -> m.early;
				case SPRINT -> m.sprintHits;
				case CRITS -> m.crits;
				case JUMP_RESETS -> m.jumpResets;
				case HITS_TAKEN -> m.hitsTaken;
			};
		}
		return total;
	}

	/** part / whole in percent over the matches, or -1 if whole is 0. */
	private static int rate(List<PlayerData.Match> matches, Stat part, Stat whole) {
		int w = sum(matches, whole);
		return w == 0 ? -1 : Math.round(100.0F * sum(matches, part) / w);
	}

	private static Component line(String name, int now, int was, boolean higherIsBetter) {
		MutableComponent line = Component.literal(" " + name + " " + (now < 0 ? "-" : now + "%")).withStyle(ChatFormatting.WHITE);
		if (now < 0 || was < 0) return line;
		line.append(Component.literal(" (was " + was + "%)").withStyle(ChatFormatting.GRAY));
		if (Math.abs(now - was) < SAME) return line;
		boolean better = (now > was) == higherIsBetter;
		return line.append(Component.literal(now > was ? " ↑" : " ↓").withStyle(better ? ChatFormatting.GREEN : ChatFormatting.RED));
	}
}
