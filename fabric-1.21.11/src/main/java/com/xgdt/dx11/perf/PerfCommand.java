package com.xgdt.dx11.perf;

import com.xgdt.dx11.perf.region.RegionManager;
import com.xgdt.dx11.perf.region.RegionSize;
import java.util.Locale;

/**
 * Local {@code /dx11perf} command implementation.
 *
 * <p>The command is intercepted before it reaches the server.  It deliberately
 * returns plain text so it can be unit-tested and so the mixin owns the chat
 * formatting.</p>
 */
public final class PerfCommand {
    public static final String NAME = "dx11perf";

    private PerfCommand() {
    }

    public static boolean isPerfCommand(String rawCommand) {
        if (rawCommand == null) {
            return false;
        }
        String command = rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        return command.equals(NAME) || command.startsWith(NAME + " ");
    }

    /** Executes a command and returns newline-separated chat feedback. */
    public static String execute(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] tokens = command.isEmpty() ? new String[0] : command.split("\\s+");
        if (tokens.length == 0 || !tokens[0].equals(NAME)) {
            return help();
        }
        if (tokens.length == 1) {
            return help();
        }

        String action = tokens[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "stats", "status" -> stats();
            case "reset" -> reset();
            case "leaks" -> leaks();
            case "toggle" -> tokens.length >= 3 ? toggle(tokens[2]) : toggleEnabled();
            case "reload" -> reload();
            case "save" -> save();
            case "regionsize", "region" -> tokens.length >= 3 ? regionSize(tokens[2]) : help();
            case "help", "?" -> help();
            default -> "Unknown /" + NAME + " subcommand: " + action + "\n" + help();
        };
    }

    /** True when a command changes vertex/region state and needs a full rebuild. */
    public static boolean requiresChunkRebuild(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] tokens = command.isEmpty() ? new String[0] : command.split("\\s+");
        if (tokens.length < 2 || !tokens[0].equals(NAME)) {
            return false;
        }
        String action = tokens[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "regionsize", "region", "reload" -> true;
            case "toggle" -> {
                if (tokens.length < 3) {
                    yield true;
                }
                String flag = tokens[2].toLowerCase(Locale.ROOT);
                yield !(flag.equals("debughud")
                    || flag.equals("hud")
                    || flag.equals("debuglog")
                    || flag.equals("log"));
            }
            default -> false;
        };
    }

    private static String stats() {
        PerfConfig config = PerfState.config();
        PerfCounters.Snapshot snapshot = PerfCounters.snapshot();
        return "DX11 perf | active=" + PerfState.active()
            + " enabled=" + config.enabled
            + " property=" + config.enabledByProperty()
            + " region=" + config.regionSize
            + " upload=" + config.regionUpload
            + " regionDraws=" + config.regionDraws
            + " compact=" + config.compactVertices
            + " translucentRegion=" + config.translucentRegion
            + "\n" + snapshot.format()
            + "\n" + RegionManager.describe()
            + "\nconfig=" + PerfState.configPath();
    }

    private static String reset() {
        PerfCounters.reset();
        return "DX11 perf counters reset.";
    }

    private static String leaks() {
        return "DX11 perf " + RegionManager.describe()
            + (PerfState.active() ? "" : " (perf layer inactive)");
    }

    private static String toggleEnabled() {
        PerfConfig config = PerfState.config();
        boolean target = !config.enabled;
        boolean active = PerfState.setEnabled(target);
        String note = config.enabledByProperty()
            ? " (-Dgl4dx11.perf.enabled=true is still forcing it on)"
            : "";
        return "DX11 perf enabled=" + target + " active=" + active + note
            + ". Region rendering changes require a world reload/full chunk rebuild.";
    }

    private static String toggle(String rawFlag) {
        PerfConfig config = PerfState.config();
        String flag = rawFlag.toLowerCase(Locale.ROOT);
        Boolean value = switch (flag) {
            case "debughud", "hud" -> {
                config.debugHud = !config.debugHud;
                yield config.debugHud;
            }
            case "debuglog", "log" -> {
                config.debugLog = !config.debugLog;
                yield config.debugLog;
            }
            case "regionupload", "upload" -> {
                config.regionUpload = !config.regionUpload;
                yield config.regionUpload;
            }
            case "regiondraws", "draws" -> {
                config.regionDraws = !config.regionDraws;
                yield config.regionDraws;
            }
            case "compactvertices", "compact" -> {
                config.compactVertices = !config.compactVertices;
                yield config.compactVertices;
            }
            case "compactverticesforce", "forcecompact" -> {
                config.compactVerticesForce = !config.compactVerticesForce;
                yield config.compactVerticesForce;
            }
            case "translucentregion", "translucent" -> {
                config.translucentRegion = !config.translucentRegion;
                yield config.translucentRegion;
            }
            case "fogocclusion", "fog" -> {
                config.fogOcclusion = !config.fogOcclusion;
                yield config.fogOcclusion;
            }
            case "deferredoffscreenupdates", "deferred" -> {
                config.deferredOffscreenUpdates = !config.deferredOffscreenUpdates;
                yield config.deferredOffscreenUpdates;
            }
            case "entityculling", "entities" -> {
                config.entityCulling = !config.entityCulling;
                yield config.entityCulling;
            }
            case "visibletextureanimation", "textures" -> {
                config.visibleTextureAnimation = !config.visibleTextureAnimation;
                yield config.visibleTextureAnimation;
            }
            default -> null;
        };
        if (value == null) {
            return "Unknown perf flag: " + rawFlag + "\n" + help();
        }
        PerfState.save();
        PerfState.refresh();
        return "DX11 perf " + flag + "=" + value + " (saved)";
    }

    private static String regionSize(String rawSize) {
        if (!RegionSize.isKnown(rawSize)) {
            return "Unknown region size: " + rawSize + " (expected REGION_4 or REGION_8)";
        }
        PerfConfig config = PerfState.config();
        config.regionSize = RegionSize.fromName(rawSize).name();
        PerfState.save();
        return "DX11 perf regionSize=" + config.regionSize
            + " (requires a world reload/full chunk rebuild)";
    }

    private static String reload() {
        PerfState.reload();
        return "DX11 perf config reloaded from " + PerfState.configPath();
    }

    private static String save() {
        PerfState.save();
        return "DX11 perf config saved to " + PerfState.configPath();
    }

    private static String help() {
        return """
            DX11 perf commands:
              /dx11perf stats | reset | leaks | reload | save
              /dx11perf toggle            - enable/disable the perf layer
              /dx11perf toggle <flag>     - debugHud, debugLog, regionUpload, regionDraws,
                                            compactVertices, compactVerticesForce,
                                            translucentRegion, fogOcclusion,
                                            deferredOffscreenUpdates, entityCulling,
                                            visibleTextureAnimation
              /dx11perf regionSize <REGION_4|REGION_8>
            The perf layer is experimental and requires the D3D11 backend.""";
    }
}
