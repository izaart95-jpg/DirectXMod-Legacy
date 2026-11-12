# AGENT.md

Notes for coding agents working in this repository.

## What this project is

A Direct3D 11 backend for Minecraft: Java Edition, built as a Fabric mod. The
goal is to run modern Minecraft on GPUs that top out at DirectX 10.1, such as
the Intel HD Graphics 3000. OpenGL remains the fallback whenever the D3D11
probe fails or the backend is not explicitly enabled.

This work is inspired by and derived from the original DirectXMod project:

- https://github.com/366862732/DirectXmod

The upstream project targets Minecraft 26.2 with a D3D12 backend. This
repository is a separate, additive effort: a D3D11 feature-level 10.x backend
for Minecraft Fabric 1.21.11.

## Branch layout

- `main` keeps the existing 26.2 / D3D12 code (`fabric/`, `native/`).
- `feature/dx11-1.21.11` adds the D3D11 backend:
  - `fabric-1.21.11/` - Fabric mod and Java-side `GpuDevice` implementation
  - `native-dx11/` - `dx11_mc.dll`, the native D3D11 layer
  - `shaderpacks/` - shader packs carried over from the main repo

Do not modify files under `fabric/` or `native/` when working on the D3D11
backend. The D3D11 work must stay additive and must not regress the 26.2/D3D12
path.

## Build

### Native (`dx11_mc.dll`)

Windows x64, JDK 21, Visual Studio 2022 with the C++ workload, and CMake:

```bat
cd native-dx11
build.bat
```

Output: `native-dx11\build\bin\Release\dx11_mc.dll`. The DLL imports only
`d3d11.dll` and `dxgi.dll`; `d3dcompiler_47.dll` is loaded dynamically
(falling back to 46/43), so a missing compiler is reported as a probe error
instead of a DLL load failure.

### Java (Fabric mod)

```bat
cd fabric-1.21.11
gradlew.bat build -x test
```

Output: `fabric-1.21.11\build\libs\gl4dx11-0.1.0.jar`. The Gradle build
packages `dx11_mc.dll` from `native-dx11\build\bin` when present.

## Run

Development client:

```bat
set "JAVA_TOOL_OPTIONS=-Dgl4dx12.dx11.enabled=true -Dgl4dx11.nativePath=C:\path\to\native-dx11\build\bin\Release\dx11_mc.dll"
cd fabric-1.21.11
gradlew.bat runClient
```

`JAVA_TOOL_OPTIONS` is used because Loom's `runClient` does not forward `-D`
flags from the Gradle command line to the game JVM.

Probe only:

```bat
gradlew.bat runDx11Probe -Dgl4dx11.nativePath=..\native-dx11\build\bin\Release\dx11_mc.dll
```

Normal launcher install: copy `gl4dx11-0.1.0.jar` into `.minecraft\mods` and
add `-Dgl4dx12.dx11.enabled=true` to the launcher JVM arguments.

## How it works

- The backend is opt-in. `Dx11Config.shouldUseDx11()` runs a native probe at
  startup; the D3D11 device is only installed when the probe succeeds and
  `-Dgl4dx12.dx11.enabled=true` is set. Otherwise the vanilla OpenGL path runs
  unchanged.
- `WindowMixin` creates the GLFW window with `GLFW_NO_API`; `RenderSystemMixin`
  replaces `GlDevice` with `Dx11Device` and routes `flipFrame`/vsync to the
  D3D11 swapchain.
- GLSL 330 shaders are compiled to SPIR-V with shaderc, reflected and
  translated to HLSL with SPIRV-Cross, then compiled to SM4.0 (`vs_4_0` /
  `ps_4_0`, with `4_1` fallback) by the native layer.
- The Java side implements the 1.21.11 `GpuDevice` / `CommandEncoder` /
  `RenderPass` / `RenderPipeline` / `Texture` / `Buffer` interfaces; the native
  side owns D3D11 resources behind opaque handles.
- FL10.1 lacks several FL11 features; notable emulations live in
  `native-dx11/src/dx11_device.cpp` and `Dx11IntermediaryShaderModule.java`
  (typed buffers, per-slot constant buffers, rect clears, triangle fans).

## Conventions and constraints

- Keep all D3D11 changes additive; do not break the 26.2/D3D12 code on `main`.
- Do not modify files under `fabric/` or `native/`.
- Keep `shaderpacks/` in the tree. Rebuild or adjust incompatible packs instead
  of deleting them.
- Vertex formats are padded for D3D11 alignment via `VertexFormatBuilderMixin`,
  gated on the D3D11 property so GL behavior is unchanged.
- Mapped buffer `ByteBuffer`s must be set to native byte order explicitly;
  `NewDirectByteBuffer` returns a big-endian buffer.
- Prefer diagnostics over guessing: the backend logs device state, present
  state, draw/pipeline binding details, and can dump first-frame BMPs with
  `-Dgl4dx11.debug.dumpDir=<dir>`.

## Credits

- Original DirectXMod (D3D12 / Minecraft 26.2):
  https://github.com/366862732/DirectXmod
- Minecraft is a trademark of Mojang Studios. This project is not affiliated
  with Mojang or Microsoft.
