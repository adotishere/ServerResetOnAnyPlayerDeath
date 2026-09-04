package com.cooper.serverresethardcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private static final Path MOTD_BACKUP = GAME_DIR.resolve("server-reset-hardcore-motd-backup.txt");
    private static final Set<UUID> QUOTED_DEATH_PLAYERS = new HashSet<>();

    private static HardcoreConfig config;
    private static boolean resetInProgress;
    private static boolean pendingConsoleConfirmation;
    private static long confirmationPromptAtTick = Long.MAX_VALUE;
    private static Component triggeringDeathMessage;
    private static long shutdownAtTick = Long.MAX_VALUE;

    @Override
    public void onInitialize() {
        prepareWorldBeforeStartup();
        config = HardcoreConfig.load(CONFIG_PATH, LOGGER);
        updateServerPropertiesMotd();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            resetInProgress = false;
            pendingConsoleConfirmation = false;
            confirmationPromptAtTick = Long.MAX_VALUE;
            shutdownAtTick = Long.MAX_VALUE;
        });
        ServerTickEvents.END_SERVER_TICK.register(ServerResetHardcore::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerConfirmationCommand(dispatcher, "y", true);
            registerConfirmationCommand(dispatcher, "Y", true);
            registerConfirmationCommand(dispatcher, "n", false);
            registerConfirmationCommand(dispatcher, "N", false);
        });
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
            confirmationPromptAtTick = server.getTickCount() + 1L;
        } else {
            beginShutdown(server);
        }
    }

    private static void registerConfirmationCommand(
            CommandDispatcher<CommandSourceStack> dispatcher, String name, boolean approve) {
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
                beginShutdown(source.getServer());
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
        beginShutdown(source.getServer());
        source.sendSuccess(() -> Component.literal("World reset confirmed."), false);
        return true;
    }

    private static void beginShutdown(MinecraftServer server) {
        pendingConsoleConfirmation = false;
        confirmationPromptAtTick = Long.MAX_VALUE;
        config.resetCount++;
        config.save(CONFIG_PATH, LOGGER);
        updateServerPropertiesMotd();
        shutdownAtTick = server.getTickCount() + (long) config.shutdownDelaySeconds * 20L;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("World reset in " + config.shutdownDelaySeconds + " second(s)."), false);
    }

    public static Component deathMessageFor(ServerPlayer player, Component vanillaMessage) {
        if (!QUOTED_DEATH_PLAYERS.remove(player.getUUID()) || triggeringDeathMessage == null) return vanillaMessage;
        return Component.literal("\"").append(triggeringDeathMessage.copy()).append("\"");
    }

    private static void tick(MinecraftServer server) {
        if (pendingConsoleConfirmation && server.getTickCount() >= confirmationPromptAtTick) {
            confirmationPromptAtTick = Long.MAX_VALUE;
            LOGGER.warn("Reset the world? [Y/n]");
        }
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

    private static void updateServerPropertiesMotd() {
        Path propertiesPath = GAME_DIR.resolve("server.properties");
        try {
            List<String> lines = Files.exists(propertiesPath)
                    ? new ArrayList<>(Files.readAllLines(propertiesPath))
                    : new ArrayList<>();
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
                desiredMotd = "§6§lReset #" + (config.resetCount + 1) + "§r\\n§7" + config.motdText;
            } else {
                if (!Files.exists(MOTD_BACKUP)) return;
                desiredMotd = Files.readString(MOTD_BACKUP);
            }

            String replacement = "motd=" + desiredMotd;
            if (motdLine >= 0) lines.set(motdLine, replacement);
            else lines.add(replacement);
            Files.write(propertiesPath, lines);
            if (!config.trackResets) Files.deleteIfExists(MOTD_BACKUP);
        } catch (IOException e) {
            LOGGER.error("Could not update motd in server.properties", e);
        }
    }
}
