package com.example;

import java.awt.Color;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
	private static final long COOLDOWN_MS = 60_000L;     // wait 60 seconds between bases
	private static final int MAX_RADIUS = 30;            // search up to 30 blocks away for clear space
	private static final int BLOCKS_PER_TICK = 512;      // pace placement so the game doesn't freeze
	private static final int MAX_VOLUME = 500_000;       // skip schematics bigger than this
	private static final boolean DEBUG_LOG = true;       // print placement steps in the console

	// ------------------------------------------------------------------ state
	private static final Set<UUID> ENABLED_3X3 = new HashSet<>();
	private static final Set<UUID> ENABLED_FAKEBAN = new HashSet<>();
	private static final Map<UUID, Direction> PENDING_FACE = new HashMap<>();
	private static final Map<UUID, BlockPos> CURRENT_MINING = new HashMap<>();
	private static int extraBroken = 0;

	private static long nextAllowed = 0L;
	private static Job activeJob = null;
	private static final Map<UUID, Integer> DEEP_STREAK = new HashMap<>();

	private static final Map<UUID, Watch> WATCH = new HashMap<>();
	private static final Map<UUID, HeadAnim> HEADS = new HashMap<>();
	private static Trap trap = null;
	private static int cmdErrors = 0;

	private static final Skin[] SKINS = {
			new Skin("Pastagamer08", UUID.fromString("5cff4acd-fda7-413a-a369-a0928b5c70c9")),
			new Skin("WiZoX", UUID.fromString("77482a7d-e661-4927-8641-ee5c62245dd1"))
	};

	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED OVERLAY CONFIGURATION & STATE
	// ===============================================================================
	public static boolean susChunksEnabled = false;
	public static boolean susAutoSpawn = true;    // automatically spawns a random sus chunk every 30s
	private static int susAutoTimer = 0;          // counts ticks (600 ticks = 30 seconds)
	public static int susColorIndex = 0;          // 0=Cyan (matches image), 1=Green, 2=Red, 3=Purple, 4=Yellow, 5=Orange, 6=White, 7=Rainbow
	public static int susOpacityIndex = 2;        // 0=25%, 1=50%, 2=75%, 3=100%
	public static int susCurvinessIndex = 2;      // 0=Sharp (0°), 1=Low (R=1.5), 2=Medium (R=3.5), 3=High (R=5.5), 4=Circular (R=7.9)
	public static int susElevationIndex = 0;      // 0=Ground (+0.0), 1=Raised (+0.5), 2=Hover (+1.0), 3=Float (+2.0)

	public static final String[] SUS_COLORS = {"Cyan", "Green", "Red", "Purple", "Yellow", "Orange", "White", "Rainbow"};
	public static final float[][] SUS_COLOR_RGB = {
			{0.0f, 0.90f, 1.0f},  // Cyan (vibrant light blue matching screenshot)
			{0.0f, 1.0f, 0.35f},  // Green
			{1.0f, 0.20f, 0.25f}, // Red
			{0.80f, 0.20f, 1.0f}, // Purple
			{1.0f, 0.95f, 0.15f}, // Yellow
			{1.0f, 0.55f, 0.05f}, // Orange
			{1.0f, 1.0f, 1.0f},   // White
			{0.0f, 0.90f, 1.0f}   // Rainbow (dynamic)
	};

	public static final String[] SUS_OPACITIES = {"25%", "50%", "75%", "100%"};
	public static final float[] SUS_OPACITY_VALUES = {0.25f, 0.50f, 0.75f, 1.0f};

	public static final String[] SUS_CURVINESS = {"Sharp (0°)", "Low (R=1.5)", "Medium (R=3.5)", "High (R=5.5)", "Circular (R=7.9)"};
	public static final float[] SUS_CURVE_VALUES = {0.0f, 1.5f, 3.5f, 5.5f, 7.9f};

	public static final String[] SUS_ELEVATIONS = {"Ground (+0.0)", "Raised (+0.5)", "Hover (+1.0)", "Float (+2.0)"};
	public static final double[] SUS_ELEVATION_VALUES = {0.0, 0.5, 1.0, 2.0};

	public static final class SusSpot {
		public final int chunkX;
		public final int chunkZ;
		public final double minX;
		public final double minZ;
		public final double maxX;
		public final double maxZ;
		public final double centerX;
		public final double centerZ;
		public double cachedGroundY = Double.NaN;

		public SusSpot(int chunkX, int chunkZ) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.minX = chunkX << 4;
			this.minZ = chunkZ << 4;
			this.maxX = this.minX + 16.0;
			this.maxZ = this.minZ + 16.0;
			this.centerX = this.minX + 8.0;
			this.centerZ = this.minZ + 8.0;
		}
	}

	public static final List<SusSpot> SUS_SPOTS = new ArrayList<>();

	public static void randomizeSusSpots() {
		SUS_SPOTS.clear();
		Minecraft client = Minecraft.getInstance();
		int pChunkX = 0;
		int pChunkZ = 0;
		if (client.player != null) {
			pChunkX = client.player.getBlockX() >> 4;
			pChunkZ = client.player.getBlockZ() >> 4;
		}
		// Spot 0: Chunk where player is currently standing (guaranteed immediate view)
		SUS_SPOTS.add(new SusSpot(pChunkX, pChunkZ));

		// Spots 1-3: Nearby random chunks (within 1-3 chunks)
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		for (int i = 0; i < 3; i++) {
			int dx = rnd.nextInt(-3, 4);
			int dz = rnd.nextInt(-3, 4);
			if (dx == 0 && dz == 0) dx = (i % 2 == 0 ? 1 : -1);
			SUS_SPOTS.add(new SusSpot(pChunkX + dx, pChunkZ + dz));
		}
	}

	public static void spawnRandomSusSpot(Player player) {
		int pChunkX = player != null ? (player.getBlockX() >> 4) : 0;
		int pChunkZ = player != null ? (player.getBlockZ() >> 4) : 0;
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int dx = rnd.nextInt(-3, 4);
		int dz = rnd.nextInt(-3, 4);
		if (dx == 0 && dz == 0) dx = 1;

		SusSpot newSpot = new SusSpot(pChunkX + dx, pChunkZ + dz);
		if (SUS_SPOTS.size() >= 5) {
			SUS_SPOTS.remove(0);
		}
		SUS_SPOTS.add(newSpot);

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.displayClientMessage(
					Component.literal("§d[🔮 Sus Chunks] §eNew random suspicious chunk at §b[" + newSpot.chunkX + ", " + newSpot.chunkZ + "]§e!"),
					false
			);
		}
	}

	// ===============================================================================
	// ClickGUI Auto-Discovery Command Entry Data
	// ===============================================================================
	public static class GuiCommand {
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

			// ---- /3x3 ----
			dispatcher.register(Commands.literal("3x3")
					.executes(ctx -> {
						ServerPlayer p = ctx.getSource().getPlayerOrException();
						UUID id = p.getUUID();
						if (ENABLED_3X3.contains(id)) {
							ENABLED_3X3.remove(id);
							p.sendSystemMessage(Component.literal("3x3 mining §cDISABLED"));
						} else {
							ENABLED_3X3.add(id);
							p.sendSystemMessage(Component.literal("3x3 mining §aENABLED§r (crouch to mine 1 block)"));
						}
						return 1;
					}));

			// ---- /fakebase ----
			dispatcher.register(Commands.literal("fakebase").executes(ctx -> cmdFakeBase(ctx.getSource())));

			// ---- /fakeban ----
			dispatcher.register(Commands.literal("fakeban")
					.executes(ctx -> cmdFakeBan(ctx.getSource()))
					.then(Commands.literal("status").executes(ctx -> cmdFakeBanStatus(ctx.getSource())))
					.then(Commands.literal("watch").executes(ctx -> cmdFakeBanWatch(ctx.getSource())))
					.then(Commands.literal("trap").executes(ctx -> cmdFakeBanTrap(ctx.getSource())))
					.then(Commands.literal("kick").executes(ctx -> cmdFakeBanKick(ctx.getSource())))
					.then(Commands.literal("reset").executes(ctx -> cmdFakeBanReset(ctx.getSource()))));

			// ---- /rtp ----
			dispatcher.register(Commands.literal("rtp").executes(ctx -> cmdRtp(ctx.getSource())));

			// ---- /suschunks : Fake Sus Chunks 2D rounded ground overlay ----
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
					.then(Commands.literal("elevation").executes(ctx -> {
						susElevationIndex = (susElevationIndex + 1) % SUS_ELEVATIONS.length;
						ctx.getSource().sendSuccess(() -> Component.literal("Sus Chunks Elevation: §e" + SUS_ELEVATIONS[susElevationIndex]), false);
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
			rememberFace(world, player, pos);
			return true;
		});

		// ---- break the other 8 blocks, and trigger the random fake base ----
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (!(world instanceof ServerLevel serverLevel)) return;
			if (!(player instanceof ServerPlayer serverPlayer)) return;

			handle3x3(serverLevel, serverPlayer, pos);
			handleBaseSpawning(serverLevel, serverPlayer, pos, state);
		});

		// ---- pacing tick: place a few blocks each tick so we don't freeze the game ----
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (activeJob != null) {
				tickPlacement();
			}
			tickWatch(server);
			tickTrap(server);
		});

		// ---- clean up state when server stops or players leave ----
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			activeJob = null;
			trap = null;
			HEADS.values().forEach(HeadAnim::discard);
			HEADS.clear();
			WATCH.clear();
			DEEP_STREAK.clear();
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUUID();
			ENABLED_3X3.remove(id);
			ENABLED_FAKEBAN.remove(id);
			PENDING_FACE.remove(id);
			CURRENT_MINING.remove(id);
			DEEP_STREAK.remove(id);
			WATCH.remove(id);
			HeadAnim anim = HEADS.remove(id);
			if (anim != null) anim.discard();
			if (trap != null && trap.target.equals(id)) {
				trap = null;
			}
		});

		LOGGER.info("ExampleMod initialized.");
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = getGuiCategory();
		guiKeyMapping = registerKeyMapping(createGuiKeyMapping(category));

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

			// Sus Chunks Auto-Spawn timer (600 ticks = 30 seconds)
			if (susChunksEnabled && susAutoSpawn && client.level != null && client.player != null) {
				susAutoTimer++;
				if (susAutoTimer >= 600) {
					susAutoTimer = 0;
					spawnRandomSusSpot(client.player);
				}
			} else if (!susChunksEnabled) {
				susAutoTimer = 0;
			}
		});

		// 2. In-world 2D rounded square overlay rendering on ground level (no particles, passable)
		WorldRenderEvents.AFTER_TRANSLUCENT.register(ExampleMod::renderSusChunksWorld);
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


	// ===============================================================================
	// SUS CHUNKS (FAKE) 2D ROUNDED SQUARE OVERLAY RENDERING (NO PARTICLES, PASSABLE)
	// ===============================================================================
	private static void renderSusChunksWorld(WorldRenderContext context) {
		if (!susChunksEnabled) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;
		if (SUS_SPOTS.isEmpty()) {
			randomizeSusSpots();
		}

		PoseStack poseStack = context.matrixStack();
		if (poseStack == null) return;

		Camera camera = context.camera();
		if (camera == null) return;
		Vec3 camPos = camera.getPosition();
		if (camPos == null) return;

		MultiBufferSource consumers = context.consumers();
		if (consumers == null) {
			consumers = client.renderBuffers().bufferSource();
		}
		if (consumers == null) return;

		RenderType renderType = getOverlayRenderType();
		if (renderType == null) return;

		VertexConsumer consumer = consumers.getBuffer(renderType);
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
				emitQuadVertex(consumer, matrix, cx, groundY, cz, fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, p1[0], groundY, p1[1], fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, p2[0], groundY, p2[1], fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, cx, groundY, cz, fillR, fillG, fillB, fillA);

				// Back face (so it is visible from below as well)
				emitQuadVertex(consumer, matrix, cx, groundY, cz, fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, p2[0], groundY, p2[1], fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, p1[0], groundY, p1[1], fillR, fillG, fillB, fillA);
				emitQuadVertex(consumer, matrix, cx, groundY, cz, fillR, fillG, fillB, fillA);
			}

			// 2. Draw Vibrant Border Outline Ribbon
			if (innerPoints.size() == count) {
				for (int i = 0; i < count; i++) {
					double[] o1 = outerPoints.get(i);
					double[] o2 = outerPoints.get((i + 1) % count);
					double[] in1 = innerPoints.get(i);
					double[] in2 = innerPoints.get((i + 1) % count);

					// Front face
					emitQuadVertex(consumer, matrix, o1[0], groundY + 0.002, o1[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, o2[0], groundY + 0.002, o2[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, in2[0], groundY + 0.002, in2[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, in1[0], groundY + 0.002, in1[1], borderR, borderG, borderB, borderA);

					// Back face
					emitQuadVertex(consumer, matrix, in1[0], groundY + 0.002, in1[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, in2[0], groundY + 0.002, in2[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, o2[0], groundY + 0.002, o2[1], borderR, borderG, borderB, borderA);
					emitQuadVertex(consumer, matrix, o1[0], groundY + 0.002, o1[1], borderR, borderG, borderB, borderA);
				}
			}
		}

		poseStack.popPose();
	}

	private static void emitQuadVertex(VertexConsumer consumer, Matrix4f mat, double x, double y, double z, int r, int g, int b, int a) {
		try {
			consumer.addVertex(mat, (float) x, (float) y, (float) z).setColor(r, g, b, a);
			return;
		} catch (Throwable ignored) {}

		try {
			consumer.addVertex((float) x, (float) y, (float) z).setColor(r, g, b, a);
			return;
		} catch (Throwable ignored) {}

		try {
			// Pre-1.21 fallback: vertex(Matrix4f, float, float, float).color(int, int, int, int).endVertex()
			Method mVertex = consumer.getClass().getMethod("vertex", Matrix4f.class, float.class, float.class, float.class);
			Object ret = mVertex.invoke(consumer, mat, (float) x, (float) y, (float) z);
			Method mColor = ret.getClass().getMethod("color", int.class, int.class, int.class, int.class);
			Object ret2 = mColor.invoke(ret, r, g, b, a);
			try {
				Method mEnd = ret2.getClass().getMethod("endVertex");
				mEnd.invoke(ret2);
			} catch (Throwable ignored) {}
		} catch (Throwable ignored) {}
	}

	private static RenderType getOverlayRenderType() {
		try {
			return RenderType.lightning();
		} catch (Throwable t) {
			try {
				return RenderType.debugQuads();
			} catch (Throwable t2) {
				return null;
			}
		}
	}

	private static double getGroundYForSpot(ClientLevel level, Player player, SusSpot spot) {
		if (!Double.isNaN(spot.cachedGroundY)) {
			return spot.cachedGroundY;
		}

		int bx = (int) Math.floor(spot.centerX);
		int bz = (int) Math.floor(spot.centerZ);

		int refY = (int) player.getY();
		int startY = Math.min(level.getMaxBuildHeight() - 1, Math.max(level.getMinBuildHeight() + 10, refY + 30));
		int minY = Math.max(level.getMinBuildHeight(), refY - 40);

		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(bx, startY, bz);
		for (int y = startY; y >= minY; y--) {
			mpos.setY(y);
			BlockState state = level.getBlockState(mpos);
			if (!state.isAir() && state.getFluidState().isEmpty()) {
				spot.cachedGroundY = y + 1.02; // rest directly on surface
				return spot.cachedGroundY;
			}
		}

		// Chunk not yet loaded; don't cache yet, return player Y for now
		return player.getY() + 0.02;
	}

	private static List<double[]> get2DRoundedRectPoints(double minX, double maxX, double minZ, double maxZ, float radius, int stepsPerCorner) {
		List<double[]> points = new ArrayList<>();
		double hw = (maxX - minX) / 2.0;
		double hl = (maxZ - minZ) / 2.0;
		double r = Math.min(radius, Math.min(hw, hl) - 0.1);

		if (r <= 0.05) {
			points.add(new double[]{maxX, maxZ});
			points.add(new double[]{minX, maxZ});
			points.add(new double[]{minX, minZ});
			points.add(new double[]{maxX, minZ});
			return points;
		}

		double c1x = maxX - r, c1z = minZ + r; // Bottom-Right
		double c2x = maxX - r, c2z = maxZ - r; // Top-Right
		double c3x = minX + r, c3z = maxZ - r; // Top-Left
		double c4x = minX + r, c4z = minZ + r; // Bottom-Left

		// Top-Right arc (0 to PI/2)
		for (int i = 0; i <= stepsPerCorner; i++) {
			double ang = (Math.PI / 2.0) * (i / (double) stepsPerCorner);
			points.add(new double[]{c2x + r * Math.cos(ang), c2z + r * Math.sin(ang)});
		}
		// Top-Left arc (PI/2 to PI)
		for (int i = 1; i <= stepsPerCorner; i++) {
			double ang = (Math.PI / 2.0) + (Math.PI / 2.0) * (i / (double) stepsPerCorner);
			points.add(new double[]{c3x + r * Math.cos(ang), c3z + r * Math.sin(ang)});
		}
		// Bottom-Left arc (PI to 3*PI/2)
		for (int i = 1; i <= stepsPerCorner; i++) {
			double ang = Math.PI + (Math.PI / 2.0) * (i / (double) stepsPerCorner);
			points.add(new double[]{c4x + r * Math.cos(ang), c4z + r * Math.sin(ang)});
		}
		// Bottom-Right arc (3*PI/2 to 2*PI)
		for (int i = 1; i < stepsPerCorner; i++) {
			double ang = (1.5 * Math.PI) + (Math.PI / 2.0) * (i / (double) stepsPerCorner);
			points.add(new double[]{c1x + r * Math.cos(ang), c1z + r * Math.sin(ang)});
		}

		return points;
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
		addGuiCommand("suschunks", "Sus Chunks (Fake)", "Toggles 2D rounded square overlay on ground level (walk-through)", true);
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
					|| cmdName.toLowerCase(Locale.ROOT).contains("toggle");
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

		// Client-side immediate state toggles (returns early to prevent double-toggling on integrated server)
		if (clean.equalsIgnoreCase("suschunks")) {
			susChunksEnabled = !susChunksEnabled;
			if (susChunksEnabled) {
				if (SUS_SPOTS.isEmpty()) {
					randomizeSusSpots();
				}
				if (mc.player != null) {
					mc.player.displayClientMessage(
							Component.literal("§d[🔮 Sus Chunks] §aENABLED §7- 2D rounded overlay visible on ground!"),
							false
					);
				}
			} else {
				if (mc.player != null) {
					mc.player.displayClientMessage(
							Component.literal("§d[🔮 Sus Chunks] §cDISABLED"),
							false
					);
				}
			}
			susAutoTimer = 0;
			return;
		} else if (clean.equalsIgnoreCase("suschunks auto")) {
			susAutoSpawn = !susAutoSpawn;
			susAutoTimer = 0;
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §eAuto-Spawn (30s): " + (susAutoSpawn ? "§aENABLED" : "§cDISABLED")),
						false
				);
			}
			return;
		} else if (clean.equalsIgnoreCase("suschunks color")) {
			susColorIndex = (susColorIndex + 1) % SUS_COLORS.length;
			return;
		} else if (clean.equalsIgnoreCase("suschunks opacity")) {
			susOpacityIndex = (susOpacityIndex + 1) % SUS_OPACITIES.length;
			return;
		} else if (clean.equalsIgnoreCase("suschunks curviness")) {
			susCurvinessIndex = (susCurvinessIndex + 1) % SUS_CURVINESS.length;
			return;
		} else if (clean.equalsIgnoreCase("suschunks elevation")) {
			susElevationIndex = (susElevationIndex + 1) % SUS_ELEVATIONS.length;
			return;
		} else if (clean.equalsIgnoreCase("suschunks random")) {
			randomizeSusSpots();
			if (mc.player != null) {
				mc.player.displayClientMessage(
						Component.literal("§d[🔮 Sus Chunks] §aGenerated new random Sus Chunk locations near you."),
						false
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
			int panelH = Math.min(340, this.height - 30);
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
				.tooltip(Tooltip.create(Component.literal("Toggles 2D rounded square overlay on ground level (walk through, no particles)")))
				.build();
				this.addRenderableWidget(btnToggle);
				buttons.put("suschunks_toggle", btnToggle);

				// 2. Auto-Spawn (30s) Toggle Button
				Button btnAuto = Button.builder(getSusAutoMessage(), b -> {
					executeGuiCommand("suschunks auto");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Automatically spawns a new random suspicious chunk every 30 seconds")))
				.build();
				this.addRenderableWidget(btnAuto);
				buttons.put("suschunks_auto", btnAuto);

				// 3. Color Button
				Button btnColor = Button.builder(getSusColorMessage(), b -> {
					executeGuiCommand("suschunks color");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 2, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle 2D overlay color (Cyan, Green, Red, Purple, Rainbow, etc.)")))
				.build();
				this.addRenderableWidget(btnColor);
				buttons.put("suschunks_color", btnColor);

				// 4. Opacity Button
				Button btnOpacity = Button.builder(getSusOpacityMessage(), b -> {
					executeGuiCommand("suschunks opacity");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 3, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle plane fill and border translucency (25%, 50%, 75%, 100%)")))
				.build();
				this.addRenderableWidget(btnOpacity);
				buttons.put("suschunks_opacity", btnOpacity);

				// 5. Curviness Button
				Button btnCurve = Button.builder(getSusCurvinessMessage(), b -> {
					executeGuiCommand("suschunks curviness");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 4, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle corner curvature (Sharp 0°, Low, Medium, High, Circular)")))
				.build();
				this.addRenderableWidget(btnCurve);
				buttons.put("suschunks_curviness", btnCurve);

				// 6. Elevation Button
				Button btnElevation = Button.builder(getSusElevationMessage(), b -> {
					executeGuiCommand("suschunks elevation");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 5, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Click to cycle vertical height offset above ground level (Ground +0.0, +0.5, +1.0, +2.0)")))
				.build();
				this.addRenderableWidget(btnElevation);
				buttons.put("suschunks_elevation", btnElevation);

				// 7. Randomize Spots Button
				Button btnRandom = Button.builder(Component.literal("🎲 Spawn New Spots  §b[RUN]"), b -> {
					executeGuiCommand("suschunks random");
					this.updateAllButtons();
				})
				.bounds(panelX + 12, startY + spacing * 6, btnW, btnH)
				.tooltip(Tooltip.create(Component.literal("Instantly generates new random 2D sus chunk locations near you")))
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
				if (bAuto != null) bAuto.setMessage(getSusAutoMessage());

				Button bColor = buttons.get("suschunks_color");
				if (bColor != null) bColor.setMessage(getSusColorMessage());

				Button bOpacity = buttons.get("suschunks_opacity");
				if (bOpacity != null) bOpacity.setMessage(getSusOpacityMessage());

				Button bCurve = buttons.get("suschunks_curviness");
				if (bCurve != null) bCurve.setMessage(getSusCurvinessMessage());

				Button bElevation = buttons.get("suschunks_elevation");
				if (bElevation != null) bElevation.setMessage(getSusElevationMessage());
			}
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

		private static Component getSusAutoMessage() {
			MutableComponent c = Component.literal("Auto-Spawn (30s): ");
			if (susAutoSpawn) {
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

		private static Component getSusElevationMessage() {
			return Component.literal("Elevation: ")
					.append(Component.literal("[" + SUS_ELEVATIONS[susElevationIndex] + "]").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
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
			int panelH = Math.min(340, this.height - 30);
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

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (guiKeyMapping != null && guiKeyMapping.matches(keyCode, scanCode)) {
				this.onClose();
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
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
			Vec3 look = p.getLookAngle();
			face = Direction.getNearest(look.x, look.y, look.z).getOpposite();
		}

		Direction right;
		Direction up;
		if (face.getAxis() == Direction.Axis.Y) {
			Direction horizontal = p.getDirection();
			right = horizontal.getClockWise();
			up = horizontal;
		} else {
			right = face.getClockWise();
			up = Direction.UP;
		}

		ItemStack pick = p.getMainHandItem();

		for (int r = -1; r <= 1; r++) {
			for (int u = -1; u <= 1; u++) {
				if (r == 0 && u == 0) continue;
				BlockPos target = origin.relative(right, r).relative(up, u);
				BlockState s = level.getBlockState(target);

				if (s.isAir()) continue;
				if (s.getDestroySpeed(level, target) < 0) continue;
				if (!pick.isCorrectToolForDrops(s)) continue;

				CURRENT_MINING.put(p.getUUID(), target);
				try {
					boolean ok = p.gameMode.destroyBlock(target);
					if (ok) extraBroken++;
				} finally {
					CURRENT_MINING.remove(p.getUUID());
				}

				if (p.getMainHandItem().isEmpty()) return;
			}
		}
	}

	// ------------------------------------------------------------------ base spawning

	private static void handleBaseSpawning(ServerLevel level, ServerPlayer p, BlockPos pos, BlockState brokenState) {
		boolean isDeep = pos.getY() < TRIGGER_Y && isDeepslate(brokenState);

		if (ENABLED_FAKEBAN.contains(p.getUUID())) {
			if (isDeep) {
				int streak = DEEP_STREAK.compute(p.getUUID(), (k, v) -> (v == null ? 0 : v) + 1 + extraBroken);
				if (streak >= 3) {
					DEEP_STREAK.put(p.getUUID(), 0);
					addSuspicion(p, 1, "continuous deepslate mining (" + streak + " blocks)");
				}
			}
		}

		if (pos.getY() >= BASE_TRIGGER_Y) return;

		long now = System.currentTimeMillis();
		if (now < nextAllowed) return;
		if (activeJob != null) return;
		if (level.random.nextDouble() > CHANCE) return;

		nextAllowed = now + COOLDOWN_MS;

		try {
			String res = tryPlaceRandomBase(level, pos.getX(), pos.getY(), pos.getZ());
			if (DEBUG_LOG) LOGGER.info("Base placement at Y<{} result: {}", BASE_TRIGGER_Y, res);
		} catch (Throwable e) {
			LOGGER.warn("Could not place base: {}", e.toString(), e);
		}
	}

	// ------------------------------------------------------------------ fake anti-cheat (/fakeban)

	private static ServerPlayer requireSingleplayer(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null) {
			src.sendFailure(Component.literal("This command must be run by a player."));
			return null;
		}
		MinecraftServer s = src.getServer();
		if (s.isDedicatedServer() || s.getPlayerCount() > 1) {
			src.sendFailure(Component.literal("Only works in singleplayer (no other players allowed)."));
			return null;
		}
		return p;
	}

	private static int cmdFakeBan(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		UUID id = p.getUUID();
		if (ENABLED_FAKEBAN.contains(id)) {
			ENABLED_FAKEBAN.remove(id);
			WATCH.remove(id);
			HeadAnim anim = HEADS.remove(id);
			if (anim != null) anim.discard();
			if (trap != null && trap.target.equals(id)) trap = null;
			p.sendSystemMessage(Component.literal("Fake anti-cheat §cDISABLED"));
		} else {
			ENABLED_FAKEBAN.add(id);
			p.sendSystemMessage(Component.literal("Fake anti-cheat §aENABLED§r (simulates an admin watching you)"));
		}
		return 1;
	}

	private static int cmdFakeBanStatus(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		Watch w = WATCH.get(p.getUUID());
		int lvl = w == null ? 0 : w.vl;
		int stage = w == null ? 0 : w.stage;
		p.sendSystemMessage(Component.literal("Fake anti-cheat: "
				+ (ENABLED_FAKEBAN.contains(p.getUUID()) ? "§aENABLED" : "§cDISABLED")
				+ " §7| VL: §e" + lvl + " §7| Stage: §e" + stage));
		return 1;
	}

	private static int cmdFakeBanWatch(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
		w.stage = 1;
		Skin skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];
		p.sendSystemMessage(Component.literal("[§cADMIN§r] §7" + skin.name() + " joined the game (vanished)"));
		spawnHead(p, skin);
		return 1;
	}

	private static int cmdFakeBanTrap(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = requireSingleplayer(src);
		if (p == null) return 0;
		if (!(p.level() instanceof ServerLevel level)) return 0;
		if (trap != null && trap.active) {
			p.sendSystemMessage(Component.literal("A trap is already active."));
			return 0;
		}
		spawnTrapBase(level, p);
		return 1;
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
		WATCH.remove(p.getUUID());
		DEEP_STREAK.remove(p.getUUID());
		HeadAnim anim = HEADS.remove(p.getUUID());
		if (anim != null) anim.discard();
		if (trap != null && trap.target.equals(p.getUUID())) trap = null;
		p.sendSystemMessage(Component.literal("Fake anti-cheat state reset."));
		return 1;
	}

	private static int cmdRtp(CommandSourceStack src) throws CommandSyntaxException {
		ServerPlayer p = src.getPlayerOrException();
		ServerLevel level = p.serverLevel();

		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int rx = rnd.nextInt(-5000, 5001);
		int rz = rnd.nextInt(-5000, 5001);

		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, rx, rz);
		if (surfaceY <= level.getMinBuildHeight()) {
			surfaceY = 70;
		}

		p.teleportTo(level, rx + 0.5, surfaceY + 1.0, rz + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
		p.sendSystemMessage(Component.literal("§a[RTP] §7Teleported to random location: §e" + rx + ", " + (surfaceY + 1) + ", " + rz));
		return 1;
	}

	private static void addSuspicion(ServerPlayer p, int amount, String reason) {
		Watch w = WATCH.computeIfAbsent(p.getUUID(), k -> new Watch());
		w.vl += amount;
		if (DEBUG_LOG) LOGGER.info("Player {} VL is now {} (+{} from {})", p.getScoreboardName(), w.vl, amount, reason);

		if (w.stage == 0 && w.vl >= 3) {
			w.stage = 1;
			Skin skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];
			p.sendSystemMessage(Component.literal("[§cADMIN§r] §7" + skin.name() + " joined the game (vanished)"));
			spawnHead(p, skin);
		} else if (w.stage == 1 && w.vl >= 6) {
			w.stage = 2;
			if (p.level() instanceof ServerLevel level) {
				spawnTrapBase(level, p);
			}
		}
	}

	private static void spawnHead(ServerPlayer p, Skin skin) {
		HeadAnim old = HEADS.remove(p.getUUID());
		if (old != null) old.discard();

		ServerLevel level = (ServerLevel) p.level();
		ArmorStand stand = new ArmorStand(level, p.getX(), p.getY(), p.getZ());
		stand.setInvisible(true);
		stand.setNoGravity(true);
		stand.setSilent(true);
		stand.noPhysics = true);
		stand.setSmall(false);

		ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
		skull.set(DataComponents.PROFILE, ResolvableProfile.create(new GameProfile(skin.uuid(), skin.name())));
		stand.setItemSlot(EquipmentSlot.HEAD, skull);

		level.addFreshEntity(stand);
		HEADS.put(p.getUUID(), new HeadAnim(stand, p, skin, 0));

		// Show watch prompt and subtitle jumpscare only during fakeban watch/trap event
		runCmd(p.getServer(), "title " + p.getScoreboardName() + " times 5 40 10");
		runCmd(p.getServer(), "title " + p.getScoreboardName() + " title {\"text\":\"\"}");
		runCmd(p.getServer(), "title " + p.getScoreboardName() + " subtitle {\"text\":\"[ " + skin.name() + " is watching you ]\",\"color\":\"red\",\"bold\":true}");
		runCmd(p.getServer(), "playsound minecraft:entity.elder_guardian.curse master "
				+ p.getScoreboardName() + " ~ ~ ~ 1.0 1.0");
	}

	private static void tickWatch(MinecraftServer server) {
		if (HEADS.isEmpty()) return;
		List<UUID> done = new ArrayList<>();
		for (Map.Entry<UUID, HeadAnim> e : HEADS.entrySet()) {
			HeadAnim h = e.getValue();
			if (!h.stand.isAlive() || !h.target.isAlive()) {
				done.add(e.getKey());
				continue;
			}
			h.ticks++;
			if (h.ticks > 200) {
				h.discard();
				done.add(e.getKey());
				continue;
			}
			Vec3 look = h.target.getLookAngle();
			double dist = 1.6;
			double x = h.target.getX() + look.x * dist;
			double y = h.target.getY() + h.target.getEyeHeight() - 1.4;
			double z = h.target.getZ() + look.z * dist;
			h.stand.setPos(x, y, z);
			float yaw = h.target.getYRot() + 180f;
			float pitch = -h.target.getXRot();
			h.stand.setYRot(yaw);
			h.stand.setXRot(pitch);
			h.stand.setHeadPose(new Rotations(pitch, 0f, 0f));
		}
		done.forEach(HEADS::remove);
	}

	private static void spawnTrapBase(ServerLevel level, ServerPlayer p) {
		Path schem = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("fakebase.litematic");
		if (!Files.isRegularFile(schem)) {
			p.sendSystemMessage(Component.literal("§c[Anti-Cheat] Could not spawn trap: fakebase.litematic not found in .minecraft/schematics/"));
			return;
		}
		int px = p.getBlockX();
		int py = p.getBlockY();
		int pz = p.getBlockZ();
		PlacePlan plan = findClearSpace(level, schem, px, py, pz, MAX_RADIUS);
		if (plan == null) {
			p.sendSystemMessage(Component.literal("§c[Anti-Cheat] Could not find clear space for trap base."));
			return;
		}
		boolean ok = startPasting(level, plan, true);
		if (!ok) return;

		// Trigger jumpscare only for fakeban trap bases!
		Skin skin = SKINS[ThreadLocalRandom.current().nextInt(SKINS.length)];
		spawnHead(p, skin);

		trap = new Trap(level, p.getUUID(), plan.box(), true, 0);
		p.sendSystemMessage(Component.literal("§7[Debug] Trap base spawning near you..."));
	}

	private static void tickTrap(MinecraftServer server) {
		if (trap == null || !trap.active) return;
		ServerPlayer p = server.getPlayerList().getPlayer(trap.target);
		if (p == null) {
			trap = null;
			return;
		}
		trap.ticksInside++;
		if (trap.box.contains(p.getBlockX(), p.getBlockY(), p.getBlockZ())) {
			if (trap.ticksInside == 10) {
				p.sendSystemMessage(Component.literal("[§cADMIN§r] §7Found you."));
			} else if (trap.ticksInside == 40) {
				p.sendSystemMessage(Component.literal("[§cADMIN§r] §7Say goodbye."));
			} else if (trap.ticksInside >= 70) {
				fakeBanKick(p);
				trap = null;
			}
		}
	}

	private static void fakeBanKick(ServerPlayer p) {
		String text = """
				§c§lYOU ARE PERMANENTLY BANNED FROM THIS SERVER!

				§7Reason: §fCheating / X-Ray (detected by staff)
				§7Ban ID: §e#FB-""" + ThreadLocalRandom.current().nextInt(100000, 999999) + """

				§7Sharing this ban on social media will not help your appeal.
				§7Appeal at: §bhttps://discord.gg/example-appeals
				""";
		p.connection.disconnect(Component.literal(text));
	}

	private record Skin(String name, UUID uuid) {}

	private record Trap(ServerLevel level, UUID target, Box box, boolean active, int ticksInside) {
		Trap(ServerLevel level, UUID target, Box box, boolean active, int ticksInside) {
			this.level = level;
			this.target = target;
			this.box = box;
			this.active = active;
			this.ticksInside = ticksInside;
		}
	}

	private static final class Watch {
		int vl = 0;
		int stage = 0;
	}

	private static final class HeadAnim {
		final ArmorStand stand;
		final ServerPlayer target;
		final Skin skin;
		int ticks;

		HeadAnim(ArmorStand stand, ServerPlayer target, Skin skin, int ticks) {
			this.stand = stand;
			this.target = target;
			this.skin = skin;
			this.ticks = ticks;
		}

		void discard() {
			stand.discard();
		}
	}

	// ------------------------------------------------------------------ schematic loading and placement

	private static String tryPlaceRandomBase(ServerLevel level, int px, int py, int pz) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		if (!Files.isDirectory(dir)) return "nofolder";

		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
					.filter(p -> !p.getFileName().toString().equalsIgnoreCase("fakebase.litematic"))
					.toList();
		} catch (IOException e) {
			return "ioerror: " + e.getMessage();
		}
		if (files.isEmpty()) return "nofiles";

		List<Path> shuffled = new ArrayList<>(files);
		Collections.shuffle(shuffled);

		for (Path p : shuffled) {
			PlacePlan plan = findClearSpace(level, p, px, py, pz, MAX_RADIUS);
			if (plan != null) {
				boolean ok = startPasting(level, plan, false);
				if (ok) return "ok";
			}
		}
		return "nospace";
	}

	private static String tryPlaceFakeBase(ServerLevel level, int px, int py, int pz) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		Path schem = dir.resolve("fakebase.litematic");
		if (!Files.isRegularFile(schem)) {
			return tryPlaceRandomBase(level, px, py, pz);
		}
		PlacePlan plan = findClearSpace(level, schem, px, py, pz, MAX_RADIUS);
		if (plan == null) return "nospace";
		boolean ok = startPasting(level, plan, false);
		return ok ? "ok" : "nospace";
	}

	private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
	}

	private record PlacePlan(Path path, Schem schem, int ox, int oy, int oz, Box box) {}

	private static PlacePlan findClearSpace(ServerLevel level, Path path, int px, int py, int pz, int maxRadius) {
		Schem s;
		try {
			s = readLitematic(path);
		} catch (Exception e) {
			LOGGER.warn("Could not read {}: {}", path.getFileName(), e.toString());
			return null;
		}
		if (s.w() <= 0 || s.h() <= 0 || s.d() <= 0) return null;
		long vol = (long) s.w() * s.h() * s.d();
		if (vol > MAX_VOLUME) return null;

		int bw = s.w();
		int bh = s.h();
		int bd = s.d();

		List<int[]> candidates = new ArrayList<>();
		for (int dx = -maxRadius; dx <= maxRadius; dx += 2) {
			for (int dz = -maxRadius; dz <= maxRadius; dz += 2) {
				int distSq = dx * dx + dz * dz;
				if (distSq < 16 || distSq > maxRadius * maxRadius) continue;
				for (int dy = -4; dy <= 4; dy += 2) {
					candidates.add(new int[]{px + dx, py + dy, pz + dz, distSq});
				}
			}
		}
		candidates.sort((a, b) -> Integer.compare(a[3], b[3]));

		int minY = level.getMinBuildHeight();
		int maxY = level.getMaxBuildHeight();

		for (int[] c : candidates) {
			int ox = c[0];
			int oy = c[1];
			int oz = c[2];

			if (oy < minY + 2 || oy + bh >= maxY - 2) continue;

			Box box = new Box(ox, oy, oz, ox + bw - 1, oy + bh - 1, oz + bd - 1);
			if (isSolidCube(level, box)) {
				return new PlacePlan(path, s, ox, oy, oz, box);
			}
		}
		return null;
	}

	private static boolean isSolidCube(ServerLevel level, Box b) {
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
		int step = 2;
		for (int x = b.minX(); x <= b.maxX(); x += step) {
			for (int y = b.minY(); y <= b.maxY(); y += step) {
				for (int z = b.minZ(); z <= b.maxZ(); z += step) {
					mpos.set(x, y, z);
					BlockState state = level.getBlockState(mpos);
					if (state.isAir()) return false;
					if (state.is(BlockTags.LIQUIDS)) return false;
					if (state.getDestroySpeed(level, mpos) < 0) return false;
				}
			}
		}
		return true;
	}

	private static boolean startPasting(ServerLevel level, PlacePlan plan, boolean isTrap) {
		Schem s = plan.schem();
		Map<BlockPos, BlockState> blockMap = new HashMap<>();
		List<Tile> tileList = new ArrayList<>();
		List<Ent> entList = new ArrayList<>();

		for (Region r : s.regions()) {
			int rx = plan.ox() + r.ox();
			int ry = plan.oy() + r.oy();
			int rz = plan.oz() + r.oz();
			decodeRegionBlocks(r, rx, ry, rz, blockMap);

			for (Tile t : r.tiles()) {
				tileList.add(new Tile(rx + t.x(), ry + t.y(), rz + t.z(), t.id(), t.nbt()));
			}
			for (Ent e : r.entities()) {
				entList.add(new Ent(e.type(), rx + e.x(), ry + e.y(), rz + e.z(), e.nbt(), e.hanging()));
			}
		}

		List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(blockMap.entrySet());
		sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

		CommandSourceStack src = level.getServer().createCommandSourceStack()
				.withPermission(PermissionSet.ALL)
				.withSuppressedOutput();

		activeJob = new Job(level, src, sortedBlocks, tileList, entList,
				plan.ox(), plan.oy(), plan.oz(), isTrap);

		if (DEBUG_LOG) {
			LOGGER.info("Starting base paste: {} blocks, {} tiles, {} entities at {} {} {}",
					sortedBlocks.size(), tileList.size(), entList.size(),
					plan.ox(), plan.oy(), plan.oz());
		}
		return true;
	}

	private static void tickPlacement() {
		Job job = activeJob;
		if (job == null) return;

		int placed = 0;
		while (job.blockNext < job.blocks.size() && placed < BLOCKS_PER_TICK) {
			Map.Entry<BlockPos, BlockState> e = job.blocks.get(job.blockNext++);
			job.level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
			placed++;
		}

		if (job.blockNext < job.blocks.size()) return;

		if (job.tileNext < job.tiles.size() || job.entNext < job.entities.size()) {
			HolderLookup.Provider lookup = job.level.registryAccess();
			List<Tile> tiles = job.tiles;
			int tLimit = Math.min(tiles.size(), job.tileNext + 10);
			while (job.tileNext < tLimit) {
				Tile t = tiles.get(job.tileNext++);
				String snbt = toSnbt(t.nbt(), lookup);
				String cmd = "execute positioned " + t.x() + " " + t.y() + " " + t.z()
						+ " run data merge block ~ ~ ~ " + snbt;
				runCommand(job, cmd, "tile " + t.id());
			}

			List<Ent> ents = job.entities;
			int eLimit = Math.min(ents.size(), job.entNext + 4);
			while (job.entNext < eLimit) {
				Ent e = ents.get(job.entNext++);
				String snbt = toSnbt(e.nbt(), lookup);
				double ax = e.x();
				double ay = e.y();
				double az = e.z();
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
		final CommandSourceStack source;
		final List<Map.Entry<BlockPos, BlockState>> blocks;
		final List<Tile> tiles;
		final List<Ent> entities;
		final int ox, oy, oz;
		final boolean isTrap;
		int blockNext = 0;
		int tileNext = 0;
		int entNext = 0;
		int errors = 0;

		Job(ServerLevel level, CommandSourceStack source,
			List<Map.Entry<BlockPos, BlockState>> blocks, List<Tile> tiles, List<Ent> entities,
			int ox, int oy, int oz, boolean isTrap) {
			this.level = level;
			this.source = source;
			this.blocks = blocks;
			this.tiles = tiles;
			this.entities = entities;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.isTrap = isTrap;
		}
	}

	private record Tile(int x, int y, int z, String id, Map<String, Object> nbt) {}

	private record Ent(String type, double x, double y, double z, Map<String, Object> nbt, boolean hanging) {}

	private record Schem(int w, int h, int d, List<Region> regions) {}

	private record Region(int ox, int oy, int oz, int sx, int sy, int sz,
						  long[] data, List<Object> palette, List<Tile> tiles, List<Ent> entities) {}

	// ------------------------------------------------------------------ litematic decoder

	@SuppressWarnings("unchecked")
	private static Schem readLitematic(Path path) throws Exception {
		Map<String, Object> root;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
			root = (Map<String, Object>) readTag(in, in.readByte());
		}

		Map<String, Object> meta = asMap(root.get("Metadata"));
		Map<String, Object> size = meta != null ? asMap(meta.get("EnclosingSize")) : null;
		int totalW = size != null ? num(size.get("x")) : 0;
		int totalH = size != null ? num(size.get("y")) : 0;
		int totalD = size != null ? num(size.get("z")) : 0;

		Map<String, Object> regionsTag = asMap(root.get("Regions"));
		List<Region> regionList = new ArrayList<>();
		if (regionsTag != null) {
			for (Map.Entry<String, Object> entry : regionsTag.entrySet()) {
				Map<String, Object> r = asMap(entry.getValue());
				if (r == null) continue;
				Map<String, Object> pos = asMap(r.get("Position"));
				Map<String, Object> rSize = asMap(r.get("Size"));
				int ox = pos != null ? num(pos.get("x")) : 0;
				int oy = pos != null ? num(pos.get("y")) : 0;
				int oz = pos != null ? num(pos.get("z")) : 0;
				int sx = rSize != null ? num(rSize.get("x")) : 0;
				int sy = rSize != null ? num(rSize.get("y")) : 0;
				int sz = rSize != null ? num(rSize.get("z")) : 0;

				long[] data = (long[]) r.get("BlockStates");
				List<Object> palette = asList(r.get("BlockStatePalette"));
				if (palette == null) palette = List.of();

				List<Tile> tiles = new ArrayList<>();
				List<Object> tileTags = asList(r.get("TileEntities"));
				if (tileTags != null) {
					for (Object o : tileTags) {
						Map<String, Object> m = asMap(o);
						if (m == null) continue;
						tiles.add(new Tile(num(m.get("x")), num(m.get("y")), num(m.get("z")),
								String.valueOf(m.getOrDefault("id", "")), m));
					}
				}

				List<Ent> entities = new ArrayList<>();
				List<Object> entTags = asList(r.get("Entities"));
				if (entTags != null) {
					for (Object o : entTags) {
						Map<String, Object> m = asMap(o);
						if (m == null) continue;
						List<Object> posList = asList(m.get("Pos"));
						double x = 0, y = 0, z = 0;
						if (posList != null && posList.size() >= 3) {
							x = dbl(posList.get(0));
							y = dbl(posList.get(1));
							z = dbl(posList.get(2));
						}
						String id = String.valueOf(m.getOrDefault("id", ""));
						boolean hanging = id.contains("item_frame") || id.contains("painting") || id.contains("glow_item_frame");
						entities.add(new Ent(id, x, y, z, m, hanging));
					}
				}

				regionList.add(new Region(ox, oy, oz, sx, sy, sz, data, palette, tiles, entities));
			}
		}

		if (totalW == 0 && !regionList.isEmpty()) {
			for (Region r : regionList) {
				totalW = Math.max(totalW, Math.abs(r.sx()));
				totalH = Math.max(totalH, Math.abs(r.sy()));
				totalD = Math.max(totalD, Math.abs(r.sz()));
			}
		}

		return new Schem(Math.abs(totalW), Math.abs(totalH), Math.abs(totalD), regionList);
	}

	private static void decodeRegionBlocks(Region r, int originX, int originY, int originZ,
										   Map<BlockPos, BlockState> out) {
		int sx = r.sx();
		int sy = r.sy();
		int sz = r.sz();
		int absX = Math.abs(sx);
		int absY = Math.abs(sy);
		int absZ = Math.abs(sz);
		if (absX == 0 || absY == 0 || absZ == 0) return;

		int offX = sx < 0 ? sx + 1 : 0;
		int offY = sy < 0 ? sy + 1 : 0;
		int offZ = sz < 0 ? sz + 1 : 0;

		List<Object> palette = r.palette();
		if (palette.isEmpty()) return;

		BlockState[] parsed = new BlockState[palette.size()];
		HolderLookup.Provider lookup = BuiltInRegistries.BLOCK.asLookup();
		for (int i = 0; i < palette.size(); i++) {
			parsed[i] = parsePaletteEntry(palette.get(i), lookup);
		}

		long[] data = r.data();
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
		long mask = (1L << bits) - 1L;
		int count = absX * absY * absZ;

		for (int i = 0; i < count; i++) {
			int pIndex;
			if (data == null || data.length == 0) {
				pIndex = 0;
			} else {
				long bitIndex = (long) i * bits;
				int longIndex = (int) (bitIndex >> 6);
				int startBit = (int) (bitIndex & 63);
				if (longIndex >= data.length) break;

				long val = data[longIndex] >>> startBit;
				if (startBit + bits > 64 && longIndex + 1 < data.length) {
					val |= data[longIndex + 1] << (64 - startBit);
				}
				pIndex = (int) (val & mask);
			}

			if (pIndex >= parsed.length) continue;
			BlockState state = parsed[pIndex];
			if (state == null || state.isAir()) continue;

			int y = i / (absX * absZ);
			int rem = i % (absX * absZ);
			int z = rem / absX;
			int x = rem % absX;

			BlockPos pos = new BlockPos(
					originX + offX + x,
					originY + offY + y,
					originZ + offZ + z
			);
			out.put(pos, state);
		}
	}

	@SuppressWarnings("unchecked")
	private static BlockState parsePaletteEntry(Object entry, HolderLookup.Provider lookup) {
		Map<String, Object> map = asMap(entry);
		if (map == null) return Blocks.AIR.defaultBlockState();
		String name = String.valueOf(map.getOrDefault("Name", "minecraft:air"));
		Map<String, Object> props = asMap(map.get("Properties"));

		StringBuilder sb = new StringBuilder(name);
		if (props != null && !props.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (Map.Entry<String, Object> p : props.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append(p.getKey()).append('=').append(p.getValue());
			}
			sb.append(']');
		}

		try {
			return BlockStateParser.parseForBlock(lookup, sb.toString(), true).blockState();
		} catch (Exception e) {
			try {
				return BlockStateParser.parseForBlock(lookup, name, true).blockState();
			} catch (Exception ignored) {
				return Blocks.AIR.defaultBlockState();
			}
		}
	}

	// ------------------------------------------------------------------ SNBT serializer

	@SuppressWarnings("unchecked")
	private static String toSnbt(Object o, HolderLookup.Provider lookup) {
		if (o == null) return "0";
		if (o instanceof String s) return quote(s);
		if (o instanceof Byte b) return b + "b";
		if (o instanceof Short s) return s + "s";
		if (o instanceof Integer i) return i.toString();
		if (o instanceof Long l) return l + "L";
		if (o instanceof Float f) return f + "f";
		if (o instanceof Double d) return d.toString();
		if (o instanceof byte[] bytes) {
			StringBuilder sb = new StringBuilder("[B;");
			for (int i = 0; i < bytes.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(bytes[i]).append('b');
			}
			return sb.append(']').toString();
		}
		if (o instanceof int[] ints) {
			StringBuilder sb = new StringBuilder("[I;");
			for (int i = 0; i < ints.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(ints[i]);
			}
			return sb.append(']').toString();
		}
		if (o instanceof long[] longs) {
			StringBuilder sb = new StringBuilder("[L;");
			for (int i = 0; i < longs.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(longs[i]).append('L');
			}
			return sb.append(']').toString();
		}
		if (o instanceof List<?> list) {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) sb.append(',');
				sb.append(toSnbt(list.get(i), lookup));
			}
			return sb.append(']').toString();
		}
		if (o instanceof Map<?, ?> map) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				String k = String.valueOf(e.getKey());
				if (k.equals("x") || k.equals("y") || k.equals("z")) continue;
				if (!first) sb.append(',');
				first = false;
				sb.append(safeKey(k)).append(':').append(toSnbt(e.getValue(), lookup));
			}
			return sb.append('}').toString();
		}
		return quote(o.toString());
	}

	private static String safeKey(String key) {
		return key.matches("^[a-zA-Z0-9_.-]+$") ? key : quote(key);
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static void runCmd(MinecraftServer s, String cmd) {
		try {
			CommandSourceStack stack = s.createCommandSourceStack()
					.withPermission(PermissionSet.ALL)
					.withSuppressedOutput();
			s.getCommands().performPrefixedCommand(stack, cmd);
		} catch (Exception e) {
			if (cmdErrors++ < 3) {
				LOGGER.warn("Failed command: {}", cmd, e);
			}
		}
	}

	// ------------------------------------------------------------------ minimal NBT reader

	private static Object readTag(DataInputStream in, byte type) throws IOException {
		return switch (type) {
			case 0 -> null;
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
				byte itemType = in.readByte();
				int len = in.readInt();
				List<Object> list = new ArrayList<>(Math.max(0, len));
				for (int i = 0; i < len; i++) {
					list.add(readTag(in, itemType));
				}
				yield list;
			}
			case 10 -> {
				Map<String, Object> map = new HashMap<>();
				while (true) {
					byte t = in.readByte();
					if (t == 0) break;
					String name = in.readUTF();
					map.put(name, readTag(in, t));
				}
				yield map;
			}
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
