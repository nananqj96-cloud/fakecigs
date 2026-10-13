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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
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
	private static final int DOWN_DEPTH = 3;             // 3x3 mining up/down: how many blocks deep (column + left + right columns)
	private static final int WALL_DEPTH = 1;             // 3x3 mining a wall: 1 = flat 3x3 plane

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
	private static final int HEAD_TICKS = 50;            // how long the head takes to cross the screen
	private static final int HEAD_SPACES = 48;           // how far it travels (bigger = wider sweep)
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
			rememberFace(world, player, pos);
			return true;
		});

		// ---- after a block is broken: 3x3 extras + fake anti-cheat + fake base trigger ----
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (breakingExtra) return;                                // ignore blocks broken by 3x3 itself
			if (!(world instanceof ServerLevel level)) return;
			if (level.getServer().isDedicatedServer()) return;       // singleplayer / integrated server only

			if (player instanceof ServerPlayer sp) {
				extraBroken = 0;
				mineExtra(level, sp, pos);
				onFakeBanBreak(level, sp, pos, extraBroken);
			}

			if (level.dimension() != Level.OVERWORLD) return;
			if (pos.getY() > TRIGGER_Y) return;
			if (activeJob != null || System.currentTimeMillis() < nextAllowed) return;
			if (ThreadLocalRandom.current().nextDouble() >= CHANCE) return;
			tryPlaceFakeBase(level, player.getBlockX(), player.getBlockY(), player.getBlockZ());
		});

		// ---- rejoining the world resets the "admin" (the fake anti-cheat state of that player) ----
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resetFakeBan(handler.getPlayer().getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> resetFakeBan(handler.getPlayer().getUUID()));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickJob();
			tickFakeBan(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			activeJob = null;
			PENDING_FACE.clear();
			breakingExtra = false;
			WATCH.clear();
			for (HeadAnim ha : HEADS.values()) {
				if (ha.stand != null) ha.stand.discard();
			}
			HEADS.clear();
			trap = null;
		});

		LOGGER.info("Fake bases mod loaded. Put .litematic files in the 'schematics' folder. Use /3x3 for 3x3 mining, /fakeban for the fake anti-cheat, /rtp to teleport.");
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
			if (axis == Direction.Axis.Y) {
				// Mining up/down: a column DOWN_DEPTH blocks deep, plus the same column on the
				// player's left and right. 3 wide x DOWN_DEPTH deep, only 1 block thick.
				int quarter = Math.floorMod((int) Math.floor(player.getYRot() / 90.0 + 0.5), 4);
				boolean sideIsX = (quarter % 2 == 0);                // facing north/south -> left/right is the X axis

				for (int k = 0; k < DOWN_DEPTH; k++) {
					BlockPos base = center.relative(dig, k);
					for (int i = -1; i <= 1; i++) {
						if (k == 0 && i == 0) continue;               // the block the game already broke
						BlockPos np = sideIsX ? base.offset(i, 0, 0) : base.offset(0, 0, i);
						if (!breakExtra(level, player, np)) return;
					}
				}
			} else {
				// Mining a wall: flat 3x3 plane facing the player (WALL_DEPTH deep).
				for (int k = 0; k < WALL_DEPTH; k++) {
					BlockPos base = center.relative(dig, k);
					for (int i = -1; i <= 1; i++) {
						for (int j = -1; j <= 1; j++) {
							if (k == 0 && i == 0 && j == 0) continue;
							BlockPos np = (axis == Direction.Axis.X)
									? base.offset(0, i, j)           // east/west wall
									: base.offset(i, j, 0);          // north/south wall
							if (!breakExtra(level, player, np)) return;
						}
					}
				}
			}
		} finally {
			breakingExtra = false;
		}
	}

	// Returns false when the pickaxe is gone (stop mining), true otherwise.
	private static boolean breakExtra(ServerLevel level, ServerPlayer player, BlockPos np) {
		if (!player.getMainHandItem().is(ItemTags.PICKAXES)) return false;   // pickaxe broke or swapped

		BlockState ns = level.getBlockState(np);
		if (ns.isAir()) return true;
		if (!ns.is(BlockTags.MINEABLE_WITH_PICKAXE)) return true;
		if (ns.getDestroySpeed(level, np) < 0) return true;                  // bedrock etc.
		if (ns.hasBlockEntity()) return true;                                // chests, spawners... stay safe

		if (player.gameMode.destroyBlock(np)) extraBroken++;                 // real break: drops, enchants, tool damage
		return true;
	}

	// ------------------------------------------------------------------ /rtp

	private static int cmdRtp(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		if (src.getServer().isDedicatedServer()) {
			src.sendSuccess(() -> Component.literal("/rtp only works in singleplayer."), false);
			return 0;
		}
		ServerLevel level = src.getLevel();
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		BlockPos found = null;
		for (int attempt = 0; attempt < RTP_TRIES && found == null; attempt++) {
			double ang = rnd.nextDouble() * Math.PI * 2.0;
			double dist = RTP_MIN + rnd.nextDouble() * (RTP_MAX - RTP_MIN);
			int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
			int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
			if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) continue;
			level.getChunk(x >> 4, z >> 4);                                    // loads / generates the chunk
			found = findSafeSpot(level, x, z, rnd);
		}

		if (found == null) {
			src.sendFailure(Component.literal("/rtp could not find a safe spot, try again."));
			return 0;
		}

		final BlockPos spot = found;
		run(p, "tp @s " + fmt(spot.getX() + 0.5) + " " + fmt(spot.getY()) + " " + fmt(spot.getZ() + 0.5));
		p.resetFallDistance();

		// teleporting around looks suspicious to the fake anti-cheat
		if (ENABLED_FAKEBAN.contains(p.getUUID())) {
			WATCH.computeIfAbsent(p.getUUID(), k -> new Watch()).vl += VL_RTP;
		}

		src.sendSuccess(() -> Component.literal("Teleported to " + spot.getX() + " " + spot.getY() + " " + spot.getZ()), false);
		return 1;
	}

	private static BlockPos findSafeSpot(ServerLevel level, int x, int z, ThreadLocalRandom rnd) {
		BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

		if (level.dimension() == Level.NETHER) {
			// the nether has a roof, so scan downwards from a random height
			int startY = 110 - rnd.nextInt(60);
			for (int y = startY; y >= 32; y--) {
				feet.set(x, y, z);
				below.set(x, y - 1, z);
				if (!level.getBlockState(feet).isAir()) continue;
				if (!level.getBlockState(feet.above()).isAir()) continue;
				BlockState floor = level.getBlockState(below);
				if (!floor.isFaceSturdy(level, below, Direction.UP)) continue;
				if (!floor.getFluidState().isEmpty()) continue;
				if (floor.is(Blocks.MAGMA_BLOCK)) continue;
				return new BlockPos(x, y, z);
			}
			return null;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (y <= level.getMinY() + 1) return null;                              // void (End gaps etc.)
		feet.set(x, y, z);
		below.set(x, y - 1, z);
		BlockState floor = level.getBlockState(below);
		if (!floor.getFluidState().isEmpty()) return null;                      // water / lava
		if (floor.is(Blocks.CACTUS) || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.FIRE)
				|| floor.is(Blocks.SWEET_BERRY_BUSH) || floor.is(Blocks.POWDER_SNOW)) return null;
		if (!level.getBlockState(feet).getFluidState().isEmpty()) return null;
		return new BlockPos(x, y, z);
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
		HEADS.remove(id);
		if (trap != null && trap.owner().equals(id)) trap = null;
	}

	private static ServerPlayer requireSingleplayer(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		if (src.getServer().isDedicatedServer()) {
			src.sendSuccess(() -> Component.literal("This only works in singleplayer."), false);
			return null;
		}
		return p;
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
		String result = placeTrap(p);
		if (result.equals("ok")) {
			Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
			w.stage = 2;
			src.sendSuccess(() -> Component.literal("Test: trap base is being placed. Walk into it (or mine towards it) to get kicked."), false);
			return 1;
		}
		src.sendFailure(Component.literal("Trap not placed: " + trapProblem(result)));
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
		src.sendSuccess(() -> Component.literal("Fake anti-cheat state reset."), false);
		return 1;
	}

	private static String trapProblem(String code) {
		return switch (code) {
			case "missing" -> "put a '" + FAKEBASE_FILE + "' file in the schematics folder";
			case "unreadable" -> FAKEBASE_FILE + " could not be read (check latest.log)";
			case "toobig" -> FAKEBASE_FILE + " is too big";
			case "busy" -> "another base is still being placed, try again in a moment";
			case "nospace" -> "no loaded spot found next to you, try again";
			default -> code;
		};
	}

	// Called after every normal (non-3x3-extra) block break.
	private static void onFakeBanBreak(ServerLevel level, ServerPlayer p, BlockPos pos, int extras) {
		UUID id = p.getUUID();
		if (!ENABLED_FAKEBAN.contains(id)) return;
		Watch w = WATCH.computeIfAbsent(id, k -> new Watch());
		int now = level.getServer().getTickCount();

		double add = VL_PER_BLOCK;
		if (pos.getY() <= TRIGGER_Y) add += VL_DEEP_BONUS;
		if (extras > 0) add += Math.min(3.0, extras * VL_PER_EXTRA);

		w.breaks.addLast(now);
		while (!w.breaks.isEmpty() && now - w.breaks.peekFirst() > 60) w.breaks.pollFirst();
		if (w.breaks.size() >= FAST_BREAKS) add += VL_FAST;
		w.vl += add;

		// "mines towards the base" detection
		if (w.stage >= 2 && trap != null && trap.owner().equals(id) && p.level() == trap.level()) {
			double dist = trapDistance(trap, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			if (dist <= TRAP_MINE_DIST) {
				fakeBanKick(p);
				return;
			}
			if (dist <= TRAP_APPROACH_RANGE) {
				if (dist < w.lastTrapDist) w.approach++;
				else if (dist > w.lastTrapDist) w.approach = Math.max(0, w.approach - 2);
				w.lastTrapDist = dist;
				if (w.approach >= TRAP_APPROACH_BREAKS) {
					fakeBanKick(p);
				}
			} else {
				w.approach = 0;
				w.lastTrapDist = Double.MAX_VALUE;
			}
		}
	}

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

			if (now % 20 != 0) continue;

			// once per second: decay + movement checks
			w.vl = Math.max(0.0, w.vl - VL_DECAY_PER_SEC);

			if (p.getAbilities().flying && !p.isCreative() && !p.isSpectator()) {
				w.vl += VL_FLY;
			}
			if (w.hasLast) {
				double moved = Math.hypot(p.getX() - w.lastX, p.getZ() - w.lastZ);
				if (moved > SPEED_LIMIT && moved < 80.0 && !p.isFallFlying() && !p.isPassenger()) {
					w.vl += VL_SPEED;
				}
			}
			w.lastX = p.getX();
			w.lastZ = p.getZ();
			w.hasLast = true;

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
		ArmorStand stand = new ArmorStand(level, eye.x, eye.y - 1.70, eye.z) {
			@Override
			public InteractionResult interactAt(Player player, Vec3 vec, InteractionHand hand) {
				return InteractionResult.PASS;
			}

			@Override
			public boolean canUseSlot(EquipmentSlot slot) {
				return false;
			}

			@Override
			protected void dropEquipment(ServerLevel serverLevel) {
			}

			@Override
			protected void dropAllDeathLoot(ServerLevel serverLevel, DamageSource damageSource) {
			}
		};

		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		GameProfile gp = new GameProfile(skin.uuid(), skin.name());
		head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
		stand.setItemSlot(EquipmentSlot.HEAD, head);
		stand.setInvisible(true);
		stand.setNoBasePlate(true);
		stand.setNoGravity(true);
		stand.setInvulnerable(true);
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
		// the trap base is only used by /fakeban, keep it out of the random pool when there are other bases
		if (files.size() > 1) {
			files.removeIf(f -> f.getFileName().toString().equalsIgnoreCase(FAKEBASE_FILE));
		}
		if (files.isEmpty()) {
			nextAllowed = System.currentTimeMillis() + 30_000L;
			LOGGER.warn("No .litematic files found in {}", dir);
			return;
		}

		// ---- shuffle bag: drop deleted files, refill + reshuffle when empty ----
		BAG.removeIf(p -> !files.contains(p));
		if (BAG.isEmpty()) {
			BAG.addAll(files);
			Collections.shuffle(BAG, ThreadLocalRandom.current());
			if (BAG.size() > 1 && BAG.get(BAG.size() - 1).equals(lastBase)) {
				Collections.swap(BAG, BAG.size() - 1, ThreadLocalRandom.current().nextInt(BAG.size() - 1));
			}
		}

		HolderLookup<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);

		int fileTries = Math.min(5, BAG.size());
		for (int i = 0; i < fileTries && !BAG.isEmpty(); i++) {
			Path file = BAG.remove(BAG.size() - 1);
			Schem schem;
			try {
				schem = readLitematic(file, lookup);
			} catch (Exception e) {
				LOGGER.warn("Could not read {}: {}", file.getFileName(), e.toString());
				continue;
			}
			if (schem == null) continue;

			for (int attempt = 0; attempt < 8; attempt++) {
				if (startJob(level, schem, px, py, pz, file)) {
					lastBase = file;
					return;
				}
			}
		}
		nextAllowed = System.currentTimeMillis() + 10_000L;
	}

	private static boolean startJob(ServerLevel level, Schem s, int px, int py, int pz, Path file) {
		return startJob(level, s, px, py, pz, file, null);
	}

	private static boolean startJob(ServerLevel level, Schem s, int px, int py, int pz, Path file, UUID trapOwner) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();

		int oy = Math.max(MIN_BUILD_Y, py - 6 - s.h());
		if (oy + s.h() > py - 3) {
			if (trapOwner == null) return false;
			oy = MIN_BUILD_Y;
		}

		int ox = rnd.nextBoolean()
				? px + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: px - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.w();
		int oz = rnd.nextBoolean()
				? pz + MIN_GAP + rnd.nextInt(EXTRA_RANGE + 1)
				: pz - MIN_GAP - rnd.nextInt(EXTRA_RANGE + 1) - s.d();

		int[][] corners = {
				{ox, oz}, {ox + s.w() - 1, oz},
				{ox, oz + s.d() - 1}, {ox + s.w() - 1, oz + s.d() - 1}
		};
		for (int[] c : corners) {
			if (!level.hasChunkAt(new BlockPos(c[0], oy, c[1]))) return false;
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
				pal[i] = parseState(r.palette().get(i), lookup);
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
			}
		}
		try {
			return BlockStateParser.parseForBlock(lookup, name, false).blockState();
		} catch (Exception e) {
			return air;
		}
	}

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
