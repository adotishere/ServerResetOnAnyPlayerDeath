package com.cooper.serverresethardcore.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

@Mixin(net.minecraft.server.MinecraftServer.class)
public interface MinecraftServerAccessor {
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> serverResetHardcore$getLevels();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess serverResetHardcore$getStorageSource();

    @Accessor("executor")
    Executor serverResetHardcore$getExecutor();
}
