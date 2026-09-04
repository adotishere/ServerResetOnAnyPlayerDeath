package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$onDeath(DamageSource source, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (ServerResetHardcore.onPlayerDeath(player, player.getCombatTracker().getDeathMessage())) ci.cancel();
    }
}
