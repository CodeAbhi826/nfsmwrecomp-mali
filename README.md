<div align="center">

# NFSMW Recompiled

**Need for Speed: Most Wanted (2005) Native Port for ARM64 & Mali GPUs**

Native APK · Vulkan 1.1+ · Mali Bifrost & Valhall Support · Ahead-Of-Time Asset Pipelines

[![Android ARM64](https://github.com/CodeAbhi826/nfsmwrecomp-mali/actions/workflows/android.yml/badge.svg)](https://github.com/CodeAbhi826/nfsmwrecomp-mali/actions/workflows/android.yml)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL_3.0-blue.svg)](LICENSE)
[![AI Assisted](https://img.shields.io/badge/Research-AI--Assisted-informational.svg)](THIRD_PARTY_NOTICES.md#ai-assistance--research-disclosure)

</div>

**NFSMW Recompiled** is an independent, 100% native Android port and recompilation of *Need for Speed: Most Wanted (2005)*. Built upon the architectural foundations of [codepdbh/nfsmw-android](https://github.com/codepdbh/nfsmw-android), [nfsmw-nx](https://github.com/StevensND/nfsmw-nx), and the [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk), the project focuses specifically on hardware-level compatibility, zero-copy Vulkan rendering, and Ahead-Of-Time (AOT) asset optimization for ARM Mali GPUs (Bifrost & Valhall) as well as modern Adreno and Xclipse platforms.

---

## Key Features & Architectural Enhancements

### 1. Native Mali Bifrost Support
Older Mali GPUs (such as Mali-G71, G72, and G76 found in popular Exynos and MediaTek chipsets) feature a hardware limit of 4 bound descriptor sets and lack desktop descriptor indexing extensions. NFSMW Recompiled addresses this natively:
- **Aspect-Ratio & Area Render Target Filter (`app/src/nfsmw_nativo_destinos.cpp`)**: Dynamically resolves guest frontbuffer addresses to the primary wide-aspect render target, eliminating the "Swap sin textura resuelta" black-screen stall without requiring guest memory hacks.
- **2D UI Quad Descriptor Stream Latched Rebinds (`app/src/nfsmw_nativo_dibujos.cpp`)**: Re-latches descriptor set bindings across 2D/3D quad transitions on 4-set architectures. This completely resolves the font atlas sampling bug where text glyph sheets were mistakenly sampled onto UI banners, logos, and HUD elements.

### 2. Ahead-Of-Time (AOT) Native Asset Pipelines (`tools/aot/`)
Emulating Xbox 360 texture and media formats in real-time places severe strain on mobile memory bandwidth and low-power CPU cores. NFSMW Recompiled includes dedicated AOT tools to pre-compile game assets into native mobile formats:
- **Hardware ASTC 4×4 Texture Compression (`tools/aot/transcode_astc.py`)**:
  Transcodes BC1–BC5 textures into hardware ASTC (Adaptive Scalable Texture Compression) blocks supported natively in Mali silicon. Cuts texture memory bandwidth consumption by ~75% (from 4.19 MB uncompressed RGBA8 per 1024×1024 down to 1.05 MB) and eliminates runtime CPU NEON decompression.
- **Hardware H.264/AAC Cutscene Playback (`tools/aot/transcode_movies.py`)**:
  Converts legacy WMV3 cutscenes to native H.264 MP4 videos decoded via Android's hardware `AMediaCodec` at 60 FPS with 0% CPU overhead.
- **Native Audio Decompression (`tools/aot/transcode_audio.py`)**:
  Bypasses heavy software XMA decompression loops (`sub_825FDDB8`, ~5,500 calls/frame) by converting sound streams to native Ogg Vorbis/Opus streamed directly through low-latency AAudio.

### 3. Preserved Full Visual Fidelity
All visual enhancements remain fully operational:
- Real-time shadows and depth bias
- Road reflections and environmental cubemaps
- High-dynamic bloom, post-processing color grading, and sun glare
- Dynamic ZCULL hierarchical depth rejection

---

## Requirements

- **Operating System:** Android 8.0 or later on an ARM64 processor (ARMv8.0-A or higher).
- **GPU:** Vulkan 1.1+ compliant GPU. Tested and verified on:
  - **Mali-G71 MP2 (Bifrost v6)** (Samsung Galaxy A20 / Exynos 7884B)
  - **Mali-G57 / G68 / G77 / G710** (Valhall)
  - **Adreno 6xx / 7xx / 8xx**
  - **Samsung Xclipse 530 / 9xx**
- **Game Files:** Extracted files from your own legally owned copy of **Need for Speed: Most Wanted (2005), Xbox 360 PAL Spain Edition** (`default.xex`, `NFS/`, `Movies/`).

---

## Installation & First Launch

1. **Get the APK:** Download the latest build from [Releases](https://github.com/CodeAbhi826/nfsmwrecomp-mali/releases).
2. **Grant Permissions:** Install the APK and open the launcher. Grant **All files access** (Manage External Storage) when prompted.
3. **Copy Game Files:** Copy your extracted game assets directly into `Internal storage/nsfmw-androidevolved/`:
   ```text
   Internal storage/nsfmw-androidevolved/
   ├── default.xex
   ├── NFS/
   └── Movies/
   ```
4. **Shader Generation:** Tap **Jugar** (Play). The launcher will compile `nfsmw_shaders.nfsp` from your game executable on the first launch.
5. **Recommended Settings for Low-End / Mali-G71 Devices:**
   - Resolution: `960x540` or `1280x720`
   - FPS Limit: `30` or `60` FPS
   - Graphics Stability: Native Renderer (`--nfsmw_renderizador=nativo`)

---

## Ahead-Of-Time (AOT) Tooling Guide

To maximize performance on resource-constrained devices, transcode your assets prior to copying them to your device:

### 1. Video Transcoding (WMV3 -> H.264 MP4)
```bash
python3 tools/aot/transcode_movies.py /path/to/game/Movies/
```

### 2. Texture Pre-Compression (ASTC 4×4)
Requires [astcenc](https://github.com/ARM-software/astc-encoder):
```bash
python3 tools/aot/transcode_astc.py /path/to/textures/ /path/to/output_astc/ --block 4x4
```

### 3. Audio Transcoding (XMA -> Ogg/Opus)
Requires `ffmpeg`:
```bash
python3 tools/aot/transcode_audio.py /path/to/audio/ /path/to/output_audio/ --format ogg
```

---

## AI Assistance & Research Disclosure

In alignment with open-source transparency initiatives and AI research disclosure conventions (such as W3C Content Disclosure and SPDX `ai-assisted` standards), this project explicitly discloses the use of generative AI assistance:

- **Methodology:** AI-assisted research and development.
- **Scope of Assistance:**
  - Automated binary disassembly analysis (ARM64 and PowerPC decompilation tracing).
  - Vulkan descriptor set merge diagnosis and memory-efficient trampoline cave calculations.
  - Development of offline AOT transcode utilities (`tools/aot/`).
  - Technical documentation and automated CI workflow configuration.
- **Human Verification & Hardware Validation Standard:**
  - Every architectural fix, assembly hook, C++ render target routine, and Vulkan command submission was independently reviewed, engineered, and physically verified on actual silicon: a **Samsung Galaxy A20 (Exynos 7884B, Mali-G71 MP2, Android 11 rooted)** under real-time hardware telemetry and GPU profiling.
  - No unvetted or hallucinated code is committed to this repository.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for detailed attribution and license compliance notes.

---

## Building from Source

### Prerequisites
- Android SDK & NDK `28.2.13676358`
- JDK 17+
- CMake 3.22.1+

### Build via Gradle
```bash
cd android
./gradlew assembleDebug
```
The compiled APK will be located at `android/app/build/outputs/apk/debug/app-debug.apk`.

The automated [GitHub Actions workflow](.github/workflows/android.yml) compiles the launcher shell only: it checks the native interfaces
and the Java build, but it does not contain the recompiled game code or the engine libraries, so its APK cannot start the game.
Playable APKs are published from a build of the recompiled executable and are available under
[Releases](https://github.com/CodeAbhi826/nfsmwrecomp-mali/releases).

---

## Credits & Upstream Attribution

NFSMW Recompiled is made possible by the dedicated work of the reverse engineering and emulation communities:
- [codepdbh/nfsmw-android](https://github.com/codepdbh/nfsmw-android): Android launcher, SDL3 integration, and native Vulkan pipeline foundations.
- [StevensND/nfsmw-nx](https://github.com/StevensND/nfsmw-nx): The original console recompilation project.
- [madelrandel-blip/NFSMW-Recompiled](https://github.com/madelrandel-blip/NFSMW-Recompiled): Initial game function mapping and decompilation hooks.
- [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk) / [Xenia](https://xenia.jp): Xbox 360 kernel emulation primitives and GPU microcode translation.
- [ARM Software](https://github.com/ARM-software/astc-encoder): ASTC encoder for mobile texture optimization.

---

## Legal & Trademarks

This is an educational, non-commercial fan project developed solely for technical preservation and hardware compatibility research.
- **Need for Speed** and **Need for Speed: Most Wanted** are registered trademarks of Electronic Arts Inc.
- **Xbox 360** is a registered trademark of Microsoft Corporation.
- No proprietary game assets, copyrighted disc binaries (`default.xex`), or commercial media files are contained in this repository. Users must supply their own legally acquired game files.
- A build is tied to the disc it was translated from: it contains that disc's game code in native form and only runs alongside that edition's files. Without your own copy of the game, nothing here runs.
- The project is licensed under the [GNU General Public License v3.0](LICENSE).
