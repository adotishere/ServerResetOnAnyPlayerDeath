package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "getMotd", at = @At("RETURN"), cancellable = true)
    private void serverResetHardcore$motd(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ServerResetHardcore.decorateMotd(cir.getReturnValue()));
    }
}
