package com.xgdt.dx11.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerfCommandTest {
    @Test
    void geometryFlagsRequireARebuild() {
        assertTrue(PerfCommand.requiresChunkRebuild("/dx11perf toggle"));
        assertTrue(PerfCommand.requiresChunkRebuild("/dx11perf toggle regionUpload"));
        assertTrue(PerfCommand.requiresChunkRebuild("dx11perf toggle regionDraws"));
        assertTrue(PerfCommand.requiresChunkRebuild("/dx11perf regionSize REGION_4"));
        assertTrue(PerfCommand.requiresChunkRebuild("/dx11perf reload"));
    }

    @Test
    void diagnosticsDoNotRequireARebuild() {
        assertFalse(PerfCommand.requiresChunkRebuild("/dx11perf stats"));
        assertFalse(PerfCommand.requiresChunkRebuild("/dx11perf leaks"));
        assertFalse(PerfCommand.requiresChunkRebuild("/dx11perf toggle hud"));
        assertFalse(PerfCommand.requiresChunkRebuild("/dx11perf toggle debugLog"));
        assertFalse(PerfCommand.requiresChunkRebuild("/say hello"));
        assertFalse(PerfCommand.requiresChunkRebuild(null));
    }
}
