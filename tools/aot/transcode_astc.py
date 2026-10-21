#!/usr/bin/env python3
"""
tools/aot/transcode_astc.py - Ahead-Of-Time (AOT) Texture Compression Pipeline for Mali GPUs

Compresses game textures into native ASTC (Adaptive Scalable Texture Compression) 4x4 or 6x6.
Mali GPUs (Bifrost G71/G72/G76 and Valhall G57/G77/G710) have native on-chip silicon decoders for ASTC.
ASTC 4x4 provides 8.0 bpp with superior visual fidelity compared to BC1-BC5 and drops texture memory
bandwidth footprint by ~75% compared to CPU NEON uncompressed RGBA8 (4.19 MB -> 1.05 MB per 1024x1024).
"""

import os
import sys
import subprocess
import argparse
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

IMAGE_EXTENSIONS = [".png", ".tga", ".dds", ".bmp"]

def compress_astc(input_file: Path, output_file: Path, block_size: str = "4x4", quality: str = "-fast") -> bool:
    cmd = [
        "astcenc",
        quality,
        str(input_file),
        str(output_file),
        block_size,
        "-silent"
    ]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        return True
    except subprocess.CalledProcessError as e:
        print(f"[-] Error compressing {input_file.name}: {e.stderr.decode('utf-8', errors='ignore')}")
        return False
    except FileNotFoundError:
        print("[-] Error: 'astcenc' (ARM ASTC Encoder) not found in PATH.")
        print("    Install via: apt install astcenc OR download from https://github.com/ARM-software/astc-encoder/releases")
        sys.exit(1)

def main():
    parser = argparse.ArgumentParser(description="AOT ASTC Texture Compressor for Mali GPUs (NFSMW Recompiled)")
    parser.add_argument("input_dir", help="Directory containing source textures")
    parser.add_argument("output_dir", help="Directory to save compressed .astc textures")
    parser.add_argument("--block", default="4x4", choices=["4x4", "5x5", "6x6", "8x8"], help="ASTC block footprint (default: 4x4)")
    parser.add_argument("--quality", default="-fast", choices=["-fastest", "-fast", "-medium", "-thorough", "-exhaustive"], help="Compression quality preset")
    parser.add_argument("--jobs", "-j", type=int, default=os.cpu_count() or 4, help="Concurrent encoding worker threads")
    args = parser.parse_args()

    input_path = Path(args.input_dir).resolve()
    output_path = Path(args.output_dir).resolve()
    output_path.mkdir(parents=True, exist_ok=True)

    texture_files = [p for p in input_path.rglob("*") if p.suffix.lower() in IMAGE_EXTENSIONS]
    if not texture_files:
        print(f"[*] No convertible texture files found in {input_path}")
        return

    print(f"[*] Found {len(texture_files)} textures. Compressing to ASTC {args.block} using {args.jobs} threads...")

    def worker(file_path: Path):
        rel_path = file_path.relative_to(input_path)
        dest_file = (output_path / rel_path).with_suffix(".astc")
        dest_file.parent.mkdir(parents=True, exist_ok=True)
        return compress_astc(file_path, dest_file, block_size=args.block, quality=args.quality)

    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        results = list(executor.map(worker, texture_files))

    successes = sum(1 for r in results if r)
    print(f"\n[*] ASTC compression complete: {successes}/{len(texture_files)} textures encoded successfully.")

if __name__ == "__main__":
    main()
