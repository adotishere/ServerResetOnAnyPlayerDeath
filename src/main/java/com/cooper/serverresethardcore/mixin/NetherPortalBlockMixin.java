package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.RotatingWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
abstract class NetherPortalBlockMixin {
    @Shadow
    private TeleportTransition getExitPortal(ServerLevel newLevel, Entity entity, BlockPos portalEntryPos,
                                              BlockPos approximateExitPos, boolean toNether, WorldBorder worldBorder) {
        throw new AssertionError();
    }

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void serverResetHardcore$routePortal(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos,
                                                  CallbackInfoReturnable<TeleportTransition> cir) {
        var overworld = RotatingWorldManager.sibling(currentLevel.dimension(), RotatingWorldManager.WorldPart.OVERWORLD);
        if (overworld == null) return;
        boolean toNether = currentLevel.dimension().equals(overworld);
        var destination = toNether
                ? RotatingWorldManager.sibling(currentLevel.dimension(), RotatingWorldManager.WorldPart.NETHER)
                : overworld;
        ServerLevel newLevel = currentLevel.getServer().getLevel(destination);
        if (newLevel == null) {
            cir.setReturnValue(null);
            return;
        }
        WorldBorder border = newLevel.getWorldBorder();
        double scale = DimensionType.getTeleportationScale(currentLevel.dimensionType(), newLevel.dimensionType());
        BlockPos exit = border.clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        cir.setReturnValue(getExitPortal(newLevel, entity, portalEntryPos, exit, toNether, border));
    }
}
