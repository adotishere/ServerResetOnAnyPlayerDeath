package com.cooper.serverresethardcore.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private List<ServerPlayer> players;
    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID;

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void serverResetHardcore$cleanStaleEntities(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        UUID uuid = player.getUUID();
        for (ServerLevel level : this.server.getAllLevels()) {
            Entity existing = level.getEntity(uuid);
            if (existing != null && existing != player) {
                existing.unRide();
                if (existing instanceof ServerPlayer existingPlayer) {
                    level.removePlayerImmediately(existingPlayer, Entity.RemovalReason.DISCARDED);
                } else {
                    existing.discard();
                }
            }
        }
        ServerPlayer cached = this.playersByUUID.get(uuid);
        if (cached != null && cached != player) {
            this.playersByUUID.remove(uuid);
            this.players.remove(cached);
        }
    }
}
