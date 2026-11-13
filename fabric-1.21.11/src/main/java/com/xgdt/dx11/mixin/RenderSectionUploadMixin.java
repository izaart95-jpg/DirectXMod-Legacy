package com.xgdt.dx11.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.xgdt.dx11.perf.region.RegionUploader;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the render-thread section upload body when the region renderer is
 * active.
 *
 * <p>The vanilla body is a private {@code void b(Map, CompiledSectionMesh)}
 * that runs on the section render dispatcher's main-thread executor.  The
 * region uploader must see the same call, so the injection is at HEAD and only
 * cancels after it has taken responsibility for closing every mesh.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionUploadMixin {
    @Shadow
    long sectionNode;

    @Shadow
    private long uploadedTime;

    @Shadow
    public abstract BlockPos getRenderOrigin();

    @Inject(method = "method_72051", at = @At("HEAD"), cancellable = true)
    private void dx11$uploadToRegion(
        Map<ChunkSectionLayer, MeshData> meshes,
        CompiledSectionMesh mesh,
        CallbackInfo ci) {
        // tryUpload also detaches stale region buffers when the perf layer is
        // off, so vanilla's in-place writeToBuffer never sees a registry handle.
        if (RegionUploader.tryUpload(this.getRenderOrigin(), this.sectionNode, meshes, mesh)) {
            if (this.uploadedTime == 0L) {
                this.uploadedTime = Util.getMillis();
            }
            ci.cancel();
        }
    }
}
