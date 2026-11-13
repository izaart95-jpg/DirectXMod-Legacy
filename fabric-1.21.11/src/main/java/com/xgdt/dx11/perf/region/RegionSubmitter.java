package com.xgdt.dx11.perf.region;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Issues draws for region-backed sections.
 *
 * <p>Phase 2A keeps one draw per section and therefore keeps vanilla's
 * per-section {@code ChunkSection} uniform; only the vertex storage changes.
 * The region arena is bound once and every draw uses {@code baseVertex} to
 * point at its slice.  Non-region draws in the same list are delegated back to
 * vanilla so mixed lists (for example after an arena-budget fallback) stay
 * correct.</p>
 */
public final class RegionSubmitter {
    private RegionSubmitter() {
    }

    /**
     * @return {@code true} when the list contained region slices and was fully
     *         submitted, {@code false} when the caller should use vanilla
     */
    public static <T> boolean submit(
        RenderPass pass,
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        VertexFormat.@Nullable IndexType defaultIndexType,
        Collection<String> uniformNames,
        T uniformOwner) {
        if (draws.isEmpty()) {
            return false;
        }

        boolean hasRegionDraw = false;
        int maxIndexCount = 0;
        for (RenderPass.Draw<T> draw : draws) {
            if (draw.vertexBuffer() instanceof RegionVertexSlice slice) {
                hasRegionDraw = true;
                if (!slice.isClosed()) {
                    maxIndexCount = Math.max(maxIndexCount, slice.indexCount());
                }
            }
        }
        // A list that contains any region slice must never be handed back to
        // vanilla: RegionVertexSlice is a registry handle, not a Dx11Buffer.
        // This also keeps existing region-backed sections drawable after the
        // perf layer is switched off, until they are re-uploaded/replaced.
        if (!hasRegionDraw) {
            return false;
        }

        RenderSystem.AutoStorageIndexBuffer sequential = null;
        GpuBuffer sequentialBuffer = null;
        VertexFormat.IndexType sequentialType = null;
        if (maxIndexCount > 0) {
            sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            sequentialBuffer = sequential.getBuffer(maxIndexCount);
            sequentialType = sequential.type();
        }

        RegionBuffer boundRegion = null;
        int boundSlot = -1;
        GpuBuffer boundIndex = null;
        VertexFormat.IndexType boundIndexType = null;

        for (RenderPass.Draw<T> draw : draws) {
            if (!(draw.vertexBuffer() instanceof RegionVertexSlice slice)) {
                // Mixed/fallback list: let vanilla bind its own buffers and
                // upload the per-section uniform for this draw.
                pass.drawMultipleIndexed(
                    List.of(draw), defaultIndexBuffer, defaultIndexType, uniformNames, uniformOwner);
                boundRegion = null;
                boundSlot = -1;
                boundIndex = null;
                boundIndexType = null;
                continue;
            }
            if (slice.isClosed()) {
                // The section was dropped between draw-list construction and
                // submission; its arena slice is already gone.
                continue;
            }

            RegionBuffer regionBuffer = slice.regionBuffer();
            if (regionBuffer == null || regionBuffer.isClosed()
                || regionBuffer.gpuBuffer().isClosed()) {
                continue;
            }

            if (boundRegion != regionBuffer || boundSlot != draw.slot()) {
                pass.setVertexBuffer(draw.slot(), regionBuffer.gpuBuffer());
                boundRegion = regionBuffer;
                boundSlot = draw.slot();
            }

            GpuBuffer drawIndexBuffer = draw.indexBuffer();
            VertexFormat.IndexType drawIndexType = draw.indexType();
            if (drawIndexBuffer == null) {
                if (boundIndex != sequentialBuffer || boundIndexType != sequentialType) {
                    pass.setIndexBuffer(sequentialBuffer, sequentialType);
                    boundIndex = sequentialBuffer;
                    boundIndexType = sequentialType;
                }
            } else if (boundIndex != drawIndexBuffer || boundIndexType != drawIndexType) {
                pass.setIndexBuffer(
                    drawIndexBuffer,
                    drawIndexType == null
                        ? (defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType)
                        : drawIndexType);
                boundIndex = drawIndexBuffer;
                boundIndexType = drawIndexType;
            }

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(
                    uniformOwner, (name, value) -> pass.setUniform(name, value));
            }
            pass.drawIndexed(slice.startVertex(), 0, slice.indexCount(), 1);
        }
        return true;
    }
}
