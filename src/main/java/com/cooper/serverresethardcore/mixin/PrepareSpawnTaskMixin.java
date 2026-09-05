package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.RotatingWorldManager;
import com.cooper.serverresethardcore.ServerResetHardcore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

@Mixin(PrepareSpawnTask.class)
abstract class PrepareSpawnTaskMixin {
    @Shadow @Final private NameAndId nameAndId;

    @SuppressWarnings("unchecked")
    @Redirect(
        method = "start",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;",
            ordinal = 0
        )
    )
    private Object serverResetHardcore$routeSavedPosition(Optional<?> optional, Object fallback) {
        ServerPlayer.SavedPosition loadedPosition = (ServerPlayer.SavedPosition) ((Optional<ServerPlayer.SavedPosition>) optional)
                .orElse((ServerPlayer.SavedPosition) fallback);
        ResourceKey<Level> activeDim = RotatingWorldManager.keyFor(ServerResetHardcore.currentWorldNumber());
        boolean inCurrentSet = loadedPosition != null
                && loadedPosition.dimension().isPresent()
                && ServerResetHardcore.isDimensionInCurrentActiveSet(loadedPosition.dimension().get());

        if (!inCurrentSet) {
            ServerResetHardcore.markPendingWipe(this.nameAndId.id());
            Vec3 spawn = ServerResetHardcore.getActiveSpawnPos();
            if (spawn != null) {
                return new ServerPlayer.SavedPosition(Optional.of(activeDim), Optional.of(spawn), Optional.empty());
            } else {
                return new ServerPlayer.SavedPosition(Optional.of(activeDim), Optional.empty(), Optional.empty());
            }
        }
        return loadedPosition;
    }
}
