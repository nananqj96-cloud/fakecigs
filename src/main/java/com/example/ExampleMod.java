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
	// CONFIGURATION
	// ===============================================================================
	private static final int TRIGGER_Y = -40;            // fake anti-cheat deep mining bonus threshold
	private static final int BASE_TRIGGER_Y = -10;       // random fake bases can spawn when mining BELOW this Y
	private static final double CHANCE = 1.0;            // 100% chance per block mined (spawns when not on cooldown)
	private static final long COOLDOWN_MS = 60_000L;     // wait 60 seconds between bases
	private static final int MAX_RADIUS = 30;            // search up to 30 blocks away for clear space
	private static final int MIN_BUILD_Y = -58;          // stay above bedrock
	private static final int MAX_VOLUME = 3_000_000;
	private static final int BLOCKS_PER_TICK = 6000;
	private static final int TILES_PER_TICK = 40;
	private static final int DOWN_DEPTH = 3;
	private static final int WALL_DEPTH = 1;
	private static final boolean DEBUG_LOG = true;

	// ---- /fakeban (fake anti-cheat) ----
	private static final String FAKEBASE_FILE = "fakebase.litematic";
	private static final double VL_WATCH = 20.0;
	private static final double VL_TRAP = 20.0;
	private static final int TRAP_DELAY_TICKS = 400;
	private static final double VL_DECAY_PER_SEC = 0.5;
	private static final double VL_PER_BLOCK = 0.15;
	private static final double VL_DEEP_BONUS = 0.10;
	private static final double VL_PER_EXTRA = 0.35;
	private static final double VL_FAST = 1.0;
	private static final int FAST_BREAKS = 10;
	private static final double VL_FLY = 2.0;
	private static final double VL_SPEED = 1.5;
	private static final double SPEED_LIMIT = 14.0;
	private static final double VL_RTP = 6.0;
	private static final int HEAD_TICKS = 50;
	private static final double TRAP_ENTER_DIST = 1.0;
	private static final double TRAP_MINE_DIST = 3.0;
	private static final double TRAP_APPROACH_RANGE = 16.0;
	private static final int TRAP_APPROACH_BREAKS = 6;
	private static final String APPEAL_URL = "support.donutsmp.net";

	// ---- /rtp ----
	private static final int RTP_MIN = 500;
	private static final int RTP_MAX = 5000;
	private static final int RTP_TRIES = 25;

	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED SQUARE CONFIGURATION & STATE
	// ===============================================================================
	public static boolean susChunksEnabled = false;
	public static boolean susAutoSpawn = true;    // automatically spawns a random sus chunk every 30s
	private static int susAutoTimer = 0;          // counts ticks (600 ticks = 30 seconds)

	public static int susColorIndex = 0;          // 0=Cyan (default matching screenshot), 1=Green, 2=Red, 3=Purple, 4=Yellow, 5=Orange, 6=White, 7=Rainbow
	public static int susOpacityIndex = 2;        // 0=25%, 1=50%, 2=75%, 3=100%
	public static int susCurvinessIndex = 2;      // 0=Sharp (0), 1=Low (R=1.5), 2=Medium (R=3.0), 3=High (R=5.0), 4=Circular (R=7.5)
	public static int susElevationIndex = 0;      // 0=Flush Ground (+0.02), 1=Raised (+0.25), 2=Hover (+0.50), 3=Waist (+1.00)

	public static final String[] SUS_COLORS = {"Cyan (Default)", "Green", "Red", "Purple", "Yellow", "Orange", "White", "Rainbow (Chroma)"};
	public static final float[][] SUS_COLOR_RGB = {
			{0.00f, 0.85f, 1.00f}, // Cyan (#00D9FF)
			{0.15f, 0.95f, 0.35f}, // Green (#26F259)
			{1.00f, 0.20f, 0.25f}, // Red (#FF3340)
			{0.75f, 0.25f, 1.00f}, // Purple (#BF40FF)
			{1.00f, 0.90f, 0.15f}, // Yellow (#FFE626)
			{1.00f, 0.55f, 0.10f}, // Orange (#FF8C1A)
			{0.95f, 0.98f, 1.00f}, // White
			{1.00f, 1.00f, 1.00f}  // Rainbow (dynamic HSB)
	};

	public static final String[] SUS_OPACITIES = {"25% (Subtle)", "50% (Medium)", "75% (Vibrant)", "100% (Solid)"};
	public static final float[] SUS_OPACITY_VALUES = {0.25f, 0.50f, 0.75f, 1.00f};

	public static final String[] SUS_CURVINESS = {"Sharp Corners (0°)", "Low Curvature (R=1.5)", "Medium Curvature (R=3.0)", "High Curvature (R=5.0)", "Circular (R=7.5)"};
	public static final float[] SUS_CURVE_VALUES = {0.0f, 1.5f, 3.0f, 5.0f, 7.5f};

	public static final String[] SUS_ELEVATIONS = {"Flush Surface (+0.02)", "Slightly Raised (+0.25)", "Hovering (+0.50)", "Waist Height (+1.00)"};
	public static final double[] SUS_ELEVATION_VALUES = {0.02, 0.25, 0.50, 1.00};

	public static final List<SusSpot> SUS_SPOTS = new ArrayList<>();

	public static class SusSpot {
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

	private static Job activeJob = null;
	private static long nextAllowed = 0L;
	private static final Set<UUID> ENABLED_3X3 = new HashSet<>();
	private static final Set<UUID> RECURSION_GUARD = new HashSet<>();
	private static final Map<UUID, PlayerState> STATES = new HashMap<>();
	private static final List<HeadPass> ACTIVE_HEADS = new ArrayList<>();
	private static final List<TrapBase> ACTIVE_TRAPS = new ArrayList<>();

	private static final AdminSkin[] ADMIN_SKINS = {
			new AdminSkin("drdonuts", "99ba64e4-c119-4cb5-8d57-b248eb3fb020",
					"ewogICJ0aW1lc3RhbXAiIDogMTcwODcxNDkyNzg2OSwKICAicHJvZmlsZUlkIiA6ICI5OWJhNjRlNGMxMTk0Y2I1OGQ1N2IyNDhlYjNmYjAyMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJkcmRvbnV0cyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS82NGVhOTJmMjdmMDhiOGRlNzQ2Y2M2YzlkMmUyNzI2MWY5OTY4OGU0YTM4MjA5YjFmOGNmNzFjYjA0NTRjMjA0IgogICAgfQogIH0KfQ=="),
			new AdminSkin("SammyCheez", "7f14bfa4-0461-4aa2-944f-c0067ffbc989",
					"ewogICJ0aW1lc3RhbXAiIDogMTYyNzc4NDMyNTE5NiwKICAicHJvZmlsZUlkIiA6ICI3ZjE0YmZhNDA0NjE0YWEyOTQ0ZmMwMDY3ZmZiYzk4OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYW1teUNoZWV6IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhMWE3NmJjYTg2NzFhMDRhMjFjYjFiYWE0MjYzNTU1N2M1ZTQ0YTg1OWI0ZWM1ZjEwNzNmNGVkNjc1MDVhOGIiCiAgICB9CiAgfQp9"),
			new AdminSkin("drpeppas", "a8607fa6-2f00-4ea1-b27e-399fa37812ec",
					"ewogICJ0aW1lc3RhbXAiIDogMTY4Mzg2NjI2MjA5NCwKICAicHJvZmlsZUlkIiA6ICJhODYwN2ZhNjJmMDA0ZWExYjI3ZTM5OWZhMzc4MTJlYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJkcnBlcHBhcyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85OGQ5YzFkNWFiOTdmZWRhOTY3MWNlYTVkMzYzNmYwNGEzNmQ1NWNhMTIxNDcyMDNmNTA4ZDkzNWIwODJjYzg5IgogICAgfQogIH0KfQ=="),
			new AdminSkin("swoopdd", "49ec1782-bdf9-42b4-82a1-faad1c8939c3",
					"ewogICJ0aW1lc3RhbXAiIDogMTcwODcxNDkyNzg2OSwKICAicHJvZmlsZUlkIiA6ICI0OWVjMTc4MmJkZjktNDJiNC04MmExLWZhYWQxYzg5MzljMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJzd29vcGRkIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2QzM2I2ZTU5MThjYmQ4ZmM1NGMxMGY5MDlmZjIzMmFhNTBlNDQxYjc1YTE5ZDcyYmM5ODk0MWY4YTg5YjNiN2EiCiAgICB9CiAgfQp9")
	};

	public static KeyMapping guiKeyMapping;
	public static final Map<String, CommandUiEntry> DISCOVERED_COMMANDS = new TreeMap<>();

	public static class CommandUiEntry {
		public final String commandName;
		public final String fullCommand;
		public final boolean isToggle;

		public CommandUiEntry(String commandName, String fullCommand, boolean isToggle) {
			this.commandName = commandName;
			this.fullCommand = fullCommand;
			this.isToggle = isToggle;
		}
	}

	@Override
	public void onInitialize() {
		LOGGER.info("ExampleMod initializing with all chat commands intact, auto-discovery GUI, and 2D ground sus chunks overlay.");

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world.isClientSide()) return;
			if (!(world instanceof ServerLevel serverLevel)) return;
			if (!(player instanceof ServerPlayer serverPlayer)) return;

			// Handle /3x3 mining
			if (ENABLED_3X3.contains(player.getUUID())) {
				handle3x3Break(serverLevel, serverPlayer, pos, state);
			}

			// Fake Anti-Cheat VL tracking
			onPlayerMineBlock(serverPlayer, pos);

			// Mining below BASE_TRIGGER_Y spawns a random fake base with 60s cooldown
			if (pos.getY() < BASE_TRIGGER_Y) {
				long now = System.currentTimeMillis();
				if (now >= nextAllowed) {
					if (ThreadLocalRandom.current().nextDouble() < CHANCE) {
						spawnFakeBaseNear(serverLevel, pos, false);
					}
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickActiveJob();
			tickFakeAntiCheat(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			activeJob = null;
			STATES.clear();
			ACTIVE_HEADS.clear();
			ACTIVE_TRAPS.clear();
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			STATES.put(handler.getPlayer().getUUID(), new PlayerState());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("3x3").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				UUID id = p.getUUID();
				if (ENABLED_3X3.contains(id)) {
					ENABLED_3X3.remove(id);
					p.sendSystemMessage(Component.literal("§6[3x3 Mining] §cDisabled"));
				} else {
					ENABLED_3X3.add(id);
					p.sendSystemMessage(Component.literal("§6[3x3 Mining] §aEnabled (3x3 tunneling active)"));
				}
				return 1;
			}));

			dispatcher.register(Commands.literal("fakebase").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				spawnFakeBaseNear(((ServerLevel) p.level()), p.blockPosition(), false);
				p.sendSystemMessage(Component.literal("§aSpawning a random fake base near you..."));
				return 1;
			}));

			dispatcher.register(Commands.literal("fakeban").executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				PlayerState st = getState(p.getUUID());
				st.enabled = !st.enabled;
				p.sendSystemMessage(Component.literal("§c[FakeAntiCheat] " + (st.enabled ? "§aENABLED for you" : "§cDISABLED for you")));
				return 1;
			}));

			dispatcher.register(Commands.literal("rtp").executes(ctx -> cmdRtp(ctx.getSource())));

			// ---- /suschunks : Fake Sus Chunks overlay commands ----
			dispatcher.register(Commands.literal("suschunks")
					.executes(ctx -> {
						susChunksEnabled = !susChunksEnabled;
						if (susChunksEnabled && SUS_SPOTS.isEmpty()) {
							randomizeSusSpots();
						}
						susAutoTimer = 0;
						ctx.getSource().sendSuccess(
								() -> Component.literal("§d[🔮 Sus Chunks] Overlay: " + (susChunksEnabled ? "§aENABLED" : "§cDISABLED")),
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
					.then(Commands.literal("randomize").executes(ctx -> {
						randomizeSusSpots();
						ctx.getSource().sendSuccess(() -> Component.literal("§d[🔮 Sus Chunks] §aGenerated new random sus spots near you!"), false);
						return 1;
					}))
					.then(Commands.literal("color").executes(ctx -> {
						susColorIndex = (susColorIndex + 1) % SUS_COLORS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("§d[🔮 Sus Chunks] Color set to: §b" + SUS_COLORS[susColorIndex]), false);
						return 1;
					}))
					.then(Commands.literal("opacity").executes(ctx -> {
						susOpacityIndex = (susOpacityIndex + 1) % SUS_OPACITIES.length;
						ctx.getSource().sendSuccess(() -> Component.literal("§d[🔮 Sus Chunks] Opacity set to: §b" + SUS_OPACITIES[susOpacityIndex]), false);
						return 1;
					}))
					.then(Commands.literal("curve").executes(ctx -> {
						susCurvinessIndex = (susCurvinessIndex + 1) % SUS_CURVINESS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("§d[🔮 Sus Chunks] Curviness set to: §b" + SUS_CURVINESS[susCurvinessIndex]), false);
						return 1;
					}))
					.then(Commands.literal("elevation").executes(ctx -> {
						susElevationIndex = (susElevationIndex + 1) % SUS_ELEVATIONS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("§d[🔮 Sus Chunks] Ground Elevation set to: §b" + SUS_ELEVATIONS[susElevationIndex]), false);
						return 1;
					}))
			);

			// Automatically register built-in commands
			registerDiscoveredCommand("3x3", "/3x3", true);
			registerDiscoveredCommand("fakebase", "/fakebase", false);
			registerDiscoveredCommand("fakeban", "/fakeban", true);
			registerDiscoveredCommand("rtp", "/rtp", false);
			registerDiscoveredCommand("suschunks", "/suschunks", true);
			registerDiscoveredCommand("suschunks auto", "/suschunks auto", true);
			registerDiscoveredCommand("suschunks randomize", "/suschunks randomize", false);
			registerDiscoveredCommand("suschunks color", "/suschunks color", false);
			registerDiscoveredCommand("suschunks opacity", "/suschunks opacity", false);
			registerDiscoveredCommand("suschunks curve", "/suschunks curve", false);
			registerDiscoveredCommand("suschunks elevation", "/suschunks elevation", false);

			// Brigadier tree introspection: automatically register all current and future commands
			try {
				for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
					autoDiscoverNode(child, "");
				}
			} catch (Throwable t) {
				LOGGER.debug("Auto-discovery node parse notice: {}", t.getMessage());
			}
		});
	}

	private static void autoDiscoverNode(CommandNode<CommandSourceStack> node, String prefix) {
		String name = node.getName();
		if (name == null || name.isEmpty() || name.startsWith("minecraft:") || name.startsWith("fabric:")) return;

		String currentPath = prefix.isEmpty() ? name : prefix + " " + name;

		if (isKnownVanillaCommand(currentPath.split(" ")[0])) {
			return;
		}

		if (node.getCommand() != null) {
			boolean isToggle = isToggleLike(currentPath);
			registerDiscoveredCommand(currentPath, "/" + currentPath, isToggle);
		}

		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			autoDiscoverNode(child, currentPath);
		}
	}

	private static boolean isKnownVanillaCommand(String base) {
		String b = base.toLowerCase(Locale.ROOT);
		return b.equals("gamemode") || b.equals("tp") || b.equals("teleport") || b.equals("give") ||
				b.equals("kill") || b.equals("time") || b.equals("weather") || b.equals("ban") ||
				b.equals("kick") || b.equals("op") || b.equals("deop") || b.equals("whitelist") ||
				b.equals("say") || b.equals("me") || b.equals("tell") || b.equals("msg") ||
				b.equals("w") || b.equals("help") || b.equals("list") || b.equals("summon");
	}

	private static boolean isToggleLike(String cmd) {
		String l = cmd.toLowerCase(Locale.ROOT);
		return l.equals("3x3") || l.equals("fakeban") || l.equals("suschunks") || l.equals("suschunks auto") ||
				l.endsWith(" toggle") || l.endsWith(" enable") || l.endsWith(" on") || l.endsWith(" off");
	}

	public static void registerDiscoveredCommand(String name, String fullCmd, boolean isToggle) {
		DISCOVERED_COMMANDS.put(name, new CommandUiEntry(name, fullCmd, isToggle));
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info("ExampleMod client initializing: Registering Insert key ClickGUI and Sus Chunks 2D ground overlay.");

		KeyMapping.Category category = getGuiCategory();
		int insertKey = InputConstants.KEY_INSERT; // 260
		guiKeyMapping = new KeyMapping(
				"key.modid.clickgui",
				InputConstants.Type.KEYSYM,
				insertKey,
				category
		);

		// 1. Client Tick: Watch for Insert key & handle 30s auto-spawn timer
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (guiKeyMapping.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new ClickGuiScreen(null));
				} else if (client.screen instanceof ClickGuiScreen) {
					client.setScreen(null);
				}
			}

			// Automatically spawn a new random sus chunk spot every 30 seconds (600 ticks)
			if (susChunksEnabled && susAutoSpawn && client.level != null && client.player != null) {
				susAutoTimer++;
				if (susAutoTimer >= 600) {
					susAutoTimer = 0;
					addRandomSusSpotNear(client.player.getX(), client.player.getZ());
				}
			} else {
				susAutoTimer = 0;
			}
		});

		// 2. In-world 2D rounded square overlay rendering on ground level (no particles, passable)
		initWorldRendering();
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
				try {
					Method mOld = KeyMapping.Category.class.getMethod("register", String.class);
					return (KeyMapping.Category) mOld.invoke(null, "category.modid.gui");
				} catch (Throwable t3) {
					Constructor<?>[] ctors = KeyMapping.Category.class.getDeclaredConstructors();
					for (Constructor<?> c : ctors) {
						if (c.getParameterCount() == 1) {
							try {
								c.setAccessible(true);
								return (KeyMapping.Category) c.newInstance(id("gui"));
							} catch (Throwable ignored) {}
						}
					}
					return null;
				}
			}
		}
	}

	public static void randomizeSusSpots() {
		SUS_SPOTS.clear();
		Minecraft client = Minecraft.getInstance();
		double px = (client.player != null) ? client.player.getX() : 0.0;
		double pz = (client.player != null) ? client.player.getZ() : 0.0;

		int baseChunkX = ((int) Math.floor(px)) >> 4;
		int baseChunkZ = ((int) Math.floor(pz)) >> 4;

		int count = ThreadLocalRandom.current().nextInt(3, 6);
		for (int i = 0; i < count; i++) {
			int dx = ThreadLocalRandom.current().nextInt(-6, 7);
			int dz = ThreadLocalRandom.current().nextInt(-6, 7);
			SUS_SPOTS.add(new SusSpot(baseChunkX + dx, baseChunkZ + dz));
		}
	}

	public static void addRandomSusSpotNear(double px, double pz) {
		int baseChunkX = ((int) Math.floor(px)) >> 4;
		int baseChunkZ = ((int) Math.floor(pz)) >> 4;
		int dx = ThreadLocalRandom.current().nextInt(-5, 6);
		int dz = ThreadLocalRandom.current().nextInt(-5, 6);
		SUS_SPOTS.add(new SusSpot(baseChunkX + dx, baseChunkZ + dz));
		if (SUS_SPOTS.size() > 10) {
			SUS_SPOTS.remove(0);
		}
	}

	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED SQUARE OVERLAY RENDERING (NO PARTICLES, PASSABLE)
	// ===============================================================================
	private static boolean worldRendererInitialized = false;

	private static void initWorldRendering() {
		if (worldRendererInitialized) return;
		worldRendererInitialized = true;
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
			if (eventsClass == null) return;

			String[] eventFieldNames = {
					"BEFORE_TRANSLUCENT",
					"AFTER_ENTITIES",
					"AFTER_TRANSLUCENT",
					"END_MAIN",
					"START_MAIN"
			};
			Field targetField = null;
			for (String fName : eventFieldNames) {
				try {
					targetField = eventsClass.getField(fName);
					if (targetField != null) break;
				} catch (Throwable ignored) {}
			}
			if (targetField == null) return;

			Object eventObj = targetField.get(null);
			if (eventObj == null) return;

			Method regMethod = null;
			for (Method m : eventObj.getClass().getMethods()) {
				if (m.getName().equals("register") && m.getParameterCount() == 1) {
					regMethod = m;
					break;
				}
			}
			if (regMethod == null) return;

			Class<?> listenerType = regMethod.getParameterTypes()[0];
			Object proxyListener = Proxy.newProxyInstance(
					ExampleMod.class.getClassLoader(),
					new Class<?>[]{listenerType},
					(proxy, method, args) -> {
						if (args != null && args.length > 0) {
							renderSusChunksWorld(args[0]);
						}
						return null;
					}
			);
			regMethod.invoke(eventObj, proxyListener);
			LOGGER.info("[SusChunks] World render overlay hooked successfully via {}", targetField.getName());
		} catch (Throwable t) {
			LOGGER.warn("[SusChunks] Could not hook world render events: {}", t.getMessage());
		}
	}

	private static void renderSusChunksWorld(Object context) {
		if (!susChunksEnabled) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;
		if (SUS_SPOTS.isEmpty()) {
			randomizeSusSpots();
		}

		PoseStack poseStack = null;
		try {
			Method m = context.getClass().getMethod("matrices");
			poseStack = (PoseStack) m.invoke(context);
		} catch (Throwable t1) {
			try {
				Method m = context.getClass().getMethod("matrixStack");
				poseStack = (PoseStack) m.invoke(context);
			} catch (Throwable ignored) {}
		}
		if (poseStack == null) return;

		Vec3 camPos = null;
		try {
			Method cm = context.getClass().getMethod("camera");
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
			camPos = client.player.position();
		}
		if (camPos == null) return;

		MultiBufferSource consumers = null;
		try {
			Method m = context.getClass().getMethod("consumers");
			consumers = (MultiBufferSource) m.invoke(context);
		} catch (Throwable ignored) {}
		if (consumers == null && client.renderBuffers() != null) {
			consumers = client.renderBuffers().bufferSource();
		}
		if (consumers == null) return;

		Object renderType = getOverlayRenderType();
		if (renderType == null) return;

		VertexConsumer consumer = null;
		for (Method m : consumers.getClass().getMethods()) {
			if (m.getName().equals("getBuffer") && m.getParameterCount() == 1) {
				try {
					consumer = (VertexConsumer) m.invoke(consumers, renderType);
					if (consumer != null) break;
				} catch (Throwable ignored) {}
			}
		}
		if (consumer == null) return;

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
		double elevationOffset = SUS_ELEVATION_VALUES[susElevationIndex];

		// Translucent interior fill RGBA
		int fillR = (int) (r * 255.0f);
		int fillG = (int) (g * 255.0f);
		int fillB = (int) (b * 255.0f);
		int fillA = Math.min(255, Math.max(15, (int) (opacity * 0.45f * 255.0f)));

		// Prominent border outline RGBA
		int borderR = Math.min(255, (int) (r * 1.15f * 255.0f));
		int borderG = Math.min(255, (int) (g * 1.15f * 255.0f));
		int borderB = Math.min(255, (int) (b * 1.15f * 255.0f));
		int borderA = Math.min(255, Math.max(50, (int) (opacity * 0.95f * 255.0f)));

		poseStack.pushPose();
		poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f matrix = poseStack.last().pose();

		double borderWidth = 0.25;

		for (int s = 0; s < SUS_SPOTS.size(); s++) {
			SusSpot spot = SUS_SPOTS.get(s);
			double distSq = client.player.distanceToSqr(spot.centerX, client.player.getY(), spot.centerZ);
			if (distSq > 256.0 * 256.0) continue; // visible up to 256 blocks (16 chunks)

			// Determine ground Y for this chunk spot
			double groundY = getGroundYForSpot(client.level, client.player, spot) + elevationOffset;

			// Generate outer perimeter points (32 points around rounded square)
			List<double[]> outerPoints = get2DRoundedRectPoints(
					spot.minX, spot.maxX,
					spot.minZ, spot.maxZ,
					curveRadius, 8
			);

			// Generate inner perimeter points for border outline ribbon
			double innerR = Math.max(0.0, curveRadius - borderWidth);
			List<double[]> innerPoints = get2DRoundedRectPoints(
					spot.minX + borderWidth, spot.maxX - borderWidth,
					spot.minZ + borderWidth, spot.maxZ - borderWidth,
					(float) innerR, 8
			);

			int count = outerPoints.size();

			// 1. Draw Translucent Filled Interior (Triangle Fan as Quads)
			double cx = spot.centerX;
			double cz = spot.centerZ;
			for (int i = 0; i < count; i++) {
				double[] p1 = outerPoints.get(i);
				double[] p2 = outerPoints.get((i + 1) % count);

				// Front face
				drawQuad(consumer, matrix,
						cx, groundY, cz,
						p1[0], groundY, p1[1],
						p2[0], groundY, p2[1],
						cx, groundY, cz,
						fillR, fillG, fillB, fillA);

				// Reverse face
				drawQuad(consumer, matrix,
						cx, groundY, cz,
						p2[0], groundY, p2[1],
						p1[0], groundY, p1[1],
						cx, groundY, cz,
						fillR, fillG, fillB, fillA);
			}

			// 2. Draw Prominent Glowing Border Ribbon Outline (Connecting Outer & Inner Perimeters)
			for (int i = 0; i < count; i++) {
				double[] p1 = outerPoints.get(i);
				double[] p2 = outerPoints.get((i + 1) % count);
				double[] ip1 = innerPoints.get(i);
				double[] ip2 = innerPoints.get((i + 1) % count);

				// Top face border ribbon
				drawQuad(consumer, matrix,
						p1[0], groundY + 0.002, p1[1],
						p2[0], groundY + 0.002, p2[1],
						ip2[0], groundY + 0.002, ip2[1],
						ip1[0], groundY + 0.002, ip1[1],
						borderR, borderG, borderB, borderA);

				// Bottom face border ribbon
				drawQuad(consumer, matrix,
						ip1[0], groundY + 0.002, ip1[1],
						ip2[0], groundY + 0.002, ip2[1],
						p2[0], groundY + 0.002, p2[1],
						p1[0], groundY + 0.002, p1[1],
						borderR, borderG, borderB, borderA);
			}
		}

		poseStack.popPose();
	}

	private static Vec3 extractCameraPos(Object cam) {
		try {
			Method m = cam.getClass().getMethod("position");
			return (Vec3) m.invoke(cam);
		} catch (Throwable t1) {
			try {
				Method m = cam.getClass().getMethod("getPosition");
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
		try {
			consumer.addVertex(matrix, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
			consumer.addVertex(matrix, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
			consumer.addVertex(matrix, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
			consumer.addVertex(matrix, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
		} catch (Throwable ignored) {}
	}

	private static Object getOverlayRenderType() {
		String[] typesClasses = {
				"net.minecraft.client.renderer.rendertype.RenderTypes",
				"net.minecraft.client.renderer.rendertype.RenderType",
				"net.minecraft.client.renderer.RenderType"
		};
		String[] fieldCandidates = {
				"LIGHTNING",
				"DEBUG_QUADS",
				"DEBUG_FILLED_BOX",
				"LINES_TRANSLUCENT",
				"TRANSLUCENT_MOVING_BLOCK"
		};
		String[] methodCandidates = {
				"lightning",
				"debugQuads",
				"lines",
				"translucentMovingBlock"
		};

		for (String className : typesClasses) {
			try {
				Class<?> clazz = Class.forName(className);
				for (String fName : fieldCandidates) {
					try {
						Field f = clazz.getField(fName);
						Object val = f.get(null);
						if (val != null) return val;
					} catch (Throwable ignored) {}
				}
				for (String mName : methodCandidates) {
					try {
						Method m = clazz.getMethod(mName);
						Object val = m.invoke(null);
						if (val != null) return val;
					} catch (Throwable ignored) {}
				}
			} catch (Throwable ignored) {}
		}
		return null;
	}

	private static double getGroundYForSpot(ClientLevel level, Player player, SusSpot spot) {
		if (!Double.isNaN(spot.cachedGroundY)) {
			return spot.cachedGroundY;
		}

		int bx = (int) Math.floor(spot.centerX);
		int bz = (int) Math.floor(spot.centerZ);

		int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		if (groundY > level.getMinY()) {
			spot.cachedGroundY = groundY + 0.02;
			return spot.cachedGroundY;
		}
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

	// ===============================================================================
	// ClickGUI Client Command Execution & State Introspection
	// ===============================================================================
	public static void executeGuiCommand(String cmd) {
		Minecraft mc = Minecraft.getInstance();
		String clean = cmd.startsWith("/") ? cmd.substring(1).trim() : cmd.trim();

		// Client-side immediate state toggles for UI responsiveness
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
		} else if (clean.equalsIgnoreCase("suschunks randomize")) {
			randomizeSusSpots();
			susAutoTimer = 0;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §aRandomized chunk spots!"),
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
		} else if (clean.equalsIgnoreCase("suschunks curve")) {
			susCurvinessIndex = (susCurvinessIndex + 1) % SUS_CURVINESS.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eCurviness: §b" + SUS_CURVINESS[susCurvinessIndex]),
						true
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks elevation")) {
			susElevationIndex = (susElevationIndex + 1) % SUS_ELEVATIONS.length;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eElevation: §b" + SUS_ELEVATIONS[susElevationIndex]),
						true
				);
			}
			return;
		}

		// Dispatch through network connection to server
		if (mc.getConnection() != null) {
			mc.getConnection().sendCommand(clean);
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
		private final Screen parent;
		private int activeTab = 0; // 0 = General Mods, 1 = Sus Chunks Config
		private int scrollOffset = 0;

		public ClickGuiScreen(Screen parent) {
			super(Component.literal("ClickGUI"));
			this.parent = parent;
		}

		@Override
		public boolean isPauseScreen() {
			return false; // Does not pause the game in singleplayer
		}

		@Override
		protected void init() {
			super.init();
			rebuildGui();
		}

		public void rebuildGui() {
			clearWidgets();

			int panelW = Math.min(360, this.width - 40);
			int panelH = Math.min(270, this.height - 40);
			int panelX = (this.width - panelW) / 2;
			int panelY = (this.height - panelH) / 2;

			// Close Button (Top-Right)
			this.addRenderableWidget(
					Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), btn -> {
						this.onClose();
					}).bounds(panelX + panelW - 26, panelY + 5, 20, 20).build()
			);

			// Tab 0: General Mods Button
			this.addRenderableWidget(
					Button.builder(Component.literal(activeTab == 0 ? "§6⚡ [General Mods]" : "⚡ General Mods"), btn -> {
						activeTab = 0;
						scrollOffset = 0;
						rebuildGui();
					}).bounds(panelX + 10, panelY + 30, (panelW - 30) / 2, 20).build()
			);

			// Tab 1: Sus Chunks Config Button
			this.addRenderableWidget(
					Button.builder(Component.literal(activeTab == 1 ? "§d🔮 [Sus Chunks]" : "🔮 Sus Chunks"), btn -> {
						activeTab = 1;
						scrollOffset = 0;
						rebuildGui();
					}).bounds(panelX + 20 + (panelW - 30) / 2, panelY + 30, (panelW - 30) / 2, 20).build()
			);

			int contentY = panelY + 56;
			int contentW = panelW - 20;

			if (activeTab == 0) {
				// Tab 0: Dynamically populate all auto-discovered commands
				List<CommandUiEntry> entries = new ArrayList<>(DISCOVERED_COMMANDS.values());
				int start = Math.max(0, scrollOffset);
				int visibleCount = 6;

				for (int i = 0; i < visibleCount && (start + i) < entries.size(); i++) {
					CommandUiEntry entry = entries.get(start + i);
					int btnY = contentY + (i * 24);

					Component label = formatCommandLabel(entry);
					Button cmdBtn = Button.builder(label, btn -> {
						executeGuiCommand(entry.fullCommand);
						rebuildGui();
					}).bounds(panelX + 10, btnY, contentW, 20).build();

					cmdBtn.setTooltip(Tooltip.create(Component.literal("Executes: " + entry.fullCommand)));
					this.addRenderableWidget(cmdBtn);
				}

				// Scroll Buttons if many commands exist
				if (entries.size() > visibleCount) {
					int navY = panelY + panelH - 24;
					this.addRenderableWidget(
							Button.builder(Component.literal("▲ Prev"), btn -> {
								if (scrollOffset > 0) {
									scrollOffset--;
									rebuildGui();
								}
							}).bounds(panelX + 10, navY, 80, 18).build()
					);

					this.addRenderableWidget(
							Button.builder(Component.literal("Next ▼"), btn -> {
								if (scrollOffset + visibleCount < entries.size()) {
									scrollOffset++;
									rebuildGui();
								}
							}).bounds(panelX + panelW - 90, navY, 80, 18).build()
					);
				}
			} else {
				// Tab 1: Sus Chunks Customizer
				int row = 0;

				// Master Overlay Toggle
				this.addRenderableWidget(
						Button.builder(getSusToggleMessage(), btn -> {
							executeGuiCommand("/suschunks");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Auto-Spawn Toggle (Every 30s)
				this.addRenderableWidget(
						Button.builder(getSusAutoSpawnMessage(), btn -> {
							executeGuiCommand("/suschunks auto");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Randomize Spots
				this.addRenderableWidget(
						Button.builder(Component.literal("🎲 Randomize Spots Near Player"), btn -> {
							executeGuiCommand("/suschunks randomize");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Color Cycler
				this.addRenderableWidget(
						Button.builder(Component.literal("🎨 Color: §b" + SUS_COLORS[susColorIndex]), btn -> {
							executeGuiCommand("/suschunks color");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Opacity Cycler
				this.addRenderableWidget(
						Button.builder(Component.literal("🌫 Opacity: §b" + SUS_OPACITIES[susOpacityIndex]), btn -> {
							executeGuiCommand("/suschunks opacity");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Curviness Cycler
				this.addRenderableWidget(
						Button.builder(Component.literal("📐 Curviness: §b" + SUS_CURVINESS[susCurvinessIndex]), btn -> {
							executeGuiCommand("/suschunks curve");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);

				// Elevation Cycler
				this.addRenderableWidget(
						Button.builder(Component.literal("📏 Elevation: §b" + SUS_ELEVATIONS[susElevationIndex]), btn -> {
							executeGuiCommand("/suschunks elevation");
							rebuildGui();
						}).bounds(panelX + 10, contentY + (row++ * 24), contentW, 20).build()
				);
			}
		}

		private static Component formatCommandLabel(CommandUiEntry entry) {
			MutableComponent comp = Component.literal(entry.commandName);
			if (entry.isToggle) {
				comp.append(Component.literal(" : "));
				if (isCommandActive(entry.fullCommand)) {
					comp.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
				} else {
					comp.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				}
			} else {
				comp.append(Component.literal(" ➔ [RUN]").withStyle(ChatFormatting.GRAY));
			}
			return comp;
		}

		private static Component getSusToggleMessage() {
			MutableComponent c = Component.literal("Sus Chunks Overlay:  ");
			if (susChunksEnabled) {
				c.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			} else {
				c.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			return c;
		}

		private static Component getSusAutoSpawnMessage() {
			MutableComponent c = Component.literal("Auto-Spawn (Every 30s):  ");
			if (susAutoSpawn) {
				c.append(Component.literal("[ON]").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			} else {
				c.append(Component.literal("[OFF]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			return c;
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
			int panelW = Math.min(360, this.width - 40);
			int panelH = Math.min(270, this.height - 40);
			int panelX = (this.width - panelW) / 2;
			int panelY = (this.height - panelH) / 2;

			// Dark theme window background
			graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE141416);
			// Accent top border (Cyan)
			graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, 0xFF00D9FF);
			// Window outline
			graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF2A2A30);
			graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF2A2A30);
			graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF2A2A30);

			// Title Bar
			graphics.drawString(this.font, "⚡ ClickGUI  §7[Insert to Toggle]", panelX + 12, panelY + 10, 0xFFFFFFFF, false);

			super.render(graphics, mouseX, mouseY, partialTicks);
		}

		@Override
		public void onClose() {
			if (this.minecraft != null) {
				this.minecraft.setScreen(this.parent);
			}
		}
	}

	// ===============================================================================
	// 3x3 MINING IMPLEMENTATION
	// ===============================================================================
	private static void handle3x3Break(ServerLevel world, ServerPlayer player, BlockPos center, BlockState centerState) {
		UUID pid = player.getUUID();
		if (RECURSION_GUARD.contains(pid)) return;
		RECURSION_GUARD.add(pid);
		try {
			ItemStack tool = player.getMainHandItem();
			if (!tool.isCorrectToolForDrops(centerState)) return;

			Direction facing = getPlayerLookDirection(player);
			List<BlockPos> extraBlocks = get3x3Positions(center, facing);

			int extraBroken = 0;
			for (BlockPos target : extraBlocks) {
				if (tool.isEmpty()) break;
				BlockState st = world.getBlockState(target);
				if (st.isAir() || st.getDestroySpeed(world, target) < 0) continue;
				if (!tool.isCorrectToolForDrops(st)) continue;

				if (world.destroyBlock(target, false, player)) {
					Block.dropResources(st, world, target, world.getBlockEntity(target), player, tool);
					tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
					extraBroken++;
				}
			}

			if (extraBroken > 0) {
				PlayerState state = getState(pid);
				if (state.enabled) {
					state.addVl(Math.min(3.0, extraBroken * VL_PER_EXTRA));
				}
			}
		} finally {
			RECURSION_GUARD.remove(pid);
		}
	}

	private static Direction getPlayerLookDirection(ServerPlayer player) {
		HitResult hr = player.pick(20.0, 0.0f, false);
		if (hr instanceof BlockHitResult bhr) {
			return bhr.getDirection();
		}
		return Direction.orderedByNearest(player)[0];
	}

	private static List<BlockPos> get3x3Positions(BlockPos center, Direction hitFace) {
		List<BlockPos> list = new ArrayList<>(8);
		Direction.Axis axis = hitFace.getAxis();

		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (a == 0 && b == 0) continue;
				BlockPos pos = switch (axis) {
					case Y -> center.offset(a, 0, b);
					case X -> center.offset(0, a, b);
					case Z -> center.offset(a, b, 0);
				};
				list.add(pos);
			}
		}
		return list;
	}

	// ===============================================================================
	// FAKE ANTI-CHEAT (/fakeban)
	// ===============================================================================
	private static class PlayerState {
		boolean enabled = false;
		double vl = 0.0;
		boolean watched = false;
		boolean trapSpawned = false;
		int ticksSinceWatch = 0;
		int blocksInWindow = 0;
		int windowTimer = 0;
		double lastX = 0, lastY = 0, lastZ = 0;
		boolean hasLastPos = false;

		void addVl(double amount) {
			vl = Math.min(100.0, Math.max(0.0, vl + amount));
		}
	}

	private static class HeadPass {
		final ServerLevel level;
		final ServerPlayer target;
		final ArmorStand stand;
		final Vec3 start;
		final Vec3 end;
		int tick = 0;

		HeadPass(ServerLevel level, ServerPlayer target, ArmorStand stand, Vec3 start, Vec3 end) {
			this.level = level;
			this.target = target;
			this.stand = stand;
			this.start = start;
			this.end = end;
		}
	}

	private static class TrapBase {
		final ServerLevel level;
		final ServerPlayer target;
		final BlockPos center;
		final double radiusSq;
		double lastDistSq = Double.MAX_VALUE;
		int consecutiveApproaches = 0;

		TrapBase(ServerLevel level, ServerPlayer target, BlockPos center, double radius) {
			this.level = level;
			this.target = target;
			this.center = center;
			this.radiusSq = radius * radius;
		}
	}

	private static record AdminSkin(String name, String uuid, String textureBase64) {}

	private static PlayerState getState(UUID id) {
		return STATES.computeIfAbsent(id, k -> new PlayerState());
	}

	public static final Set<UUID> ENABLED_FAKEBAN = new HashSet<>();

	private static void onPlayerMineBlock(ServerPlayer p, BlockPos pos) {
		PlayerState st = getState(p.getUUID());
		if (!st.enabled) return;

		double add = VL_PER_BLOCK;
		if (pos.getY() <= TRIGGER_Y) {
			add += VL_DEEP_BONUS;
		}
		st.blocksInWindow++;
		if (st.blocksInWindow >= FAST_BREAKS) {
			add += VL_FAST;
		}
		st.addVl(add);
	}

	private static void tickFakeAntiCheat(MinecraftServer server) {
		Iterator<HeadPass> hIt = ACTIVE_HEADS.iterator();
		while (hIt.hasNext()) {
			HeadPass h = hIt.next();
			h.tick++;
			double t = (double) h.tick / HEAD_TICKS;
			if (t >= 1.0 || !h.target.isAlive() || h.target.level() != h.level) {
				h.stand.discard();
				hIt.remove();
				continue;
			}
			Vec3 cur = h.start.lerp(h.end, t);
			h.stand.setPos(cur.x, cur.y, cur.z);
			Vec3 toPlayer = h.target.position().add(0, h.target.getEyeHeight(), 0).subtract(cur);
			float yaw = (float) Math.toDegrees(Math.atan2(-toPlayer.x, toPlayer.z));
			h.stand.setYRot(yaw);
			h.stand.setYHeadRot(yaw);
		}

		Iterator<TrapBase> tIt = ACTIVE_TRAPS.iterator();
		while (tIt.hasNext()) {
			TrapBase tb = tIt.next();
			if (!tb.target.isAlive() || tb.target.level() != tb.level) {
				tIt.remove();
				continue;
			}
			Vec3 pPos = tb.target.position();
			double distSq = pPos.distanceToSqr(tb.center.getX() + 0.5, tb.center.getY() + 0.5, tb.center.getZ() + 0.5);

			if (distSq <= TRAP_ENTER_DIST * TRAP_ENTER_DIST) {
				fakebanKick(tb.target, "Illegal position / clipping");
				tIt.remove();
				continue;
			}

			if (distSq <= TRAP_APPROACH_RANGE * TRAP_APPROACH_RANGE) {
				if (distSq < tb.lastDistSq - 0.05) {
					tb.consecutiveApproaches++;
					if (tb.consecutiveApproaches >= TRAP_APPROACH_BREAKS) {
						fakebanKick(tb.target, "X-Ray / Mining directly toward unregistered structure");
						tIt.remove();
						continue;
					}
				} else if (distSq > tb.lastDistSq + 0.5) {
					tb.consecutiveApproaches = 0;
				}
				tb.lastDistSq = distSq;
			}
		}

		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			PlayerState st = getState(p.getUUID());
			if (!st.enabled) continue;

			if (st.hasLastPos) {
				double dx = p.getX() - st.lastX;
				double dy = p.getY() - st.lastY;
				double dz = p.getZ() - st.lastZ;
				double horizontalSpeed = Math.sqrt(dx * dx + dz * dz) * 20.0;

				if (horizontalSpeed > SPEED_LIMIT && !p.isFallFlying()) {
					st.addVl(VL_SPEED / 20.0);
				}
				if (p.getAbilities().flying && !p.isCreative() && !p.isSpectator()) {
					st.addVl(VL_FLY / 20.0);
				}
			}
			st.lastX = p.getX();
			st.lastY = p.getY();
			st.lastZ = p.getZ();
			st.hasLastPos = true;

			st.windowTimer++;
			if (st.windowTimer >= 60) {
				st.windowTimer = 0;
				st.blocksInWindow = 0;
			}

			st.addVl(-VL_DECAY_PER_SEC / 20.0);

			if (!st.watched && st.vl >= VL_WATCH) {
				st.watched = true;
				st.ticksSinceWatch = 0;
				triggerHeadScare(p);
			}

			if (st.watched && !st.trapSpawned) {
				st.ticksSinceWatch++;
				if (st.ticksSinceWatch >= TRAP_DELAY_TICKS && st.vl >= VL_TRAP) {
					st.trapSpawned = true;
					spawnTrapBase(p);
				}
			}
		}
	}

	private static void triggerHeadScare(ServerPlayer p) {
		ServerLevel level = ((ServerLevel) p.level());
		AdminSkin skin = ADMIN_SKINS[ThreadLocalRandom.current().nextInt(ADMIN_SKINS.length)];

		Vec3 look = p.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
		if (right.lengthSqr() < 0.001) right = new Vec3(1, 0, 0);

		Vec3 eye = p.position().add(0, p.getEyeHeight(), 0);
		Vec3 forward = look.scale(1.6);
		Vec3 mid = eye.add(forward);

		double span = 2.4;
		boolean leftToRight = ThreadLocalRandom.current().nextBoolean();
		Vec3 start = mid.add(right.scale(leftToRight ? -span : span)).subtract(0, 0.4, 0);
		Vec3 end = mid.add(right.scale(leftToRight ? span : -span)).subtract(0, 0.4, 0);

		ArmorStand stand = new ArmorStand(level, start.x, start.y, start.z);
		stand.setInvisible(true);
		stand.setNoGravity(true);
		stand.noPhysics = true;
		stand.setSilent(true);

		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		applySkinToHead(head, skin);
		stand.setItemSlot(EquipmentSlot.HEAD, head);

		level.addFreshEntity(stand);
		ACTIVE_HEADS.add(new HeadPass(level, p, stand, start, end));

		level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_HEARTBEAT, p.getSoundSource(), 1.0f, 0.8f);

		run(p, "title @s times 10 40 10");
		run(p, "title @s subtitle {\"text\":\"[ " + skin.name() + " is watching you ]\",\"color\":\"red\",\"bold\":true}");
		run(p, "title @s title {\"text\":\"\"}");
	}

	private static void applySkinToHead(ItemStack head, AdminSkin skin) {
		try {
			GameProfile profile = new GameProfile(UUID.fromString(skin.uuid()), skin.name());
			ResolvableProfile res = new ResolvableProfile(profile);
			head.set(DataComponents.PROFILE, res);
		} catch (Throwable t) {
			LOGGER.debug("Skin profile notice: {}", t.getMessage());
		}
	}

	private static void spawnTrapBase(ServerPlayer p) {
		ServerLevel level = ((ServerLevel) p.level());
		Path file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(FAKEBASE_FILE);
		if (!Files.exists(file)) {
			Path sub = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("fakebase").resolve(FAKEBASE_FILE);
			if (Files.exists(sub)) file = sub;
		}

		if (!Files.exists(file)) {
			spawnFakeBaseNear(level, p.blockPosition(), true);
			return;
		}

		try {
			Schematic schem = LitematicaParser.read(file);
			BlockPos target = p.blockPosition().relative(p.getDirection(), 14);
			loadAndPlaceJob(level, schem, target, true, p);
		} catch (Throwable t) {
			LOGGER.error("Failed to load trap base file: {}", t.getMessage());
		}
	}

	private static void fakebanKick(ServerPlayer p, String reason) {
		String msg = "§cYou are permanently banned from this server!\n\n"
				+ "§7Reason: §f[Cheat Detection] " + reason + "\n"
				+ "§7Ban ID: §f#" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF)).toUpperCase(Locale.ROOT) + "\n"
				+ "§7Appeal at: §e" + APPEAL_URL;
		p.connection.disconnect(Component.literal(msg));
	}

	// ===============================================================================
	// FAKE BASES AUTO-GENERATION / COOLDOWN LOGIC
	// ===============================================================================
	private static void spawnFakeBaseNear(ServerLevel world, BlockPos origin, boolean isTrapBase) {
		List<Path> files = listSchematicFiles();
		if (files.isEmpty()) return;

		Path chosen = files.get(ThreadLocalRandom.current().nextInt(files.size()));
		try {
			Schematic schem = LitematicaParser.read(chosen);
			int rad = Math.max(12, MAX_RADIUS);
			int offX = ThreadLocalRandom.current().nextInt(-rad, rad + 1);
			int offZ = ThreadLocalRandom.current().nextInt(-rad, rad + 1);
			BlockPos spawnPos = origin.offset(offX, 0, offZ);

			if (spawnPos.getY() < MIN_BUILD_Y) {
				spawnPos = new BlockPos(spawnPos.getX(), MIN_BUILD_Y, spawnPos.getZ());
			}

			loadAndPlaceJob(world, schem, spawnPos, isTrapBase, null);
			nextAllowed = System.currentTimeMillis() + COOLDOWN_MS;
		} catch (Throwable t) {
			LOGGER.error("Failed placing schematic: {}", t.getMessage());
		}
	}

	private static List<Path> listSchematicFiles() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		if (!Files.isDirectory(dir)) return Collections.emptyList();
		try (Stream<Path> s = Files.walk(dir, 3)) {
			return s.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
					.toList();
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	private static void loadAndPlaceJob(ServerLevel world, Schematic schem, BlockPos origin, boolean isTrap, ServerPlayer target) {
		Job job = new Job(world, schem, origin, isTrap, target);
		activeJob = job;
	}

	private static void tickActiveJob() {
		if (activeJob == null) return;
		boolean done = activeJob.step();
		if (done) {
			if (activeJob.isTrap && activeJob.target != null) {
				ACTIVE_TRAPS.add(new TrapBase(activeJob.level, activeJob.target, activeJob.origin, 16.0));
				triggerHeadScare(activeJob.target);
			}
			activeJob = null;
		}
	}

	private static class Job {
		final ServerLevel level;
		final Schematic schem;
		final BlockPos origin;
		final boolean isTrap;
		final ServerPlayer target;
		int cursor = 0;
		final int total;

		Job(ServerLevel level, Schematic schem, BlockPos origin, boolean isTrap, ServerPlayer target) {
			this.level = level;
			this.schem = schem;
			this.origin = origin;
			this.isTrap = isTrap;
			this.target = target;
			this.total = schem.blocks.size();
		}

		boolean step() {
			int count = 0;
			while (cursor < total && count < BLOCKS_PER_TICK) {
				BlockPlacement bp = schem.blocks.get(cursor++);
				BlockPos targetPos = origin.offset(bp.pos);
				level.setBlock(targetPos, bp.state, 2);
				count++;
			}
			return cursor >= total;
		}
	}

	private static record BlockPlacement(BlockPos pos, BlockState state) {}
	private static class Schematic {
		final List<BlockPlacement> blocks = new ArrayList<>();
	}

	// ===============================================================================
	// LITEMATICA NBT PARSER
	// ===============================================================================
	private static class LitematicaParser {
		public static Schematic read(Path file) throws Exception {
			Schematic schem = new Schematic();
			try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
				NbtCompound root = NbtCompound.read(dis);
				NbtCompound regions = root.getCompound("Regions");
				if (regions == null) return schem;

				for (String regKey : regions.keys()) {
					NbtCompound reg = regions.getCompound(regKey);
					if (reg == null) continue;

					NbtCompound size = reg.getCompound("Size");
					int sx = Math.abs(size.getInt("x"));
					int sy = Math.abs(size.getInt("y"));
					int sz = Math.abs(size.getInt("z"));

					NbtList paletteList = reg.getList("BlockStatePalette");
					List<BlockState> palette = new ArrayList<>();
					HolderLookup<Block> lookup = BuiltInRegistries.BLOCK.asLookup();

					for (int i = 0; i < paletteList.size(); i++) {
						NbtCompound bsTag = paletteList.getCompound(i);
						String bName = bsTag.getString("Name");
						try {
							BlockState st = BlockStateParser.parseForBlock(lookup, bName, false).blockState();
							palette.add(st);
						} catch (Throwable t) {
							palette.add(Blocks.AIR.defaultBlockState());
						}
					}

					long[] blockStates = reg.getLongArray("BlockStates");
					int bits = Math.max(2, 64 - Long.numberOfLeadingZeros(Math.max(0, palette.size() - 1)));
					BitArray bitArray = new BitArray(bits, sx * sy * sz, blockStates);

					for (int y = 0; y < sy; y++) {
						for (int z = 0; z < sz; z++) {
							for (int x = 0; x < sx; x++) {
								int index = (y * sz + z) * sx + x;
								int palIndex = bitArray.get(index);
								if (palIndex >= 0 && palIndex < palette.size()) {
									BlockState bs = palette.get(palIndex);
									if (!bs.isAir()) {
										schem.blocks.add(new BlockPlacement(new BlockPos(x, y, z), bs));
									}
								}
							}
						}
					}
				}
			}
			return schem;
		}
	}

	private static class BitArray {
		private final int bits;
		private final int size;
		private final long[] data;
		private final long mask;

		BitArray(int bits, int size, long[] data) {
			this.bits = bits;
			this.size = size;
			this.data = data;
			this.mask = (1L << bits) - 1L;
		}

		int get(int index) {
			if (index < 0 || index >= size || data.length == 0) return 0;
			long bitIndex = (long) index * bits;
			int startLong = (int) (bitIndex >> 6);
			int startBit = (int) (bitIndex & 63);

			if (startLong >= data.length) return 0;

			long val = data[startLong] >>> startBit;
			int endBit = startBit + bits;
			if (endBit > 64 && (startLong + 1) < data.length) {
				val |= data[startLong + 1] << (64 - startBit);
			}
			return (int) (val & mask);
		}
	}

	private static class NbtCompound {
		private final Map<String, Object> map = new HashMap<>();

		static NbtCompound read(DataInputStream in) throws IOException {
			byte b = in.readByte();
			if (b == 0) return new NbtCompound();
			in.readUTF(); // root name
			return readCompoundContent(in);
		}

		static NbtCompound readCompoundContent(DataInputStream in) throws IOException {
			NbtCompound compound = new NbtCompound();
			while (true) {
				byte tagId = in.readByte();
				if (tagId == 0) break;
				String name = in.readUTF();
				Object obj = readTag(tagId, in);
				compound.map.put(name, obj);
			}
			return compound;
		}

		static Object readTag(byte tagId, DataInputStream in) throws IOException {
			return switch (tagId) {
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
				case 9 -> NbtList.read(in);
				case 10 -> readCompoundContent(in);
				case 11 -> {
					int len = in.readInt();
					int[] arr = new int[len];
					for (int i = 0; i < len; i++) arr[i] = in.readInt();
					yield arr;
				}
				case 12 -> {
					int len = in.readInt();
					long[] arr = new long[len];
					for (int i = 0; i < len; i++) arr[i] = in.readLong();
					yield arr;
				}
				default -> null;
			};
		}

		public Set<String> keys() { return map.keySet(); }
		public NbtCompound getCompound(String key) { Object o = map.get(key); return o instanceof NbtCompound c ? c : null; }
		public NbtList getList(String key) { Object o = map.get(key); return o instanceof NbtList l ? l : new NbtList(); }
		public String getString(String key) { Object o = map.get(key); return o instanceof String s ? s : ""; }
		public int getInt(String key) { Object o = map.get(key); return o instanceof Number n ? n.intValue() : 0; }
		public long[] getLongArray(String key) { Object o = map.get(key); return o instanceof long[] arr ? arr : new long[0]; }
	}

	private static class NbtList {
		private final List<Object> list = new ArrayList<>();

		static NbtList read(DataInputStream in) throws IOException {
			NbtList l = new NbtList();
			byte itemType = in.readByte();
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				l.list.add(NbtCompound.readTag(itemType, in));
			}
			return l;
		}

		public int size() { return list.size(); }
		public NbtCompound getCompound(int idx) { Object o = list.get(idx); return o instanceof NbtCompound c ? c : new NbtCompound(); }
	}

	// ===============================================================================
	// /rtp COMMAND IMPLEMENTATION
	// ===============================================================================
	private static int cmdRtp(CommandSourceStack source) {
		try {
			ServerPlayer p = source.getPlayerOrException();
			ServerLevel level = ((ServerLevel) p.level());

			for (int i = 0; i < RTP_TRIES; i++) {
				double dist = ThreadLocalRandom.current().nextDouble(RTP_MIN, RTP_MAX);
				double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
				int x = (int) (p.getX() + Math.cos(angle) * dist);
				int z = (int) (p.getZ() + Math.sin(angle) * dist);

				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (y <= level.getMinY() + 2) continue;

				BlockPos ground = new BlockPos(x, y - 1, z);
				BlockState groundState = level.getBlockState(ground);
				if (!groundState.isAir() && groundState.getFluidState().isEmpty() && !groundState.is(Blocks.LAVA)) {
					p.teleportTo(level, x + 0.5, y + 0.2, z + 0.5, p.getYRot(), p.getXRot());
					p.sendSystemMessage(Component.literal("§a[RTP] Teleported to §e" + x + " " + y + " " + z));
					PlayerState st = getState(p.getUUID());
					if (st.enabled) st.addVl(VL_RTP);
					return 1;
				}
			}
			p.sendSystemMessage(Component.literal("§c[RTP] Could not find a safe landing spot. Try again."));
		} catch (CommandSyntaxException e) {
			source.sendFailure(Component.literal("Only players can use /rtp"));
		}
		return 0;
	}

	private static void run(ServerPlayer p, String command) {
		if (!(p.level() instanceof ServerLevel level)) return;
		try {
			CommandSourceStack src = p.createCommandSourceStack()
					.withLevel(level)
					.withSuppressedOutput();
			level.getServer().getCommands().performPrefixedCommand(src, command);
		} catch (Throwable t) {
			LOGGER.debug("Command notice: {}", t.getMessage());
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
