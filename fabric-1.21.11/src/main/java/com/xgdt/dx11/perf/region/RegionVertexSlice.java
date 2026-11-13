package com.xgdt.dx11.perf.region;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Lightweight {@link GpuBuffer} handle for one section's vertex allocation.
 *
 * <p>It is never bound directly: {@code RegionSubmitter} resolves the owning
 * {@link RegionBuffer} and issues a draw with that real buffer and this
 * slice's vertex offset.  The handle exists so vanilla's
 * {@code CompiledSectionMesh.close()} path frees the allocation through the
 * normal {@link GpuBuffer#close()} contract.</p>
 */
public final class RegionVertexSlice extends GpuBuffer {
    private static int liveSlices;
    private static int liveRebasedSlices;

    private final RegionBuffer regionBuffer;
    private final long offset;
    private final long byteSize;
    private final int vertexStride;
    private final int indexCount;
    private final int vertexCount;
    private final ChunkSectionLayer layer;
    private final boolean regionRelative;
    private final int startVertex;
    private boolean closed;

    RegionVertexSlice(
        RegionBuffer regionBuffer,
        long offset,
        long byteSize,
        int vertexStride,
        int indexCount,
        int vertexCount,
        ChunkSectionLayer layer,
        boolean regionRelative) {
        super(USAGE_VERTEX | USAGE_COPY_DST, byteSize);
        if (offset < 0 || byteSize <= 0 || vertexStride <= 0 || indexCount < 0
            || vertexCount < 0) {
            throw new IllegalArgumentException("Invalid region vertex slice");
        }
        long start = offset / vertexStride;
        if (start > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Region slice offset is too large");
        }
        this.regionBuffer = regionBuffer;
        this.offset = offset;
        this.byteSize = byteSize;
        this.vertexStride = vertexStride;
        this.indexCount = indexCount;
        this.vertexCount = vertexCount;
        this.layer = layer;
        this.regionRelative = regionRelative;
        this.startVertex = (int) start;
        regionBuffer.region().retainSlice();
        liveSlices++;
        if (regionRelative) {
            liveRebasedSlices++;
        }
    }

    public RegionBuffer regionBuffer() {
        return this.regionBuffer;
    }

    public long offset() {
        return this.offset;
    }

    public long byteSize() {
        return this.byteSize;
    }

    public int vertexStride() {
        return this.vertexStride;
    }

    public int indexCount() {
        return this.indexCount;
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public ChunkSectionLayer layer() {
        return this.layer;
    }

    /** True when the vertex positions were rebased to the region origin. */
    public boolean regionRelative() {
        return this.regionRelative;
    }

    /** First vertex of this slice relative to the region arena. */
    public int startVertex() {
        return this.startVertex;
    }

    /** Number of live rebased slices; guards the per-frame region uniforms. */
    public static int liveRebasedSlices() {
        return liveRebasedSlices;
    }

    /** Number of live region slices across every upload mode. */
    public static int liveSlices() {
        return liveSlices;
    }

    @Override
    public long size() {
        return this.byteSize;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        liveSlices--;
        if (this.regionRelative) {
            liveRebasedSlices--;
        }
        this.regionBuffer.free(this.offset, this.byteSize);
        this.regionBuffer.region().releaseSlice();
    }
}
