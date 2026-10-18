#!/usr/bin/env python3
import struct, os, glob

print("Patching runtimeDescriptorArray check across all libmain*.so...")

LIBS_DIR = "android/app/src/main/jniLibs/arm64-v8a"
# The exact check in Inicializar():
# 0xb9403808 : ldr w8, [x0, #0x38]
# 0x7100111f : cmp w8, #4
# 0x5400020c : b.gt
pattern = struct.pack('<III', 0xb9403808, 0x7100111f, 0x5400020c)

for so_file in glob.glob(os.path.join(LIBS_DIR, "libmain*.so")):
    if "hook" in so_file:
        continue
    with open(so_file, "rb") as f:
        data = bytearray(f.read())
    
    pos = 0
    count = 0
    while True:
        idx = data.find(pattern, pos)
        if idx == -1:
            break
        # Replace b.gt with b #+0x7c (0x1400001f) which skips the error logging and continues
        data[idx+8:idx+12] = struct.pack('<I', 0x1400001f)
        count += 1
        pos = idx + 12
    
    with open(so_file, "wb") as f:
        f.write(data)
    print(f"✔ Patched {os.path.basename(so_file)} ({count} locations)")
