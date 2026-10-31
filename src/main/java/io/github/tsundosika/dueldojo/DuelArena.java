package io.github.tsundosika.dueldojo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A walled arena built for one duel near the top of the world, above where the duel was started, and taken down
 * when it ends. Original design, a small sky dojo: a mat floor of bottom slabs (nothing can spawn on it), and
 * 4-high walls of dark wood pillars and pale panels with lanterns on top.
 *
 * <p>It is only built where every block of it is air, and taking it down only removes blocks that are still
 * exactly what it placed, so it never touches anything of yours.
 */
final class DuelArena {
	/** Half the inner floor width: the fighting area is 31 x 31 blocks. */
	private static final int HALF = 15;
	private static final int WALL = HALF + 1;
	private static final int WALL_HEIGHT = 4;
	/** Each fighter starts this far from the centre, so 16 blocks apart. */
	private static final int SPAWN_OFFSET = 8;
	/** Where to try if the space right above the player isn't free. */
	private static final int[][] OFFSETS = {{0, 0}, {48, 0}, {-48, 0}, {0, 48}, {0, -48}};

	final ResourceKey<Level> dimension;
	/** Centre of the floor. */
	final BlockPos center;

	private DuelArena(ResourceKey<Level> dimension, BlockPos center) {
		this.dimension = dimension;
		this.center = center;
	}

	/** Builds an arena high above {@code near}, or returns null if there's no free space for one. */
	static DuelArena build(ServerLevel level, BlockPos near) {
		int floor = level.getMaxY() - 8;
		for (int[] offset : OFFSETS) {
			BlockPos center = new BlockPos(near.getX() + offset[0], floor, near.getZ() + offset[1]);
			if (isFree(level, center)) {
				DuelArena arena = new DuelArena(level.dimension(), center);
				arena.forEachBlock((pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS));
				return arena;
			}
		}
		return null;
	}

	private static boolean isFree(ServerLevel level, BlockPos center) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -WALL; dx <= WALL; dx++) {
			for (int dz = -WALL; dz <= WALL; dz++) {
				for (int dy = 0; dy <= WALL_HEIGHT + 1; dy++) {
					if (!level.getBlockState(pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz)).isAir()) return false;
				}
			}
		}
		return true;
	}

	/** Takes the arena down again (lanterns first, they stand on the pillars). */
	void remove(MinecraftServer server) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) return;
		List<BlockPos> positions = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		forEachBlock((pos, state) -> {
			positions.add(pos);
			states.add(state);
		});
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int i = positions.size() - 1; i >= 0; i--) {
			if (level.getBlockState(positions.get(i)) == states.get(i)) level.setBlock(positions.get(i), air, Block.UPDATE_CLIENTS);
		}
	}

	/** Where you start: on the floor (bottom slabs, so half a block up), facing the bot. */
	Vec3 playerSpawn() {
		return Vec3.atBottomCenterOf(center.offset(0, 0, -SPAWN_OFFSET)).add(0.0, 0.5, 0.0);
	}

	/** The middle of the floor, and a place {@code blocks} from it towards the bot's side. */
	Vec3 middle(int blocks) {
		return Vec3.atBottomCenterOf(center.offset(0, 0, blocks)).add(0.0, 0.5, 0.0);
	}

	/** How far this place is from the nearest wall's inner face. */
	double roomToWall(Vec3 place) {
		double half = HALF + 0.5;
		return half - Math.max(Math.abs(place.x - (center.getX() + 0.5)), Math.abs(place.z - (center.getZ() + 0.5)));
	}

	Vec3 botSpawn() {
		return Vec3.atBottomCenterOf(center.offset(0, 0, SPAWN_OFFSET)).add(0.0, 0.5, 0.0);
	}

	void save(ValueOutput out) {
		out.putString("dimension", dimension.location().toString());
		out.putInt("x", center.getX());
		out.putInt("y", center.getY());
		out.putInt("z", center.getZ());
	}

	static DuelArena load(ValueInput in) {
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
				ResourceLocation.parse(in.getStringOr("dimension", Level.OVERWORLD.location().toString())));
		return new DuelArena(dimension, new BlockPos(in.getIntOr("x", 0), in.getIntOr("y", 0), in.getIntOr("z", 0)));
	}

	private void forEachBlock(BiConsumer<BlockPos, BlockState> action) {
		BlockState border = Blocks.DARK_OAK_SLAB.defaultBlockState();
		BlockState line = Blocks.SPRUCE_SLAB.defaultBlockState();
		BlockState mat = Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState();
		BlockState pillar = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
		BlockState beam = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState panel = Blocks.PALE_OAK_PLANKS.defaultBlockState();

		// Floor: mats with a line every 5 blocks and a dark border.
		for (int dx = -HALF; dx <= HALF; dx++) {
			for (int dz = -HALF; dz <= HALF; dz++) {
				BlockState state = Math.abs(dx) == HALF || Math.abs(dz) == HALF ? border : dx % 5 == 0 || dz % 5 == 0 ? line : mat;
				action.accept(center.offset(dx, 0, dz), state);
			}
		}
		// Walls: a pillar every 4 blocks, panels with a dark beam at the bottom and top in between.
		for (int dx = -WALL; dx <= WALL; dx++) {
			for (int dz = -WALL; dz <= WALL; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != WALL) continue;
				boolean isPillar = (Math.abs(dx) == WALL ? dz : dx) % 4 == 0;
				for (int dy = 0; dy < WALL_HEIGHT; dy++) {
					action.accept(center.offset(dx, dy, dz), isPillar ? pillar : dy == 0 || dy == WALL_HEIGHT - 1 ? beam : panel);
				}
			}
		}
		// Lanterns on the pillars, last, since they need the pillar below them.
		BlockState lantern = Blocks.LANTERN.defaultBlockState();
		for (int dx = -WALL; dx <= WALL; dx++) {
			for (int dz = -WALL; dz <= WALL; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) == WALL && (Math.abs(dx) == WALL ? dz : dx) % 4 == 0) {
					action.accept(center.offset(dx, WALL_HEIGHT, dz), lantern);
				}
			}
		}
	}
}
