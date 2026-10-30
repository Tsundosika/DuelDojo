package io.github.tsundosika.dueldojo.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tsundosika.dueldojo.BotEntity;
import io.github.tsundosika.dueldojo.Drill;
import io.github.tsundosika.dueldojo.DrillGuide;
import io.github.tsundosika.dueldojo.DuelDojo;
import io.github.tsundosika.dueldojo.TutorialNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A drill's tutorial window: the game pauses behind a panel with a title, a looping scene that shows the move with the
 * keys lighting up, a line or two of text, and pages to flip through. Closing it starts "try it"; Skip goes straight
 * to the drill.
 */
public class DrillTutorialScreen extends Screen {
	private static final int PANEL_WIDTH = 380;
	private static final int PANEL_HEIGHT = 214;
	private static final int STAGE_X = 30;
	private static final int STAGE_Y = 24;
	private static final int STAGE_WIDTH = 320;
	private static final int STAGE_HEIGHT = 112;
	private static final int FLOOR_HEIGHT = 14;
	private static final int BLOCK = 26;
	private static final int FRONT_BLOCK = 30;
	private static final int TEXT_Y = 142;
	private static final int BUTTON_Y = 188;
	private static final int BUTTON_HEIGHT = 18;

	private static final int DIM = 0xA0000000;
	private static final int PANEL = 0xF2262B3A;
	private static final int PANEL_EDGE = 0xFF1A1E2A;
	private static final int GOLD = 0xFFD3BC8E;
	private static final int GOLD_DIM = 0x80D3BC8E;
	private static final int CREAM = 0xFFECE5D8;
	private static final int MUTED = 0xFFA8A296;
	private static final int DARK_TEXT = 0xFF3B4255;
	private static final int KEY_ON = 0xFFFFD780;
	private static final int KEY_OFF = 0xD0262B3A;

	private static final Identifier MAT = Identifier.withDefaultNamespace("textures/block/bamboo_mosaic.png");
	private static final Identifier WALL = Identifier.withDefaultNamespace("textures/block/pale_oak_planks.png");
	private static final Identifier BEAM = Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png");
	private static final Identifier PILLAR = Identifier.withDefaultNamespace("textures/block/stripped_dark_oak_log.png");

	private final Drill drill;
	private final DrillGuide guide;
	private final boolean sendClosed;
	private int page;
	private long pageStart;
	private DrillDemo.Scene scene;
	/** For tests: the moment of the scene to show instead of playing it. */
	private float fixedTime = -1.0F;
	private BotEntity youFigure;
	private BotEntity botFigure;
	private boolean closed;

	/** @param sendClosed tell the server when it closes (false only for tests that open the window on their own) */
	public DrillTutorialScreen(Drill drill, boolean sendClosed) {
		super(Component.literal(drill.title + " tutorial"));
		this.drill = drill;
		this.guide = DrillGuide.of(drill);
		this.sendClosed = sendClosed;
		showPage(0);
	}

	public void showPage(int index) {
		page = index;
		pageStart = Util.getMillis();
		scene = DrillDemo.scene(guide.pages.get(page).demo());
	}

	public void freezeAt(float time) {
		fixedTime = time;
	}

	public int pageCount() {
		return guide.pages.size();
	}

	public int page() {
		return page;
	}

	@Override
	protected void init() {
		if (youFigure == null && minecraft.level != null) {
			youFigure = kitted();
			botFigure = kitted();
		}
	}

	private BotEntity kitted() {
		BotEntity figure = new BotEntity(DuelDojo.BOT, minecraft.level);
		figure.setId(-1); // never added to the world, but the renderer asks for an id
		figure.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
		figure.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		figure.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
		figure.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
		figure.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
		return figure;
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	/** The panel's size is a whole multiple of this, so the pixel font stays sharp; only shrinks on a very small window. */
	private float zoom() {
		float fit = Math.min(width / 400.0F, height / 225.0F);
		return fit >= 1.0F ? (float) Math.floor(fit) : fit;
	}

	private int panelX(float zoom) {
		return Math.round((width - PANEL_WIDTH * zoom) / 2.0F);
	}

	private int panelY(float zoom) {
		return Math.round((height - PANEL_HEIGHT * zoom) / 2.0F);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		float zoom = zoom();
		int ox = panelX(zoom);
		int oy = panelY(zoom);
		int mx = Math.round((mouseX - ox) / zoom);
		int my = Math.round((mouseY - oy) / zoom);
		float time = fixedTime >= 0.0F ? fixedTime : (Util.getMillis() - pageStart) / 1000.0F;
		DrillDemo.Frame frame = scene.at(time);
		DrillGuide.Page current = guide.pages.get(page);

		g.pose().pushMatrix();
		g.pose().translate(ox, oy);
		g.pose().scale(zoom, zoom);
		drawPanel(g);
		g.centeredText(font, Component.literal(current.title()), PANEL_WIDTH / 2, 8, GOLD);
		g.text(font, Component.literal(drill.title + " drill"), 10, 8, MUTED, false);
		if (guide.pages.size() > 1) {
			String pages = (page + 1) + " / " + guide.pages.size();
			g.text(font, pages, PANEL_WIDTH - 10 - font.width(pages), 8, MUTED, false);
		}
		g.fill(10, 19, PANEL_WIDTH - 10, 20, GOLD_DIM);

		drawStageBackground(g, frame);
		g.pose().popMatrix();
		submitFighters(g, frame, zoom, ox, oy);
		g.nextStratum();
		g.pose().pushMatrix();
		g.pose().translate(ox, oy);
		g.pose().scale(zoom, zoom);
		drawStageOverlay(g, frame);

		List<FormattedCharSequence> lines = font.split(Component.literal(current.text()), PANEL_WIDTH - 40);
		int textY = TEXT_Y + (3 - Math.min(3, lines.size())) * 5;
		for (FormattedCharSequence line : lines) {
			g.centeredText(font, line, PANEL_WIDTH / 2, textY, CREAM);
			textY += 11;
		}

		if (page > 0) drawArrow(g, 6, "<", mx, my);
		if (page < guide.pages.size() - 1) drawArrow(g, PANEL_WIDTH - 24, ">", mx, my);
		drawDots(g);
		drawButton(g, 12, 70, "Skip", false, mx, my);
		drawButton(g, PANEL_WIDTH - 12 - 90, 90, page < guide.pages.size() - 1 ? "Next" : "Try it", true, mx, my);
		g.pose().popMatrix();
	}

	private void drawPanel(GuiGraphicsExtractor g) {
		g.fill(2, 0, PANEL_WIDTH - 2, PANEL_HEIGHT, PANEL);
		g.fill(0, 2, 2, PANEL_HEIGHT - 2, PANEL);
		g.fill(PANEL_WIDTH - 2, 2, PANEL_WIDTH, PANEL_HEIGHT - 2, PANEL);
		outline(g, 3, 3, PANEL_WIDTH - 3, PANEL_HEIGHT - 3, GOLD_DIM);
		// Small gold corners, inside the thin line.
		corner(g, 3, 3, 1, 1);
		corner(g, PANEL_WIDTH - 4, 3, -1, 1);
		corner(g, 3, PANEL_HEIGHT - 4, 1, -1);
		corner(g, PANEL_WIDTH - 4, PANEL_HEIGHT - 4, -1, -1);
	}

	private void corner(GuiGraphicsExtractor g, int x, int y, int dx, int dy) {
		g.fill(Math.min(x, x + 6 * dx), y, Math.max(x + dx, x + 6 * dx), y + dy, GOLD);
		g.fill(x, Math.min(y, y + 6 * dy), x + dx, Math.max(y + dy, y + 6 * dy), GOLD);
	}

	private void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y0 + 1, color);
		g.fill(x0, y1 - 1, x1, y1, color);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
	}

	private void drawStageBackground(GuiGraphicsExtractor g, DrillDemo.Frame frame) {
		int x0 = STAGE_X;
		int x1 = STAGE_X + STAGE_WIDTH;
		int y0 = STAGE_Y;
		int y1 = STAGE_Y + STAGE_HEIGHT;
		int floor = y1 - FLOOR_HEIGHT;
		g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, PANEL_EDGE);
		g.enableScissor(x0, y0, x1, y1);
		g.fillGradient(x0, y0, x1, floor, 0xFF84AEFF, 0xFFBFD6FF);
		// The arena's wall, far away, then its floor.
		int wallTop = floor - 34;
		tile(g, WALL, x0, wallTop + 10, x1, floor - 6, 12);
		tile(g, BEAM, x0, wallTop, x1, wallTop + 10, 10);
		tile(g, BEAM, x0, floor - 6, x1, floor, 6);
		for (int x = x0 + 22; x < x1; x += 46) tile(g, PILLAR, x, wallTop, x + 8, floor, 8);
		tile(g, MAT, x0, floor, x1, y1, 14);
		g.fill(x0, floor, x1, floor + 1, 0x60000000);
		g.disableScissor();
	}

	private void tile(GuiGraphicsExtractor g, Identifier texture, int x0, int y0, int x1, int y1, int size) {
		for (int y = y0; y < y1; y += size) {
			for (int x = x0; x < x1; x += size) {
				g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, Math.min(size, x1 - x), Math.min(size, y1 - y), size, size);
			}
		}
	}

	private void submitFighters(GuiGraphicsExtractor g, DrillDemo.Frame frame, float zoom, int ox, int oy) {
		if (youFigure == null) return;
		List<DemoStage.Fighter> fighters = new ArrayList<>();
		if (frame.front) {
			fighters.add(new DemoStage.Fighter(figureState(botFigure, frame.bot, 180.0F), frame.bot.x * BLOCK / FRONT_BLOCK, 0.0F));
		} else {
			// Turned a little towards you, so you see more than a thin slice of them.
			fighters.add(new DemoStage.Fighter(figureState(youFigure, frame.you, 180.0F - 65.0F), frame.you.x, frame.you.y));
			fighters.add(new DemoStage.Fighter(figureState(botFigure, frame.bot, 180.0F + 65.0F), frame.bot.x, frame.bot.y));
		}
		int x0 = ox + Math.round(STAGE_X * zoom);
		int y0 = oy + Math.round(STAGE_Y * zoom);
		int x1 = ox + Math.round((STAGE_X + STAGE_WIDTH) * zoom);
		int y1 = oy + Math.round((STAGE_Y + STAGE_HEIGHT) * zoom);
		float block = (frame.front ? FRONT_BLOCK : BLOCK);
		// From the middle of the picture down to where they stand, in blocks.
		float floorDrop = (STAGE_HEIGHT / 2.0F - FLOOR_HEIGHT + 5.0F) / block;
		ScreenRectangle scissor = g.scissorStack.peek();
		g.guiRenderState.addPicturesInPictureState(new DemoStage.State(fighters, x0, y0, x1, y1, block * zoom, floorDrop, scissor,
				PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor)));
	}

	/** The fighter as the game would draw it, then posed by the scene. */
	private HumanoidRenderState figureState(BotEntity figure, DrillDemo.Figure pose, float bodyRot) {
		HumanoidRenderState state = (HumanoidRenderState) minecraft.getEntityRenderDispatcher().getRenderer(figure).createRenderState(figure, 1.0F);
		state.shadowPieces.clear();
		state.shadowRadius = 0.0F;
		state.outlineColor = 0;
		state.nameTag = null;
		state.scoreText = null;
		state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
		state.bodyRot = bodyRot;
		state.yRot = 0.0F;
		state.xRot = 0.0F;
		state.walkAnimationPos = pose.walkPosition;
		state.walkAnimationSpeed = pose.walkSpeed;
		state.hasRedOverlay = pose.hurt;
		state.swingAnimation = pose.swing;
		state.currentSwing = pose.swing > 0.0F ? new LivingEntity.SwingDescription(InteractionHand.MAIN_HAND, figure.getMainHandItem().getAttackAnimation(), 6) : null;
		return state;
	}

	private int stageX(float x, boolean front) {
		return STAGE_X + STAGE_WIDTH / 2 + Math.round(x * (front ? FRONT_BLOCK : BLOCK));
	}

	private void drawStageOverlay(GuiGraphicsExtractor g, DrillDemo.Frame frame) {
		int floor = STAGE_Y + STAGE_HEIGHT - FLOOR_HEIGHT + 5;
		g.enableScissor(STAGE_X, STAGE_Y, STAGE_X + STAGE_WIDTH, STAGE_Y + STAGE_HEIGHT);
		if (frame.front) {
			drawCrosshair(g, frame);
		} else {
			int youX = stageX(frame.you.x, false);
			int botX = stageX(frame.bot.x, false);
			int headRoom = Math.round(2.0F * BLOCK);
			smallCentered(g, "You", youX, floor - Math.round(frame.you.y * BLOCK) - headRoom - 8, 0xFF8CF08C);
			smallCentered(g, "Bot", botX, floor - Math.round(frame.bot.y * BLOCK) - headRoom - 8, 0xFFFF9A9A);
			if (frame.ruler) drawRuler(g, frame, youX, botX, floor);
			if (frame.charge >= 0.0F) drawCharge(g, frame.charge, youX, floor - Math.round(frame.you.y * BLOCK) - headRoom - 14);
		}
		for (DrillDemo.Pop pop : frame.pops) {
			int alpha = Math.round(255.0F * Math.min(1.0F, (1.0F - pop.age()) * 2.0F));
			// Above the fighter and its label (in the view from your eyes, there's no label).
			int y = floor - Math.round(pop.y() * BLOCK) - Math.round(2.0F * BLOCK) - (frame.front ? 10 : 22) - Math.round(pop.age() * 10.0F);
			int width = font.width(pop.text());
			int x = stageX(pop.x(), frame.front);
			// High in a jump it would run into the caption, so it goes beside the fighter instead.
			if (y < STAGE_Y + 20) {
				y = STAGE_Y + 20;
				x += width / 2 + 16;
			}
			g.fill(x - width / 2 - 2, y - 1, x + width / 2 + 2, y + 9, (alpha / 2) << 24);
			g.text(font, pop.text(), x - width / 2, y, (alpha << 24) | (pop.color() & 0xFFFFFF), false);
		}
		if (frame.stars > 0.0F) drawStars(g, frame, floor);
		if (!frame.caption.isEmpty()) {
			int width = font.width(frame.caption);
			int cx = STAGE_X + STAGE_WIDTH / 2;
			g.fill(cx - width / 2 - 5, STAGE_Y + 4, cx + width / 2 + 5, STAGE_Y + 17, 0xB0000000);
			g.centeredText(font, Component.literal(frame.caption), cx, STAGE_Y + 7, frame.captionColor);
		}
		drawKeys(g, frame);
		if (frame.fade > 0.0F) {
			g.fill(STAGE_X, STAGE_Y, STAGE_X + STAGE_WIDTH, STAGE_Y + STAGE_HEIGHT, Math.round(frame.fade * 255.0F) << 24);
		}
		g.disableScissor();
	}

	private void smallCentered(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		int width = font.width(text);
		g.fill(x - width / 2 - 2, y - 1, x + width / 2 + 2, y + 8, 0x80000000);
		g.text(font, text, x - width / 2, y, color, false);
	}

	private void drawCharge(GuiGraphicsExtractor g, float charge, int x, int y) {
		int width = 30;
		int x0 = x - width / 2;
		g.fill(x0 - 1, y - 1, x0 + width + 1, y + 4, 0xE0000000);
		g.fill(x0, y, x0 + Math.round(width * charge), y + 3, charge >= 1.0F ? 0xFF8CF08C : 0xFFE8E8E8);
	}

	/** Your reach, from you towards the bot; green when the bot is inside it. */
	private void drawRuler(GuiGraphicsExtractor g, DrillDemo.Frame frame, int youX, int botX, int floor) {
		boolean inReach = frame.bot.x - frame.you.x <= 3.3F;
		int color = inReach ? 0xFF8CF08C : 0xFFFFD780;
		int y = floor - Math.round(1.3F * BLOCK);
		int end = youX + Math.round(3.3F * BLOCK);
		for (int x = youX + 8; x < end; x += 6) g.fill(x, y, Math.min(end, x + 3), y + 1, color);
		g.fill(end - 1, y - 3, end, y + 4, color);
		String label = "3 blocks";
		int labelX = (youX + end - font.width(label)) / 2;
		g.fill(labelX - 2, y - 11, labelX + font.width(label) + 2, y - 2, 0x80000000);
		g.text(font, label, labelX, y - 10, color, false);
	}

	private void drawCrosshair(GuiGraphicsExtractor g, DrillDemo.Frame frame) {
		if (frame.crossX > 50.0F) return;
		int x = stageX(frame.crossX, true);
		int y = STAGE_Y + STAGE_HEIGHT - FLOOR_HEIGHT + 5 - Math.round(1.25F * FRONT_BLOCK);
		g.fill(x - 5, y, x + 6, y + 1, 0xFFFFFFFF);
		g.fill(x, y - 5, x + 1, y + 6, 0xFFFFFFFF);
	}

	private void drawStars(GuiGraphicsExtractor g, DrillDemo.Frame frame, int floor) {
		int x = stageX(frame.bot.x, false);
		int y = floor - Math.round(1.2F * BLOCK);
		int reach = Math.round((1.0F - frame.stars) * 22.0F) + 6;
		int alpha = Math.round(frame.stars * 255.0F) << 24;
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI * 2.0 * i / 8.0 + 0.3;
			int sx = x + (int) (Math.cos(angle) * reach);
			int sy = y + (int) (Math.sin(angle) * reach);
			g.fill(sx - 1, sy, sx + 2, sy + 1, alpha | 0xFFD84A);
			g.fill(sx, sy - 1, sx + 1, sy + 2, alpha | 0xFFD84A);
		}
	}

	private void drawKeys(GuiGraphicsExtractor g, DrillDemo.Frame frame) {
		int x = STAGE_X + 5;
		int y = STAGE_Y + STAGE_HEIGHT - 17;
		for (DrillGuide.Key key : scene.keys()) {
			String label = TutorialHud.keyName(minecraft, key);
			x += TutorialHud.drawKey(g, font, label, x, y, Boolean.TRUE.equals(frame.keys.get(key)), KEY_ON, KEY_OFF) + 3;
		}
	}

	private void drawArrow(GuiGraphicsExtractor g, int x, String arrow, int mx, int my) {
		int y = STAGE_Y + STAGE_HEIGHT / 2 - 12;
		boolean hover = mx >= x && mx < x + 18 && my >= y && my < y + 24;
		g.fill(x, y, x + 18, y + 24, hover ? 0x60D3BC8E : 0x30D3BC8E);
		g.centeredText(font, Component.literal(arrow), x + 9, y + 8, hover ? 0xFFFFFFFF : GOLD);
	}

	private void drawDots(GuiGraphicsExtractor g) {
		if (guide.pages.size() < 2) return;
		int total = guide.pages.size() * 10 - 4;
		int x = PANEL_WIDTH / 2 - total / 2;
		for (int i = 0; i < guide.pages.size(); i++) {
			g.fill(x, BUTTON_Y + 6, x + 6, BUTTON_Y + 12, i == page ? GOLD : 0x60D3BC8E);
			x += 10;
		}
	}

	private void drawButton(GuiGraphicsExtractor g, int x, int width, String label, boolean main, int mx, int my) {
		boolean hover = mx >= x && mx < x + width && my >= BUTTON_Y && my < BUTTON_Y + BUTTON_HEIGHT;
		int fill = main ? (hover ? 0xFFFFFFFF : CREAM) : (hover ? 0x60ECE5D8 : 0x30ECE5D8);
		g.fill(x + 2, BUTTON_Y, x + width - 2, BUTTON_Y + BUTTON_HEIGHT, fill);
		g.fill(x, BUTTON_Y + 2, x + 2, BUTTON_Y + BUTTON_HEIGHT - 2, fill);
		g.fill(x + width - 2, BUTTON_Y + 2, x + width, BUTTON_Y + BUTTON_HEIGHT - 2, fill);
		int textX = x + (width - font.width(label)) / 2;
		g.text(font, label, textX, BUTTON_Y + 5, main ? DARK_TEXT : CREAM, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		float zoom = zoom();
		int mx = (int) Math.floor((event.x() - panelX(zoom)) / zoom);
		int my = (int) Math.floor((event.y() - panelY(zoom)) / zoom);
		int arrowY = STAGE_Y + STAGE_HEIGHT / 2 - 12;
		if (my >= arrowY && my < arrowY + 24) {
			if (mx >= 6 && mx < 24 && page > 0) return flip(page - 1);
			if (mx >= PANEL_WIDTH - 24 && mx < PANEL_WIDTH - 6 && page < guide.pages.size() - 1) return flip(page + 1);
		}
		if (my >= BUTTON_Y && my < BUTTON_Y + BUTTON_HEIGHT) {
			if (mx >= 12 && mx < 82) {
				skip();
				return true;
			}
			if (mx >= PANEL_WIDTH - 102 && mx < PANEL_WIDTH - 12) {
				nextOrClose();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case InputConstants.KEY_RIGHT, InputConstants.KEY_RETURN, InputConstants.KEY_SPACE, InputConstants.KEY_NUMPADENTER -> {
				nextOrClose();
				return true;
			}
			case InputConstants.KEY_LEFT -> {
				return page > 0 && flip(page - 1);
			}
			default -> {
				return super.keyPressed(event);
			}
		}
	}

	private void nextOrClose() {
		if (page < guide.pages.size() - 1) {
			flip(page + 1);
		} else {
			close(false);
		}
	}

	private boolean flip(int to) {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		showPage(to);
		return true;
	}

	/** Esc closes the window the same way as the last page's button. */
	@Override
	public void onClose() {
		close(false);
	}

	/** Straight to the drill, without "try it". */
	public void skip() {
		close(true);
	}

	private void close(boolean skip) {
		if (closed) return;
		closed = true;
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		minecraft.gui.setScreen(null);
		if (sendClosed) ClientPlayNetworking.send(new TutorialNet.Closed(skip));
	}
}
