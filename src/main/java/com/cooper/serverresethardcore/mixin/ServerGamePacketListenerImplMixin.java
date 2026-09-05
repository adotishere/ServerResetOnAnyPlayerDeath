package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$blockRespawnDuringReset(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN && ServerResetHardcore.isResetInProgress()) {
            ci.cancel();
        }
    }
}
