package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.RotatingWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(EndPortalBlock.class)
abstract class EndPortalBlockMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$routePortal(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos,
                                                  CallbackInfoReturnable<TeleportTransition> cir) {
        var overworldKey = RotatingWorldManager.sibling(currentLevel.dimension(), RotatingWorldManager.WorldPart.OVERWORLD);
        if (overworldKey == null) return;
        var endKey = RotatingWorldManager.sibling(currentLevel.dimension(), RotatingWorldManager.WorldPart.END);
        boolean fromEnd = currentLevel.dimension().equals(endKey);
        ServerLevel destination = currentLevel.getServer().getLevel(fromEnd ? overworldKey : endKey);
        if (destination == null) {
            cir.setReturnValue(null);
            return;
        }

        if (!fromEnd) {
            EndPlatformFeature.createEndPlatform(destination, ServerLevel.END_SPAWN_POINT.below(), true);
            Vec3 spawn = Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT);
            if (entity instanceof ServerPlayer) {
                spawn = spawn.subtract(0.0, 1.0, 0.0);
            }
            cir.setReturnValue(new TeleportTransition(destination, spawn, Vec3.ZERO, Direction.WEST.toYRot(), 0.0F,
                    Relative.union(Relative.DELTA, Set.of(Relative.X_ROT)),
                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
        } else {
            BlockPos pos = destination.getRespawnData().pos();
            Vec3 spawn = Vec3.atBottomCenterOf(entity.adjustSpawnLocation(destination, pos));
            cir.setReturnValue(new TeleportTransition(destination, spawn, Vec3.ZERO,
                    destination.getRespawnData().yaw(), destination.getRespawnData().pitch(),
                    Relative.union(Relative.DELTA, Relative.ROTATION),
                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)));
        }
    }
}
