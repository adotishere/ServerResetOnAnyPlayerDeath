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
    private static long cleanupAtTick = Long.MAX_VALUE;
    private static int cleanupWorldNumber = -1;
    private static int pendingPostResetWorldNumber = -1;
    private static long pendingNetherSeed;
    private static long pendingEndSeed;
    private static CompletableFuture<Vec3> standbySpawn;
    private static volatile Vec3 activeSpawnPos;
    private static volatile Vec3 standbySpawnPos;
    private static Component triggeringDeathMessage;
    private static final Set<Long> USED_SEEDS = ConcurrentHashMap.newKeySet();

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
            cleanupAtTick = Long.MAX_VALUE;
            cleanupWorldNumber = -1;
            pendingPostResetWorldNumber = -1;
            USED_SEEDS.add(server.getWorldGenSettings().options().seed());
            try {
                ServerLevel active = RotatingWorldManager.ensureWorldSet(server, currentWorldNumber(),
                        config.activeOverworldSeed, config.activeNetherSeed, config.activeEndSeed);
                RotatingWorldManager.findSpawn(active).thenAccept(pos -> activeSpawnPos = pos);
                ServerLevel standby = RotatingWorldManager.ensureOverworld(
                        server, currentWorldNumber() + 1, config.nextOverworldSeed);
                standbySpawnPos = null;
                standbySpawn = RotatingWorldManager.findSpawn(standby);
                standbySpawn.thenAccept(pos -> standbySpawnPos = pos);
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

    /** Returns true when vanilla death handling must be cancelled. */
    public static boolean onPlayerDeath(ServerPlayer player, Component vanillaDeathMessage) {
        if (resetInProgress) {
            revive(player);
            return true;
        }

        resetInProgress = true;
        triggeringDeathMessage = vanillaDeathMessage.copy();
        revive(player);
        MinecraftServer server = player.level().getServer();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("\"").append(triggeringDeathMessage.copy()).append("\""), false);
        config.deathsSinceLastReset = 0;
        config.save(CONFIG_PATH, LOGGER);

        // Always instantly transfer players to the new overworld dimension on any player death
        activateStandbyWorld(server);
        return true;
    }

    private static void revive(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.clearFire();
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
        activateStandbyWorld(server);
    }

    private static void tick(MinecraftServer server) {
        if (pendingPostResetWorldNumber > 0) {
            int num = pendingPostResetWorldNumber;
            long netherSeed = pendingNetherSeed;
            long endSeed = pendingEndSeed;
            pendingPostResetWorldNumber = -1;
            try {
                RotatingWorldManager.ensureNetherAndEnd(server, num, netherSeed, endSeed);
                ServerLevel nextStandby = RotatingWorldManager.ensureOverworld(
                        server, num + 1, config.nextOverworldSeed);
                standbySpawnPos = null;
                standbySpawn = RotatingWorldManager.findSpawn(nextStandby);
                standbySpawn.thenAccept(pos -> standbySpawnPos = pos);
            } catch (Exception e) {
                LOGGER.error("Could not complete post-reset dimension generation", e);
            } finally {
                resetInProgress = false;
                triggeringDeathMessage = null;
            }
        }
        if (pendingConsoleConfirmation && server.getTickCount() >= confirmationPromptAtTick) {
            confirmationPromptAtTick = Long.MAX_VALUE;
            LOGGER.warn("Reset the world? [Y/n]");
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
            newWorld = RotatingWorldManager.ensureOverworld(server, newNumber, config.nextOverworldSeed);
        } catch (RuntimeException e) {
            resetInProgress = false;
            LOGGER.error("Could not open the standby world; the old world was kept", e);
            server.getPlayerList().broadcastSystemMessage(Component.literal("World reset failed; the old world was kept."), false);
            return;
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
                wipePlayer(player);
                RotatingWorldManager.teleport(player, newWorld, spawn);
            }
            RotatingWorldManager.resetWorldTime(server);
            RotatingWorldManager.clearWeather(server);
            long newNetherSeed = uniqueSeed(config.nextOverworldSeed);
            long newEndSeed = uniqueSeed(config.nextOverworldSeed, newNetherSeed);
            config.resetCount++;
            config.activeOverworldSeed = config.nextOverworldSeed;
            config.activeNetherSeed = newNetherSeed;
            config.activeEndSeed = newEndSeed;
            config.nextOverworldSeed = uniqueSeed(
                    config.activeOverworldSeed, config.activeNetherSeed, config.activeEndSeed);
            config.save(CONFIG_PATH, LOGGER);
            updateServerPropertiesMotd();

            pendingPostResetWorldNumber = newNumber;
            pendingNetherSeed = newNetherSeed;
            pendingEndSeed = newEndSeed;

            cleanupWorldNumber = oldNumber;
            cleanupAtTick = server.getTickCount() + (long) config.resetDelaySeconds * 20L;
            LOGGER.warn("Players moved instantly to world set #{}; old set retires in {} second(s)",
                    newNumber, config.resetDelaySeconds);
            server.getPlayerList().broadcastSystemMessage(Component.literal("The fresh world is ready."), false);
        } catch (Exception e) {
            resetInProgress = false;
            LOGGER.error("Could not finish activating the standby world", e);
            return;
        }
    }

    private static void wipePlayer(ServerPlayer player) {
        player.stopRiding();
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
        ServerLevel active = server.getLevel(RotatingWorldManager.keyFor(currentWorldNumber()));
        if (active == null || player.level() == active) return;
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

    private static int currentWorldNumber() { return config.resetCount + 1; }

    private static void ensureUniqueSeeds(MinecraftServer server) {
        if (server != null) {
            USED_SEEDS.add(server.getWorldGenSettings().options().seed());
        }
        boolean changed = false;
        if (config.activeOverworldSeed == 0L || USED_SEEDS.contains(config.activeOverworldSeed)) {
            config.activeOverworldSeed = uniqueSeed();
            changed = true;
        } else {
            USED_SEEDS.add(config.activeOverworldSeed);
        }
        if (config.activeNetherSeed == 0L || USED_SEEDS.contains(config.activeNetherSeed)) {
            config.activeNetherSeed = uniqueSeed();
            changed = true;
        } else {
            USED_SEEDS.add(config.activeNetherSeed);
        }
        if (config.activeEndSeed == 0L || USED_SEEDS.contains(config.activeEndSeed)) {
            config.activeEndSeed = uniqueSeed();
            changed = true;
        } else {
            USED_SEEDS.add(config.activeEndSeed);
        }
        if (config.nextOverworldSeed == 0L || USED_SEEDS.contains(config.nextOverworldSeed)) {
            config.nextOverworldSeed = uniqueSeed();
            changed = true;
        } else {
            USED_SEEDS.add(config.nextOverworldSeed);
        }
        if (changed) config.save(CONFIG_PATH, LOGGER);
        registerKnownSeeds();
    }

    private static void registerKnownSeeds() {
        RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.OVERWORLD), config.activeOverworldSeed);
        RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.NETHER), config.activeNetherSeed);
        RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber(), RotatingWorldManager.WorldPart.END), config.activeEndSeed);
        RotatingWorldManager.registerSeed(RotatingWorldManager.keyFor(currentWorldNumber() + 1, RotatingWorldManager.WorldPart.OVERWORLD), config.nextOverworldSeed);
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
