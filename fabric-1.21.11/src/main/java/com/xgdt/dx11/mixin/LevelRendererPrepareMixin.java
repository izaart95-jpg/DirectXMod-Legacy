package com.xgdt.dx11.mixin;

import com.xgdt.dx11.perf.region.RegionFrame;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the frame's model-view matrix and the visible-section walk.
 *
 * <p>{@code prepareChunkRenders} is the only place where vanilla still has the
 * camera matrix, the atlas size and every visible section's fade visibility
 * together.  Region draws need the same information once per region rather
 * than once per section, so the frame is opened here and closed at RETURN,
 * where {@link RegionFrame} writes the region uniforms before the render
 * passes start.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(LevelRenderer.class)
public class LevelRendererPrepareMixin {
    @Inject(method = "prepareChunkRenders", at = @At("HEAD"))
    private void dx11$beginRegionFrame(
        Matrix4fc modelView,
        double cameraX,
        double cameraY,
        double cameraZ,
        CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        RegionFrame.begin(modelView, cameraX, cameraY, cameraZ);
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"))
    private void dx11$endRegionFrame(
        Matrix4fc modelView,
        double cameraX,
        double cameraY,
        double cameraZ,
        CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        RegionFrame.end();
    }
}
