package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @Inject(method = "handleConsoleInput", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$defaultConfirmation(
            String input, CommandSourceStack source, CallbackInfo ci) {
        if (ServerResetHardcore.handleEmptyConsoleConfirmation(input, source)) {
            ci.cancel();
        }
    }
}
