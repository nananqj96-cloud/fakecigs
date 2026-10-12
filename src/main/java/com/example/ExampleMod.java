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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// ===================== SETTINGS (change these if you want) =====================
	private static final int TRIGGER_Y = -10;            // only triggers when you break a block at or below this Y
	private static final double CHANCE = 1.0;           // 3% chance per block broken below TRIGGER_Y
	private static final long COOLDOWN_MS = 120_000L;    // 2 minutes between fake bases
	private static final int MIN_GAP = 8;                // fake base is at least this far to the side of you (X and Z)
	private static final int EXTRA_RANGE = 18;           // plus a random 0..18 extra blocks
	private static final int MIN_BUILD_Y = -58;          // never build below this (stay above bedrock)
	private static final int MAX_VOLUME = 3_000_000;     // skip schematics bigger than this many blocks
	private static final int BLOCKS_PER_TICK = 6000;     // placement speed (lower = less lag)
	// ===============================================================================

	private static Job activeJob = null;
	private static long nextAllowed = 0L;

	@Override
	public void onInitialize() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel level)) return;
			if (level.getServer().isDedicatedServer()) return;       // singleplayer / integrated server only
			if (level.dimension() != Level.OVERWORLD) return;
			if (pos.getY() > TRIGGER_Y) return;
			if (activeJob != null || System.currentTimeMillis() < nextAllowed) return;
			if (ThreadLocalRandom.current().nextDouble() >= CHANCE) return;
			tryPlaceFakeBase(level, player.getBlockX(), player.getBlockY(), player.getBlockZ());
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> tickJob());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> activeJob = null);

		LOGGER.info("Fake bases mod loaded. Put .litematic files in the 'schematics' folder.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	// ------------------------------------------------------------------ main logic

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
		LOGGER.info("Placing fake base '{}' ({}x{}x{}) at {} {} {}",
				file.getFileName(), s.w(), s.h(), s.d(), ox, oy, oz);
		return true;
	}

	// Clears and places the schematic in one pass, a few thousand blocks per tick.
	private static void tickJob() {
		Job job = activeJob;
		if (job == null) return;

		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int w = job.s.w();
		int d = job.s.d();
		BlockState[] states = job.s.states();
		int budget = BLOCKS_PER_TICK;

		while (budget-- > 0 && job.next < states.length) {
			int i = job.next++;
			int x = i % w;
			int z = (i / w) % d;
			int y = i / (w * d);
			p.set(job.ox + x, job.oy + y, job.oz + z);
			job.level.setBlock(p, states[i], 18);
		}

		if (job.next >= states.length) {
			activeJob = null;
			nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
		}
	}

	// ------------------------------------------------------------------ data types

	private static final class Job {
		final ServerLevel level;
		final int ox, oy, oz;
		final Schem s;
		int next = 0;

		Job(ServerLevel level, int ox, int oy, int oz, Schem s) {
			this.level = level;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.s = s;
		}
	}

	private record Schem(int w, int h, int d, BlockState[] states) {}

	private record Region(int ox, int oy, int oz, int ax, int ay, int az, long[] data, List<Object> palette) {}

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

			// Negative sizes mean the region extends backwards from its position.
			int ox = num(pos.get("x")) + (sx < 0 ? sx + 1 : 0);
			int oy = num(pos.get("y")) + (sy < 0 ? sy + 1 : 0);
			int oz = num(pos.get("z")) + (sz < 0 ? sz + 1 : 0);

			long[] data = reg.get("BlockStates") instanceof long[] a ? a : null;
			List<Object> palette = asList(reg.get("BlockStatePalette"));
			if (data == null || palette == null || palette.isEmpty()) continue;

			list.add(new Region(ox, oy, oz, ax, ay, az, data, palette));
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
		}
		return new Schem(w, h, d, out);
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
}
