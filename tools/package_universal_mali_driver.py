#!/usr/bin/env python3
"""
Universal Mali GPU Driver Packager
Packs MaliCompat into a universal custom GPU driver archive compatible with:
- Yuzu / Suyu / Sudachi / Skyline / Strato (Nintendo Switch emulators on Android)
- Winlator / Mobox / Box64Droid (Windows x86/x64 PC emulators on Android)
- Vita3K Android (PlayStation Vita emulator)
- Custom Adrenotools-enabled native Android games and recompilations
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile

META_DATA = {
    "schemaVersion": 1,
    "name": "MaliCompat Universal Vulkan Driver",
    "description": "Universal Vulkan Compatibility & Descriptor Indexing Shim for ARM Mali GPUs (Bifrost & Valhall)",
    "author": "CodeAbhi826 / Malicomp",
    "packageVersion": "1.0.0",
    "vendor": "ARM Mali / MaliCompat",
    "driverVersion": "1.0.0-universal",
    "minApi": 28,
    "libraryName": "libmalicompat.so"
}

DRIVER_JSON = {
    "name": "MaliCompat-Universal",
    "libraryName": "libmalicompat.so",
    "folder": "payload"
}

README_TEXT = """# MaliCompat Universal GPU Driver for ARM Mali

This driver package wraps the vendor Mali Vulkan driver to provide compatibility fixes for games and emulators running on ARM Mali Bifrost & Valhall GPUs (e.g. Mali-G71, G72, G76, G77, G78, G710, G715).

## Compatibility & Target Emulators
1. **Yuzu / Suyu / Sudachi / Skyline:**
   - Go to Settings -> GPU Driver Manager -> Install GPU Driver.
   - Select this .zip file.
2. **Winlator / Mobox / Box64Droid:**
   - Place this driver into the custom Turnip/Mali driver selection menu.
3. **Vita3K Android:**
   - Go to Settings -> GPU -> Custom Driver -> Install .zip.
4. **Android Native Recompilations:**
   - Directly loaded via libadrenotools or MaliCompat Cvar `--gpu_driver=malicompat`.

## Features
- Hardware dual-target dispatcher routing WSI swapchain surfaces to the Android system loader.
- Virtualized descriptor indexing (`descriptorIndexing`, `runtimeDescriptorArray`).
- 4-descriptor-set adaptation for Mali Bifrost architectures.
- BC texture decompression fallback for Mali hardware without BC1-BC7 support.
"""

def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def build_universal_package(binary_path: str, output_zip_paths: list):
    if not os.path.exists(binary_path):
        print(f"[-] Error: input binary '{binary_path}' not found!", file=sys.stderr)
        sys.exit(1)

    bin_size = os.path.getsize(binary_path)
    bin_sha = sha256_file(binary_path)
    print(f"[*] Input MaliCompat Binary: {binary_path}")
    print(f"    Size: {bin_size:,} bytes")
    print(f"    SHA256: {bin_sha}")

    meta_json = json.dumps(META_DATA, indent=2) + "\n"
    driver_json = json.dumps(DRIVER_JSON, indent=2) + "\n"
    libreria = "libmalicompat.so\n"

    with open(binary_path, "rb") as f:
        binary_bytes = f.read()

    for out_zip in output_zip_paths:
        os.makedirs(os.path.dirname(os.path.abspath(out_zip)), exist_ok=True)
        with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED) as z:
            # 1. Root level (for Adrenotools v1 / Skyline / Vita3K)
            z.writestr("meta.json", meta_json)
            z.writestr("driver.json", driver_json)
            z.writestr(".libreria", libreria)
            z.writestr("libmalicompat.so", binary_bytes)
            z.writestr("README.md", README_TEXT)

            # 2. Payload layout (for Turnip / Adrenotools v2)
            z.writestr("payload/meta.json", meta_json)
            z.writestr("payload/libmalicompat.so", binary_bytes)

            # 3. Standard Android ABI layout (for Winlator / Box64Droid)
            z.writestr("lib/arm64-v8a/libmalicompat.so", binary_bytes)

        zip_size = os.path.getsize(out_zip)
        zip_sha = sha256_file(out_zip)
        print(f"[+] Generated: {out_zip} ({zip_size:,} bytes, SHA256: {zip_sha})")

def main():
    parser = argparse.ArgumentParser(description="Build Universal Mali GPU Driver ZIP")
    parser.add_argument("--binary", default="dist/libmalicompat.so", help="Path to libmalicompat.so")
    parser.add_argument("--out", nargs="+", default=[
        "dist/universal-mali-gpu-driver.zip",
        "drivers/universal-mali-gpu-driver.zip",
        "android/app/src/main/assets/drivers/universal-mali-gpu-driver.zip"
    ], help="Output zip paths")
    args = parser.parse_args()

    build_universal_package(args.binary, args.out)

if __name__ == "__main__":
    main()
