#!/usr/bin/env python3
"""Mali-G71 / Vulkan 1.1: let the native renderer start without descriptor-indexing features.

The prebuilt core (Victor's v0.5.1 libs, all editions) aborts the native renderer in
DibujosVulkan::Inicializar() when the physical device does not advertise any of the six features
its shaders "require":

    independentBlend
    runtimeDescriptorArray
    shaderSampledImageArrayDynamicIndexing
    descriptorBindingPartiallyBound
    descriptorBindingSampledImageUpdateAfterBind
    descriptorBindingUpdateUnusedWhilePending

Mali-G71 (r26p0) reports the descriptor-indexing ones as false, so the engine logs

    [nativo] C6: el dispositivo Vulkan no tiene runtimeDescriptorArray: no se dibuja

and disables drawing: only clears, copies, movies and the HUD ever get drawn (black screen).
The engine, however, has complete Vulkan 1.1 fallbacks - it lays descriptors out in 4 sets when
maxBoundDescriptorSets < 5, reads every constant from UBOs (nfsmw_nativo_constantes_ubo) and picks
the SPIR-V version from the device's apiVersion - so the requirement check is stricter than the
renderer actually is. This script removes just the check.

In the shipped binaries the check is six back-to-back conditional branches that all jump to the
"missing capability" log and `return false`:

    0x23f8d50: tbz w13, #0, <fail>   ; independentBlend
    0x23f8d54: cbz w12, <fail>       ; runtimeDescriptorArray
    0x23f8d58: cbz w10, <fail>       ; shaderSampledImageArrayDynamicIndexing
    0x23f8d5c: cbz w9,  <fail>       ; descriptorBindingPartiallyBound
    0x23f8d60: cbz w8,  <fail>       ; descriptorBindingSampledImageUpdateAfterBind
    0x23f8d64: cbz w11, <fail>       ; descriptorBindingUpdateUnusedWhilePending

(offsets differ per edition library, the 24 bytes are identical). Each branch is replaced with a
NOP so execution falls through into the real initialization. Everything else keeps reading the
true device capabilities.

Usage:
    python3 tools/patch_mali_native_gate.py <dir-or-lib> [<dir-or-lib> ...] [--require]

Exit status is 0 when the patch was applied wherever the gate exists; with --require it is 1
when no library was patched at all (e.g. the CI downloaded an unexpected core).
"""

import argparse
import glob
import os
import struct
import sys

NOP = struct.pack("<I", 0xD503201F)
# tbz w13,#0 ; cbz w12 ; cbz w10 ; cbz w9 ; cbz w8 ; cbz w11
BLOB = bytes.fromhex("8d120036" "cc100034" "2a110034" "89110034" "e8110034" "eb160034")


def patch_one(path):
    """Return the file offset of the patched gate, or None when the gate is not present."""
    with open(path, "r+b") as f:
        data = bytearray(f.read())
        pos = data.find(BLOB)
        if pos < 0:
            return None
        if data.find(BLOB, pos + 1) >= 0:
            print(f"error: {path}: the gate blob appears more than once, refusing to patch",
                  file=sys.stderr)
            sys.exit(2)
        data[pos:pos + len(BLOB)] = NOP * 6
        f.seek(pos)
        f.write(data[pos:pos + len(BLOB)])
        return pos


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("paths", nargs="+", help="directory with libmain*.so files, or individual files")
    ap.add_argument("--require", action="store_true",
                    help="fail when no library was patched")
    args = ap.parse_args()

    files = []
    for p in args.paths:
        if os.path.isdir(p):
            files += sorted(glob.glob(os.path.join(p, "libmain*.so")))
        else:
            files.append(p)

    patched = 0
    for f in files:
        name = os.path.basename(f)
        # libmain_hook.so is a different, small library without the gate.
        if name.startswith("libmain_hook"):
            continue
        pos = patch_one(f)
        if pos is None:
            print(f"{name}: native-renderer gate not found (already patched or different build)")
        else:
            patched += 1
            print(f"{name}: native-renderer gate at 0x{pos:x} -> 6 NOPs")

    print(f"{patched} library(ies) patched")
    if args.require and patched == 0:
        print("error: no library contained the Mali native-renderer gate", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
