package com.example;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// ===================== SETTINGS (change these if you want) =====================
	private static final int TRIGGER_Y = -10;            // only triggers when you break a block at or below this Y
	private static final double CHANCE = 0.20;           // 20% chance per block broken below TRIGGER_Y
	private static final long COOLDOWN_MS = 120_000L;    // 2 minutes between fake bases
	private static final int MIN_GAP = 8;                // fake base is at least this far to the side of you (X and Z)
	private static final int EXTRA_RANGE = 18;           // plus a random 0..18 extra blocks
	private static final int MIN_BUILD_Y = -58;          // never build below this (stay above bedrock)
	private static final int MAX_VOLUME = 3_000_000;     // skip schematics bigger than this many blocks
	private static final int BLOCKS_PER_TICK = 6000;     // placement speed (lower = less lag)
	private static final int TILES_PER_TICK = 40;        // chests / entities handled per tick after the blocks are placed
	private static final int DEPTH = 1;                  // 3x3 mining depth: 1 = flat 3x3 layer (like the real plugin), 3 = 3x3x3
	// ===============================================================================

	private static Job activeJob = null;
	private static long nextAllowed = 0L;

	// 3x3 mining state (only touched on the server thread)
	private static final Set<UUID> ENABLED_3X3 = new HashSet<>();
	private static final Map<UUID, Direction> PENDING_FACE = new HashMap<>();
	private static boolean breakingExtra = false;

	@Override
	public void onInitialize() {
		// ---- /3x3 command (toggle) ----
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("3x3").executes(ctx -> {
					CommandSourceStack src = ctx.getSource();
					ServerPlayer p = src.getPlayerOrException();
					if (src.getServer().isDedicatedServer()) {
						src.sendSuccess(() -> Component.literal("3x3 mining only works in singleplayer."), false);
						return 0;
					}
					UUID id = p.getUUID();
					boolean nowOn = !ENABLED_3X3.remove(id);
					if (nowOn) ENABLED_3X3.add(id);
					String msg = nowOn
							? "3x3 mining: ON (hold a pickaxe, sneak to mine a single block)"
							: "3x3 mining: OFF";
					src.sendSuccess(() -> Component.literal(msg), false);
					return 1;
				})));

		// ---- remember which face of the block the player is mining ----
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			rememberFace(world, player, pos);
			return true;
		});

		// ---- after a block is broken: 3x3 extras + fake base trigger ----
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (breakingExtra) return;                                // ignore blocks broken by 3x3 itself
			if (!(world instanceof ServerLevel level)) return;
			if (level.getServer().isDedicatedServer()) return;       // singleplayer / integrated server only

			if (player instanceof ServerPlayer sp) {
				mineExtra(level, sp, pos);
			}

			if (level.dimension() != Level.OVERWORLD) return;
			if (pos.getY() > TRIGGER_Y) return;
			if (activeJob != null || System.currentTimeMillis() < nextAllowed) return;
			if (ThreadLocalRandom.current().nextDouble() >= CHANCE) return;
			tryPlaceFakeBase(level, player.getBlockX(), player.getBlockY(), player.getBlockZ());
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> tickJob());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			activeJob = null;
			PENDING_FACE.clear();
			breakingExtra = false;
		});

		LOGGER.info("Fake bases mod loaded. Put .litematic files in the 'schematics' folder. Use /3x3 for 3x3 mining.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// ------------------------------------------------------------------ 3x3 mining

	private static void rememberFace(Level world, Player player, BlockPos pos) {
		if (breakingExtra) return;
		if (!(world instanceof ServerLevel level)) return;
		if (level.getServer().isDedicatedServer()) return;
		UUID id = player.getUUID();
		if (!ENABLED_3X3.contains(id)) return;

		HitResult hit = player.pick(6.0, 1.0F, false);
		if (hit instanceof BlockHitResult bhr
				&& bhr.getType() == HitResult.Type.BLOCK
				&& bhr.getBlockPos().equals(pos)) {
			PENDING_FACE.put(id, bhr.getDirection());
		} else {
			PENDING_FACE.remove(id);
		}
	}

	private static void mineExtra(ServerLevel level, ServerPlayer player, BlockPos center) {
		UUID id = player.getUUID();
		Direction face = PENDING_FACE.remove(id);
		if (face == null || !ENABLED_3X3.contains(id)) return;
		if (player.isShiftKeyDown()) return;                         // sneaking = single block

		Direction.Axis axis = face.getAxis();
		Direction dig = face.getOpposite();                          // the direction "into" the mined block

		breakingExtra = true;
		try {
			for (int k = 0; k < DEPTH; k++) {
				BlockPos base = center.relative(dig, k);
				for (int i = -1; i <= 1; i++) {
					for (int j = -1; j <= 1; j++) {
						if (k == 0 && i == 0 && j == 0) continue;     // the block the game already broke
						if (!player.getMainHandItem().is(ItemTags.PICKAXES)) return;   // pickaxe broke or swapped

						// Flat 3x3 plane, perpendicular to the face that was mined.
						BlockPos np = switch (axis) {
							case Y -> base.offset(i, 0, j);   // mining up/down: horizontal layer
							case X -> base.offset(0, i, j);   // mining east/west wall
							default -> base.offset(i, j, 0);  // mining north/south wall
						};

						BlockState ns = level.getBlockState(np);
						if (ns.isAir()) continue;
						if (!ns.is(BlockTags.MINEABLE_WITH_PICKAXE)) continue;
						if (ns.getDestroySpeed(level, np) < 0) continue;      // bedrock etc.
						if (ns.hasBlockEntity()) continue;                    // chests, spawners... stay safe

						player.gameMode.destroyBlock(np);                     // real break: drops, enchants, tool damage
					}
				}
			}
		} finally {
			breakingExtra = false;
		}
	}

	// ------------------------------------------------------------------ fake base logic

	private static void tryPlaceFakeBase(ServerLevel level, int px, int py, int pz) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
						.forEach(files::add);
			} catch (IOException e) {
				LOGGER.warn("Could not list schematics folder", e);
				return;
			}
		}
		if (files.isEmpty()) {
			nextAllowed = System.currentTimeMillis() + 30_000L;
			LOGGER.warn("No .litematic files found in {}", dir);
			return;
		}

		Collections.shuffle(files);
		HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);

		int tries = Math.min(3, files.size());
		for (int i = 0; i < tries; i++) {
			Path file = files.get(i);
			Schem schem;
			try {
				schem = readLitematic(file, lookup);
			} catch (Exception e) {
				LOGGER.warn("Could not read {}: {}", file.getFileName(), e.toString());
				continue;
			}
			if (schem == null) continue;
			if (startJob(level, schem, px, py, pz, file)) return;
		}
		nextAllowed = System.currentTimeMillis() + 10_000L;
	}

	private static boolean startJob(ServerLevel level, Schem s, int px, int py, int pz, Path file) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		// Vertical: fully below the player, never into the bedrock layer.
		int oy = Math.max(MIN_BUILD_Y, py - 6 - s.h());
		if (oy + s.h() > py - 3) return false;

		// Horizontal: the whole box sits off to one side of the player on both axes,
		// so it is never directly under them.
		int ox = rnd.nextBoolean()
				? px + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: px - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.w();
		int oz = rnd.nextBoolean()
				? pz + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: pz - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.d();

		// Make sure the area is in loaded chunks.
		int[][] corners = {
				{ox, oz}, {ox + s.w() - 1, oz},
				{ox, oz + s.d() - 1}, {ox + s.w() - 1, oz + s.d() - 1}
		};
		for (int[] c : corners) {
			if (!level.hasChunkAt(new BlockPos(c[0], oy, c[1]))) return false;
		}

		activeJob = new Job(level, ox, oy, oz, s);
		nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
		LOGGER.info("Placing fake base '{}' ({}x{}x{}, {} containers, {} entities) at {} {} {}",
				file.getFileName(), s.w(), s.h(), s.d(), s.tiles().size(), s.ents().size(), ox, oy, oz);
		return true;
	}

	// Step 1: clears and places the blocks a few thousand per tick.
	// Step 2: fills chests/barrels/etc. with their saved contents.
	// Step 3: spawns armor stands and other entities.
	private static void tickJob() {
		Job job = activeJob;
		if (job == null) return;

		BlockState[] states = job.s.states();

		if (job.next < states.length) {
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			int w = job.s.w();
			int d = job.s.d();
			int budget = BLOCKS_PER_TICK;
			while (budget-- > 0 && job.next < states.length) {
				int i = job.next++;
				int x = i % w;
				int z = (i / w) % d;
				int y = i / (w * d);
				p.set(job.ox + x, job.oy + y, job.oz + z);
				job.level.setBlock(p, states[i], 18);
			}
			return;
		}

		List<Tile> tiles = job.s.tiles();
		List<Ent> ents = job.s.ents();
		if (job.tileNext < tiles.size() || job.entNext < ents.size()) {
			if (job.source == null) {
				job.source = job.level.getServer().createCommandSourceStack()
						.withLevel(job.level)
						.withSuppressedOutput();
			}
			int budget = TILES_PER_TICK;

			while (budget-- > 0 && job.tileNext < tiles.size()) {
				Tile t = tiles.get(job.tileNext++);
				String cmd = "data merge block " + (job.ox + t.x()) + " " + (job.oy + t.y()) + " "
						+ (job.oz + t.z()) + " " + t.snbt();
				runCommand(job, cmd, "container data");
			}

			while (budget > 0 && job.entNext < ents.size()) {
				budget--;
				Ent e = ents.get(job.entNext++);
				double ax = job.ox + e.x();
				double ay = job.oy + e.y();
				double az = job.oz + e.z();
				String snbt = e.snbt();
				if (e.hanging()) {
					// item frames / paintings need the block position they hang on
					String bp = "block_pos:[I;" + (int) Math.floor(ax) + "," + (int) Math.floor(ay) + ","
							+ (int) Math.floor(az) + "]";
					snbt = snbt.length() > 2
							? snbt.substring(0, snbt.length() - 1) + "," + bp + "}"
							: "{" + bp + "}";
				}
				String cmd = "summon " + e.type() + " " + fmt(ax) + " " + fmt(ay) + " " + fmt(az) + " " + snbt;
				runCommand(job, cmd, "entity " + e.type());
			}

			if (job.tileNext < tiles.size() || job.entNext < ents.size()) return;
		}

		activeJob = null;
		nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
	}

	private static void runCommand(Job job, String cmd, String what) {
		try {
			job.level.getServer().getCommands().performPrefixedCommand(job.source, cmd);
		} catch (Exception ex) {
			if (job.errors++ < 3) {
				LOGGER.warn("Could not apply {}: {}", what, ex.toString());
			}
		}
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}

	// ------------------------------------------------------------------ data types

	private static final class Job {
		final ServerLevel level;
		final int ox, oy, oz;
		final Schem s;
		int next = 0;
		int tileNext = 0;
		int entNext = 0;
		int errors = 0;
		CommandSourceStack source = null;

		Job(ServerLevel level, int ox, int oy, int oz, Schem s) {
			this.level = level;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.s = s;
		}
	}

	private record Tile(int x, int y, int z, String snbt) {}

	// x/y/z are relative to the lowest corner of the whole schematic
	private record Ent(String type, double x, double y, double z, String snbt, boolean hanging) {}

	private record Schem(int w, int h, int d, BlockState[] states, List<Tile> tiles, List<Ent> ents) {}

	private record Region(int ox, int oy, int oz, int rawX, int rawY, int rawZ, int ax, int ay, int az,
						  long[] data, List<Object> palette, List<Object> tiles, List<Object> entities) {}

	// ------------------------------------------------------------------ .litematic reader

	private static Schem readLitematic(Path file, HolderLookup<Block> lookup) throws IOException {
		Map<String, Object> root;
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			int type = in.readByte();
			if (type != 10) throw new IOException("not an NBT compound");
			in.readUTF();
			root = asMap(readTag(in, 10));
		}
		if (root == null) throw new IOException("empty file");

		Map<String, Object> regions = asMap(root.get("Regions"));
		if (regions == null || regions.isEmpty()) throw new IOException("no regions");

		List<Region> list = new ArrayList<>();
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		for (Object r : regions.values()) {
			Map<String, Object> reg = asMap(r);
			if (reg == null) continue;
			Map<String, Object> pos = asMap(reg.get("Position"));
			Map<String, Object> size = asMap(reg.get("Size"));
			if (pos == null || size == null) continue;

			int sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			if (ax == 0 || ay == 0 || az == 0) continue;

			int rawX = num(pos.get("x")), rawY = num(pos.get("y")), rawZ = num(pos.get("z"));

			// Negative sizes mean the region extends backwards from its position.
			int ox = rawX + (sx < 0 ? sx + 1 : 0);
			int oy = rawY + (sy < 0 ? sy + 1 : 0);
			int oz = rawZ + (sz < 0 ? sz + 1 : 0);

			long[] data = reg.get("BlockStates") instanceof long[] a ? a : null;
			List<Object> palette = asList(reg.get("BlockStatePalette"));
			if (data == null || palette == null || palette.isEmpty()) continue;

			list.add(new Region(ox, oy, oz, rawX, rawY, rawZ, ax, ay, az, data, palette,
					asList(reg.get("TileEntities")), asList(reg.get("Entities"))));
			minX = Math.min(minX, ox);
			minY = Math.min(minY, oy);
			minZ = Math.min(minZ, oz);
			maxX = Math.max(maxX, ox + ax);
			maxY = Math.max(maxY, oy + ay);
			maxZ = Math.max(maxZ, oz + az);
		}
		if (list.isEmpty()) throw new IOException("no usable regions");

		long volume = (long) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
		if (volume > MAX_VOLUME) {
			LOGGER.warn("{} is too big ({} blocks), skipping", file.getFileName(), volume);
			return null;
		}

		int w = maxX - minX, h = maxY - minY, d = maxZ - minZ;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState[] out = new BlockState[w * h * d];
		Arrays.fill(out, air);
		List<Tile> tiles = new ArrayList<>();
		List<Ent> ents = new ArrayList<>();

		for (Region r : list) {
			BlockState[] pal = new BlockState[r.palette().size()];
			for (int i = 0; i < pal.length; i++) {
				pal[i] = parseState(r.palette().get(i), lookup);   // null = structure void (left alone)
			}
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
			long mask = (1L << bits) - 1L;

			for (int y = 0; y < r.ay(); y++) {
				for (int z = 0; z < r.az(); z++) {
					for (int x = 0; x < r.ax(); x++) {
						int idx = (y * r.az() + z) * r.ax() + x;
						int pi = readPacked(r.data(), bits, mask, idx);
						if (pi < 0 || pi >= pal.length) continue;
						BlockState st = pal[pi];
						if (st == null || st.isAir()) continue;
						int gx = r.ox() - minX + x;
						int gy = r.oy() - minY + y;
						int gz = r.oz() - minZ + z;
						out[(gy * d + gz) * w + gx] = st;
					}
				}
			}

			// Container contents (chests, barrels, shulker boxes...) and other block data.
			if (r.tiles() != null) {
				for (Object t : r.tiles()) {
					Map<String, Object> te = asMap(t);
					if (te == null) continue;
					int tx = num(te.get("x")), ty = num(te.get("y")), tz = num(te.get("z"));
					if (tx < 0 || ty < 0 || tz < 0 || tx >= r.ax() || ty >= r.ay() || tz >= r.az()) continue;

					Map<String, Object> copy = new HashMap<>(te);
					copy.remove("id");
					copy.remove("x");
					copy.remove("y");
					copy.remove("z");
					if (copy.isEmpty()) continue;
					upgradeItems(copy);

					StringBuilder sb = new StringBuilder();
					writeSnbt(copy, sb);
					tiles.add(new Tile(r.ox() - minX + tx, r.oy() - minY + ty, r.oz() - minZ + tz, sb.toString()));
				}
			}

			// Entities (armor stands, item frames, paintings...). Positions are relative to the region position.
			if (r.entities() != null) {
				for (Object eo : r.entities()) {
					Map<String, Object> en = asMap(eo);
					if (en == null) continue;
					if (!(en.get("id") instanceof String etype)) continue;
					List<Object> pp = asList(en.get("Pos"));
					if (pp == null || pp.size() < 3) continue;

					double ex = (r.rawX() - minX) + dbl(pp.get(0));
					double ey = (r.rawY() - minY) + dbl(pp.get(1));
					double ez = (r.rawZ() - minZ) + dbl(pp.get(2));

					Map<String, Object> copy = new HashMap<>(en);
					copy.remove("id");
					copy.remove("Pos");
					copy.remove("UUID");
					boolean hanging = copy.containsKey("block_pos") || copy.containsKey("TileX");
					copy.remove("block_pos");
					copy.remove("TileX");
					copy.remove("TileY");
					copy.remove("TileZ");
					upgradeItems(copy);

					StringBuilder sb = new StringBuilder();
					writeSnbt(copy, sb);
					ents.add(new Ent(etype, ex, ey, ez, sb.toString(), hanging));
				}
			}
		}
		return new Schem(w, h, d, out, tiles, ents);
	}

	// Old schematics store item counts as "Count" (byte); current Minecraft wants "count" (int).
	private static void upgradeItems(Map<String, Object> te) {
		List<Object> items = asList(te.get("Items"));
		if (items == null) return;
		for (Object it : items) {
			Map<String, Object> m = asMap(it);
			if (m == null) continue;
			if (m.containsKey("Count") && !m.containsKey("count")) {
				m.put("count", num(m.remove("Count")));
			}
		}
	}

	private static BlockState parseState(Object entry, HolderLookup<Block> lookup) {
		BlockState air = Blocks.AIR.defaultBlockState();
		Map<String, Object> m = asMap(entry);
		if (m == null) return air;
		Object nm = m.get("Name");
		if (!(nm instanceof String)) return air;
		String name = (String) nm;
		if (name.equals("minecraft:structure_void")) return null;

		Map<String, Object> props = asMap(m.get("Properties"));
		if (props != null && !props.isEmpty()) {
			StringBuilder sb = new StringBuilder(name).append('[');
			boolean first = true;
			for (Map.Entry<String, Object> e : new TreeMap<>(props).entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append(e.getKey()).append('=').append(e.getValue());
			}
			sb.append(']');
			try {
				return BlockStateParser.parseForBlock(lookup, sb.toString(), false).blockState();
			} catch (Exception ignored) {
				// fall through and try the block's default state
			}
		}
		try {
			return BlockStateParser.parseForBlock(lookup, name, false).blockState();
		} catch (Exception e) {
			return air;
		}
	}

	// Litematica packs palette indices tightly across longs (no padding).
	private static int readPacked(long[] arr, int bits, long mask, int index) {
		long start = (long) index * bits;
		int a = (int) (start >> 6);
		int b = (int) ((start + bits - 1) >> 6);
		int off = (int) (start & 63);
		if (a >= arr.length) return 0;
		if (a == b) return (int) ((arr[a] >>> off) & mask);
		if (b >= arr.length) return 0;
		int firstBits = 64 - off;
		return (int) (((arr[a] >>> off) | (arr[b] << firstBits)) & mask);
	}

	// ------------------------------------------------------------------ NBT -> SNBT text (for the /data and /summon commands)

	private static void writeSnbt(Object o, StringBuilder sb) {
		if (o instanceof Byte bv) {
			sb.append(bv.intValue()).append('b');
		} else if (o instanceof Short sv) {
			sb.append(sv.intValue()).append('s');
		} else if (o instanceof Integer iv) {
			sb.append(iv.intValue());
		} else if (o instanceof Long lv) {
			sb.append(lv.longValue()).append('L');
		} else if (o instanceof Float fv) {
			sb.append(fv.floatValue()).append('f');
		} else if (o instanceof Double dv) {
			sb.append(dv.doubleValue()).append('d');
		} else if (o instanceof String str) {
			quote(str, sb);
		} else if (o instanceof byte[] ba) {
			sb.append("[B;");
			for (int i = 0; i < ba.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(ba[i]).append('b');
			}
			sb.append(']');
		} else if (o instanceof int[] ia) {
			sb.append("[I;");
			for (int i = 0; i < ia.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(ia[i]);
			}
			sb.append(']');
		} else if (o instanceof long[] la) {
			sb.append("[L;");
			for (int i = 0; i < la.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(la[i]).append('L');
			}
			sb.append(']');
		} else if (o instanceof List<?> list) {
			sb.append('[');
			boolean first = true;
			for (Object e : list) {
				if (!first) sb.append(',');
				first = false;
				writeSnbt(e, sb);
			}
			sb.append(']');
		} else if (o instanceof Map<?, ?> map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				String key = String.valueOf(e.getKey());
				if (key.matches("[A-Za-z0-9._+-]+")) {
					sb.append(key);
				} else {
					quote(key, sb);
				}
				sb.append(':');
				writeSnbt(e.getValue(), sb);
			}
			sb.append('}');
		} else {
			sb.append("\"\"");
		}
	}

	private static void quote(String s, StringBuilder sb) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '"') sb.append('\\');
			sb.append(c);
		}
		sb.append('"');
	}

	// ------------------------------------------------------------------ tiny NBT parser (no Minecraft API needed)

	private static Object readTag(DataInputStream in, int type) throws IOException {
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: {
				byte[] a = new byte[in.readInt()];
				in.readFully(a);
				return a;
			}
			case 8: return in.readUTF();
			case 9: {
				int t = in.readByte();
				int n = in.readInt();
				List<Object> l = new ArrayList<>(Math.max(0, n));
				for (int i = 0; i < n; i++) l.add(readTag(in, t));
				return l;
			}
			case 10: {
				Map<String, Object> m = new HashMap<>();
				while (true) {
					int t = in.readByte();
					if (t == 0) break;
					String key = in.readUTF();
					m.put(key, readTag(in, t));
				}
				return m;
			}
			case 11: {
				int[] a = new int[in.readInt()];
				for (int i = 0; i < a.length; i++) a[i] = in.readInt();
				return a;
			}
			case 12: {
				long[] a = new long[in.readInt()];
				for (int i = 0; i < a.length; i++) a[i] = in.readLong();
				return a;
			}
			default:
				throw new IOException("bad NBT tag type " + type);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asList(Object o) {
		return o instanceof List ? (List<Object>) o : null;
	}

	private static int num(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	private static double dbl(Object o) {
		return o instanceof Number n ? n.doubleValue() : 0.0;
	}
}
