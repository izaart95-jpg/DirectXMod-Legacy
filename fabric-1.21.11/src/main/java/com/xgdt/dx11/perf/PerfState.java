package com.xgdt.dx11.perf;

import com.xgdt.dx11.config.Dx11Config;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime state for the experimental perf layer.
 *
 * <p>The perf feature is only active when all three conditions hold:</p>
 * <ol>
 *   <li>it is enabled in {@code config/gl4dx11-perf.json} (or via
 *       {@code -Dgl4dx11.perf.enabled=true}),</li>
 *   <li>the D3D11 backend was explicitly enabled and probed successfully,</li>
 *   <li>the game is running on Windows (the D3D11 backend is Windows-only).</li>
 * </ol>
 *
 * <p>When inactive, every hook checks {@link #active()} and takes the vanilla
 * path.  Initialization is lazy so the command and debug-screen mixins can be
 * loaded before the client entrypoint runs.</p>
 */
public final class PerfState {
    private static final Logger LOGGER = LoggerFactory.getLogger("gl4dx11");
    public static final String DEBUG_PROPERTY = "gl4dx11.perf.debug";

    private static volatile PerfConfig config;
    private static volatile Path configPath;
    private static volatile boolean active;
    private static volatile boolean initialized;

    private PerfState() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            configPath = FabricLoader.getInstance()
                .getConfigDir()
                .resolve(PerfConfig.FILE_NAME);
        } catch (Throwable t) {
            LOGGER.warn("[dx11-perf] Could not resolve the config directory: {}", t.toString());
            configPath = null;
        }

        PerfConfig.LoadResult result = PerfConfig.load(configPath);
        config = result.config();
        for (String warning : result.warnings()) {
            LOGGER.warn("[dx11-perf] {}", warning);
        }
        if (result.created()) {
            LOGGER.info("[dx11-perf] Created {}", configPath);
        }
        LOGGER.info(
            "[dx11-perf] Config loaded: enabled={} regionSize={} (file: {})",
            config.enabled,
            config.regionSize,
            configPath);
        refresh();
    }

    public static PerfConfig config() {
        init();
        return config;
    }

    public static Path configPath() {
        init();
        return configPath;
    }

    /** True only when the perf layer is enabled and the D3D11 backend is live. */
    public static boolean active() {
        init();
        return active;
    }

    /**
     * Re-evaluates backend availability after a config/property change.  This
     * is cheap and safe to call from the render thread.
     */
    public static void refresh() {
        PerfConfig current = config;
        if (current == null) {
            return;
        }

        boolean backendActive;
        try {
            backendActive = Dx11Config.shouldUseDx11();
        } catch (Throwable t) {
            LOGGER.warn("[dx11-perf] D3D11 availability check failed: {}", t.toString());
            backendActive = false;
        }

        boolean wasActive = active;
        active = current.enabled() && backendActive;
        PerfCounters.setEnabled(active);
        if (active != wasActive) {
            if (active) {
                LOGGER.info(
                    "[dx11-perf] Enabled: regionSize={} upload={} draws={}",
                    current.regionSize,
                    current.regionUpload,
                    current.regionDraws);
            } else {
                LOGGER.info("[dx11-perf] Disabled");
            }
        }
    }

    public static boolean setEnabled(boolean enabled) {
        PerfConfig current = config();
        current.enabled = enabled;
        save();
        refresh();
        return active();
    }

    /** Reloads the JSON file from disk and re-applies the active state. */
    public static void reload() {
        PerfConfig.LoadResult result = PerfConfig.load(configPath);
        config = result.config();
        for (String warning : result.warnings()) {
            LOGGER.warn("[dx11-perf] {}", warning);
        }
        refresh();
    }

    public static void save() {
        PerfConfig current = config;
        if (current == null) {
            return;
        }
        try {
            current.save(configPath);
        } catch (IOException e) {
            LOGGER.warn("[dx11-perf] Could not save {}: {}", configPath, e.toString());
        }
    }
}
