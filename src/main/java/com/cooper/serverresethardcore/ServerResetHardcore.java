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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
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
    private static Component triggeringDeathMessage;

    @Override
    public void onInitialize() {
        preparePersistentDatapacks();
        config = HardcoreConfig.load(CONFIG_PATH, LOGGER);
        if (config.activeWorldSeed == 0L) {
            config.activeWorldSeed = newWorldSeed();
            config.save(CONFIG_PATH, LOGGER);
        }
        updateServerPropertiesMotd();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            resetInProgress = false;
            pendingConsoleConfirmation = false;
            confirmationPromptAtTick = Long.MAX_VALUE;
            rotateAtTick = Long.MAX_VALUE;
            try {
                RotatingWorldManager.ensureWorldSet(server, currentWorldNumber(), config.activeWorldSeed);
            } catch (RuntimeException e) {
                LOGGER.error("Could not load the active gameplay world", e);
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
        LOGGER.info("Server Reset Hardcore v2 loaded (allowed deaths: {})", config.allowedDeaths);
    }

    /** Returns true when vanilla death handling must be cancelled. */
    public static boolean onPlayerDeath(ServerPlayer player, Component vanillaDeathMessage) {
        if (resetInProgress) {
            revive(player);
            return true;
        }
        config.deathsSinceLastReset++;
        config.save(CONFIG_PATH, LOGGER);
        if (config.deathsSinceLastReset < config.allowedDeaths) {
            int remaining = config.allowedDeaths - config.deathsSinceLastReset;
            LOGGER.info("Death {}/{}; {} death(s) remain before reset", config.deathsSinceLastReset, config.allowedDeaths, remaining);
            return false;
        }

        resetInProgress = true;
        triggeringDeathMessage = vanillaDeathMessage.copy();
        revive(player);
        MinecraftServer server = player.level().getServer();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("\"").append(triggeringDeathMessage.copy()).append("\""), false);
        config.deathsSinceLastReset = 0;
        config.save(CONFIG_PATH, LOGGER);
        if (config.requireConsoleConfirmation) {
            pendingConsoleConfirmation = true;
            confirmationPromptAtTick = server.getTickCount() + 1L;
        } else {
            beginRotation(server);
        }
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
        rotateAtTick = server.getTickCount() + (long) config.resetDelaySeconds * 20L;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("A fresh world opens in " + config.resetDelaySeconds + " second(s)."), false);
    }

    private static void tick(MinecraftServer server) {
        if (pendingConsoleConfirmation && server.getTickCount() >= confirmationPromptAtTick) {
            confirmationPromptAtTick = Long.MAX_VALUE;
            LOGGER.warn("Reset the world? [Y/n]");
        }
        if (!resetInProgress || server.getTickCount() < rotateAtTick) return;
        rotateAtTick = Long.MAX_VALUE;
        rotateWorld(server);
    }

    private static void rotateWorld(MinecraftServer server) {
        int oldNumber = currentWorldNumber();
        int newNumber = oldNumber + 1;
        long newSeed = newWorldSeed();
        final ServerLevel newWorld;
        try {
            newWorld = RotatingWorldManager.ensureWorldSet(server, newNumber, newSeed);
        } catch (RuntimeException e) {
            resetInProgress = false;
            LOGGER.error("Could not create the replacement world; the old world was kept", e);
            server.getPlayerList().broadcastSystemMessage(Component.literal("World reset failed; the old world was kept."), false);
            return;
        }

        CompletableFuture<?> spawnFuture = RotatingWorldManager.findSpawn(newWorld).thenAccept(spawn -> server.execute(() -> {
            try {
                for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                    wipePlayer(player);
                    RotatingWorldManager.teleport(player, newWorld, spawn);
                }
                config.resetCount++;
                config.activeWorldSeed = newSeed;
                config.save(CONFIG_PATH, LOGGER);
                updateServerPropertiesMotd();
                RotatingWorldManager.deleteWorldSet(server, oldNumber, resolveWorldPath());
                LOGGER.warn("Completed live world reset #{}; old gameplay dimension deleted", config.resetCount);
                server.getPlayerList().broadcastSystemMessage(Component.literal("The fresh world is ready."), false);
            } catch (Exception e) {
                LOGGER.error("Players reached the new world, but cleanup of the old world failed", e);
            } finally {
                resetInProgress = false;
                triggeringDeathMessage = null;
            }
        }));
        spawnFuture.exceptionally(error -> {
            server.execute(() -> {
                resetInProgress = false;
                LOGGER.error("Could not prepare a safe spawn in the replacement world", error);
            });
            return null;
        });
    }

    private static void wipePlayer(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.setExperiencePoints(0);
        player.setExperienceLevels(0);
        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.setRespawnPosition(null, false);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void moveJoiningPlayer(ServerPlayer player, MinecraftServer server) {
        ServerLevel active = server.getLevel(RotatingWorldManager.keyFor(currentWorldNumber()));
        if (active == null || player.level() == active) return;
        RotatingWorldManager.findSpawn(active).thenAccept(spawn -> server.execute(() -> {
            if (player.connection != null) RotatingWorldManager.teleport(player, active, spawn);
        }));
    }

    private static int currentWorldNumber() { return config.resetCount + 1; }

    private static long newWorldSeed() {
        long seed;
        do seed = ThreadLocalRandom.current().nextLong(); while (seed == 0L);
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
