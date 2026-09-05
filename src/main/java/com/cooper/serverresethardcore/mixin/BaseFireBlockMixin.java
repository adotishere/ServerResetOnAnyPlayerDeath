package com.cooper.serverresethardcore.mixin;

import com.cooper.serverresethardcore.RotatingWorldManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseFireBlock.class)
abstract class BaseFireBlockMixin {
    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void serverResetHardcore$inPortalDimension(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (RotatingWorldManager.isPortalDimension(level.dimension())) {
            cir.setReturnValue(true);
        }
    }
}
