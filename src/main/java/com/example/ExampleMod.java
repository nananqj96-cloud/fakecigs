package com.example;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer, ClientModInitializer {
	public static final String MOD_ID = "modid";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// ===============================================================================
	// CONFIGURATION - change these numbers if you want
	// ===============================================================================
	private static final int TRIGGER_Y = -40;            // only used by the fake anti-cheat's deep mining bonus (unchanged)
	private static final int BASE_TRIGGER_Y = -10;       // random fake bases can spawn when mining BELOW this Y
	private static final double CHANCE = 1.0;            // 100% chance per block mined (spawns when not on cooldown)
	private static final long COOLDOWN_MS = 60_000L;     // wait at least 1 minute (60 seconds) between fake bases
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
	private static int extraBroken = 0;

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

	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED SQUARE CONFIGURATION & STATE
	// ===============================================================================
	public static boolean susChunksEnabled = true;
	public static boolean susAutoSpawn = true;    // automatically spawns a random sus chunk every 30s
	private static int susAutoTimer = 0;          // counts ticks (600 ticks = 30 seconds)
	public static int susColorIndex = 0;          // 0=Cyan (default matching screenshot), 1=Green, 2=Red, 3=Purple, 4=Yellow, 5=Orange, 6=White, 7=Rainbow
	public static int susOpacityIndex = 2;        // 0=25%, 1=50%, 2=75%, 3=100%
	public static int susCurvinessIndex = 2;      // 0=Sharp (0°), 1=Low (R=1.5), 2=Medium (R=3.0), 3=High (R=5.0), 4=Circular (R=7.5)
	public static int susHeightIndex = 0;         // 0=Flush Ground (+0.02), 1=Raised (+0.25), 2=Hover (+0.50), 3=Waist (+1.00)

	public static final String[] SUS_COLORS = {"Cyan (Default)", "Green", "Red", "Purple", "Yellow", "Orange", "White", "Rainbow (Chroma)"};
	public static final float[][] SUS_COLOR_RGB = {
			{0.00f, 0.85f, 1.00f}, // Cyan (#00D9FF)
			{0.15f, 0.95f, 0.35f}, // Green (#26F259)
			{1.00f, 0.20f, 0.25f}, // Red (#FF3340)
			{0.75f, 0.25f, 1.00f}, // Purple (#BF40FF)
			{1.00f, 0.90f, 0.10f}, // Yellow (#FFE61A)
			{1.00f, 0.55f, 0.05f}, // Orange (#FF8C0D)
			{1.00f, 1.00f, 1.00f}  // White (#FFFFFF)
	};
	public static final String[] SUS_OPACITIES = {"25%", "50%", "75%", "100%"};
	public static final float[] SUS_OPACITY_VALUES = {0.25f, 0.50f, 0.75f, 1.00f};
	public static final String[] SUS_CURVINESS = {"Sharp (0°)", "Low (R=1.5)", "Medium (R=3.0)", "High (R=5.0)", "Circular (R=7.5)"};
	public static final float[] SUS_CURVE_VALUES = {0.0f, 1.5f, 3.0f, 5.0f, 7.5f};
	public static final String[] SUS_HEIGHTS = {"Low (+0.10)", "Raised (+0.30)", "Hover (+0.60)", "Waist (+1.00)"};
	public static final double[] SUS_ELEVATION_OFFSETS = {0.10, 0.30, 0.60, 1.00};

	public static final class SusSpot {
		public final int chunkX;
		public final int chunkZ;
		public final double minX;
		public final double maxX;
		public final double minZ;
		public final double maxZ;
		public final double centerX;
		public final double centerZ;
		public double cachedGroundY = Double.NaN;

		public SusSpot(int chunkX, int chunkZ) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.minX = chunkX * 16.0;
			this.maxX = this.minX + 16.0;
			this.minZ = chunkZ * 16.0;
			this.maxZ = this.minZ + 16.0;
			this.centerX = this.minX + 8.0;
			this.centerZ = this.minZ + 8.0;
		}
	}

	public static final List<SusSpot> SUS_SPOTS = new ArrayList<>();

	public static void randomizeSusSpots() {
		SUS_SPOTS.clear();
		Minecraft client = Minecraft.getInstance();
		double px = 0;
		double pz = 0;
		if (client.player != null) {
			px = client.player.getX();
			pz = client.player.getZ();
		}
		int pChunkX = ((int) Math.floor(px)) >> 4;
		int pChunkZ = ((int) Math.floor(pz)) >> 4;

		// Spawn 4 visible sus chunks 1 to 2 chunks away from the player
		int[][] offsets = {
				{1, 0},
				{-1, 1},
				{1, -2},
				{-2, -1}
		};
		for (int[] off : offsets) {
			SUS_SPOTS.add(new SusSpot(pChunkX + off[0], pChunkZ + off[1]));
		}
		LOGGER.info("[SusChunks] Generated {} sus chunks near player chunk [{}, {}]", SUS_SPOTS.size(), pChunkX, pChunkZ);
	}

	// ------------------------------------------------------------------ ClickGUI System
	public static final class GuiCommand {
		public final String command;
		public final String label;
		public final String description;
		public final boolean isToggle;

		public GuiCommand(String command, String label, String description, boolean isToggle) {
			this.command = command;
			this.label = label;
			this.description = description;
			this.isToggle = isToggle;
		}
	}

	public static final List<GuiCommand> REGISTERED_COMMANDS = new ArrayList<>();
	private static KeyMapping guiKeyMapping = null;
	private static boolean clientInitialized = false;

	@Override
	public void onInitialize() {
		// Populate initial known commands
		registerDefaultGuiCommands();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			Set<String> before = new HashSet<>();
			for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
				before.add(node.getName());
			}

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

			// ---- /fakebase : TEST COMMAND ----
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

			// ---- /suschunks : Fake Sus Chunks 2D rounded square overlay ----
			dispatcher.register(Commands.literal("suschunks")
					.executes(ctx -> {
						susChunksEnabled = !susChunksEnabled;
						if (susChunksEnabled && SUS_SPOTS.isEmpty()) {
							randomizeSusSpots();
						}
						susAutoTimer = 0;
						ctx.getSource().sendSuccess(
								() -> Component.literal("Fake Sus Chunks overlay: " + (susChunksEnabled ? "§aENABLED" : "§cDISABLED")),
								false
						);
						return 1;
					})
					.then(Commands.literal("auto").executes(ctx -> {
						susAutoSpawn = !susAutoSpawn;
						susAutoTimer = 0;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Auto-Spawn (30s): " + (susAutoSpawn ? "§aENABLED" : "§cDISABLED")), false);
						return 1;
					}))
					.then(Commands.literal("color").executes(ctx -> {
						susColorIndex = (susColorIndex + 1) % SUS_COLORS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Color: §e" + SUS_COLORS[susColorIndex]), false);
						return 1;
					}))
					.then(Commands.literal("opacity").executes(ctx -> {
						susOpacityIndex = (susOpacityIndex + 1) % SUS_OPACITIES.length;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Opacity: §e" + SUS_OPACITIES[susOpacityIndex]), false);
						return 1;
					}))
					.then(Commands.literal("curviness").executes(ctx -> {
						susCurvinessIndex = (susCurvinessIndex + 1) % SUS_CURVINESS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Curviness: §e" + SUS_CURVINESS[susCurvinessIndex]), false);
						return 1;
					}))
					.then(Commands.literal("height").executes(ctx -> {
						susHeightIndex = (susHeightIndex + 1) % SUS_HEIGHTS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Height: §e" + SUS_HEIGHTS[susHeightIndex] + " Blocks"), false);
						return 1;
					}))
					.then(Commands.literal("random").executes(ctx -> {
						randomizeSusSpots();
						ctx.getSource().sendSuccess(() -> Component.literal("Generated new random Sus Chunk locations near you."), false);
						return 1;
					}))
			);

			// Auto-discover all mod commands (including any command added in the future!)
			for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
				if (!before.contains(node.getName())) {
					autoDiscoverNode(node, "");
				}
			}
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
			if (breakingExtra) return;
			if (!(world instanceof ServerLevel level)) return;
			if (level.getServer().isDedicatedServer()) return;

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
			if (pos.getY() >= BASE_TRIGGER_Y) return;

			if (CHANCE < 1.0 && ThreadLocalRandom.current().nextDouble() >= CHANCE) return;

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

		LOGGER.info("Fake bases mod loaded. Press [INSERT] to open ClickGUI. Use /fakebase to test, /3x3 for 3x3 mining, /fakeban for fake anti-cheat, /rtp to teleport.");
	}

	@Override
	public void onInitializeClient() {
		initClient();
	}

	private static synchronized void initClient() {
		if (clientInitialized) return;
		clientInitialized = true;
		initWorldRendering();

		try {
			KeyMapping.Category guiCategory = getGuiCategory();
			guiKeyMapping = registerKeyMapping(createGuiKeyMapping(guiCategory));
		} catch (Throwable t) {
			LOGGER.warn("Failed to register Insert keymapping: {}", t.toString());
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (guiKeyMapping != null) {
				while (guiKeyMapping.consumeClick()) {
					if (client.screen == null) {
						client.setScreen(new ClickGuiScreen());
					} else if (client.screen instanceof ClickGuiScreen) {
						client.setScreen(null);
					}
				}
			}

			if (!worldRendererInitialized) {
				initWorldRendering();
			}

			// Welcome message on first world load
			if (!hasShownWelcome && client.player != null && client.level != null) {
				hasShownWelcome = true;
				client.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §aActive! (Spots visible nearby | Auto-spawn 30s: ON | [Insert] for GUI)"),
						false
				);
			}

			// Automatically spawn a new random sus chunk spot every 30 seconds (600 ticks)
			if (client.level != null && client.player != null) {
				if (susChunksEnabled && susAutoSpawn) {
					susAutoTimer++;
					if (susAutoTimer >= 600) {
						susAutoTimer = 0;
						SusSpot newSpot = addRandomSusSpotNear(client.player.getX(), client.player.getZ());
						if (newSpot != null) {
							client.player.displayClientMessage(
									Component.literal("§d[🔮 Sus Chunks] §aNew sus chunk spawned at chunk [" 
											+ newSpot.chunkX + ", " + newSpot.chunkZ + "]! (X: " + (int)newSpot.centerX + ", Z: " + (int)newSpot.centerZ + ")"),
									true
							);
							client.player.displayClientMessage(
									Component.literal("§d[🔮 Sus Chunks] §e[30s Auto-Spawn] §aAdded new sus chunk at X=" + (int)newSpot.centerX + " Z=" + (int)newSpot.centerZ),
									false
							);
						}
					}
				} else {
					susAutoTimer = 0;
				}
			}
		});
	}

	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED SQUARE OVERLAY RENDERING (NO PARTICLES, PASSABLE)
	// ===============================================================================
	private static boolean worldRendererInitialized = false;
	private static boolean hasLoggedRender = false;
	private static boolean hasShownWelcome = false;

	private static void initWorldRendering() {
		if (worldRendererInitialized) return;
		try {
			Class<?> eventsClass = null;
			String[] candidateClassNames = {
					"net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents",
					"net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents"
			};
			for (String name : candidateClassNames) {
				try {
					eventsClass = Class.forName(name);
					if (eventsClass != null) break;
				} catch (Throwable ignored) {}
			}
			if (eventsClass == null) {
				LOGGER.warn("[SusChunks] WorldRenderEvents class not found!");
				return;
			}

			String[] eventFieldNames = {
					"AFTER_ENTITIES",
					"BEFORE_TRANSLUCENT",
					"END_MAIN"
			};

			Field targetField = null;
			Object eventObj = null;
			Class<?> listenerType = null;

			for (String fName : eventFieldNames) {
				try {
					Field f = eventsClass.getField(fName);
					if (f != null) {
						Object obj = f.get(null);
						if (obj != null) {
							// Match inner interface
							String cleanName = fName.replace("_", "").toLowerCase(Locale.ROOT);
							for (Class<?> inner : eventsClass.getDeclaredClasses()) {
								if (inner.isInterface() && inner.getSimpleName().toLowerCase(Locale.ROOT).equals(cleanName)) {
									targetField = f;
									eventObj = obj;
									listenerType = inner;
									break;
								}
							}
							if (listenerType == null) {
								java.lang.reflect.Type gType = f.getGenericType();
								if (gType instanceof java.lang.reflect.ParameterizedType pt) {
									java.lang.reflect.Type[] pArgs = pt.getActualTypeArguments();
									if (pArgs.length > 0 && pArgs[0] instanceof Class<?> c && c.isInterface()) {
										targetField = f;
										eventObj = obj;
										listenerType = c;
									}
								}
							}
							if (listenerType != null) break;
						}
					}
				} catch (Throwable ignored) {}
			}

			if (targetField == null || eventObj == null || listenerType == null) {
				LOGGER.warn("[SusChunks] Could not find valid WorldRenderEvents target field or interface!");
				return;
			}

			Method regMethod = null;
			for (Method m : eventObj.getClass().getMethods()) {
				if (m.getName().equals("register") && m.getParameterCount() == 1) {
					regMethod = m;
					break;
				}
			}
			if (regMethod == null) return;

			Class<?> finalListenerType = listenerType;
			Object proxyListener = Proxy.newProxyInstance(
					eventsClass.getClassLoader(),
					new Class<?>[]{finalListenerType},
					(proxy, method, args) -> {
						if (method.getName().equals("toString")) return "SusChunksListener";
						if (method.getName().equals("hashCode")) return 42;
						if (method.getName().equals("equals")) return proxy == (args != null && args.length > 0 ? args[0] : null);
						if (args != null && args.length > 0) {
							renderSusChunksWorld(args[0]);
						}
						return null;
					}
			);
			regMethod.invoke(eventObj, proxyListener);
			worldRendererInitialized = true;
			LOGGER.info("[SusChunks] World render overlay registered successfully on: {} with interface: {}",
					targetField.getName(), finalListenerType.getName());
		} catch (Throwable t) {
			LOGGER.warn("[SusChunks] Could not hook world render events: {}", t.toString());
		}
	}

	private static void renderSusChunksWorld(Object context) {
		if (!susChunksEnabled) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;

		// Ensure spots are populated and near the player
		if (SUS_SPOTS.isEmpty()) {
			randomizeSusSpots();
		} else {
			boolean anyNear = false;
			for (SusSpot sp : SUS_SPOTS) {
				if (client.player.distanceToSqr(sp.centerX, client.player.getY(), sp.centerZ) <= 160.0 * 160.0) {
					anyNear = true;
					break;
				}
			}
			if (!anyNear) {
				randomizeSusSpots();
			}
		}

		PoseStack poseStack = null;
		try {
			Method m = context.getClass().getMethod("matrices");
			m.setAccessible(true);
			poseStack = (PoseStack) m.invoke(context);
		} catch (Throwable t1) {
			try {
				Method m = context.getClass().getMethod("matrixStack");
				m.setAccessible(true);
				poseStack = (PoseStack) m.invoke(context);
			} catch (Throwable ignored) {}
		}
		if (poseStack == null) {
			poseStack = new PoseStack();
		}

		Vec3 camPos = null;
		try {
			Method cm = context.getClass().getMethod("camera");
			cm.setAccessible(true);
			Object cam = cm.invoke(context);
			if (cam != null) {
				camPos = extractCameraPos(cam);
			}
		} catch (Throwable ignored) {}

		if (camPos == null) {
			try {
				Object cam = client.gameRenderer.getMainCamera();
				if (cam != null) {
					camPos = extractCameraPos(cam);
				}
			} catch (Throwable ignored) {}
		}

		if (camPos == null && client.player != null) {
			camPos = client.player.getEyePosition();
		}
		if (camPos == null) return;

		MultiBufferSource consumers = null;
		try {
			Method m = context.getClass().getMethod("consumers");
			m.setAccessible(true);
			consumers = (MultiBufferSource) m.invoke(context);
		} catch (Throwable ignored) {}

		if (consumers == null && client.renderBuffers() != null) {
			consumers = client.renderBuffers().bufferSource();
		}
		if (consumers == null) return;

		Object renderType = getOverlayRenderType(consumers);
		if (renderType == null) return;

		VertexConsumer consumer = null;
		try {
			for (Method m : consumers.getClass().getMethods()) {
				if (m.getName().equals("getBuffer") && m.getParameterCount() == 1) {
					if (m.getParameterTypes()[0].isInstance(renderType)) {
						m.setAccessible(true);
						consumer = (VertexConsumer) m.invoke(consumers, renderType);
						break;
					}
				}
			}
		} catch (Throwable ignored) {}

		if (consumer == null) {
			for (Method m : consumers.getClass().getMethods()) {
				if (m.getName().equals("getBuffer") && m.getParameterCount() == 1) {
					try {
						m.setAccessible(true);
						consumer = (VertexConsumer) m.invoke(consumers, renderType);
						if (consumer != null) break;
					} catch (Throwable ignored) {}
				}
			}
		}
		if (consumer == null) return;

		if (!hasLoggedRender) {
			hasLoggedRender = true;
			LOGGER.info("[SusChunks] Overlay rendering active! Total spots: {}", SUS_SPOTS.size());
		}

		// Color calculation
		float r, g, b;
		if (susColorIndex == 7) { // Rainbow
			float hue = (float) ((System.currentTimeMillis() % 4000L) / 4000.0);
			int rgb = Color.HSBtoRGB(hue, 0.9f, 1.0f);
			r = ((rgb >> 16) & 0xFF) / 255.0f;
			g = ((rgb >> 8) & 0xFF) / 255.0f;
			b = (rgb & 0xFF) / 255.0f;
		} else {
			r = SUS_COLOR_RGB[susColorIndex][0];
			g = SUS_COLOR_RGB[susColorIndex][1];
			b = SUS_COLOR_RGB[susColorIndex][2];
		}

		float opacity = SUS_OPACITY_VALUES[susOpacityIndex];
		float curveRadius = SUS_CURVE_VALUES[susCurvinessIndex];
		double elevationOffset = SUS_ELEVATION_OFFSETS[Math.min(susHeightIndex, 3)];

		// Translucent interior fill RGBA (matching image-1.png glowing translucent cyan quad)
		int fillR = (int) (r * 255.0f);
		int fillG = (int) (g * 255.0f);
		int fillB = (int) (b * 255.0f);
		int fillA = Math.min(255, Math.max(45, (int) (opacity * 0.55f * 255.0f)));

		// Prominent border outline RGBA
		int borderR = Math.min(255, (int) (r * 1.15f * 255.0f));
		int borderG = Math.min(255, (int) (g * 1.15f * 255.0f));
		int borderB = Math.min(255, (int) (b * 1.15f * 255.0f));
		int borderA = Math.min(255, Math.max(90, (int) (opacity * 0.95f * 255.0f)));

		double borderWidth = 0.35;

		// Generate relative perimeter points centered at origin (0, 0)
		List<double[]> outerPoints = get2DRoundedRectPoints(
				-8.0, 8.0,
				-8.0, 8.0,
				curveRadius, 8
		);

		double innerR = Math.max(0.0, curveRadius - borderWidth);
		List<double[]> innerPoints = get2DRoundedRectPoints(
				-8.0 + borderWidth, 8.0 - borderWidth,
				-8.0 + borderWidth, 8.0 - borderWidth,
				(float) innerR, 8
		);

		int count = outerPoints.size();

		for (int s = 0; s < SUS_SPOTS.size(); s++) {
			SusSpot spot = SUS_SPOTS.get(s);
			double relX = spot.centerX - camPos.x;
			double relZ = spot.centerZ - camPos.z;
			double distSq = relX * relX + relZ * relZ;
			if (distSq > 256.0 * 256.0) continue; // visible up to 256 blocks (16 chunks)

			// Determine ground Y for this chunk spot
			double groundY = getGroundYForSpot(client.level, client.player, spot) + elevationOffset;
			double relY = groundY - camPos.y;

			poseStack.pushPose();
			poseStack.translate(relX, relY, relZ);
			Matrix4f matrix = poseStack.last().pose();

			// 1. Draw Translucent Filled Interior (Triangle Fan from local center 0, 0)
			for (int i = 0; i < count; i++) {
				double[] p1 = outerPoints.get(i);
				double[] p2 = outerPoints.get((i + 1) % count);

				// Front face (visible from above)
				drawQuad(consumer, matrix,
						0.0, 0.0, 0.0,
						p1[0], 0.0, p1[1],
						p2[0], 0.0, p2[1],
						0.0, 0.0, 0.0,
						fillR, fillG, fillB, fillA);

				// Reverse face (visible from underneath)
				drawQuad(consumer, matrix,
						0.0, 0.0, 0.0,
						p2[0], 0.0, p2[1],
						p1[0], 0.0, p1[1],
						0.0, 0.0, 0.0,
						fillR, fillG, fillB, fillA);
			}

			// 2. Draw Prominent Glowing Border Ribbon Outline
			for (int i = 0; i < count; i++) {
				double[] p1 = outerPoints.get(i);
				double[] p2 = outerPoints.get((i + 1) % count);
				double[] ip1 = innerPoints.get(i);
				double[] ip2 = innerPoints.get((i + 1) % count);

				// Top face border ribbon
				drawQuad(consumer, matrix,
					p1[0], 0.005, p1[1],
					p2[0], 0.005, p2[1],
					ip2[0], 0.005, ip2[1],
					ip1[0], 0.005, ip1[1],
					borderR, borderG, borderB, borderA);

				// Bottom face border ribbon
				drawQuad(consumer, matrix,
					ip1[0], 0.005, ip1[1],
					ip2[0], 0.005, ip2[1],
					p2[0], 0.005, p2[1],
					p1[0], 0.005, p1[1],
					borderR, borderG, borderB, borderA);
			}

			poseStack.popPose();
		}

		// Flush batch to GPU so it renders immediately
		try {
			for (Method m : consumers.getClass().getMethods()) {
				if (m.getName().equals("endBatch") && m.getParameterCount() == 1) {
					if (m.getParameterTypes()[0].isInstance(renderType)) {
						m.setAccessible(true);
						m.invoke(consumers, renderType);
						break;
					}
				}
			}
		} catch (Throwable t) {
			try {
				for (Method m : consumers.getClass().getMethods()) {
					if (m.getName().equals("endBatch") && m.getParameterCount() == 0) {
						m.setAccessible(true);
						m.invoke(consumers);
						break;
					}
				}
			} catch (Throwable ignored) {}
		}
	}

	private static Vec3 extractCameraPos(Object cam) {
		try {
			Method m = cam.getClass().getMethod("position");
			m.setAccessible(true);
			return (Vec3) m.invoke(cam);
		} catch (Throwable t1) {
			try {
				Method m = cam.getClass().getMethod("getPosition");
				m.setAccessible(true);
				return (Vec3) m.invoke(cam);
			} catch (Throwable ignored) {}
		}
		return null;
	}

	private static void drawQuad(VertexConsumer consumer, Matrix4f matrix,
								 double x1, double y1, double z1,
								 double x2, double y2, double z2,
								 double x3, double y3, double z3,
								 double x4, double y4, double z4,
								 int r, int g, int b, int a) {
		emitVertex(consumer, matrix, x1, y1, z1, r, g, b, a);
		emitVertex(consumer, matrix, x2, y2, z2, r, g, b, a);
		emitVertex(consumer, matrix, x3, y3, z3, r, g, b, a);
		emitVertex(consumer, matrix, x4, y4, z4, r, g, b, a);
	}

	private static void emitVertex(VertexConsumer consumer, Matrix4f mat, double x, double y, double z, int r, int g, int b, int a) {
		try {
			consumer.addVertex(mat, (float) x, (float) y, (float) z).setColor(r, g, b, a);
			return;
		} catch (Throwable ignored) {}

		try {
			consumer.addVertex((float) x, (float) y, (float) z).setColor(r, g, b, a);
			return;
		} catch (Throwable ignored) {}
	}

	private static Object getOverlayRenderType(MultiBufferSource consumers) {
		Class<?> targetType = null;
		if (consumers != null) {
			for (Method m : consumers.getClass().getMethods()) {
				if (m.getName().equals("getBuffer") && m.getParameterCount() == 1) {
					Class<?> p = m.getParameterTypes()[0];
					if (p != Object.class) {
						targetType = p;
						break;
					}
				}
			}
		}

		List<String> candidateClasses = new ArrayList<>();
		if (targetType != null) {
			candidateClasses.add(targetType.getName());
			String pkg = targetType.getPackageName();
			candidateClasses.add(pkg + ".RenderTypes");
			candidateClasses.add(pkg + ".RenderType");
		}
		candidateClasses.addAll(Arrays.asList(
				"net.minecraft.client.renderer.rendertype.RenderTypes",
				"net.minecraft.client.renderer.rendertype.RenderType",
				"net.minecraft.client.renderer.RenderTypes",
				"net.minecraft.client.renderer.RenderType"
		));

		String[] methodNames = {"debugQuads", "lightning", "lines", "translucent", "tripwire", "cutout"};

		for (String clsName : candidateClasses) {
			try {
				Class<?> cls = Class.forName(clsName);
				for (String mName : methodNames) {
					try {
						Method m = cls.getMethod(mName);
						m.setAccessible(true);
						Object res = m.invoke(null);
						if (res != null) {
							if (targetType == null || targetType.isInstance(res)) {
								return res;
							}
						}
					} catch (Throwable ignored) {}
				}
			} catch (Throwable ignored) {}
		}

		if (targetType != null) {
			for (String clsName : candidateClasses) {
				try {
					Class<?> cls = Class.forName(clsName);
					for (Method m : cls.getMethods()) {
						if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
							if (targetType.isAssignableFrom(m.getReturnType())) {
								try {
									m.setAccessible(true);
									Object res = m.invoke(null);
									if (res != null) return res;
								} catch (Throwable ignored) {}
							}
						}
					}
				} catch (Throwable ignored) {}
			}
		}

		return null;
	}

	private static double getGroundYForSpot(ClientLevel level, Player player, SusSpot spot) {
		if (!Double.isNaN(spot.cachedGroundY)) {
			return spot.cachedGroundY;
		}

		int bx = (int) Math.floor(spot.centerX);
		int bz = (int) Math.floor(spot.centerZ);
		int py = (int) Math.floor(player.getY());

		// 1. Surface heightmap check (standard world surface matching image-1.png)
		try {
			int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			if (groundY > level.getMinY()) {
				if (Math.abs(py - groundY) <= 40 || py >= 50) {
					spot.cachedGroundY = groundY + 0.02;
					return spot.cachedGroundY;
				}
			}
		} catch (Throwable ignored) {}

		// 2. If underground, scan down from player level to find local floor
		int startY = Math.min(level.getMaxY() - 1, py + 5);
		int minY = Math.max(level.getMinY(), py - 35);
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(bx, startY, bz);
		for (int y = startY; y >= minY; y--) {
			mpos.setY(y);
			BlockState state = level.getBlockState(mpos);
			if (!state.isAir()) {
				spot.cachedGroundY = y + 1.02;
				return spot.cachedGroundY;
			}
		}

		// 3. Fallback to surface heightmap
		try {
			int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
			if (groundY > level.getMinY()) {
				spot.cachedGroundY = groundY + 0.02;
				return spot.cachedGroundY;
			}
		} catch (Throwable ignored) {}

		return player.getY() + 0.02;
	}

	private static List<double[]> get2DRoundedRectPoints(double minX, double maxX, double minZ, double maxZ, float radius, int stepsPerCorner) {
		List<double[]> points = new ArrayList<>();
		double hw = (maxX - minX) / 2.0;
		double hl = (maxZ - minZ) / 2.0;
		double r = Math.min(radius, Math.min(hw, hl) - 0.1);

		if (r <= 0.1) {
			points.add(new double[]{minX, minZ});
			points.add(new double[]{maxX, minZ});
			points.add(new double[]{maxX, maxZ});
			points.add(new double[]{minX, maxZ});
			return points;
		}

		// Corner arc centers
		double[][] centers = {
				{maxX - r, maxZ - r, 0.0},               // Corner 0: top-right (0 to PI/2)
				{minX + r, maxZ - r, Math.PI / 2.0},     // Corner 1: top-left (PI/2 to PI)
				{minX + r, minZ + r, Math.PI},           // Corner 2: bottom-left (PI to 3PI/2)
				{maxX - r, minZ + r, 3.0 * Math.PI / 2.0}// Corner 3: bottom-right (3PI/2 to 2PI)
		};

		for (double[] c : centers) {
			double cx = c[0];
			double cz = c[1];
			double startAngle = c[2];
			for (int s = 0; s < stepsPerCorner; s++) {
				double angle = startAngle + (s / (double) stepsPerCorner) * (Math.PI / 2.0);
				points.add(new double[]{cx + Math.cos(angle) * r, cz + Math.sin(angle) * r});
			}
		}

		return points;
	}

	public static SusSpot addRandomSusSpotNear(double px, double pz) {
		int baseChunkX = ((int) Math.floor(px)) >> 4;
		int baseChunkZ = ((int) Math.floor(pz)) >> 4;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int dx = (rnd.nextBoolean() ? 1 : -1) * rnd.nextInt(1, 4);
		int dz = (rnd.nextBoolean() ? 1 : -1) * rnd.nextInt(1, 4);
		SusSpot spot = new SusSpot(baseChunkX + dx, baseChunkZ + dz);
		SUS_SPOTS.add(spot);
		if (SUS_SPOTS.size() > 12) {
			SUS_SPOTS.remove(0);
		}
		LOGGER.info("[SusChunks] Added new random sus chunk at chunk [{}, {}]", spot.chunkX, spot.chunkZ);
		return spot;
	}

	private static KeyMapping.Category getGuiCategory() {
		try {
			Method m = KeyMapping.Category.class.getMethod("register", Identifier.class);
			return (KeyMapping.Category) m.invoke(null, id("gui"));
		} catch (Throwable t1) {
			try {
				Constructor<KeyMapping.Category> ctor = KeyMapping.Category.class.getConstructor(Identifier.class);
				return ctor.newInstance(id("gui"));
			} catch (Throwable t2) {
				return KeyMapping.Category.MISC;
			}
		}
	}

	private static KeyMapping createGuiKeyMapping(KeyMapping.Category category) {
		int insertKey = InputConstants.KEY_INSERT; // 260

		// Modern 26.3 constructor: KeyMapping(String, int, KeyMapping.Category)
		try {
			Constructor<KeyMapping> ctor = KeyMapping.class.getConstructor(String.class, int.class, KeyMapping.Category.class);
			return ctor.newInstance("key." + MOD_ID + ".gui", insertKey, category);
		} catch (Throwable ignored) {}

		// Pre-26.3 constructor: KeyMapping(String, InputConstants.Type, int, KeyMapping.Category)
		try {
			InputConstants.Type kbType = null;
			for (InputConstants.Type t : InputConstants.Type.values()) {
				String name = t.name();
				if (name.equals("KEYBOARD") || name.equals("KEY") || name.equals("KEYSYM")) {
					kbType = t;
					break;
				}
			}
			if (kbType == null && InputConstants.Type.values().length > 0) {
				kbType = InputConstants.Type.values()[0];
			}
			Constructor<KeyMapping> ctor = KeyMapping.class.getConstructor(String.class, InputConstants.Type.class, int.class, KeyMapping.Category.class);
			return ctor.newInstance("key." + MOD_ID + ".gui", kbType, insertKey, category);
		} catch (Throwable ignored) {}

		return null;
	}

	private static KeyMapping registerKeyMapping(KeyMapping mapping) {
		if (mapping == null) return null;
		// 1. Try modern Fabric API (26.1+): KeyMappingHelper.registerKeyMapping
		try {
			Class<?> kmHelper = Class.forName("net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper");
			Method m = kmHelper.getMethod("registerKeyMapping", KeyMapping.class);
			return (KeyMapping) m.invoke(null, mapping);
		} catch (Throwable ignored) {}

		// 2. Try legacy Fabric API (<26.1): KeyBindingHelper.registerKeyBinding
		try {
			Class<?> kbHelper = Class.forName("net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper");
			Method m = kbHelper.getMethod("registerKeyBinding", KeyMapping.class);
			return (KeyMapping) m.invoke(null, mapping);
		} catch (Throwable ignored) {}

		return mapping;
	}

	private static void registerDefaultGuiCommands() {
		addGuiCommand("3x3", "3x3 Mining", "Toggles 3x3 pickaxe mining mode (hold sneak to mine 1 block)", true);
		addGuiCommand("fakeban", "Fake Anti-Cheat", "Toggles fake anti-cheat watcher and traps", true);
		addGuiCommand("fakebase", "Spawn Fake Base", "Forces a random fake base schematic to paste near you", false);
		addGuiCommand("rtp", "Random Teleport", "Teleports you to a random safe surface location", false);
		addGuiCommand("fakeban status", "Anti-Cheat Status", "Prints your violation level and stage into chat", false);
		addGuiCommand("fakeban watch", "Trigger Watch Scare", "Simulates an admin watching you with the head jumpscare", false);
		addGuiCommand("fakeban trap", "Spawn Trap Base", "Pastes the fakeban trap base near you", false);
		addGuiCommand("fakeban kick", "Test Fake Ban Kick", "Disconnects you with a fake permanent ban screen", false);
		addGuiCommand("fakeban reset", "Reset Violations", "Resets your fake anti-cheat violation level to 0", false);
		addGuiCommand("suschunks", "Sus Chunks (Fake)", "Toggles curved box overlay in random places at ground level", true);
	}

	private static void addGuiCommand(String cmd, String label, String desc, boolean isToggle) {
		for (GuiCommand c : REGISTERED_COMMANDS) {
			if (c.command.equalsIgnoreCase(cmd)) return;
		}
		REGISTERED_COMMANDS.add(new GuiCommand(cmd, label, desc, isToggle));
	}

	private static void autoDiscoverNode(CommandNode<CommandSourceStack> node, String prefix) {
		String cmdName = prefix.isEmpty() ? node.getName() : prefix + " " + node.getName();
		if (node.getCommand() != null) {
			boolean isToggle = cmdName.equalsIgnoreCase("3x3") || cmdName.equalsIgnoreCase("fakeban")
					|| cmdName.equalsIgnoreCase("suschunks") || cmdName.toLowerCase(Locale.ROOT).contains("toggle");
			addGuiCommand(cmdName, formatCmdLabel(cmdName), "Executes /" + cmdName, isToggle);
		}
		if (prefix.split(" ").length < 4) {
			for (CommandNode<CommandSourceStack> child : node.getChildren()) {
				autoDiscoverNode(child, cmdName);
			}
		}
	}

	private static String formatCmdLabel(String cmd) {
		String[] parts = cmd.split(" ");
		StringBuilder sb = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty()) continue;
			if (sb.length() > 0) sb.append(" ");
			sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.toString();
	}

	public static void executeGuiCommand(String cmd) {
		Minecraft mc = Minecraft.getInstance();
		String clean = cmd.startsWith("/") ? cmd.substring(1).trim() : cmd.trim();

		// Client-side immediate state toggles for UI responsiveness (handled completely on client)
		if (clean.equalsIgnoreCase("suschunks")) {
			susChunksEnabled = !susChunksEnabled;
			if (susChunksEnabled && SUS_SPOTS.isEmpty()) {
				randomizeSusSpots();
			}
			susAutoTimer = 0;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eOverlay: " + (susChunksEnabled ? "§aENABLED" : "§cDISABLED")),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks auto")) {
			susAutoSpawn = !susAutoSpawn;
			susAutoTimer = 0;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eAuto-Spawn (30s): " + (susAutoSpawn ? "§aENABLED" : "§cDISABLED")),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks color")) {
			susColorIndex = (susColorIndex + 1) % SUS_COLORS.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eColor: §b" + SUS_COLORS[susColorIndex]),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks opacity")) {
			susOpacityIndex = (susOpacityIndex + 1) % SUS_OPACITIES.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eOpacity: §b" + SUS_OPACITIES[susOpacityIndex]),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks curviness")) {
			susCurvinessIndex = (susCurvinessIndex + 1) % SUS_CURVINESS.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eCurviness: §b" + SUS_CURVINESS[susCurvinessIndex]),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks height")) {
			susHeightIndex = (susHeightIndex + 1) % SUS_HEIGHTS.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eElevation: §b" + SUS_HEIGHTS[susHeightIndex]),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks random")) {
			randomizeSusSpots();
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §aRandomized chunk spots!"),
						true
				);
			}
			return;
		}

		if (mc.player != null && mc.player.connection != null) {
			mc.player.connection.sendCommand(clean);
		}
	}

	public static boolean isCommandActive(String cmd) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return false;
		UUID id = mc.player.getUUID();
		String clean = cmd.startsWith("/") ? cmd.substring(1).trim() : cmd.trim();
		if (clean.equalsIgnoreCase("3x3")) return ENABLED_3X3.contains(id);
		if (clean.equalsIgnoreCase("fakeban")) return ENABLED_FAKEBAN.contains(id);
		if (clean.equalsIgnoreCase("suschunks")) return susChunksEnabled;
		if (clean.equalsIgnoreCase("suschunks auto")) return susAutoSpawn;
		return false;
	}

	// ===============================================================================
	// ClickGUI Screen Implementation (Vanilla Widgets for 100% Cross-Version Stability)
	// ===============================================================================
	public static class ClickGuiScreen extends Screen {
		private static final int WIN_WIDTH = 260;
		private static int activeTab = 0; // 0 = General Mods, 1 = Sus Chunks
		private final Map<String, Button> buttons = new HashMap<>();

		public ClickGuiScreen() {
			super(Component.literal("Mod ClickGUI"));
		}

		@Override
		public boolean isPauseScreen() {
			return false; // does not pause in singleplayer
		}

		@Override
		protected void init() {
			this.clearWidgets();
			buttons.clear();

			int panelW = WIN_WIDTH;
			int panelH = Math.min(330, this.height - 30);
			int panelX = (this.width - panelW) / 2;
			int panelY = (this.height - panelH) / 2;

			int btnW = panelW - 24;
			int btnH = 20;

			// Tab Selector Buttons
			int tabW = (btnW - 6) / 2;
			Button tabMods = Button.builder(
					Component.literal(activeTab == 0 ? "§b§l[⚡ General Mods]" : "§7⚡ General Mods"),
					b -> { activeTab = 0; this.init(); }
			).bounds(panelX + 12, panelY + 30, tabW, 18).build();
			this.addRenderableWidget(tabMods);

			Button tabSus = Button.builder(
					Component.literal(activeTab == 1 ? "§d§l[🔮 Sus Chunks]" : "§7🔮 Sus Chunks"),
					b -> { activeTab = 1; this.init(); }
			).bounds(panelX + 12 + tabW + 6, panelY + 30, tabW, 18).build();
			this.addRenderableWidget(tabSus);

			int startY = panelY + 54;
			int spacing = 23;

			if (activeTab == 0) {
				// General Mod commands
				int displayIndex = 0;
				for (int i = 0; i < REGISTERED_COMMANDS.size(); i++) {
					GuiCommand cmd = REGISTERED_COMMANDS.get(i);
					if (cmd.command.startsWith("suschunks")) continue; // shown in sus chunks tab

					int curY = startY + (displayIndex * spacing);
					if (curY + btnH > panelY + panelH - 26) break;

					Button btn = Button.builder(getButtonMessage(cmd), b -> {
						executeGuiCommand(cmd.command);
						this.updateAllButtons();
					})
					.bounds(panelX + 12, curY, btnW, btnH)
					.tooltip(Tooltip.create(Component.literal(cmd.description)))
					.build();

					this.addRenderableWidget(btn);
					buttons.put(cmd.command, btn);
					displayIndex++;
				}
			} else {
				// Sus Chunks (Fake) Overlay Controls
				// 1. Toggle Button
				Button btnToggle = Button.builder(getSusToggleMessage(), b -> {
					executeGuiCommand("suschunks");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Toggles 2D rounded square overlay at ground level (walk through, no particles)")))
				.build();
				this.addRenderableWidget(btnToggle);
				buttons.put("suschunks_toggle", btnToggle);

				// 2. Auto-Spawn (Every 30s) Toggle Button
				Button btnAuto = Button.builder(getSusAutoSpawnMessage(), b -> {
					executeGuiCommand("suschunks auto");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Automatically spawn a new random sus chunk spot every 30 seconds")))
				.build();
				this.addRenderableWidget(btnAuto);
				buttons.put("suschunks_auto", btnAuto);

				// 3. Color Button
				Button btnColor = Button.builder(getSusColorMessage(), b -> {
					executeGuiCommand("suschunks color");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 2, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle box overlay color (Cyan, Green, Red, Purple, Rainbow, etc.)")))
				.build();
				this.addRenderableWidget(btnColor);
				buttons.put("suschunks_color", btnColor);

				// 4. Opacity Button
				Button btnOpacity = Button.builder(getSusOpacityMessage(), b -> {
					executeGuiCommand("suschunks opacity");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 3, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle box opacity (25%, 50%, 75%, 100%)")))
				.build();
				this.addRenderableWidget(btnOpacity);
				buttons.put("suschunks_opacity", btnOpacity);

				// 5. Curviness Button
				Button btnCurve = Button.builder(getSusCurvinessMessage(), b -> {
					executeGuiCommand("suschunks curviness");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 4, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle corner curviness (Sharp 0°, Low, Medium, High, Circular)")))
				.build();
				this.addRenderableWidget(btnCurve);
				buttons.put("suschunks_curviness", btnCurve);

				// 6. Height / Elevation Button
				Button btnHeight = Button.builder(getSusHeightMessage(), b -> {
					executeGuiCommand("suschunks height");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 5, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle vertical height / elevation (Flush, Raised, Hover, Waist)")))
				.build();
				this.addRenderableWidget(btnHeight);
				buttons.put("suschunks_height", btnHeight);

				// 7. Randomize Spots Button
				Button btnRandom = Button.builder(Component.literal("🎲 Randomize Spots  §b[RUN]"), b -> {
					executeGuiCommand("suschunks random");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 6, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Picks new random locations near you for the sus chunk spots")))
				.build();
				this.addRenderableWidget(btnRandom);
				buttons.put("suschunks_random", btnRandom);
			}

			// Close button at bottom
			Button closeBtn = Button.builder(Component.literal("✕ Close [Esc]"), b -> this.onClose())
					.bounds(panelX + 12, panelY + panelH - 22, btnW, 18)
					.build();
			this.addRenderableWidget(closeBtn);
		}

		private void updateAllButtons() {
			if (activeTab == 0) {
				for (GuiCommand cmd : REGISTERED_COMMANDS) {
					Button b = buttons.get(cmd.command);
					if (b != null) {
						b.setMessage(getButtonMessage(cmd));
					}
				}
			} else {
				Button bToggle = buttons.get("suschunks_toggle");
				if (bToggle != null) bToggle.setMessage(getSusToggleMessage());

				Button bAuto = buttons.get("suschunks_auto");
				if (bAuto != null) bAuto.setMessage(getSusAutoSpawnMessage());

				Button bColor = buttons.get("suschunks_color");
				if (bColor != null) bColor.setMessage(getSusColorMessage());

				Button bOpacity = buttons.get("suschunks_opacity");
				if (bOpacity != null) bOpacity.setMessage(getSusOpacityMessage());

				Button bCurve = buttons.get("suschunks_curviness");
				if (bCurve != null) bCurve.setMessage(getSusCurvinessMessage());

				Button bHeight = buttons.get("suschunks_height");
				if (bHeight != null) bHeight.setMessage(getSusHeightMessage());
			}
		}

		private static Component getSusAutoSpawnMessage() {
			MutableComponent c = Component.literal("Auto-Spawn (30s):  ");
			if (susAutoSpawn) {
				c.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			} else {
				c.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			return c;
		}

		private static Component getSusToggleMessage() {
			MutableComponent c = Component.literal("Sus Chunks:  ");
			if (susChunksEnabled) {
				c.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			} else {
				c.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			return c;
		}

		private static Component getSusColorMessage() {
			return Component.literal("Color: ")
					.append(Component.literal("[" + SUS_COLORS[susColorIndex] + "]").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
		}

		private static Component getSusOpacityMessage() {
			return Component.literal("Opacity: ")
					.append(Component.literal("[" + SUS_OPACITIES[susOpacityIndex] + "]").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		}

		private static Component getSusCurvinessMessage() {
			return Component.literal("Curviness: ")
					.append(Component.literal("[" + SUS_CURVINESS[susCurvinessIndex] + "]").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		}

		private static Component getSusHeightMessage() {
			return Component.literal("Elevation: ")
					.append(Component.literal("[" + SUS_HEIGHTS[susHeightIndex] + "]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		}

		private static Component getButtonMessage(GuiCommand cmd) {
			if (cmd.isToggle) {
				boolean on = isCommandActive(cmd.command);
				MutableComponent c = Component.literal("/" + cmd.command + "  ");
				if (on) {
					c.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
				} else {
					c.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				}
				return c;
			} else {
				return Component.literal("/" + cmd.command + "  ")
						.append(Component.literal("[RUN]").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			}
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			// Dim background
			graphics.fill(0, 0, this.width, this.height, 0x66000000);

			int panelW = WIN_WIDTH;
			int panelH = Math.min(330, this.height - 30);
			int panelX = (this.width - panelW) / 2;
			int panelY = (this.height - panelH) / 2;

			// Window Body
			graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE12151C);

			// Borders
			graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY, 0xFF282C3D);
			graphics.fill(panelX - 1, panelY + panelH, panelX + panelW + 1, panelY + panelH + 1, 0xFF282C3D);
			graphics.fill(panelX - 1, panelY, panelX, panelY + panelH, 0xFF282C3D);
			graphics.fill(panelX + panelW, panelY, panelX + panelW + 1, panelY + panelH, 0xFF282C3D);

			// Neon Cyan Accent Stripe
			graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, 0xFF00E5FF);

			// Header Title
			graphics.fill(panelX, panelY + 2, panelX + panelW, panelY + 24, 0xFF181C26);
			graphics.drawString(this.font, Component.literal("⚡ MOD CLICKGUI").withStyle(ChatFormatting.BOLD), panelX + 10, panelY + 8, 0xFFFFFFFF, false);
			graphics.drawString(this.font, Component.literal("[Insert] Toggle"), panelX + panelW - 90, panelY + 8, 0xFF71788E, false);

			// Render all widgets (buttons & their tooltips)
			super.render(graphics, mouseX, mouseY, partialTick);
		}
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
		nextAllowed = 0L;
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
				if (st.getDestroySpeed(level, target) < 0) continue;
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
		ServerPlayer p = src.getPlayerOrException();
		ServerLevel level = p.level() instanceof ServerLevel sl ? sl : null;
		if (level == null) return 0;

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < RTP_TRIES; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double dist = RTP_MIN + rnd.nextDouble() * (RTP_MAX - RTP_MIN);
			int x = (int) Math.round(p.getX() + Math.cos(angle) * dist);
			int z = (int) Math.round(p.getZ() + Math.sin(angle) * dist);

			level.getChunk(x >> 4, z >> 4);
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

			Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
			w.vl += VL_RTP;
			w.hasLast = false;

			src.sendSuccess(() -> Component.literal("Teleported to " + x + " " + surfaceY + " " + z + "."), false);
			return 1;
		}

		src.sendFailure(Component.literal("Could not find a safe place to teleport. Try again."));
		return 0;
	}

	// ------------------------------------------------------------------ fake anti-cheat (/fakeban)

	private static final class Watch {
		double vl = 0.0;
		int stage = 0;
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

			double screenX = 0.55 - 1.10 * progress;
			double screenY = 0.45 - 0.90 * progress;
			double dist = 0.85 + 0.65 * Math.pow(progress - 0.5, 2) * 4.0;

			Vec3 targetHead = eye.add(forward.scale(dist))
					.add(right.scale(screenX))
					.add(up.scale(screenY));

			double standY = targetHead.y - 1.70;

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

			w.vl = Math.max(0.0, w.vl - VL_DECAY_PER_SEC);

			// stage 0 -> 1: the admin looks at you
			if (w.stage == 0 && w.vl >= VL_WATCH) {
				startHeadPass(p);
				w.stage = 1;
				w.watchedTick = now;
				w.vl = 5.0;
				continue;
			}

			// stage 1 -> 2: trap base appears
			if (w.stage == 1 && !w.trapGaveUp && w.vl >= VL_TRAP && now - w.watchedTick >= TRAP_DELAY_TICKS) {
				String result = placeTrap(p);
				if (result.equals("ok")) {
					w.stage = 2;
				} else if (result.equals("missing") || result.equals("unreadable") || result.equals("toobig")) {
					w.trapGaveUp = true;
					LOGGER.warn("Fake anti-cheat trap base skipped: {}", trapProblem(result));
				}
			}
		}
	}

	private static void startHeadPass(ServerPlayer p) {
		if (!(p.level() instanceof ServerLevel level)) return;
		HeadAnim oldAnim = HEADS.remove(p.getUUID());
		if (oldAnim != null && oldAnim.stand != null) {
			oldAnim.stand.discard();
		}

		Skin skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];

		// 1. Play jumpscare sound
		p.playSound(SoundEvents.ELDER_GUARDIAN_CURSE, 1.0f, 1.0f);
		run(p, "playsound minecraft:entity.elder_guardian.curse hostile @s ~ ~ ~ 1 1");

		// 2. Display warning subtitle
		run(p, "title @s times 0 " + HEAD_TICKS + " 10");
		run(p, "title @s subtitle [{\"text\":\"[ \",\"color\":\"gray\"},{\"text\":\"" + skin.name() + "\",\"color\":\"red\",\"bold\":true},{\"text\":\" is watching you ]\",\"color\":\"gray\"}]");
		run(p, "title @s title {\"text\":\"\"}");

		// 3. Spawn invisible ArmorStand
		Vec3 eye = p.getEyePosition();
		ArmorStand stand = new ArmorStand(level, eye.x, eye.y - 1.70, eye.z);

		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		GameProfile gp = new GameProfile(skin.uuid(), skin.name());
		ResolvableProfile profile = createProfile(gp);
		if (profile != null) {
			head.set(DataComponents.PROFILE, profile);
		}
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

	private static ResolvableProfile createProfile(GameProfile gp) {
		try {
			Method m = ResolvableProfile.class.getMethod("createResolved", GameProfile.class);
			return (ResolvableProfile) m.invoke(null, gp);
		} catch (Throwable t) {
			try {
				Constructor<ResolvableProfile> c = ResolvableProfile.class.getConstructor(GameProfile.class);
				return c.newInstance(gp);
			} catch (Throwable t2) {
				return null;
			}
		}
	}

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
			if (startJob(level, schem, p.getBlockX(), p.getBlockY(), p.getBlockZ(), file, p.getUUID())) {
				startHeadPass(p);
				return "ok";
			}
		}
		return "nospace";
	}

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

	// ------------------------------------------------------------------ fake base logic

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
						if (st == null) continue;

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

	// ------------------------------------------------------------------ NBT -> SNBT text

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
		in.readUTF();
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
