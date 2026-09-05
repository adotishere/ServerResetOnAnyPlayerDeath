package com.cooper.serverresethardcore.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
    @Accessor("waitingForRespawn")
    void serverResetHardcore$setWaitingForRespawn(boolean waiting);

    @Accessor("waitingForRespawn")
    boolean serverResetHardcore$isWaitingForRespawn();

    @Accessor("clientLoadedTimeoutTimer")
    void serverResetHardcore$setClientLoadedTimeoutTimer(int timer);
}