package com.example;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// ===============================================================================
	// CONFIGURATION - change these numbers if you want
	// ===============================================================================
	private static final int TRIGGER_Y = -40;            // only used by the fake anti-cheat's deep mining bonus (unchanged)
	private static final int BASE_TRIGGER_Y = -10;       // random fake bases can spawn when mining BELOW this Y
	private static final double CHANCE = 1.0;            // FIXED: 100% = a base spawns on the first block mined below Y -10 (then the cooldown applies)
	private static final long COOLDOWN_MS = 60_000L;     // wait at least 1 minute between fake bases
	private static final int MIN_GAP = 12;               // at least this many blocks away from the player
	private static final int EXTRA_RANGE = 20;           // plus 0 to this many extra blocks (total 12..32)
	private static final int MIN_BUILD_Y = -58;          // never build below this (stay above bedrock)
	private static final int MAX_VOLUME = 3_000_000;     // skip schematics bigger than this many blocks
	private static final int BLOCKS_PER_TICK = 6000;     // placement speed (lower = less lag)
	private static final int TILES_PER_TICK = 40;        // chests / entities handled per tick after the blocks are placed
	private static final int DOWN_DEPTH = 3;             // 3x3 mining up/down: how many blocks deep (column + left + right columns)
	private static final int WALL_DEPTH = 1;             // 3x3 mining a wall: 1 = flat 3x3 plane
	private static final boolean DEBUG_LOG = true;       // prints why a fake base did / did not spawn into latest.log

	// ---- /fakeban (fake anti-cheat) ----
	private static final String FAKEBASE_FILE = "fakebase.litematic"; // the trap base (put it in the 'schematics' folder)
	private static final double VL_WATCH = 20.0;         // violation level that makes the "admin" look at you (head passes the screen)
	private static final double VL_TRAP = 20.0;          // violation level (after being watched) that spawns the trap base
	private static final int TRAP_DELAY_TICKS = 400;     // at least 20 seconds between the head pass and the trap base
	private static final double VL_DECAY_PER_SEC = 0.5;  // violation level that wears off every second
	private static final double VL_PER_BLOCK = 0.15;     // every block you mine
	private static final double VL_DEEP_BONUS = 0.10;    // extra when mining at/below TRIGGER_Y
	private static final double VL_PER_EXTRA = 0.35;     // per extra block broken by 3x3 mining (capped at 3.0 per break)
	private static final double VL_FAST = 1.0;           // extra per block when mining way too fast
	private static final int FAST_BREAKS = 10;           // this many blocks in 3 seconds counts as "too fast"
	private static final double VL_FLY = 2.0;            // per second while flying in survival
	private static final double VL_SPEED = 1.5;          // per second while moving too fast on foot
	private static final double SPEED_LIMIT = 14.0;      // blocks per second considered "too fast" on foot
	private static final double VL_RTP = 6.0;            // using /rtp looks like a teleport hack
	private static final int HEAD_TICKS = 50;            // how long the head takes to cross the screen (50 ticks = 2.5 seconds)
	private static final double TRAP_ENTER_DIST = 1.0;   // closer than this to the trap base = kicked
	private static final double TRAP_MINE_DIST = 3.0;    // mining a block closer than this to the trap base = kicked
	private static final double TRAP_APPROACH_RANGE = 16.0; // "mining towards it" is only checked inside this range
	private static final int TRAP_APPROACH_BREAKS = 6;   // this many blocks in a row getting closer = kicked
	private static final String APPEAL_URL = "support.donutsmp.net";

	// ---- /rtp ----
	private static final int RTP_MIN = 500;              // minimum distance of the random teleport
	private static final int RTP_MAX = 5000;             // maximum distance of the random teleport
	private static final int RTP_TRIES = 25;             // how many random spots to try before giving up
	// ===============================================================================

	private static Job activeJob = null;
	private static long nextAllowed = 0L;

	// shuffle bag for the random fake bases (every base is used once before any repeats)
	private static final List<Path> BAG = new ArrayList<>();
	private static Path lastBase = null;

	// 3x3 mining state (only touched on the server thread)
	private static final Set<UUID> ENABLED_3X3 = new HashSet<>();
	private static final Map<UUID, Direction> PENDING_FACE = new HashMap<>();
	private static boolean breakingExtra = false;
	private static int extraBroken = 0;                      // how many extra blocks the last 3x3 break removed

	// fake anti-cheat state (only touched on the server thread)
	private static final Set<UUID> ENABLED_FAKEBAN = new HashSet<>();
	private static final Map<UUID, Watch> WATCH = new HashMap<>();
	private static final Map<UUID, HeadAnim> HEADS = new HashMap<>();
	private static Trap trap = null;
	private static int cmdErrors = 0;

	private static final Skin[] SKINS = {
			new Skin("Pastagamer08", UUID.fromString("5cff4acd-fda7-413a-a369-a0928b5c70c9")),
			new Skin("WiZoX", UUID.fromString("77482a7d-e661-4927-8641-ee5c62245dd1"))
	};

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// ---- /3x3 command (toggle) ----
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
			}));

			// ---- /fakebase : TEST COMMAND - forces a random fake base right now and tells you what went wrong ----
			dispatcher.register(Commands.literal("fakebase").executes(ctx -> cmdFakeBase(ctx.getSource())));

			// ---- /fakeban (toggle) + test subcommands ----
			dispatcher.register(Commands.literal("fakeban")
					.executes(ctx -> cmdFakeBan(ctx.getSource()))
					.then(Commands.literal("status").executes(ctx -> cmdFakeBanStatus(ctx.getSource())))
					.then(Commands.literal("watch").executes(ctx -> cmdFakeBanWatch(ctx.getSource())))
					.then(Commands.literal("trap").executes(ctx -> cmdFakeBanTrap(ctx.getSource())))
					.then(Commands.literal("kick").executes(ctx -> cmdFakeBanKick(ctx.getSource())))
					.then(Commands.literal("reset").executes(ctx -> cmdFakeBanReset(ctx.getSource()))));

			// ---- /rtp ----
			dispatcher.register(Commands.literal("rtp").executes(ctx -> cmdRtp(ctx.getSource())));
		});

		// ---- remember which face of the block the player is mining ----
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			try {
				rememberFace(world, player, pos);
			} catch (Exception e) {
				if (cmdErrors++ < 5) LOGGER.warn("rememberFace error: {}", e.toString());
			}
			return true;
		});

		// ---- after a block is broken: 3x3 extras + fake anti-cheat + fake base trigger ----
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (breakingExtra) return;                                // ignore blocks broken by 3x3 itself
			if (!(world instanceof ServerLevel level)) return;
			if (level.getServer().isDedicatedServer()) return;       // singleplayer / integrated server only

			// These two must never be able to stop the fake base from spawning, even if they throw an error.
			if (player instanceof ServerPlayer sp) {
				try {
					handle3x3(level, sp, pos);
				} catch (Throwable e) {
					breakingExtra = false;
					if (cmdErrors++ < 5) LOGGER.warn("3x3 error: {}", e.toString());
				}
				try {
					handleFakeBanBreak(sp, pos);
				} catch (Throwable e) {
					if (cmdErrors++ < 5) LOGGER.warn("fake anti-cheat error: {}", e.toString());
				}
			}

			// ---------------- Random fake base trigger ----------------
			if (level.dimension() != Level.OVERWORLD) return;

			// Any block mined below BASE_TRIGGER_Y (Y -10) counts, no matter what block it is.
			if (pos.getY() >= BASE_TRIGGER_Y) return;

			if (ThreadLocalRandom.current().nextDouble() >= CHANCE) return;

			if (activeJob != null) {
				if (DEBUG_LOG) LOGGER.info("Fake base roll hit but a base is still being pasted, skipped.");
				return;
			}
			long wait = nextAllowed - System.currentTimeMillis();
			if (wait > 0) {
				if (DEBUG_LOG) LOGGER.info("Fake base roll hit but cooldown has {} s left, skipped.", wait / 1000);
				return;
			}

			if (DEBUG_LOG) LOGGER.info("Fake base triggered (mined {} at Y={}).",
					BuiltInRegistries.BLOCK.getKey(state.getBlock()), pos.getY());
			try {
				String res = tryPlaceFakeBase(level, player.getBlockX(), player.getBlockY(), player.getBlockZ());
				if (DEBUG_LOG && !res.equals("ok")) LOGGER.warn("Fake base did not spawn: {}", fakeBaseProblem(res));
			} catch (Throwable e) {
				LOGGER.warn("Fake base failed: {}", e.toString());
				activeJob = null;
				nextAllowed = System.currentTimeMillis() + 10_000L;
			}
		});

		// Clean up state when players join / leave
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resetFakeBan(handler.getPlayer().getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> resetFakeBan(handler.getPlayer().getUUID()));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try {
				tickJob();
			} catch (Throwable e) {
				LOGGER.warn("Fake base placement crashed, resetting: {}", e.toString());
				activeJob = null;
				nextAllowed = System.currentTimeMillis() + 10_000L;
			}
			try {
				tickFakeBan(server);
			} catch (Throwable e) {
				if (cmdErrors++ < 5) LOGGER.warn("fake anti-cheat tick error: {}", e.toString());
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			activeJob = null;
			nextAllowed = 0L;
			PENDING_FACE.clear();
			breakingExtra = false;
			WATCH.clear();
			for (HeadAnim ha : HEADS.values()) {
				if (ha.stand != null) ha.stand.discard();
			}
			HEADS.clear();
			trap = null;
		});

		LOGGER.info("Fake bases mod loaded. Put .litematic files in the 'schematics' folder. Use /fakebase to test, /3x3 for 3x3 mining, /fakeban for the fake anti-cheat, /rtp to teleport.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	private static boolean isDeepslate(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("deepslate");
	}

	// ------------------------------------------------------------------ /fakebase (test command)

	private static int cmdFakeBase(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		if (!(p.level() instanceof ServerLevel level)) return 0;
		if (activeJob != null) {
			src.sendFailure(Component.literal("A base is still being pasted, wait a moment."));
			return 0;
		}
		nextAllowed = 0L; // ignore the cooldown for the test
		String res;
		try {
			res = tryPlaceFakeBase(level, p.getBlockX(), p.getBlockY(), p.getBlockZ());
		} catch (Throwable e) {
			LOGGER.warn("Fake base failed: {}", e.toString(), e);
			src.sendFailure(Component.literal("Fake base crashed: " + e));
			return 0;
		}
		if (res.equals("ok")) {
			src.sendSuccess(() -> Component.literal("Fake base is being placed near you."), false);
			return 1;
		}
		src.sendFailure(Component.literal("Fake base did not spawn: " + fakeBaseProblem(res)));
		return 0;
	}

	private static String fakeBaseProblem(String code) {
		return switch (code) {
			case "nofolder" -> "The '.minecraft/schematics' folder does not exist (game dir: "
					+ FabricLoader.getInstance().getGameDir().toAbsolutePath() + ").";
			case "nofiles" -> "No .litematic files found in the schematics folder.";
			case "unreadable" -> "Every schematic that was tried could not be read (see latest.log).";
			case "toobig" -> "Every schematic that was tried is empty or bigger than MAX_VOLUME.";
			case "nospace" -> "Could not find a place for the base.";
			default -> code;
		};
	}

	// ------------------------------------------------------------------ 3x3 mining

	private static void rememberFace(Level world, Player player, BlockPos pos) {
		if (world.isClientSide()) return;
		if (!ENABLED_3X3.contains(player.getUUID())) return;
		if (player.isShiftKeyDown()) return;
		if (!player.getMainHandItem().is(ItemTags.PICKAXES)) return;

		HitResult hit = player.pick(20.0, 0.0f, false);
		if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
			PENDING_FACE.put(player.getUUID(), bhr.getDirection());
		} else {
			PENDING_FACE.remove(player.getUUID());
		}
	}

	private static void handle3x3(ServerLevel level, ServerPlayer p, BlockPos origin) {
		extraBroken = 0;
		if (!ENABLED_3X3.contains(p.getUUID())) return;
		if (p.isShiftKeyDown()) return;
		if (!p.getMainHandItem().is(ItemTags.PICKAXES)) return;

		Direction face = PENDING_FACE.remove(p.getUUID());
		if (face == null) {
			face = Direction.orderedByNearest(p)[0].getOpposite();
		}

		Direction.Axis axis = face.getAxis();
		List<BlockPos> targets = new ArrayList<>();

		if (axis == Direction.Axis.Y) {
			Direction facing = p.getDirection();
			Direction left = facing.getClockWise();
			Direction right = facing.getCounterClockWise();
			Direction step = (face == Direction.UP) ? Direction.DOWN : Direction.UP;

			addStep(targets, origin, step, DOWN_DEPTH);
			addStep(targets, origin.relative(left), step, DOWN_DEPTH);
			addStep(targets, origin.relative(right), step, DOWN_DEPTH);
		} else {
			Direction left = face.getClockWise();
			Direction step = face.getOpposite();

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos center = origin.relative(left, dx).above(dy);
					addStep(targets, center, step, WALL_DEPTH);
				}
			}
		}

		breakingExtra = true;
		try {
			for (BlockPos target : targets) {
				if (target.equals(origin)) continue;
				if (!level.isInWorldBounds(target)) continue;
				BlockState st = level.getBlockState(target);
				if (st.isAir()) continue;
				if (st.getDestroySpeed(level, target) < 0) continue; // bedrock etc.
				if (p.gameMode.destroyBlock(target)) {
					extraBroken++;
				}
			}
		} finally {
			breakingExtra = false;
		}
	}

	private static void addStep(List<BlockPos> list, BlockPos start, Direction dir, int depth) {
		BlockPos cur = start;
		for (int i = 0; i < depth; i++) {
			list.add(cur);
			cur = cur.relative(dir);
		}
	}

	// ------------------------------------------------------------------ /rtp command

	private static int cmdRtp(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		ServerLevel level = p.level() instanceof ServerLevel sl ? sl : null;
		if (level == null) return 0;

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < RTP_TRIES; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double dist = RTP_MIN + rnd.nextDouble() * (RTP_MAX - RTP_MIN);
			int x = (int) Math.round(p.getX() + Math.cos(angle) * dist);
			int z = (int) Math.round(p.getZ() + Math.sin(angle) * dist);

			level.getChunk(x >> 4, z >> 4); // force load so heightmap is populated
			int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (surfaceY <= level.getMinY()) continue;

			BlockPos ground = new BlockPos(x, surfaceY - 1, z);
			BlockState groundState = level.getBlockState(ground);
			if (groundState.is(Blocks.WATER) || groundState.is(Blocks.LAVA)) continue;

			BlockPos feet = new BlockPos(x, surfaceY, z);
			BlockPos head = feet.above();
			if (!level.getBlockState(feet).isAir() || !level.getBlockState(head).isAir()) continue;

			p.teleportTo(level, x + 0.5, surfaceY, z + 0.5, Set.of(), p.getYRot(), p.getXRot(), false);
			run(p, "playsound minecraft:item.chorus_fruit.teleport player @s ~ ~ ~ 1 1");

			// teleporting instantly looks like a cheat to the fake anti-cheat
			Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
			w.vl += VL_RTP;
			w.hasLast = false; // don't count the teleport itself as movement speed

			src.sendSuccess(() -> Component.literal("Teleported to " + x + " " + surfaceY + " " + z + "."), false);
			return 1;
		}

		src.sendFailure(Component.literal("Could not find a safe place to teleport. Try again."));
		return 0;
	}

	// ------------------------------------------------------------------ fake anti-cheat (/fakeban)

	private static final class Watch {
		double vl = 0.0;                  // violation level, like a real anti-cheat
		int stage = 0;                    // 0 = clean, 1 = "admin" watched you, 2 = trap base placed
		int watchedTick = 0;
		boolean trapGaveUp = false;
		final ArrayDeque<Integer> breaks = new ArrayDeque<>();
		boolean hasLast = false;
		double lastX, lastZ;
		double lastTrapDist = Double.MAX_VALUE;
		int approach = 0;
	}

	private static final class HeadAnim {
		final Skin skin;
		final ArmorStand stand;
		int tick = 0;

		HeadAnim(Skin skin, ArmorStand stand) {
			this.skin = skin;
			this.stand = stand;
		}
	}

	private record Skin(String name, UUID uuid) {}

	private record Trap(ServerLevel level, UUID owner, int ox, int oy, int oz, int w, int h, int d) {}

	private static void resetFakeBan(UUID id) {
		WATCH.remove(id);
		HeadAnim ha = HEADS.remove(id);
		if (ha != null && ha.stand != null) {
			ha.stand.discard();
		}
	}

	private static ServerPlayer requireSingleplayer(CommandSourceStack src) throws CommandSyntaxException {
		if (src.getServer().isDedicatedServer()) {
			src.sendFailure(Component.literal("This command only works in singleplayer."));
			return null;
		}
		return src.getPlayerOrException();
	}

	private static int cmdFakeBan(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		UUID id = p.getUUID();
		boolean nowOn = !ENABLED_FAKEBAN.remove(id);
		if (nowOn) {
			ENABLED_FAKEBAN.add(id);
		} else {
			resetFakeBan(id);
		}
		String msg = nowOn
				? "Fake anti-cheat: ON (cheat too obviously and an admin might start watching you)"
				: "Fake anti-cheat: OFF";
		src.sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private static int cmdFakeBanStatus(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		UUID id = p.getUUID();
		Watch w = WATCH.get(id);
		String msg = "Fake anti-cheat: " + (ENABLED_FAKEBAN.contains(id) ? "ON" : "OFF")
				+ " | violation level " + String.format(Locale.ROOT, "%.1f", w == null ? 0.0 : w.vl)
				+ " | stage " + (w == null ? 0 : w.stage)
				+ " (0 = clean, 1 = watched, 2 = trap placed)";
		src.sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	private static int cmdFakeBanWatch(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
		startHeadPass(p);
		w.stage = Math.max(w.stage, 1);
		w.watchedTick = ((ServerLevel) p.level()).getServer().getTickCount();
		src.sendSuccess(() -> Component.literal("Test: the admin is watching you now."), false);
		return 1;
	}

	private static int cmdFakeBanTrap(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		String res = placeTrap(p);
		if (res.equals("ok")) {
			Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
			w.stage = 2;
			src.sendSuccess(() -> Component.literal("Trap base placed near you."), false);
			return 1;
		}
		src.sendFailure(Component.literal("Could not place trap base: " + trapProblem(res)));
		return 0;
	}

	private static int cmdFakeBanKick(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		fakeBanKick(p);
		return 1;
	}

	private static int cmdFakeBanReset(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		resetFakeBan(p.getUUID());
		if (trap != null && trap.owner().equals(p.getUUID())) trap = null;
		src.sendSuccess(() -> Component.literal("Fake anti-cheat violation level reset to 0."), false);
		return 1;
	}

	private static String trapProblem(String code) {
		return switch (code) {
			case "missing" -> "Put '" + FAKEBASE_FILE + "' in the '.minecraft/schematics' folder first.";
			case "unreadable" -> "The '" + FAKEBASE_FILE + "' file could not be read.";
			case "toobig" -> "The schematic has more than 3,000,000 blocks (too big).";
			case "busy" -> "Another base is currently being pasted. Wait a moment.";
			case "nospace" -> "Could not find a place for the base. Try again somewhere else.";
			default -> code;
		};
	}

	// Called right after a survival block is mined.
	private static void handleFakeBanBreak(ServerPlayer p, BlockPos pos) {
		UUID id = p.getUUID();
		if (!ENABLED_FAKEBAN.contains(id)) return;
		Watch w = WATCH.computeIfAbsent(id, k -> new Watch());

		w.vl += VL_PER_BLOCK;
		if (pos.getY() <= TRIGGER_Y) w.vl += VL_DEEP_BONUS;
		if (extraBroken > 0) w.vl += Math.min(3.0, extraBroken * VL_PER_EXTRA);

		int now = ((ServerLevel) p.level()).getServer().getTickCount();
		w.breaks.addLast(now);
		while (!w.breaks.isEmpty() && now - w.breaks.peekFirst() > 60) {
			w.breaks.removeFirst();
		}
		if (w.breaks.size() >= FAST_BREAKS) {
			w.vl += VL_FAST;
		}

		// stage 2: mining into the trap base
		if (w.stage >= 2 && trap != null && trap.owner().equals(id) && p.level() == trap.level()) {
			double dist = trapDistance(trap, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			if (dist <= TRAP_MINE_DIST) {
				fakeBanKick(p);
				return;
			}
			if (dist <= TRAP_APPROACH_RANGE) {
				if (dist < w.lastTrapDist - 0.2) {
					w.approach++;
					if (w.approach >= TRAP_APPROACH_BREAKS) {
						fakeBanKick(p);
						return;
					}
				} else {
					w.approach = Math.max(0, w.approach - 1);
				}
				w.lastTrapDist = dist;
			}
		}
	}

	// Distance from a point (x, y, z) to the bounding box of a trap base.
	private static double trapDistance(Trap t, double x, double y, double z) {
		double dx = Math.max(Math.max(t.ox() - x, 0.0), x - (t.ox() + t.w()));
		double dy = Math.max(Math.max(t.oy() - y, 0.0), y - (t.oy() + t.h()));
		double dz = Math.max(Math.max(t.oz() - z, 0.0), z - (t.oz() + t.d()));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void tickFakeBan(MinecraftServer server) {
		if (server.isDedicatedServer()) return;
		int now = server.getTickCount();

		// ---- head passing across the screen ----
		Iterator<Map.Entry<UUID, HeadAnim>> it = HEADS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, HeadAnim> en = it.next();
			HeadAnim a = en.getValue();
			ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
			if (p == null || p.isRemoved() || a.stand.isRemoved() || p.level() != a.stand.level()) {
				if (a.stand != null) a.stand.discard();
				it.remove();
				continue;
			}
			if (a.tick >= HEAD_TICKS) {
				if (a.stand != null) a.stand.discard();
				it.remove();
				continue;
			}

			// Elder Guardian jumpscare animation:
			// Head sweeps smoothly across the player's screen while staring into their eyes.
			double progress = (double) a.tick / Math.max(1, HEAD_TICKS - 1);
			Vec3 eye = p.getEyePosition();
			float yaw = p.getYRot();
			float pitch = p.getXRot();

			double yawRad = Math.toRadians(yaw);
			double pitchRad = Math.toRadians(pitch);

			Vec3 forward = new Vec3(
					-Math.sin(yawRad) * Math.cos(pitchRad),
					-Math.sin(pitchRad),
					Math.cos(yawRad) * Math.cos(pitchRad)
			).normalize();

			Vec3 right = new Vec3(Math.cos(yawRad), 0.0, Math.sin(yawRad)).normalize();
			Vec3 up = right.cross(forward).normalize();

			// Sweeps across field of view (high-right down to center-left)
			double screenX = 0.55 - 1.10 * progress;
			double screenY = 0.45 - 0.90 * progress;
			// Depth curve: zooms in close at the midpoint (0.85 blocks from face), then sweeps away
			double dist = 0.85 + 0.65 * Math.pow(progress - 0.5, 2) * 4.0;

			Vec3 targetHead = eye.add(forward.scale(dist))
					.add(right.scale(screenX))
					.add(up.scale(screenY));

			double standY = targetHead.y - 1.70;

			// Rotate the head to stare directly into the player's eyes
			Vec3 toEye = eye.subtract(targetHead).normalize();
			double headYaw = Math.toDegrees(Math.atan2(-toEye.x, toEye.z));
			double headPitch = Math.toDegrees(Math.asin(-toEye.y));

			a.stand.setYRot((float) headYaw);
			a.stand.setYHeadRot((float) headYaw);
			a.stand.setHeadPose(new Rotations((float) headPitch, 0.0f, 0.0f));
			a.stand.teleportTo(targetHead.x, standY, targetHead.z);

			a.tick++;
		}

		// ---- per-player checks ----
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			UUID id = p.getUUID();
			if (!ENABLED_FAKEBAN.contains(id)) continue;
			Watch w = WATCH.computeIfAbsent(id, k -> new Watch());

			// walking into the trap base
			if (now % 5 == 0 && w.stage >= 2 && trap != null && trap.owner().equals(id) && p.level() == trap.level()) {
				if (trapDistance(trap, p.getX(), p.getY(), p.getZ()) <= TRAP_ENTER_DIST) {
					fakeBanKick(p);
					continue;
				}
			}

			// movement & decay check (once per second)
			if (now % 20 != 0) continue;

			if (w.hasLast) {
				double dx = p.getX() - w.lastX;
				double dz = p.getZ() - w.lastZ;
				double speed = Math.sqrt(dx * dx + dz * dz);

				if (!p.isCreative() && !p.isSpectator()) {
					if (p.getAbilities().flying) {
						w.vl += VL_FLY;
					} else if (speed > SPEED_LIMIT && !p.isFallFlying()) {
						w.vl += VL_SPEED;
					}
				}
			}
			w.lastX = p.getX();
			w.lastZ = p.getZ();
			w.hasLast = true;

			// decay
			w.vl = Math.max(0.0, w.vl - VL_DECAY_PER_SEC);

			// stage 0 -> 1: the admin looks at you
			if (w.stage == 0 && w.vl >= VL_WATCH) {
				startHeadPass(p);
				w.stage = 1;
				w.watchedTick = now;
				w.vl = 5.0;
				continue;
			}

			// stage 1 -> 2: you kept cheating, the trap base appears
			if (w.stage == 1 && !w.trapGaveUp && w.vl >= VL_TRAP && now - w.watchedTick >= TRAP_DELAY_TICKS) {
				String result = placeTrap(p);
				if (result.equals("ok")) {
					w.stage = 2;
				} else if (result.equals("missing") || result.equals("unreadable") || result.equals("toobig")) {
					w.trapGaveUp = true;
					LOGGER.warn("Fake anti-cheat trap base skipped: {}", trapProblem(result));
				}
				// "busy" / "nospace": simply try again next second
			}
		}
	}

	// The vanilla elder guardian "mining fatigue" scare (sound + big face crossing the screen),
	// but WITHOUT the mining fatigue potion effect, and with a real player head instead of the guardian.
	private static void startHeadPass(ServerPlayer p) {
		if (!(p.level() instanceof ServerLevel level)) return;
		HeadAnim oldAnim = HEADS.remove(p.getUUID());
		if (oldAnim != null && oldAnim.stand != null) {
			oldAnim.stand.discard();
		}

		Skin skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];

		// 1. Play Elder Guardian curse jumpscare sound directly to the player
		p.playSound(SoundEvents.ELDER_GUARDIAN_CURSE, 1.0f, 1.0f);
		run(p, "playsound minecraft:entity.elder_guardian.curse hostile @s ~ ~ ~ 1 1");

		// 2. Display ominous warning subtitle
		run(p, "title @s times 0 " + HEAD_TICKS + " 10");
		run(p, "title @s subtitle [{\"text\":\"[ \",\"color\":\"gray\"},{\"text\":\"" + skin.name() + "\",\"color\":\"red\",\"bold\":true},{\"text\":\" is watching you ]\",\"color\":\"gray\"}]");
		run(p, "title @s title {\"text\":\"\"}");

		// 3. Spawn an invisible ArmorStand wearing the configured UUID skin head right in front of the player's face
		Vec3 eye = p.getEyePosition();
		ArmorStand stand = new ArmorStand(level, eye.x, eye.y - 1.70, eye.z);

		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		GameProfile gp = new GameProfile(skin.uuid(), skin.name());
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
		stand.setItemSlot(EquipmentSlot.HEAD, head);
		stand.setInvisible(true);
		stand.setNoBasePlate(true);
		stand.setNoGravity(true);
		stand.setSilent(true);
		stand.setCustomName(Component.literal(""));
		stand.setCustomNameVisible(false);

		level.addFreshEntity(stand);
		HEADS.put(p.getUUID(), new HeadAnim(skin, stand));
	}

	// Text component with the head of a real player (resolved by the client from the UUID/name).
	private static String headSnbt(Skin s) {
		long m = s.uuid().getMostSignificantBits();
		long l = s.uuid().getLeastSignificantBits();
		int i1 = (int) (m >> 32);
		int i2 = (int) m;
		int i3 = (int) (l >> 32);
		int i4 = (int) l;
		return "{object:\"player\",player:{name:\"" + s.name() + "\",id:[I;" + i1 + "," + i2 + "," + i3 + "," + i4 + "]},hat:true}";
	}

	// Returns "ok" or a short problem code (see trapProblem).
	private static String placeTrap(ServerPlayer p) {
		if (!(p.level() instanceof ServerLevel level)) return "nospace";
		if (activeJob != null) return "busy";

		Path file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(FAKEBASE_FILE);
		if (!Files.isRegularFile(file)) return "missing";

		Schem schem;
		try {
			HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
			schem = readLitematic(file, lookup);
		} catch (Exception e) {
			LOGGER.warn("Could not read {}: {}", file.getFileName(), e.toString());
			return "unreadable";
		}
		if (schem == null) return "toobig";

		for (int i = 0; i < 8; i++) {
			if (startJob(level, schem, p.getBlockX(), p.getBlockY(), p.getBlockZ(), file, p.getUUID())) return "ok";
		}
		return "nospace";
	}

	// Simulated multiplayer ban: the disconnect screen with a fake permanent ban message.
	private static void fakeBanKick(ServerPlayer p) {
		resetFakeBan(p.getUUID());
		p.connection.disconnect(banMessage());
	}

	private static Component banMessage() {
		String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		StringBuilder banId = new StringBuilder("#");
		for (int i = 0; i < 8; i++) banId.append(chars.charAt(rnd.nextInt(chars.length())));

		MutableComponent c = Component.literal("You are permanently banned for cheating.").withStyle(ChatFormatting.RED);
		c.append(Component.literal("\n\n"));
		c.append(Component.literal("Time Left: Permanent").withStyle(ChatFormatting.GRAY));
		c.append(Component.literal("\n\n"));
		c.append(Component.literal("Ban ID: " + banId).withStyle(ChatFormatting.GRAY));
		c.append(Component.literal("\nYou may be able to appeal this ban on\n").withStyle(ChatFormatting.GRAY));
		c.append(Component.literal(APPEAL_URL).withStyle(ChatFormatting.WHITE));
		return c;
	}

	// Runs a command as the player with operator permissions (no chat output).
	private static void run(ServerPlayer p, String command) {
		if (!(p.level() instanceof ServerLevel level)) return;
		try {
			CommandSourceStack src = p.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput()
					.withPermission(PermissionSet.ALL_PERMISSIONS);
			level.getServer().getCommands().performPrefixedCommand(src, command);
		} catch (Exception e) {
			if (cmdErrors++ < 5) {
				LOGGER.warn("Command failed ({}): {}", command, e.toString());
			}
		}
	}

	private static void runAs(MinecraftServer server, ServerLevel level, String command) {
		try {
			CommandSourceStack src = server.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput()
					.withPermission(PermissionSet.ALL_PERMISSIONS);
			server.getCommands().performPrefixedCommand(src, command);
		} catch (Exception e) {
			if (cmdErrors++ < 5) {
				LOGGER.warn("Command failed ({}): {}", command, e.toString());
			}
		}
	}

	// ------------------------------------------------------------------ fake base logic

	// Returns "ok" or a problem code (see fakeBaseProblem).
	private static String tryPlaceFakeBase(ServerLevel level, int px, int py, int pz) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(f -> Files.isRegularFile(f)
						&& f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
						.forEach(files::add);
			} catch (IOException e) {
				LOGGER.warn("Could not list schematics: {}", e.toString());
			}
		} else {
			LOGGER.warn("Fake base: folder {} does not exist", dir.toAbsolutePath());
			nextAllowed = System.currentTimeMillis() + 30_000L;
			return "nofolder";
		}

		// the trap base is only used by /fakeban, keep it out of the random pool when there are other bases
		if (files.size() > 1) {
			files.removeIf(f -> f.getFileName().toString().equalsIgnoreCase(FAKEBASE_FILE));
		}
		if (files.isEmpty()) {
			nextAllowed = System.currentTimeMillis() + 30_000L;
			LOGGER.warn("Fake base: no .litematic files found in {}", dir.toAbsolutePath());
			return "nofiles";
		}

		HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);

		boolean anyUnreadable = false;
		boolean anyTooBig = false;

		// try up to 3 different bases, so one broken / too big file does not stop everything
		int fileTries = Math.min(3, files.size());
		for (int i = 0; i < fileTries; i++) {
			Path file = nextFromBag(files);
			if (file == null) break;

			Schem schem;
			try {
				schem = readLitematic(file, lookup);
			} catch (Throwable e) {
				LOGGER.warn("Fake base: could not read {}: {}", file.getFileName(), e.toString());
				anyUnreadable = true;
				continue;
			}
			if (schem == null) {
				LOGGER.warn("Fake base: {} is empty or bigger than MAX_VOLUME, skipping", file.getFileName());
				anyTooBig = true;
				continue;
			}

			for (int attempt = 0; attempt < 8; attempt++) {
				if (startJob(level, schem, px, py, pz, file, null)) return "ok";
			}
			LOGGER.warn("Fake base: could not find a spot for {}", file.getFileName());
		}

		nextAllowed = System.currentTimeMillis() + 10_000L;
		if (anyUnreadable) return "unreadable";
		if (anyTooBig) return "toobig";
		return "nospace";
	}

	private static Path nextFromBag(List<Path> available) {
		BAG.removeIf(p -> !available.contains(p));

		if (BAG.isEmpty()) {
			BAG.addAll(available);
			Collections.shuffle(BAG);
			if (BAG.size() > 1 && BAG.get(0).equals(lastBase)) {
				BAG.add(BAG.remove(0));
			}
		}

		if (BAG.isEmpty()) return null;
		Path picked = BAG.remove(0);
		lastBase = picked;
		return picked;
	}

	private static boolean startJob(ServerLevel level, Schem s, int px, int py, int pz, Path file, UUID trapOwner) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		int maxOY = Math.min(py + 4, level.getMaxY() - s.h());
		int minOY = Math.max(py - s.h() - 4, MIN_BUILD_Y);
		if (maxOY < minOY) {
			// base is too tall for the space: build it as low as allowed
			minOY = MIN_BUILD_Y;
			maxOY = MIN_BUILD_Y;
		}
		int oy = minOY + rnd.nextInt(maxOY - minOY + 1);

		int ox = rnd.nextBoolean()
				? px + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: px - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.w();

		int oz = rnd.nextBoolean()
				? pz + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: pz - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.d();

		// Load every chunk the base will cover (so it never fails because a chunk was not loaded yet).
		for (int cx = ox >> 4; cx <= (ox + s.w() - 1) >> 4; cx++) {
			for (int cz = oz >> 4; cz <= (oz + s.d() - 1) >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}

		activeJob = new Job(level, ox, oy, oz, s);
		nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
		if (trapOwner != null) {
			trap = new Trap(level, trapOwner, ox, oy, oz, s.w(), s.h(), s.d());
		}
		LOGGER.info("Placing fake base '{}' ({}x{}x{}, {} containers, {} entities) at {} {} {}",
				file.getFileName(), s.w(), s.h(), s.d(), s.tiles().size(), s.ents().size(), ox, oy, oz);
		return true;
	}

	private static void tickJob() {
		Job job = activeJob;
		if (job == null) return;

		int total = job.s.states().length;
		if (job.next < total) {
			int budget = BLOCKS_PER_TICK;
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

			while (budget-- > 0 && job.next < total) {
				int idx = job.next++;
				BlockState st = job.s.states()[idx];
				if (st == null) continue;

				int rem = idx % (job.s.w() * job.s.d());
				int x = rem % job.s.w();
				int z = rem / job.s.w();
				int y = idx / (job.s.w() * job.s.d());

				pos.set(job.ox + x, job.oy + y, job.oz + z);
				job.level.setBlock(pos, st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
			if (job.next < total) return;
		}

		List<Tile> tiles = job.s.tiles();
		List<Ent> ents = job.s.ents();
		if (job.tileNext < tiles.size() || job.entNext < ents.size()) {
			if (job.source == null) {
				job.source = job.level.getServer().createCommandSourceStack()
						.withLevel(job.level)
						.withSuppressedOutput()
						.withPermission(PermissionSet.ALL_PERMISSIONS);
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
		if (DEBUG_LOG) LOGGER.info("Fake base finished at {} {} {}", job.ox, job.oy, job.oz);
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

	private record Ent(String type, double x, double y, double z, String snbt, boolean hanging) {}

	private record Schem(int w, int h, int d, BlockState[] states, List<Tile> tiles, List<Ent> ents) {}

	private record Region(int ox, int oy, int oz, int rawX, int rawY, int rawZ, int ax, int ay, int az,
						  long[] data, List<Object> palette, List<Object> tiles, List<Object> entities) {}

	// ------------------------------------------------------------------ .litematic reader

	private static Schem readLitematic(Path file, HolderLookup<Block> lookup) throws Exception {
		Map<String, Object> root;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			root = asMap(readNamedTag(in));
		}
		if (root == null) return null;

		Map<String, Object> regions = asMap(root.get("Regions"));
		if (regions == null || regions.isEmpty()) return null;

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		List<Region> list = new ArrayList<>();

		for (Object regObj : regions.values()) {
			Map<String, Object> reg = asMap(regObj);
			if (reg == null) continue;
			Map<String, Object> pos = asMap(reg.get("Position"));
			Map<String, Object> size = asMap(reg.get("Size"));
			if (pos == null || size == null) continue;

			int sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			if (ax == 0 || ay == 0 || az == 0) continue;

			int rawX = num(pos.get("x")), rawY = num(pos.get("y")), rawZ = num(pos.get("z"));

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

		if (list.isEmpty()) return null;
		int w = maxX - minX;
		int h = maxY - minY;
		int d = maxZ - minZ;

		long volume = (long) w * h * d;
		if (volume <= 0 || volume > MAX_VOLUME) return null;

		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState[] out = new BlockState[w * h * d];
		Arrays.fill(out, air);
		List<Tile> tiles = new ArrayList<>();
		List<Ent> ents = new ArrayList<>();

		for (Region r : list) {
			BlockState[] pal = new BlockState[r.palette().size()];
			for (int i = 0; i < pal.length; i++) {
				pal[i] = parseState(r.palette().get(i), lookup);
			}
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
			long mask = (1L << bits) - 1L;

			for (int y = 0; y < r.ay(); y++) {
				for (int z = 0; z < r.az(); z++) {
					for (int x = 0; x < r.ax(); x++) {
						int palIdx = readPacked(r.data(), bits, mask, (y * r.az() + z) * r.ax() + x);
						BlockState st = palIdx < pal.length ? pal[palIdx] : air;
						if (st == null) continue; // structure void

						int gx = r.ox() - minX + x;
						int gy = r.oy() - minY + y;
						int gz = r.oz() - minZ + z;
						out[(gy * d + gz) * w + gx] = st;
					}
				}
			}

			if (r.tiles() != null) {
				for (Object t : r.tiles()) {
					Map<String, Object> te = asMap(t);
					if (te == null) continue;
					int tx = num(te.get("x"));
					int ty = num(te.get("y"));
					int tz = num(te.get("z"));

					Map<String, Object> copy = new HashMap<>(te);
					copy.remove("x");
					copy.remove("y");
					copy.remove("z");
					copy.remove("id");
					upgradeItems(copy);

					StringBuilder sb = new StringBuilder();
					writeSnbt(copy, sb);
					tiles.add(new Tile(r.ox() - minX + tx, r.oy() - minY + ty, r.oz() - minZ + tz, sb.toString()));
				}
			}

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

	private static void upgradeItems(Map<String, Object> te) {
		List<Object> items = asList(te.get("Items"));
		if (items == null) return;
		for (Object itemObj : items) {
			Map<String, Object> item = asMap(itemObj);
			if (item == null) continue;
			if (item.containsKey("Count") && !item.containsKey("count")) {
				item.put("count", num(item.remove("Count")));
			}
		}
	}

	private static BlockState parseState(Object obj, HolderLookup<Block> lookup) {
		Map<String, Object> map = asMap(obj);
		if (map == null) return Blocks.AIR.defaultBlockState();
		String name = (String) map.get("Name");
		if (name == null || name.equals("minecraft:structure_void")) return null;

		Map<String, Object> props = asMap(map.get("Properties"));
		if (props != null && !props.isEmpty()) {
			StringBuilder sb = new StringBuilder(name).append('[');
			boolean first = true;
			for (Map.Entry<String, Object> e : props.entrySet()) {
				if (!first) sb.append(',');
				sb.append(e.getKey()).append('=').append(e.getValue());
				first = false;
			}
			sb.append(']');
			try {
				return BlockStateParser.parseForBlock(lookup, sb.toString(), false).blockState();
			} catch (Exception ignored) {
			}
		}
		try {
			return BlockStateParser.parseForBlock(lookup, name, false).blockState();
		} catch (Exception e) {
			return Blocks.AIR.defaultBlockState();
		}
	}

	private static int readPacked(long[] arr, int bits, long mask, int index) {
		long start = (long) index * bits;
		int a = (int) (start >> 6);
		int off = (int) (start & 63);
		if (a >= arr.length) return 0;
		if (off + bits <= 64) {
			return (int) ((arr[a] >>> off) & mask);
		}
		int b = a + 1;
		int firstBits = 64 - off;
		if (b >= arr.length) return (int) ((arr[a] >>> off) & mask);
		return (int) (((arr[a] >>> off) | (arr[b] << firstBits)) & mask);
	}

	// ------------------------------------------------------------------ NBT -> SNBT text (for the /data and /summon commands)

	private static void writeSnbt(Object o, StringBuilder sb) {
		if (o instanceof Byte bv) {
			sb.append(bv).append('b');
		} else if (o instanceof Short sv) {
			sb.append(sv).append('s');
		} else if (o instanceof Integer iv) {
			sb.append(iv);
		} else if (o instanceof Long lv) {
			sb.append(lv).append('L');
		} else if (o instanceof Float fv) {
			sb.append(fv).append('f');
		} else if (o instanceof Double dv) {
			sb.append(dv).append('d');
		} else if (o instanceof String sv) {
			sb.append('"');
			for (int i = 0; i < sv.length(); i++) {
				char c = sv.charAt(i);
				if (c == '"' || c == '\\') sb.append('\\');
				sb.append(c);
			}
			sb.append('"');
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
		} else if (o instanceof List<?> l) {
			sb.append('[');
			for (int i = 0; i < l.size(); i++) {
				if (i > 0) sb.append(',');
				writeSnbt(l.get(i), sb);
			}
			sb.append(']');
		} else if (o instanceof Map<?, ?> m) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (!first) sb.append(',');
				String k = String.valueOf(e.getKey());
				if (k.matches("^[a-zA-Z0-9_.-]+$")) {
					sb.append(k);
				} else {
					writeSnbt(k, sb);
				}
				sb.append(':');
				writeSnbt(e.getValue(), sb);
				first = false;
			}
			sb.append('}');
		} else {
			sb.append(o);
		}
	}

	// ------------------------------------------------------------------ minimal NBT reader

	private static Object readNamedTag(DataInputStream in) throws IOException {
		byte type = in.readByte();
		if (type == 0) return null;
		in.readUTF(); // name
		return readPayload(type, in);
	}

	private static Object readPayload(byte type, DataInputStream in) throws IOException {
		return switch (type) {
			case 1 -> in.readByte();
			case 2 -> in.readShort();
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 5 -> in.readFloat();
			case 6 -> in.readDouble();
			case 7 -> {
				int len = in.readInt();
				byte[] b = new byte[len];
				in.readFully(b);
				yield b;
			}
			case 8 -> in.readUTF();
			case 9 -> {
				byte elemType = in.readByte();
				int count = in.readInt();
				List<Object> list = new ArrayList<>(Math.max(0, count));
				for (int i = 0; i < count; i++) list.add(readPayload(elemType, in));
				yield list;
			}
			case 10 -> {
				Map<String, Object> map = new TreeMap<>();
				byte childType;
				while ((childType = in.readByte()) != 0) {
					String key = in.readUTF();
					map.put(key, readPayload(childType, in));
				}
				yield map;
			}
			case 11 -> {
				int len = in.readInt();
				int[] ints = new int[len];
				for (int i = 0; i < len; i++) ints[i] = in.readInt();
				yield ints;
			}
			case 12 -> {
				int len = in.readInt();
				long[] longs = new long[len];
				for (int i = 0; i < len; i++) longs[i] = in.readLong();
				yield longs;
			}
			default -> throw new IOException("Unknown NBT tag type: " + type);
		};
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asList(Object o) {
		return o instanceof List<?> l ? (List<Object>) l : null;
	}

	private static int num(Object o) {
		return o instanceof Number n ? n.intValue() : 0;
	}

	private static double dbl(Object o) {
		return o instanceof Number n ? n.doubleValue() : 0.0;
	}
}
