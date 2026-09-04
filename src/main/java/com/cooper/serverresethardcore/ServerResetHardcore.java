package com.cooper.serverresethardcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class ServerResetHardcore implements ModInitializer {
    public static final String MOD_ID = "server_reset_hardcore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    private static final Path RESET_MARKER = GAME_DIR.resolve("server-reset-request.json");
    private static final Path PERSISTENT_DATAPACKS = GAME_DIR.resolve("persistent-datapacks");
    private static final Set<UUID> QUOTED_DEATH_PLAYERS = new HashSet<>();

    private static HardcoreConfig config;
    private static boolean resetInProgress;
    private static boolean pendingConsoleConfirmation;
    private static Component triggeringDeathMessage;
    private static long shutdownAtTick = Long.MAX_VALUE;

    @Override
    public void onInitialize() {
        prepareWorldBeforeStartup();
        config = HardcoreConfig.load(CONFIG_PATH, LOGGER);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            resetInProgress = false;
            pendingConsoleConfirmation = false;
            shutdownAtTick = Long.MAX_VALUE;
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerResetHardcore::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("confirmreset").executes(context -> {
                    if (context.getSource().getEntity() != null) {
                        context.getSource().sendFailure(Component.literal("This reset can only be confirmed from the server console."));
                        return 0;
                    }
                    if (!pendingConsoleConfirmation) {
                        context.getSource().sendFailure(Component.literal("There is no reset waiting for confirmation."));
                        return 0;
                    }
                    beginShutdown(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("World reset confirmed."), true);
                    return Command.SINGLE_SUCCESS;
                })));
        LOGGER.info("Server Reset Hardcore loaded (allowed deaths: {})", config.allowedDeaths);
    }

    public static void onPlayerDeath(ServerPlayer player, Component vanillaDeathMessage) {
        if (resetInProgress || QUOTED_DEATH_PLAYERS.remove(player.getUUID())) return;

        config.deathsSinceLastReset++;
        config.save(CONFIG_PATH, LOGGER);
        if (config.deathsSinceLastReset < config.allowedDeaths) {
            int remaining = config.allowedDeaths - config.deathsSinceLastReset;
            LOGGER.info("Death {}/{}; {} death(s) remain before reset", config.deathsSinceLastReset, config.allowedDeaths, remaining);
            return;
        }

        resetInProgress = true;
        triggeringDeathMessage = vanillaDeathMessage.copy();
        MinecraftServer server = player.level().getServer();

        for (ServerPlayer other : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (other == player || !other.isAlive()) continue;
            QUOTED_DEATH_PLAYERS.add(other.getUUID());
            other.kill(other.level());
        }

        config.deathsSinceLastReset = 0;
        config.save(CONFIG_PATH, LOGGER);
        if (config.requireConsoleConfirmation) {
            pendingConsoleConfirmation = true;
            LOGGER.warn("Death limit reached. Type 'confirmreset' in the SERVER CONSOLE to approve the world reset.");
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("World reset is waiting for server-console confirmation."), false);
        } else {
            beginShutdown(server);
        }
    }

    private static void beginShutdown(MinecraftServer server) {
        pendingConsoleConfirmation = false;
        config.resetCount++;
        config.save(CONFIG_PATH, LOGGER);
        shutdownAtTick = server.getTickCount() + (long) config.shutdownDelaySeconds * 20L;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("World reset in " + config.shutdownDelaySeconds + " second(s)."), false);
    }

    public static Component deathMessageFor(ServerPlayer player, Component vanillaMessage) {
        if (!QUOTED_DEATH_PLAYERS.remove(player.getUUID()) || triggeringDeathMessage == null) return vanillaMessage;
        return Component.literal("\"").append(triggeringDeathMessage.copy()).append("\"");
    }

    private static void tick(MinecraftServer server) {
        if (!resetInProgress || server.getTickCount() < shutdownAtTick) return;
        shutdownAtTick = Long.MAX_VALUE;
        writeResetMarker(server);
        LOGGER.warn("Death limit reached. Stopping server for world reset #{}", config.resetCount);
        server.halt(false);
    }

    private static void writeResetMarker(MinecraftServer server) {
        JsonObject marker = new JsonObject();
        marker.add("requestedBy", new JsonPrimitive(MOD_ID));
        marker.add("resetNumber", new JsonPrimitive(config.resetCount));
        try {
            Files.writeString(RESET_MARKER, marker.toString());
        } catch (IOException e) {
            LOGGER.error("Could not create reset marker; watchdog will not delete the world", e);
        }
    }

    private static void prepareWorldBeforeStartup() {
        try {
            Files.createDirectories(PERSISTENT_DATAPACKS);
            Path worldPath = resolveWorldPath();

            if (Files.exists(RESET_MARKER)) {
                JsonObject marker = JsonParser.parseString(Files.readString(RESET_MARKER)).getAsJsonObject();
                if (!MOD_ID.equals(marker.get("requestedBy").getAsString())) {
                    throw new IOException("Reset marker was not created by this mod");
                }
                deleteTree(worldPath);
                Files.delete(RESET_MARKER);
                LOGGER.warn("Deleted old world before startup");
            }

            copyPersistentDatapacks(worldPath.resolve("datapacks"));
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare the world safely; startup has been stopped", e);
        }
    }

    private static Path resolveWorldPath() throws IOException {
        Properties properties = new Properties();
        Path propertiesPath = GAME_DIR.resolve("server.properties");
        if (Files.exists(propertiesPath)) {
            try (var reader = Files.newBufferedReader(propertiesPath)) {
                properties.load(reader);
            }
        }
        String levelName = properties.getProperty("level-name", "world").trim();
        if (levelName.isEmpty() || levelName.contains("..") || levelName.contains("/") || levelName.contains("\\")) {
            throw new IOException("Unsafe level-name: " + levelName);
        }
        Path worldPath = GAME_DIR.resolve(levelName).normalize();
        if (!worldPath.getParent().equals(GAME_DIR)) {
            throw new IOException("World folder must be directly inside the server folder");
        }
        return worldPath;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
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

    public static String decorateMotd(String normalMotd) {
        if (config == null || !config.trackResets) return normalMotd;
        return "Reset #" + (config.resetCount + 1) + " : '" + config.motdText + "'";
    }
}
