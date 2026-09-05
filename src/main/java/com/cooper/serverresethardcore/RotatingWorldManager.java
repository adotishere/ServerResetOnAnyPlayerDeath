package com.cooper.serverresethardcore;

import com.cooper.serverresethardcore.mixin.MinecraftServerAccessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class RotatingWorldManager {
    public enum WorldPart { OVERWORLD, NETHER, END }
    private static final Map<ResourceKey<Level>, Long> SEED_REGISTRY = new ConcurrentHashMap<>();

    private RotatingWorldManager() {}

    public static void registerSeed(ResourceKey<Level> key, long seed) {
        SEED_REGISTRY.put(key, seed);
    }

    public static Long getRegisteredSeed(ResourceKey<Level> key) {
        return SEED_REGISTRY.get(key);
    }

    public static void unregisterSeed(ResourceKey<Level> key) {
        SEED_REGISTRY.remove(key);
    }

    public static ResourceKey<Level> keyFor(int worldNumber) {
        return keyFor(worldNumber, WorldPart.OVERWORLD);
    }

    public static ResourceKey<Level> keyFor(int worldNumber, WorldPart part) {
        String suffix = switch (part) {
            case OVERWORLD -> "";
            case NETHER -> "_nether";
            case END -> "_end";
        };
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(ServerResetHardcore.MOD_ID, "reset_" + worldNumber + suffix));
    }

    static ServerLevel ensureWorldSet(MinecraftServer server, int worldNumber,
                                      long overworldSeed, long netherSeed, long endSeed) {
        ServerLevel overworld = ensureWorld(server, worldNumber, overworldSeed, WorldPart.OVERWORLD);
        ensureWorld(server, worldNumber, netherSeed, WorldPart.NETHER);
        ensureWorld(server, worldNumber, endSeed, WorldPart.END);
        return overworld;
    }

    static ServerLevel ensureWorldSet(MinecraftServer server, int worldNumber, long seed) {
        return ensureWorldSet(server, worldNumber, seed, seed, seed);
    }

    static CompletableFuture<Void> pregenerateChunks(ServerLevel world, Vec3 spawnPos, int radius) {
        BlockPos blockPos = BlockPos.containing(spawnPos);
        int centerX = blockPos.getX() >> 4;
        int centerZ = blockPos.getZ() >> 4;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                futures.add(world.getChunkSource().getChunkFuture(centerX + dx, centerZ + dz, ChunkStatus.FULL, true));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    static ServerLevel ensureOverworld(MinecraftServer server, int worldNumber, long seed) {
        return ensureWorld(server, worldNumber, seed, WorldPart.OVERWORLD);
    }

    static void ensureNetherAndEnd(MinecraftServer server, int worldNumber, long netherSeed, long endSeed) {
        ensureWorld(server, worldNumber, netherSeed, WorldPart.NETHER);
        ensureWorld(server, worldNumber, endSeed, WorldPart.END);
    }

    private static ServerLevel ensureWorld(MinecraftServer server, int worldNumber, long seed, WorldPart part) {
        ResourceKey<Level> key = keyFor(worldNumber, part);
        registerSeed(key, seed);
        ServerLevel existing = server.getLevel(key);
        if (existing != null) return existing;

        HolderGetter<DimensionType> dimensionTypes = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        HolderGetter<NoiseGeneratorSettings> noiseSettings =
                server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS);
        Holder<DimensionType> dimensionType;
        NoiseBasedChunkGenerator generator;
        if (part == WorldPart.END) {
            HolderGetter<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
            dimensionType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.END);
            generator = new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes),
                    noiseSettings.getOrThrow(NoiseGeneratorSettings.END));
        } else {
            HolderGetter<MultiNoiseBiomeSourceParameterList> biomePresets =
                    server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            boolean nether = part == WorldPart.NETHER;
            dimensionType = dimensionTypes.getOrThrow(nether ? BuiltinDimensionTypes.NETHER : BuiltinDimensionTypes.OVERWORLD);
            MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(
                    biomePresets.getOrThrow(nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            generator = new NoiseBasedChunkGenerator(biomeSource,
                    noiseSettings.getOrThrow(nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD));
        }
        LevelStem stem = new LevelStem(dimensionType, generator);
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        ServerLevel created = new ServerLevel(
                server,
                accessor.serverResetHardcore$getExecutor(),
                accessor.serverResetHardcore$getStorageSource(),
                server.getWorldData().overworldData(),
                key,
                stem,
                server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(seed),
                ImmutableList.of(),
                part == WorldPart.OVERWORLD
        );
        accessor.serverResetHardcore$getLevels().put(key, created);
        created.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        return created;
    }

    public static ResourceKey<Level> sibling(ResourceKey<Level> current, WorldPart target) {
        String path = current.identifier().getPath();
        if (!current.identifier().getNamespace().equals(ServerResetHardcore.MOD_ID) || !path.startsWith("reset_")) return null;
        String number = path.substring("reset_".length()).replaceFirst("_(nether|end)$", "");
        try { return keyFor(Integer.parseInt(number), target); }
        catch (NumberFormatException ignored) { return null; }
    }

    public static boolean isPortalDimension(ResourceKey<Level> key) {
        if (key == null) return false;
        if (!key.identifier().getNamespace().equals(ServerResetHardcore.MOD_ID)) return false;
        String path = key.identifier().getPath();
        if (!path.startsWith("reset_")) return false;
        return !path.endsWith("_end");
    }

    public static BlockPos findSafeLandSpawn(ServerLevel world) {
        try {
            BlockPos naturalSpawn = world.getChunkSource().randomState().sampler().findSpawnPosition();
            if (naturalSpawn != null) {
                int cx = naturalSpawn.getX() >> 4;
                int cz = naturalSpawn.getZ() >> 4;
                for (int radius = 0; radius <= 16; radius++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                                ChunkPos candidate = new ChunkPos(cx + dx, cz + dz);
                                world.getChunk(candidate.x(), candidate.z(), ChunkStatus.FULL, true);
                                BlockPos safe = PlayerSpawnFinder.getSpawnPosInChunk(world, candidate);
                                if (safe != null) {
                                    return safe;
                                }
                            }
                        }
                    }
                }
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, naturalSpawn.getX(), naturalSpawn.getZ());
                return new BlockPos(naturalSpawn.getX(), Math.max(y, world.getMinY() + 64), naturalSpawn.getZ());
            }
        } catch (Exception e) {
            ServerResetHardcore.LOGGER.warn("Failed to sample natural land spawn, falling back", e);
        }
        BlockPos fallback = world.getRespawnData().pos();
        int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, fallback.getX(), fallback.getZ());
        return new BlockPos(fallback.getX(), Math.max(y, world.getMinY() + 64), fallback.getZ());
    }

    static CompletableFuture<Vec3> findSpawn(ServerLevel world) {
        BlockPos landSpawn = findSafeLandSpawn(world);
        return PlayerSpawnFinder.findSpawn(world, landSpawn);
    }

    static Vec3 getImmediateSafeSpawn(ServerLevel world) {
        BlockPos safe = findSafeLandSpawn(world);
        return Vec3.atCenterOf(safe);
    }

    static void teleport(ServerPlayer player, ServerLevel world, Vec3 spawn) {
        player.teleportTo(world, spawn.x, spawn.y, spawn.z, Set.<Relative>of(), 0.0F, 0.0F, true);
    }

    public static void resetWorldTime(MinecraftServer server) {
        try {
            if (server.getWorldData() != null && server.getWorldData().overworldData() != null) {
                server.getWorldData().overworldData().setGameTime(0L);
            }
            server.registryAccess().lookup(Registries.WORLD_CLOCK).ifPresent(lookup ->
                lookup.listElements().forEach(clock ->
                    server.clockManager().setTotalTicks(clock, 0L)
                )
            );
            if (server.getPlayerList() != null) {
                server.getPlayerList().broadcastAll(server.clockManager().createFullSyncPacket());
            }
        } catch (Exception e) {
            ServerResetHardcore.LOGGER.error("Failed to reset world time to 0", e);
        }
    }

    public static void clearWeather(MinecraftServer server) {
        try {
            int clearDuration = ServerLevel.RAIN_DELAY.sample(server.overworld().getRandom());
            server.setWeatherParameters(clearDuration, 0, false, false);
            for (ServerLevel level : server.getAllLevels()) {
                level.resetWeatherCycle();
            }
            if (server.getPlayerList() != null) {
                server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING, 0.0F));
                server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.0F));
                server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F));
            }
        } catch (Exception e) {
            ServerResetHardcore.LOGGER.error("Failed to clear weather on reset", e);
        }
    }

    static void deleteWorldSet(MinecraftServer server, int worldNumber, Path worldFolder) throws IOException {
        for (WorldPart part : WorldPart.values()) deleteWorld(server, keyFor(worldNumber, part), worldFolder);
    }

    private static void deleteWorld(MinecraftServer server, ResourceKey<Level> key, Path worldFolder) throws IOException {
        unregisterSeed(key);
        Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).serverResetHardcore$getLevels();
        ServerLevel old = levels.remove(key);
        if (old != null) {
            old.noSave = true;
            old.close();
        }
        Path dimensionFolder = worldFolder.resolve("dimensions").resolve(key.identifier().getNamespace())
                .resolve(key.identifier().getPath()).normalize();
        if (!dimensionFolder.startsWith(worldFolder.resolve("dimensions").normalize())) {
            throw new IOException("Refusing to delete an unsafe dimension path");
        }
        if (!Files.exists(dimensionFolder)) return;
        try (Stream<Path> paths = Files.walk(dimensionFolder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
