package com.xgdt.dx11.mixin;

import com.xgdt.dx11.perf.region.RegionManager;
import com.xgdt.dx11.perf.upload.UploadScheduler;
import java.util.Queue;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases region arenas when the section renderer is disposed or attached to
 * a different level, and applies the Phase 3 per-frame upload budget.
 */
@Environment(EnvType.CLIENT)
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherMixin {
    @Shadow
    @Final
    private Queue<Runnable> toUpload;

    @Shadow
    @Final
    Queue<SectionMesh> toClose;

    @Shadow
    Vec3 cameraPosition;

    @Shadow
    volatile boolean closed;

    @Inject(method = "uploadAllPendingUploads", at = @At("HEAD"), cancellable = true)
    private void dx11$budgetPendingUploads(CallbackInfo ci) {
        if (this.closed) {
            return;
        }
        if (UploadScheduler.drain(
            this.toUpload,
            this.toClose,
            this.cameraPosition.x,
            this.cameraPosition.y,
            this.cameraPosition.z)) {
            ci.cancel();
        }
    }

    @Inject(method = "dispose", at = @At("HEAD"))
    private void dx11$disposeRegions(CallbackInfo ci) {
        RegionManager.closeAll();
    }

    @Inject(method = "dispose", at = @At("TAIL"))
    private void dx11$disposeRegionsAfterUploads(CallbackInfo ci) {
        // dispose() runs a final uploadAllPendingUploads(); region uploads in
        // that drain can create fresh arenas, so close them again at the end.
        RegionManager.closeAll();
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void dx11$clearRegionsOnLevelChange(ClientLevel level, CallbackInfo ci) {
        RegionManager.clear();
    }
}
