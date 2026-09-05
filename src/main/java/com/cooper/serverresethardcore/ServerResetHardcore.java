package com.cooper.serverresethardcore;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class ServerResetHardcore implements ModInitializer {
    public static final String MOD_ID = "server_reset_hardcore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private static final Path PERSISTENT_DATAPACKS = GAME_DIR.resolve("persistent-datapacks");
    private static final Path MOTD_BACKUP = GAME_DIR.resolve("server-reset-hardcore-motd-backup.txt");

    private static HardcoreConfig config;
    private static boolean resetInProgress;
    private static boolean pendingConsoleConfirmation;
    private static long confirmationPromptAtTick = Long.MAX_VALUE;
    private static long rotateAtTick = Long.MAX_VALUE;
    private static long cleanupAtTick = Long.MAX_VALUE;
    private static int cleanupWorldNumber = -1;
    private static int pendingPostResetWorldNumber = -1;
    private static CompletableFuture<Vec3> standbySpawn;
    private static CompletableFuture<Void> standbyChunksReady;
    private static volatile Vec3 activeSpawnPos;
    private static volatile Vec3 standbySpawnPos;
    private static Component triggeringDeathMessage;
    private static final Set<Long> USED_SEEDS = ConcurrentHashMap.newKeySet();

    public static boolean isResetInProgress() {
        return resetInProgress;
    }

    private static void pregenerateStandbyWorld(MinecraftServer server, int worldNumber, long seed) {
        try {
            ServerLevel standby = RotatingWorldManager.ensureWorldSet(server, worldNumber, seed);
            standbySpawnPos = null;
            standbySpawn = RotatingWorldManager.findSpawn(standby);
            standbyChunksReady = standbySpawn.thenCompose(spawn -> {
                standbySpawnPos = spawn;
                LOGGER.info("Standby world #{} spawn found at ({}, {}, {}). Pregenerating spawn chunks...",
                        worldNumber, (int) spawn.x, (int) spawn.y, (int) spawn.z);
                return RotatingWorldManager.pregenerateChunks(standby, spawn, 2);
            });
            standbyChunksReady.thenRun(() -> {
                LOGGER.info("Standby world #{} spawn chunks pregenerated and ready.", worldNumber);
            });
        } catch (Exception e) {
            LOGGER.error("Failed to pregenerate standby world #{}", worldNumber, e);
        }
    }

    @Override
    public void onInitialize() {
        preparePersistentDatapacks();
        config = HardcoreConfig.load(CONFIG_PATH, LOGGER);
        ensureUniqueSeeds(null);
        updateServerPropertiesMotd();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            resetInProgress = false;
            pendingConsoleConfirmation = false;
            confirmationPromptAtTick = Long.MAX_VALUE;
            rotateAtTick = Long.MAX_VALUE;
            cleanupAtTick = Long.MAX_VALUE;
            cleanupWorldNumber = -1;
            pendingPostResetWorldNumber = -1;
            USED_SEEDS.add(server.getWorldGenSettings().options().seed());
            try {
                ServerLevel active = RotatingWorldManager.ensureWorldSet(server, currentWorldNumber(), config.activeSeed);
                RotatingWorldManager.findSpawn(active).thenAccept(pos -> activeSpawnPos = pos);
                pregenerateStandbyWorld(server, currentWorldNumber() + 1, config.nextSeed);
            } catch (RuntimeException e) {
                LOGGER.error("Could not load the active or standby gameplay world", e);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerResetHardcore::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> moveJoiningPlayer(handler.player, server));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerConfirmationCommand(dispatcher, "y", true);
            registerConfirmationCommand(dispatcher, "Y", true);
            registerConfirmationCommand(dispatcher, "n", false);
            registerConfirmationCommand(dispatcher, "N", false);
        });
        LOGGER.info("Server Reset Hardcore v2 loaded");
    }

    public static void onPlayerDeath(ServerPlayer player, Component vanillaDeathMessage) {
        if (resetInProgress) {
            return;
        }

        resetInProgress = true;
        triggeringDeathMessage = vanillaDeathMessage.copy();
        MinecraftServer server = player.level().getServer();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("\"").append(triggeringDeathMessage.copy()).append("\""), false);
        config.deathsSinceLastReset = 0;
        config.save(CONFIG_PATH, LOGGER);

        killAllOtherPlayers(server, player);

        if (config.requireConsoleConfirmation) {
            pendingConsoleConfirmation = true;
            confirmationPromptAtTick = server.getTickCount() + 1L;
            rotateAtTick = Long.MAX_VALUE;
        } else {
            beginRotation(server);
        }
    }

    private static void killAllOtherPlayers(MinecraftServer server, ServerPlayer triggerPlayer) {
        for (ServerPlayer other : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (other != triggerPlayer && !other.isDeadOrDying()) {
                if (other.isSpectator()) {
                    other.setGameMode(GameType.SURVIVAL);
                }
                other.kill(other.level());
            }
        }
    }

    private static void registerConfirmationCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name, boolean approve) {
        dispatcher.register(Commands.literal(name).executes(context -> {
            CommandSourceStack source = context.getSource();
            if (source.getEntity() != null) {
                source.sendFailure(Component.literal("This response can only be entered in the server console."));
                return 0;
            }
            if (!pendingConsoleConfirmation) {
                source.sendFailure(Component.literal("There is no reset waiting for confirmation."));
                return 0;
            }
            if (approve) {
                beginRotation(source.getServer());
                source.sendSuccess(() -> Component.literal("World reset confirmed."), false);
            } else {
                pendingConsoleConfirmation = false;
                confirmationPromptAtTick = Long.MAX_VALUE;
                resetInProgress = false;
                rotateAtTick = Long.MAX_VALUE;
                triggeringDeathMessage = null;
                source.sendSuccess(() -> Component.literal("World reset cancelled."), false);
            }
            return Command.SINGLE_SUCCESS;
        }));
    }

    public static boolean handleEmptyConsoleConfirmation(String input, CommandSourceStack source) {
        if (!pendingConsoleConfirmation || !input.isBlank()) return false;
        beginRotation(source.getServer());
        source.sendSuccess(() -> Component.literal("World reset confirmed."), false);
        return true;
    }

    private static void beginRotation(MinecraftServer server) {
        pendingConsoleConfirmation = false;
        confirmationPromptAtTick = Long.MAX_VALUE;
        if (config.resetDelaySeconds > 0) {
            rotateAtTick = server.getTickCount() + (long) config.resetDelaySeconds * 20L;
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("A fresh world opens in " + config.resetDelaySeconds + " second(s)."), false);
        } else {
            rotateAtTick = server.getTickCount();
        }
    }

    private static void tick(MinecraftServer server) {
        if (pendingPostResetWorldNumber > 0) {
            int num = pendingPostResetWorldNumber;
            pendingPostResetWorldNumber = -1;
            try {
                pregenerateStandbyWorld(server, num + 1, config.nextSeed);
            } catch (Exception e) {
                LOGGER.error("Could not complete post-reset next world pregeneration", e);
            } finally {
                resetInProgress = false;
                triggeringDeathMessage = null;
            }
        }
        if (pendingConsoleConfirmation && server.getTickCount() >= confirmationPromptAtTick) {
            confirmationPromptAtTick = Long.MAX_VALUE;
            LOGGER.warn("Reset the world? [Y/n]");
        }
        if (resetInProgress && !pendingConsoleConfirmation && server.getTickCount() >= rotateAtTick) {
            rotateAtTick = Long.MAX_VALUE;
            activateStandbyWorld(server);
        }
        if (cleanupWorldNumber < 0 || server.getTickCount() < cleanupAtTick) return;
        int oldNumber = cleanupWorldNumber;
        cleanupWorldNumber = -1;
        cleanupAtTick = Long.MAX_VALUE;
        try {
            RotatingWorldManager.deleteWorldSet(server, oldNumber, resolveWorldPath());
            LOGGER.warn("Deleted retired world set #{}", oldNumber);
        } catch (Exception e) {
            LOGGER.error("Players are safe in the new world, but old world cleanup failed", e);
        }
    }

    private static void activateStandbyWorld(MinecraftServer server) {
        int oldNumber = currentWorldNumber();
        int newNumber = oldNumber + 1;
        final ServerLevel newWorld;
        try {
            newWorld = RotatingWorldManager.ensureWorldSet(server, newNumber, config.nextSeed);
        } catch (RuntimeException e) {
            resetInProgress = false;
            LOGGER.error("Could not open the standby world; the old world was kept", e);
            server.getPlayerList().broadcastSystemMessage(Component.literal("World reset failed; the old world was kept."), false);
            return;
        }

        if (standbySpawn != null && !standbySpawn.isDone()) {
            try {
                LOGGER.info("Waiting for standby world spawn calculation...");
                standbySpawn.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("Timed out or interrupted waiting for standby spawn", e);
            }
        }
        if (standbyChunksReady != null && !standbyChunksReady.isDone()) {
            try {
                LOGGER.info("Waiting for standby spawn chunk pregeneration...");
                standbyChunksReady.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("Timed out or interrupted waiting for standby chunks", e);
            }
        }

        Vec3 spawn = standbySpawnPos;
        if (spawn == null && standbySpawn != null && standbySpawn.isDone()) {
            try {
                spawn = standbySpawn.join();
            } catch (Exception ignored) {}
        }
        if (spawn == null) {
            spawn = RotatingWorldManager.getImmediateSafeSpawn(newWorld);
        }
        activeSpawnPos = spawn;

        try {
            for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                ServerPlayer targetPlayer = player;
                if (player.isDeadOrDying()) {
                    targetPlayer = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
                    if (targetPlayer.connection != null) {
                        targetPlayer.connection.player = targetPlayer;
                        targetPlayer.connection.resetPosition();
                    }
                }
                targetPlayer.setGameMode(GameType.SURVIVAL);
                wipePlayer(targetPlayer);
                RotatingWorldManager.teleport(targetPlayer, newWorld, spawn);
            }
            RotatingWorldManager.resetWorldTime(server);
            RotatingWorldManager.clearWeather(server);
            long newActiveSeed = config.nextSeed;
            config.resetCount++;
            config.activeSeed = newActiveSeed;
            config.nextSeed = uniqueSeed(newActiveSeed);
            config.save(CONFIG_PATH, LOGGER);
            updateServerPropertiesMotd();

            pendingPostResetWorldNumber = newNumber;

            cleanupWorldNumber = oldNumber;
            cleanupAtTick = server.getTickCount() + Math.max(100L, (long) config.resetDelaySeconds * 20L);
            LOGGER.warn("Players moved to world set #{}; old set retires in {} second(s)",
                    newNumber, Math.max(5, config.resetDelaySeconds));
            server.getPlayerList().broadcastSystemMessage(Component.literal("The fresh world is ready."), false);
        } catch (Exception e) {
            resetInProgress = false;
            LOGGER.error("Could not finish activating the standby world", e);
            return;
        }
    }

    private static void wipePlayer(ServerPlayer player) {
        player.stopRiding();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.setExperiencePoints(0);
        player.setExperienceLevels(0);
        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.clearFire();
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.setRespawnPosition(null, false);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void moveJoiningPlayer(ServerPlayer player, MinecraftServer server) {
        if (resetInProgress) {
            if (!player.isDeadOrDying()) {
                if (player.isSpectator()) {
                    player.setGameMode(GameType.SURVIVAL);
                }
                player.kill(player.level());
            }
            return;
        }
        ServerLevel active = server.getLevel(RotatingWorldManager.keyFor(currentWorldNumber()));
        if (active == null) return;
        var dim = player.level().dimension();
        boolean inCurrentSet = dim.equals(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.OVERWORLD))
                || dim.equals(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.NETHER))
                || dim.equals(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.END));
        if (!inCurrentSet) {
            wipePlayer(player);
            Vec3 spawn = activeSpawnPos;
            if (spawn != null) {
                RotatingWorldManager.teleport(player, active, spawn);
            } else {
                RotatingWorldManager.findSpawn(active).thenAccept(s -> server.execute(() -> {
                    activeSpawnPos = s;
                    if (player.connection != null) RotatingWorldManager.teleport(player, active, s);
                }));
            }
        }
    }

    private static int currentWorldNumber() { return config.resetCount + 1; }

    private static void ensureUniqueSeeds(MinecraftServer server) {
        if (server != null) {
            USED_SEEDS.add(server.getWorldGenSettings().options().seed());
        }
        boolean changed = false;
        if (config.activeSeed == 0L || USED_SEEDS.contains(config.activeSeed)) {
            config.activeSeed = uniqueSeed();
            changed = true;
        } else {
            USED_SEEDS.add(config.activeSeed);
        }
        if (config.nextSeed == 0L || USED_SEEDS.contains(config.nextSeed) || config.nextSeed == config.activeSeed) {
            config.nextSeed = uniqueSeed(config.activeSeed);
            changed = true;
        } else {
            USED_SEEDS.add(config.nextSeed);
        }
        if (changed) config.save(CONFIG_PATH, LOGGER);
        registerKnownSeeds();
    }

    private static void registerKnownSeeds() {
        for (RotatingWorldManager.WorldPart part : RotatingWorldManager.WorldPart.values()) {
            RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber(), part), config.activeSeed);
            RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber() + 1, part), config.nextSeed);
        }
    }

    private static long uniqueSeed(long... excluded) {
        long seed;
        outer: do {
            seed = ThreadLocalRandom.current().nextLong();
            if (seed == 0L) continue;
            for (long value : excluded) {
                if (seed == value) continue outer;
            }
            if (USED_SEEDS.contains(seed)) continue;
            break;
        } while (true);
        USED_SEEDS.add(seed);
        return seed;
    }

    private static void preparePersistentDatapacks() {
        try {
            Files.createDirectories(PERSISTENT_DATAPACKS);
            copyPersistentDatapacks(resolveWorldPath().resolve("datapacks"));
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare persistent datapacks", e);
        }
    }

    private static Path resolveWorldPath() throws IOException {
        Properties properties = new Properties();
        Path propertiesPath = GAME_DIR.resolve("server.properties");
        if (Files.exists(propertiesPath)) {
            try (var reader = Files.newBufferedReader(propertiesPath)) { properties.load(reader); }
        }
        String levelName = properties.getProperty("level-name", "world").trim();
        if (levelName.isEmpty() || levelName.contains("..") || levelName.contains("/") || levelName.contains("\\")) {
            throw new IOException("Unsafe level-name: " + levelName);
        }
        Path worldPath = GAME_DIR.resolve(levelName).normalize();
        if (!worldPath.getParent().equals(GAME_DIR)) throw new IOException("World folder must be directly inside the server folder");
        return worldPath;
    }

    private static void copyPersistentDatapacks(Path destination) throws IOException {
        Files.createDirectories(destination);
        try (Stream<Path> paths = Files.walk(PERSISTENT_DATAPACKS)) {
            for (Path source : paths.toList()) {
                if (source.equals(PERSISTENT_DATAPACKS)) continue;
                Path target = destination.resolve(PERSISTENT_DATAPACKS.relativize(source)).normalize();
                if (!target.startsWith(destination)) throw new IOException("Datapack path escaped destination");
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void updateServerPropertiesMotd() {
        Path propertiesPath = GAME_DIR.resolve("server.properties");
        try {
            List<String> lines = Files.exists(propertiesPath) ? new ArrayList<>(Files.readAllLines(propertiesPath)) : new ArrayList<>();
            int motdLine = -1;
            String currentMotd = "A Minecraft Server";
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("motd=")) {
                    motdLine = i;
                    currentMotd = lines.get(i).substring("motd=".length());
                    break;
                }
            }
            String desiredMotd;
            if (config.trackResets) {
                if (!Files.exists(MOTD_BACKUP)) Files.writeString(MOTD_BACKUP, currentMotd);
                desiredMotd = "§6§lReset #" + currentWorldNumber() + "§r\\n§7" + config.motdText;
            } else {
                if (!Files.exists(MOTD_BACKUP)) return;
                desiredMotd = Files.readString(MOTD_BACKUP);
            }
            String replacement = "motd=" + desiredMotd;
            if (motdLine >= 0) lines.set(motdLine, replacement); else lines.add(replacement);
            Files.write(propertiesPath, lines);
            if (!config.trackResets) Files.deleteIfExists(MOTD_BACKUP);
        } catch (IOException e) {
            LOGGER.error("Could not update motd in server.properties", e);
        }
    }
}
