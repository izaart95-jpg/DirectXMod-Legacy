package com.xgdt.dx11.mixin;

import com.xgdt.dx11.perf.region.RegionManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases region arenas when the section renderer is disposed or attached to
 * a different level.
 */
@Environment(EnvType.CLIENT)
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherMixin {
    @Inject(method = "dispose", at = @At("HEAD"))
    private void dx11$disposeRegions(CallbackInfo ci) {
        RegionManager.closeAll();
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void dx11$clearRegionsOnLevelChange(ClientLevel level, CallbackInfo ci) {
        RegionManager.clear();
    }
}
