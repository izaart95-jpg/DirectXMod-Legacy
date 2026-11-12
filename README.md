# DirectXMod Legacy

Direct3D 11 backend for Minecraft 1.21.11. Targets feature level 10.1 cards
such as the Intel HD Graphics 3000. OpenGL stays available as the fallback.

This repo only has the 1.21.11 backend. The 26.2 / D3D12 code lives in the
main DirectXMod repo.

## Contents

- `fabric-1.21.11/` - Fabric mod and Java-side GpuDevice implementation
- `native-dx11/` - `dx11_mc.dll`, the D3D11 feature level 10.x implementation
- `shaderpacks/` - shader packs carried over from the main repo

## Build

Windows x64, JDK 21, Visual Studio 2022 with the C++ workload, and CMake.

```bat
cd native-dx11
build.bat
```

The DLL ends up at `native-dx11\build\bin\Release\dx11_mc.dll`. It imports
only `d3d11.dll` and `dxgi.dll`. `d3dcompiler_47.dll` is loaded at runtime, so
a missing compiler shows up as a probe error instead of a DLL load failure.

## Probe

```bat
cd fabric-1.21.11
gradlew.bat runDx11Probe -Dgl4dx11.nativePath=..\native-dx11\build\bin\Release\dx11_mc.dll
```

Needs feature level 10_0 or newer and a working D3D compiler. The HD 3000
reports 10_1.

## Run

Development run:

```bat
set "JAVA_TOOL_OPTIONS=-Dgl4dx12.dx11.enabled=true -Dgl4dx11.nativePath=C:\path\to\native-dx11\build\bin\Release\dx11_mc.dll"
gradlew.bat runClient
```

`JAVA_TOOL_OPTIONS` is needed because Loom's `runClient` does not pass `-D`
flags from the Gradle command line to the game JVM.

Normal launcher:

```bat
gradlew.bat build -x test
```

Copy `fabric-1.21.11\build\libs\gl4dx11-0.1.0.jar` to `.minecraft\mods` and
add `-Dgl4dx12.dx11.enabled=true` to the launcher JVM arguments. If the native
DLL was built before the Gradle build, it is already packaged in the jar.

## License

Apache 2.0. See `LICENSE`, `NOTICE`, and `ASSET-LICENSE`.
