package com.cooper.serverresethardcore.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Map;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Shadow
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Inject(method = "getAllLevels", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$safeGetAllLevels(CallbackInfoReturnable<Iterable<ServerLevel>> cir) {
        cir.setReturnValue(new ArrayList<>(this.levels.values()));
    }
}
