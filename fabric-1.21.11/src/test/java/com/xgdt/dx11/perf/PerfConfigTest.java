package com.xgdt.dx11.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsAreConservative() {
        PerfConfig config = new PerfConfig();
        assertFalse(config.enabled);
        assertFalse(config.enabledByProperty());
        assertEquals("REGION_8", config.regionSize);
        assertTrue(config.regionUpload);
        assertFalse(config.regionDraws);
        assertFalse(config.compactVertices);
        assertFalse(config.compactVerticesForce);
        assertFalse(config.translucentRegion);
        assertFalse(config.debugHud);
        assertEquals(256, config.regionArenaLimitMb);
        assertEquals(RegionSizeDefaults.EIGHT, config.regionSize().name());
    }

    @Test
    void missingFileIsCreatedWithDefaults() {
        Path file = this.tempDir.resolve("gl4dx11-perf.json");
        PerfConfig.LoadResult result = PerfConfig.load(file);

        assertTrue(result.created());
        assertTrue(Files.isRegularFile(file));
        assertFalse(result.config().enabled);
        assertEquals("REGION_8", result.config().regionSize);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        Path file = this.tempDir.resolve("perf.json");
        PerfConfig config = new PerfConfig();
        config.enabled = true;
        config.regionSize = "REGION_4";
        config.compactVertices = true;
        config.translucentRegion = true;
        config.uploadMaxSectionsPerFrame = 12;
        config.debugHud = true;
        config.save(file);

        PerfConfig.LoadResult result = PerfConfig.load(file);
        assertTrue(result.config().enabled);
        assertEquals("REGION_4", result.config().regionSize);
        assertTrue(result.config().compactVertices);
        assertTrue(result.config().translucentRegion);
        assertEquals(12, result.config().uploadMaxSectionsPerFrame);
        assertTrue(result.config().debugHud);
    }

    @Test
    void invalidValuesAreNormalizedAndClamped() throws Exception {
        Path file = this.tempDir.resolve("perf.json");
        Files.writeString(
            file,
            """
            {
              "regionSize": "huge",
              "uploadMaxSectionsPerFrame": 999,
              "uploadMaxBytesPerFrame": -4,
              "regionArenaLimitMb": -5,
              "compactPositionBits": 99,
              "translucentSortBudget": -1
            }
            """,
            StandardCharsets.UTF_8);

        PerfConfig.LoadResult result = PerfConfig.load(file);
        assertEquals("REGION_8", result.config().regionSize);
        assertEquals(64, result.config().uploadMaxSectionsPerFrame);
        assertEquals(0, result.config().uploadMaxBytesPerFrame);
        assertEquals(0, result.config().regionArenaLimitMb);
        assertEquals(20, result.config().compactPositionBits);
        assertEquals(0, result.config().translucentSortBudget);
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void corruptFileFallsBackWithoutBeingOverwritten() throws Exception {
        Path file = this.tempDir.resolve("perf.json");
        String corrupt = "{ this is not json";
        Files.writeString(file, corrupt, StandardCharsets.UTF_8);

        PerfConfig.LoadResult result = PerfConfig.load(file);
        assertFalse(result.config().enabled);
        assertFalse(result.warnings().isEmpty());
        assertEquals(corrupt, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void unknownRegionNameIsRecognisedAsInvalid() {
        assertTrue(RegionSizeDefaults.isKnown("REGION_8"));
        assertTrue(RegionSizeDefaults.isKnown("region_4"));
        assertFalse(RegionSizeDefaults.isKnown("REGION_16"));
        assertFalse(RegionSizeDefaults.isKnown(null));
    }

    /** Small indirection so the test documents the expected size names. */
    private static final class RegionSizeDefaults {
        static final String EIGHT = "REGION_8";

        static boolean isKnown(String name) {
            return com.xgdt.dx11.perf.region.RegionSize.isKnown(name);
        }
    }
}
