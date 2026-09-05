package com.cooper.serverresethardcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class HardcoreConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int allowedDeaths = 1;
    public int resetDelaySeconds = 5;
    public boolean trackResets = true;
    public boolean requireConsoleConfirmation = false;
    public String motdText = "A new world awaits";
    public int resetCount = 0;
    public int deathsSinceLastReset = 0;
    public long activeOverworldSeed = 0L;
    public long activeNetherSeed = 0L;
    public long activeEndSeed = 0L;
    public long nextOverworldSeed = 0L;

    static HardcoreConfig load(Path path, Logger logger) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                HardcoreConfig config = new HardcoreConfig();
                config.save(path, logger);
                return config;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                HardcoreConfig config = GSON.fromJson(json, HardcoreConfig.class);
                if (config == null) config = new HardcoreConfig();
                if (!json.has("resetDelaySeconds") && json.has("shutdownDelaySeconds")) {
                    config.resetDelaySeconds = json.get("shutdownDelaySeconds").getAsInt();
                }
                if (!json.has("activeOverworldSeed") && json.has("activeWorldSeed")) {
                    config.activeOverworldSeed = json.get("activeWorldSeed").getAsLong();
                }
                config.sanitize();
                config.save(path, logger);
                return config;
            }
        } catch (Exception e) {
            logger.error("Could not load {}; using defaults", path, e);
            return new HardcoreConfig();
        }
    }

    synchronized void save(Path path, Logger logger) {
        sanitize();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            logger.error("Could not write temporary config {}", temp, e);
            return;
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.error("Could not save config {}", path, e);
            }
        }
    }

    private void sanitize() {
        allowedDeaths = 1;
        resetDelaySeconds = Math.max(0, resetDelaySeconds);
        resetCount = Math.max(0, resetCount);
        deathsSinceLastReset = 0;
        if (motdText == null) motdText = "";
        motdText = motdText.replace('\n', ' ').replace('\r', ' ');
    }
}
