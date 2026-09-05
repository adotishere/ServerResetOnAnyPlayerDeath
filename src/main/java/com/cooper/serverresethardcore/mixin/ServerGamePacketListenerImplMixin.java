package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$blockRespawnDuringReset(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN && ServerResetHardcore.isResetInProgress()) {
            ci.cancel();
        }
    }

    @Inject(method = "hasClientLoaded", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$forceClientLoadedIfAlive(CallbackInfoReturnable<Boolean> cir) {
        if (!ServerResetHardcore.isResetInProgress() && this.player != null && !this.player.isDeadOrDying()) {
            cir.setReturnValue(true);
        }
    }
}
