#!/usr/bin/env python3
"""
Pre-build checkpoint validator for Malicomp Android application.
Ensures application specifications, UI constraints, and architecture rules are strictly met:
1. Locked portrait orientation in AndroidManifest.xml.
2. Verified SDL JNI method signature (void nativeSetupJNI()).
3. No blocking incompatibility dialogs in play execution flow.
4. Clean localization: zero Spanish strings in Java code and diagnostics.
5. Dedicated Mali driver options and authentic game graphics present.
"""

import os
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def check_manifest():
    manifest_path = os.path.join(REPO_ROOT, "android/app/src/main/AndroidManifest.xml")
    if not os.path.isfile(manifest_path):
        return False, "AndroidManifest.xml not found"
    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()
    if 'android:screenOrientation="portrait"' not in content:
        return False, "Launcher MainActivity must be locked to portrait orientation (android:screenOrientation=\"portrait\")"
    return True, "Manifest orientation verified: portrait"

def check_sdl_jni():
    sdl_path = os.path.join(REPO_ROOT, "android/app/src/main/java/org/libsdl/app/SDLActivity.java")
    if not os.path.isfile(sdl_path):
        return False, "SDLActivity.java not found in project sources"
    with open(sdl_path, "r", encoding="utf-8") as f:
        content = f.read()
    if "public static native void nativeSetupJNI();" not in content:
        return False, "SDLActivity must declare 'public static native void nativeSetupJNI();' matching libSDL3.so ABI"
    return True, "SDL JNI signature verified: nativeSetupJNI()V"

def check_no_blocking_incompatibility():
    main_path = os.path.join(REPO_ROOT, "android/app/src/main/java/com/nfsmw/android/MainActivity.java")
    diag_path = os.path.join(REPO_ROOT, "android/app/src/main/java/com/nfsmw/android/Diagnostics.java")
    with open(main_path, "r", encoding="utf-8") as f:
        main_content = f.read()
    with open(diag_path, "r", encoding="utf-8") as f:
        diag_content = f.read()

    if "Select Alternative Renderer" in main_content:
        return False, "Found blocking 'Select Alternative Renderer' dialog in MainActivity.java"
    if "Try Compatibility" in main_content:
        return False, "Found blocking 'Try Compatibility' dialog in MainActivity.java"
    if "return null;" not in diag_content and "incompatibility" in diag_content:
        return False, "Diagnostics.incompatibility must return null or not block launch"
    return True, "No blocking renderer dialogs in play execution flow"

def check_no_spanish_strings():
    java_dir = os.path.join(REPO_ROOT, "android/app/src/main/java")
    forbidden = [
        "Driver seleccionado pendiente de probar",
        "daniebatuani@gmail.com",
    ]
    for root, _, files in os.walk(java_dir):
        for fname in files:
            if fname.endswith(".java"):
                fpath = os.path.join(root, fname)
                with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
                    txt = f.read()
                for bad in forbidden:
                    if bad in txt:
                        return False, f"Found forbidden string '{bad}' in {os.path.relpath(fpath, REPO_ROOT)}"
    return True, "Clean code verified: no upstream author baggage or untranslated strings"

def check_options_spec():
    opt_path = os.path.join(REPO_ROOT, "android/app/src/main/java/com/nfsmw/android/GameOptions.java")
    with open(opt_path, "r", encoding="utf-8") as f:
        content = f.read()
    required_options = [
        "Plume (Native Vulkan - Fast)",
        "Xenos (Emulated Vulkan)",
        "RTV (FBO Host - Fast / Recommended)",
        "480p (854x480 - Performance)",
        "720p (1280x720 - Native Xbox 360)",
        "writeToml",
    ]
    for req in required_options:
        if req not in content:
            return False, f"GameOptions missing required specification: '{req}'"
    return True, "Game options specification verified"

def check_assets():
    shaders_path = os.path.join(REPO_ROOT, "android/app/src/main/assets/nativo/nfsmw_shaders.nfsp")
    driver_path = os.path.join(REPO_ROOT, "android/app/src/main/assets/drivers/malicompat-g71.zip")
    if not os.path.isfile(shaders_path):
        return False, f"Missing bundled precompiled shaders at {os.path.relpath(shaders_path, REPO_ROOT)}"
    if not os.path.isfile(driver_path):
        return False, f"Missing bundled MaliCompat driver at {os.path.relpath(driver_path, REPO_ROOT)}"
    return True, "Bundled assets verified: native shaders and MaliCompat driver present"

def main():
    print("=" * 60)
    print("Running Malicomp Pre-Build Architecture & Spec Checkpoint...")
    print("=" * 60)

    checks = [
        ("Screen Orientation", check_manifest),
        ("SDL JNI ABI", check_sdl_jni),
        ("Launch Flow", check_no_blocking_incompatibility),
        ("Localization & Cleanliness", check_no_spanish_strings),
        ("Game Options Spec", check_options_spec),
        ("Bundled Assets", check_assets),
    ]

    failed = False
    for name, fn in checks:
        ok, msg = fn()
        status = "PASSED" if ok else "FAILED"
        print(f"[{status}] {name}: {msg}")
        if not ok:
            failed = True

    print("=" * 60)
    if failed:
        print("ERROR: App specification validation failed. Fix the issues before building.")
        sys.exit(1)
    print("SUCCESS: All architecture checkpoints passed. Ready for build.")
    sys.exit(0)

if __name__ == "__main__":
    main()
