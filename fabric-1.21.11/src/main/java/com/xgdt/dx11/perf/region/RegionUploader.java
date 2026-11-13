package com.xgdt.dx11.perf.region;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xgdt.dx11.mixin.CompiledSectionMeshAccessor;
import com.xgdt.dx11.perf.PerfConfig;
import com.xgdt.dx11.perf.PerfState;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies compiled section meshes into region arenas.
 *
 * <p>The vanilla render-thread upload body is replaced at HEAD; this class
 * reimplements it for {@link ChunkSectionLayer#SOLID} and
 * {@link ChunkSectionLayer#CUTOUT}.  Translucent/tripwire layers keep vanilla
 * per-section buffers and vanilla sorting.  Every {@link MeshData} is closed
 * exactly once, whether the region path or the vanilla fallback ran.</p>
 */
public final class RegionUploader {
    private static final Logger LOGGER = LoggerFactory.getLogger("gl4dx11");
    private static final long WARN_INTERVAL_NANOS = 10_000_000_000L;
    private static long lastWarnNanos;

    private RegionUploader() {
    }

    /**
     * @return {@code true} when this method took over the whole upload body,
     *         {@code false} to let vanilla run unchanged
     */
    public static boolean tryUpload(
        BlockPos origin,
        long sectionNode,
        Map<ChunkSectionLayer, MeshData> meshes,
        CompiledSectionMesh mesh) {
        if (meshes.isEmpty()) {
            return false;
        }

        PerfConfig config = PerfState.config();
        if (!PerfState.active() || !config.regionUpload) {
            // Vanilla will take over.  It may reuse an existing buffer in
            // place through writeToBuffer, so region-backed layers must be
            // detached before the vanilla body runs.
            detachRegionBuffers(mesh, meshes.keySet());
            return false;
        }

        RegionSize size = config.regionSize();
        RegionPos regionPos = RegionPos.fromSection(
            origin.getX() >> 4,
            origin.getY() >> 4,
            origin.getZ() >> 4,
            size);
        Region region = RegionManager.getOrCreate(regionPos, size);

        for (Map.Entry<ChunkSectionLayer, MeshData> entry : meshes.entrySet()) {
            ChunkSectionLayer layer = entry.getKey();
            MeshData data = entry.getValue();
            if (data == null) {
                continue;
            }

            boolean uploadedToRegion = false;
            boolean vanillaAttempted = false;
            try {
                if (isOpaque(layer)) {
                    uploadedToRegion = uploadOpaque(region, layer, data, mesh);
                }
                if (!uploadedToRegion) {
                    vanillaAttempted = true;
                    detachRegionBuffers(mesh, layer);
                    mesh.uploadMeshLayer(layer, data, sectionNode);
                }
            } catch (RuntimeException e) {
                warn("Region upload failed for " + layer + ": " + e);
                if (!uploadedToRegion && !vanillaAttempted) {
                    try {
                        detachRegionBuffers(mesh, layer);
                        mesh.uploadMeshLayer(layer, data, sectionNode);
                    } catch (RuntimeException fallbackFailure) {
                        warn("Vanilla upload fallback also failed: " + fallbackFailure);
                    }
                }
            } finally {
                data.close();
            }
        }
        // If this was a fresh region and every layer fell back (budget or
        // unsupported mesh shape), do not park an empty region in the map.
        RegionManager.discardIfEmpty(region);
        return true;
    }

    private static boolean uploadOpaque(
        Region region,
        ChunkSectionLayer layer,
        MeshData data,
        CompiledSectionMesh mesh) {
        MeshData.DrawState draw = data.drawState();
        VertexFormat format = draw.format();
        // Region submission uses the shared sequential QUADS index buffer, so
        // only take over meshes that match that shape.  Anything else keeps
        // vanilla's per-section buffers and draw path.
        if (draw.mode() != VertexFormat.Mode.QUADS
            || draw.indexCount() <= 0
            || data.indexBuffer() != null) {
            return false;
        }
        int stride = format.getVertexSize();
        ByteBuffer vertices = data.vertexBuffer();
        int byteSize = vertices.remaining();

        if (stride <= 0 || byteSize <= 0 || byteSize % stride != 0) {
            return false;
        }

        RegionBuffer regionBuffer = region.getBuffer(layer);
        if (regionBuffer == null) {
            return false;
        }

        long alignment = Math.max(4, stride);
        RegionAllocator.Allocation allocation = regionBuffer.allocate(byteSize, alignment);
        if (allocation == null) {
            return false;
        }
        if (allocation.offset() % stride != 0) {
            // DrawIndexedInstanced addresses vertices in whole strides; a
            // sub-stride offset would draw the wrong rows.  Only tiny,
            // non-terrain strides can hit this with the 4-byte minimum
            // alignment, and those keep vanilla buffers.
            regionBuffer.free(allocation.offset(), allocation.size());
            return false;
        }

        RegionVertexSlice slice = null;
        try {
            slice = new RegionVertexSlice(
                regionBuffer, allocation.offset(), byteSize, stride, draw.indexCount());
            regionBuffer.write(allocation, vertices);

            Map<ChunkSectionLayer, SectionBuffers> buffers =
                ((CompiledSectionMeshAccessor) (Object) mesh).dx11$getBuffers();
            SectionBuffers previous = buffers.put(
                layer,
                new SectionBuffers(slice, null, draw.indexCount(), draw.indexType()));
            if (previous != null) {
                closeBuffers(previous);
            }
            return true;
        } catch (RuntimeException e) {
            if (slice != null) {
                slice.close();
            } else {
                regionBuffer.free(allocation.offset(), allocation.size());
            }
            throw e;
        }
    }

    /**
     * Removes a region-backed layer before asking vanilla to upload it.
     * Vanilla's {@code uploadMeshLayer} may reuse an existing buffer in place
     * via {@code writeToBuffer}; a {@link RegionVertexSlice} is not a
     * {@code Dx11Buffer}, so it must be detached first.
     */
    private static void detachRegionBuffers(CompiledSectionMesh mesh, ChunkSectionLayer layer) {
        Map<ChunkSectionLayer, SectionBuffers> buffers =
            ((CompiledSectionMeshAccessor) (Object) mesh).dx11$getBuffers();
        SectionBuffers existing = buffers.get(layer);
        if (existing != null && existing.getVertexBuffer() instanceof RegionVertexSlice) {
            buffers.remove(layer);
            closeBuffers(existing);
        }
    }

    private static void detachRegionBuffers(
        CompiledSectionMesh mesh, Collection<ChunkSectionLayer> layers) {
        for (ChunkSectionLayer layer : layers) {
            detachRegionBuffers(mesh, layer);
        }
    }

    private static void closeBuffers(SectionBuffers buffers) {
        try {
            buffers.getVertexBuffer().close();
        } catch (RuntimeException ignored) {
            // Already closed.
        }
        try {
            if (buffers.getIndexBuffer() != null) {
                buffers.getIndexBuffer().close();
            }
        } catch (RuntimeException ignored) {
            // Already closed.
        }
    }

    private static boolean isOpaque(ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT;
    }

    private static void warn(String message) {
        long now = System.nanoTime();
        if (now - lastWarnNanos < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnNanos = now;
        LOGGER.warn("[dx11-perf] {}", message);
    }
}
