#!/usr/bin/env python3
"""
tools/patch_cave_presentar.py
Applies the aspect-ratio and area-aware filter directly inside Destinos::Presentar:

1. Trampoline at 0x023c1d24:
   b 0x023c1d74
   nop
   nop
   nop
   nop

2. In-function code cave at 0x023c1d74 (replaces the unused guest-address hash lookup):
   - Iterates through std::unordered_map<uint32_t, DestinoResuelto> resueltas_
   - Rejects non-color targets (flags & 1 == 0)
   - Rejects square/portrait targets (width <= height: filters 1024x1024 shadow maps, cubemaps, font atlas)
   - Rejects small buffers (width < 800: filters small UI quads and downsampled bloom passes)
   - Selects the target with maximum area (width * height) -> composited scene target (1280x720 / 1024x576)
   - Safe fallback: if no widescreen target exists yet, falls back to __first_node_
   - Match found: x8 = best_node, b 0x023c1c58 (swapchain blit path)
   - Empty map: b 0x023c1d38 (clean epilogue exit)
"""

import sys
import subprocess
import struct
import capstone

CAVE_ADDR = 0x023c1d74
TRAMPOLINE_ADDR = 0x023c1d24
BLIT_TARGET = 0x023c1c58
CLEAN_EXIT = 0x023c1d38

def assemble_filter():
    asm_src = '''
.text
.globl cave_start
cave_start:
    ldr  x8, [x23, #0x9d0]
    cbz  x8, clean_exit
    mov  x15, x8
    mov  x12, xzr
    mov  w13, wzr

loop_start:
    ldrb w9, [x8, #0x50]
    tbz  w9, #0, next_node
    ldp  w9, w10, [x8, #0x38]
    cmp  w9, w10
    b.ls next_node
    cmp  w9, #0x320
    b.lo next_node
    mul  w14, w9, w10
    cmp  w14, w13
    b.ls next_node
    mov  w13, w14
    mov  x12, x8

next_node:
    ldr  x8, [x8]
    cbnz x8, loop_start

loop_end:
    cmp  x12, #0
    csel x8, x12, x15, ne
    b    blit_dest

clean_exit:
    b    clean_exit_dest

blit_dest:
clean_exit_dest:
'''
    p = subprocess.run(
        ['clang', '-target', 'aarch64-linux-gnu', '-c', '-x', 'assembler', '-', '-o', '/tmp/cave_infunc.o'],
        input=asm_src.encode('utf-8'),
        check=True
    )
    with open('/tmp/cave_infunc.o', 'rb') as f:
        elf = f.read()

    # Extract .text section from ELF
    e_shoff, = struct.unpack_from('<Q', elf, 40)
    e_shentsize, = struct.unpack_from('<H', elf, 58)
    e_shnum, = struct.unpack_from('<H', elf, 60)
    e_shstrndx, = struct.unpack_from('<H', elf, 62)

    shstr_hdr = elf[e_shoff + e_shstrndx * e_shentsize:]
    shstr_offset, = struct.unpack_from('<Q', shstr_hdr, 24)
    shstr_size, = struct.unpack_from('<Q', shstr_hdr, 32)
    shstrtab = elf[shstr_offset:shstr_offset + shstr_size]

    text_data = None
    for i in range(e_shnum):
        sh = elf[e_shoff + i * e_shentsize:]
        sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size = struct.unpack_from('<IIQQQQ', sh, 0)
        name = shstrtab[sh_name:].split(b'\0')[0].decode('ascii')
        if name == '.text':
            text_data = bytearray(elf[sh_offset:sh_offset + sh_size])
            break

    if text_data is None:
        raise RuntimeError("Failed to extract .text from /tmp/cave_infunc.o")

    # Fixup external branches
    addr_blit = CAVE_ADDR + 21 * 4
    diff_blit = BLIT_TARGET - addr_blit
    op_blit = 0x14000000 | ((diff_blit >> 2) & 0x03ffffff)
    text_data[21 * 4 : 21 * 4 + 4] = op_blit.to_bytes(4, 'little')

    addr_clean = CAVE_ADDR + 22 * 4
    diff_clean = CLEAN_EXIT - addr_clean
    op_clean = 0x14000000 | ((diff_clean >> 2) & 0x03ffffff)
    text_data[22 * 4 : 22 * 4 + 4] = op_clean.to_bytes(4, 'little')

    return bytes(text_data)

def patch_binary(input_path, output_path):
    print(f"Reading input binary: {input_path}")
    with open(input_path, 'rb') as f:
        data = bytearray(f.read())

    # 1. Assemble in-function filter
    filter_bytes = assemble_filter()
    print(f"Generated filter cave: {len(filter_bytes)} bytes")

    # 2. Write filter cave to CAVE_ADDR (0x023c1d74)
    data[CAVE_ADDR : CAVE_ADDR + len(filter_bytes)] = filter_bytes

    # 3. Write trampoline at TRAMPOLINE_ADDR (0x023c1d24)
    diff_cave = CAVE_ADDR - TRAMPOLINE_ADDR
    op_cave = 0x14000000 | ((diff_cave >> 2) & 0x03ffffff)
    trampoline_bytes = bytearray()
    trampoline_bytes += op_cave.to_bytes(4, 'little')  # b 0x023c1d74
    trampoline_bytes += (0xd503201f).to_bytes(4, 'little') * 4  # 4x nop
    data[TRAMPOLINE_ADDR : TRAMPOLINE_ADDR + len(trampoline_bytes)] = trampoline_bytes

    # 4. Verify with Capstone
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    print("\n--- Verified Trampoline at 0x023c1d24 ---")
    for i in md.disasm(bytes(data[TRAMPOLINE_ADDR : TRAMPOLINE_ADDR + 20]), TRAMPOLINE_ADDR):
        print(f"  0x{i.address:08x}: {i.mnemonic:8s} {i.op_str}")

    print("\n--- Verified Filter at 0x023c1d74 ---")
    for i in md.disasm(bytes(data[CAVE_ADDR : CAVE_ADDR + len(filter_bytes)]), CAVE_ADDR):
        print(f"  0x{i.address:08x}: {i.mnemonic:8s} {i.op_str}")

    print(f"\nWriting patched binary to: {output_path}")
    with open(output_path, 'wb') as f:
        f.write(data)
    print("Done! Binary patched successfully.")

if __name__ == '__main__':
    in_file = sys.argv[1] if len(sys.argv) > 1 else 'build_work/libmain_test.so'
    out_file = sys.argv[2] if len(sys.argv) > 2 else 'build_work/libmain_custom.so'
    patch_binary(in_file, out_file)
