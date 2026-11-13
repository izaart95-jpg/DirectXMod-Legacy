package com.xgdt.dx11.perf.region;

import com.mojang.blaze3d.buffers.GpuBuffer;

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
    private final RegionBuffer regionBuffer;
    private final long offset;
    private final long byteSize;
    private final int vertexStride;
    private final int indexCount;
    private final int startVertex;
    private boolean closed;

    RegionVertexSlice(
        RegionBuffer regionBuffer,
        long offset,
        long byteSize,
        int vertexStride,
        int indexCount) {
        super(USAGE_VERTEX | USAGE_COPY_DST, byteSize);
        if (offset < 0 || byteSize <= 0 || vertexStride <= 0 || indexCount < 0) {
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
        this.startVertex = (int) start;
        regionBuffer.region().retainSlice();
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

    /** First vertex of this slice relative to the region arena. */
    public int startVertex() {
        return this.startVertex;
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
        this.regionBuffer.free(this.offset, this.byteSize);
        this.regionBuffer.region().releaseSlice();
    }
}
