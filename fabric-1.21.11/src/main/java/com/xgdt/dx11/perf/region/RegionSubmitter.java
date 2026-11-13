package com.xgdt.dx11.perf.region;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xgdt.dx11.perf.PerfCounters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/**
 * Issues draws for region-backed sections.
 *
 * <p>Phase 2A keeps one draw per section and therefore keeps vanilla's
 * per-section {@code ChunkSection} uniform; only the vertex storage changes.
 * Phase 2B (when the slice was uploaded region-relative) groups co-located
 * slices by region, coalesces contiguous arena ranges and issues one indexed
 * draw per run with a single region uniform.  Non-region draws in the same list
 * are delegated back to vanilla so mixed lists (for example after an
 * arena-budget fallback) stay correct.</p>
 */
public final class RegionSubmitter {
    private static final RegionIndexBuffer REGION_INDICES = new RegionIndexBuffer();

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
        if (RegionVertexSlice.liveSlices() == 0) {
            return false;
        }

        boolean containsRegionHandle = false;
        boolean hasLiveRegionSlice = false;
        boolean allRegion = true;
        boolean allRebased = true;
        boolean singleLayer = true;
        ChunkSectionLayer layer = null;

        for (RenderPass.Draw<T> draw : draws) {
            if (!(draw.vertexBuffer() instanceof RegionVertexSlice slice)) {
                allRegion = false;
                continue;
            }
            containsRegionHandle = true;
            if (slice.isClosed()) {
                continue;
            }
            hasLiveRegionSlice = true;
            if (!slice.regionRelative()) {
                allRebased = false;
            }
            if (layer == null) {
                layer = slice.layer();
            } else if (layer != slice.layer()) {
                singleLayer = false;
            }
        }

        // A list without any region handle is entirely vanilla's.  A list that
        // only contains closed region handles must be swallowed: vanilla would
        // try to bind the registry handle as a real GPU buffer.
        if (!containsRegionHandle) {
            return false;
        }
        if (!hasLiveRegionSlice) {
            return true;
        }

        String uniformName = uniformNames.isEmpty()
            ? "ChunkSection"
            : uniformNames.iterator().next();

        if (allRegion
            && allRebased
            && singleLayer
            && (layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT)) {
            if (submitRuns(pass, draws, uniformName)) {
                return true;
            }
        }

        submitPerSection(
            pass,
            draws,
            defaultIndexBuffer,
            defaultIndexType,
            uniformNames,
            uniformOwner,
            uniformName);
        return true;
    }

    private static <T> boolean submitRuns(
        RenderPass pass, Collection<RenderPass.Draw<T>> draws, String uniformName) {
        Map<GroupKey, List<RegionVertexSlice>> groups = new LinkedHashMap<>();
        for (RenderPass.Draw<T> draw : draws) {
            if (draw.vertexBuffer() instanceof RegionVertexSlice slice && !slice.isClosed()) {
                groups.computeIfAbsent(
                        new GroupKey(slice.regionBuffer(), draw.slot()),
                        key -> new ArrayList<>())
                    .add(slice);
            }
        }

        List<GroupRuns> plans = new ArrayList<>(groups.size());
        int maxRunIndexCount = 0;
        for (Map.Entry<GroupKey, List<RegionVertexSlice>> entry : groups.entrySet()) {
            RegionBuffer buffer = entry.getKey().buffer();
            if (buffer.isClosed() || buffer.gpuBuffer().isClosed()) {
                continue;
            }

            List<RegionVertexSlice> slices = entry.getValue();
            slices.sort(Comparator.comparingLong(RegionVertexSlice::offset));
            List<RegionRuns.Span> spans = new ArrayList<>(slices.size());
            for (RegionVertexSlice slice : slices) {
                spans.add(new RegionRuns.Span(
                    slice.offset(), slice.byteSize(), slice.vertexStride(), slice.indexCount()));
            }
            List<RegionRuns.Run> runs = RegionRuns.coalesce(entry.getKey().slot(), spans);
            if (runs.isEmpty()) {
                continue;
            }
            for (RegionRuns.Run run : runs) {
                maxRunIndexCount = Math.max(maxRunIndexCount, run.indexCount());
            }
            plans.add(new GroupRuns(entry.getKey(), runs));
        }

        if (plans.isEmpty() || maxRunIndexCount <= 0) {
            return true;
        }

        GpuBuffer indexBuffer = REGION_INDICES.get(maxRunIndexCount);
        if (indexBuffer == null) {
            return false;
        }
        VertexFormat.IndexType indexType = REGION_INDICES.type();

        RegionBuffer boundBuffer = null;
        int boundSlot = -1;

        // Every region draw in this layer uses the same sequential QUADS
        // index buffer, so bind it once for the whole list.
        pass.setIndexBuffer(indexBuffer, indexType);

        for (GroupRuns plan : plans) {
            RegionBuffer buffer = plan.key().buffer();
            Region region = buffer.region();
            GpuBufferSlice uniform = RegionFrame.uniform(region.pos());
            if (uniform == null) {
                RegionFrame.warnMissingUniform(region);
                continue;
            }

            if (boundBuffer != buffer || boundSlot != plan.key().slot()) {
                pass.setVertexBuffer(plan.key().slot(), buffer.gpuBuffer());
                boundBuffer = buffer;
                boundSlot = plan.key().slot();
            }
            pass.setUniform(uniformName, uniform);
            for (RegionRuns.Run run : plan.runs()) {
                PerfCounters.onRegionRun();
                pass.drawIndexed(run.startVertex(), 0, run.indexCount(), 1);
            }
        }
        return true;
    }

    private static <T> void submitPerSection(
        RenderPass pass,
        Collection<RenderPass.Draw<T>> draws,
        @Nullable GpuBuffer defaultIndexBuffer,
        VertexFormat.@Nullable IndexType defaultIndexType,
        Collection<String> uniformNames,
        T uniformOwner,
        String uniformName) {
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
                GpuBuffer sequentialBuffer = defaultIndexBuffer;
                VertexFormat.IndexType sequentialType =
                    defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;
                if (sequentialBuffer == null) {
                    RenderSystem.AutoStorageIndexBuffer sequential =
                        RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                    sequentialBuffer = sequential.getBuffer(slice.indexCount());
                    sequentialType = sequential.type();
                }
                if (boundIndex != sequentialBuffer || boundIndexType != sequentialType) {
                    pass.setIndexBuffer(sequentialBuffer, sequentialType);
                    boundIndex = sequentialBuffer;
                    boundIndexType = sequentialType;
                }
            } else if (boundIndex != drawIndexBuffer || boundIndexType != drawIndexType) {
                pass.setIndexBuffer(
                    drawIndexBuffer,
                    drawIndexType == null
                        ? (defaultIndexType == null
                            ? VertexFormat.IndexType.SHORT
                            : defaultIndexType)
                        : drawIndexType);
                boundIndex = drawIndexBuffer;
                boundIndexType = drawIndexType;
            }

            if (slice.regionRelative()) {
                // Rebased vertices already include the section offset; the
                // vanilla per-section slice would add it a second time.
                GpuBufferSlice uniform = RegionFrame.uniform(regionBuffer.region().pos());
                if (uniform == null) {
                    RegionFrame.warnMissingUniform(regionBuffer.region());
                    continue;
                }
                pass.setUniform(uniformName, uniform);
            } else if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(
                    uniformOwner, (name, value) -> pass.setUniform(name, value));
            }
            pass.drawIndexed(slice.startVertex(), 0, slice.indexCount(), 1);
        }
    }

    private record GroupKey(RegionBuffer buffer, int slot) {
    }

    private record GroupRuns(GroupKey key, List<RegionRuns.Run> runs) {
    }
}
