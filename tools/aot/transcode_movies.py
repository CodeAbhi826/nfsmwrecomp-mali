#!/usr/bin/env python3
"""
tools/aot/transcode_movies.py - Ahead-Of-Time (AOT) Video Transcoder for Malicomp / Android

Converts legacy Xbox 360 WMV3 cutscenes and intro movies into hardware-accelerated H.264/AAC MP4 files.
Android AMediaCodec decodes MP4 directly on the hardware VPU at 60 FPS with 0% CPU overhead,
completely replacing CPU NEON software WMV3 decoding.
"""

import os
import sys
import subprocess
import argparse
from pathlib import Path

SUPPORTED_EXTENSIONS = [".wmv", ".bik"]

def transcode_video(input_path: Path, output_path: Path, crf: int = 20) -> bool:
    print(f"[*] Transcoding: {input_path.name} -> {output_path.name}")
    cmd = [
        "ffmpeg", "-y",
        "-i", str(input_path),
        "-c:v", "libx264",
        "-profile:v", "high",
        "-level", "4.1",
        "-preset", "medium",
        "-crf", str(crf),
        "-pix_fmt", "yuv420p",
        "-c:a", "aac",
        "-b:a", "192k",
        "-movflags", "+faststart",
        str(output_path)
    ]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        print(f"[+] Successfully transcoded: {output_path.name} ({output_path.stat().st_size // 1024} KB)")
        return True
    except subprocess.CalledProcessError as e:
        print(f"[-] Error transcoding {input_path.name}: {e.stderr.decode('utf-8', errors='ignore')}")
        return False
    except FileNotFoundError:
        print("[-] Error: 'ffmpeg' not found in PATH. Please install ffmpeg to use this tool.")
        sys.exit(1)

def main():
    parser = argparse.ArgumentParser(description="AOT Video Transcoder for NFSMW Android (Malicomp)")
    parser.add_argument("movies_dir", help="Path to the 'Movies' directory containing .wmv files")
    parser.add_argument("--output_dir", "-o", help="Optional output directory (defaults to replacing or alongside)")
    parser.add_argument("--crf", type=int, default=20, help="H.264 CRF quality level (default: 20)")
    args = parser.parse_args()

    movies_path = Path(args.movies_dir).resolve()
    if not movies_path.is_dir():
        print(f"[-] Error: '{movies_path}' is not a directory.")
        sys.exit(1)

    output_dir = Path(args.output_dir).resolve() if args.output_dir else movies_path
    output_dir.mkdir(parents=True, exist_ok=True)

    videos = [p for p in movies_path.glob("*") if p.suffix.lower() in SUPPORTED_EXTENSIONS]
    if not videos:
        print(f"[*] No video files found in {movies_path}")
        return

    print(f"[*] Found {len(videos)} video file(s) to transcode in {movies_path}")
    success_count = 0
    for video in videos:
        out_file = output_dir / (video.stem + ".mp4")
        if transcode_video(video, out_file, crf=args.crf):
            success_count += 1

    print(f"\n[*] Transcoding complete: {success_count}/{len(videos)} videos converted to native H.264 MP4.")

if __name__ == "__main__":
    main()
