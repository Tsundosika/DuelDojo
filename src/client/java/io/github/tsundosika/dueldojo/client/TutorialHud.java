package io.github.tsundosika.dueldojo.client;

import io.github.tsundosika.dueldojo.Drill;
import io.github.tsundosika.dueldojo.DrillGuide;
import io.github.tsundosika.dueldojo.TutorialNet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** What's on screen during "try it": the objective at the left, and a key prompt above the hotbar. */
final class TutorialHud {
	private static final int PANEL = 0xC0262B3A;
	private static final int GOLD = 0xFFD3BC8E;
	private static final int CREAM = 0xFFECE5D8;
	private static final int MUTED = 0xFFA8A296;
	private static final int KEY_ON = 0xFFFFD780;
	private static final int KEY_OFF = 0xE0ECE5D8;

	private static TutorialNet.Hud state = TutorialNet.Hud.HIDDEN;

	private TutorialHud() {
	}

	static void set(TutorialNet.Hud hud) {
		state = hud;
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (state.drill() < 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.hud.isHidden()) return;
		Drill drill = Drill.values()[state.drill()];
		DrillGuide guide = DrillGuide.of(drill);
		Font font = minecraft.font;
		drawObjective(g, font, drill, guide);
		drawPrompt(g, minecraft, font, guide);
	}

	private static void drawObjective(GuiGraphicsExtractor g, Font font, Drill drill, DrillGuide guide) {
		String heading = "Try it: " + drill.title;
		String objective = guide.objective + " (" + state.done() + "/" + guide.goal + ")";
		String skip = "Hold Shift: skip";
		int width = Math.max(font.width(heading), Math.max(font.width(objective), font.width(skip))) + 14;
		int x = 6;
		int y = g.guiHeight() / 3;
		g.fill(x, y, x + width, y + 45, PANEL);
		g.fill(x, y, x + 2, y + 45, GOLD);
		g.text(font, heading, x + 7, y + 5, GOLD, false);
		g.text(font, objective, x + 7, y + 18, CREAM, false);
		g.text(font, skip, x + 7, y + 32, MUTED, false);
		if (state.skip() > 0) g.fill(x + 7, y + 41, x + 7 + (width - 14) * state.skip() / 100, y + 42, GOLD);
	}

	private static void drawPrompt(GuiGraphicsExtractor g, Minecraft minecraft, Font font, DrillGuide guide) {
		DrillGuide.Hint hint = guide.hints.get(Math.min(state.hint(), guide.hints.size() - 1));
		int width = font.width(hint.text());
		for (DrillGuide.Key key : hint.keys()) width += keyWidth(font, keyName(minecraft, key)) + 4;
		int x = (g.guiWidth() - width) / 2;
		int y = g.guiHeight() - 92; // above the action bar, where the coach's notes go
		g.fill(x - 6, y - 3, x + width + 6, y + 13, PANEL);
		for (DrillGuide.Key key : hint.keys()) {
			x += drawKey(g, font, keyName(minecraft, key), x, y - 1, true, KEY_ON, KEY_OFF) + 4;
		}
		g.text(font, hint.text(), x, y + 1, CREAM, false);
	}

	private static int keyWidth(Font font, String label) {
		return Math.max(12, font.width(label) + 6);
	}

	/** A key cap; returns its width. */
	static int drawKey(GuiGraphicsExtractor g, Font font, String label, int x, int y, boolean on, int onColor, int offColor) {
		int width = keyWidth(font, label);
		g.fill(x, y, x + width, y + 12, on ? onColor : offColor);
		g.fill(x, y + 11, x + width, y + 12, on ? 0xFFB09050 : 0xFF111111);
		g.text(font, label, x + (width - font.width(label)) / 2 + 1, y + 2, on ? 0xFF2A2A2A : 0xFFECE5D8, false);
		return width;
	}

	/** The key as you have it bound, shortened to fit on a key cap. */
	static String keyName(Minecraft minecraft, DrillGuide.Key key) {
		KeyMapping mapping = switch (key) {
			case FORWARD -> minecraft.options.keyUp;
			case BACK -> minecraft.options.keyDown;
			case LEFT -> minecraft.options.keyLeft;
			case RIGHT -> minecraft.options.keyRight;
			case JUMP -> minecraft.options.keyJump;
			case ATTACK -> minecraft.options.keyAttack;
		};
		String name = mapping.getTranslatedKeyMessage().getString();
		return key == DrillGuide.Key.ATTACK && name.toLowerCase().contains("left") ? "Click" : name;
	}
}
