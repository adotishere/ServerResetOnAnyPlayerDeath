package com.cooper.serverresethardcore;

import com.cooper.serverresethardcore.mixin.MinecraftServerAccessor;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

final class RotatingWorldManager {
    private RotatingWorldManager() {}

    static ResourceKey<Level> keyFor(int worldNumber) {
        return ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(ServerResetHardcore.MOD_ID, "reset_" + worldNumber));
    }

    static ServerLevel ensureWorld(MinecraftServer server, int worldNumber, long seed) {
        ResourceKey<Level> key = keyFor(worldNumber);
        ServerLevel existing = server.getLevel(key);
        if (existing != null) return existing;

        HolderGetter<DimensionType> dimensionTypes = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        HolderGetter<MultiNoiseBiomeSourceParameterList> biomePresets =
                server.registryAccess().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        HolderGetter<NoiseGeneratorSettings> noiseSettings =
                server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS);
        Holder<DimensionType> dimensionType = dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(
                biomePresets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(
                biomeSource, noiseSettings.getOrThrow(NoiseGeneratorSettings.OVERWORLD));
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
                true
        );
        accessor.serverResetHardcore$getLevels().put(key, created);
        created.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
        return created;
    }

    static CompletableFuture<Vec3> findSpawn(ServerLevel world) {
        return PlayerSpawnFinder.findSpawn(world, world.getRespawnData().pos());
    }

    static void teleport(ServerPlayer player, ServerLevel world, Vec3 spawn) {
        player.teleportTo(world, spawn.x, spawn.y, spawn.z, Set.<Relative>of(), 0.0F, 0.0F, true);
    }

    static void deleteWorld(MinecraftServer server, ResourceKey<Level> key, Path worldFolder) throws IOException {
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
