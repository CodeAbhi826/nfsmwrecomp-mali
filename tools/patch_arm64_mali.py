#!/usr/bin/env python3
import struct, os, glob

print("Running patch_arm64_mali.py to patch runtimeDescriptorArray check on Mali GPUs...")

LIBS_DIR = "android/app/src/main/jniLibs/arm64-v8a"
pattern = struct.pack('<II', 0x7100111f, 0x5400020c) # cmp w8, #4; b.gt
jump_patch = struct.pack('<I', 0x1400001f)          # b +0x7c (bypasses error branch)

for so_file in glob.glob(os.path.join(LIBS_DIR, "libmain*.so")):
    if "hook" in so_file:
        continue
    with open(so_file, "rb") as f:
        data = bytearray(f.read())
    idx = data.find(pattern)
    if idx != -1:
        data[idx+4:idx+8] = jump_patch
        with open(so_file, "wb") as f:
            f.write(data)
        print(f"✔ Successfully patched Mali Vulkan descriptor check in {os.path.basename(so_file)} at {hex(idx)}")
    else:
        print(f"⚠ Pattern not found in {os.path.basename(so_file)}")
