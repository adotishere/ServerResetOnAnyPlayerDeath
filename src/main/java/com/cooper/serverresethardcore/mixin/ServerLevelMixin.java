package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.RotatingWorldManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$getCustomSeed(CallbackInfoReturnable<Long> cir) {
        ServerLevel level = (ServerLevel) (Object) this;
        Long registeredSeed = RotatingWorldManager.getRegisteredSeed(level.dimension());
        if (registeredSeed != null) {
            cir.setReturnValue(registeredSeed);
        }
    }
}
