#!/bin/bash
set -e

DEVICE_LIB_DIR="/data/app/~~3sce5frA5G8YvmBw8NDVuQ==/io.github.nfsmwrecomp-XO6J7eSARHLXLxBxpO-tdg==/lib/arm64"

echo "[1] Waiting for device..."
adb wait-for-device

echo "[2] Stopping any existing game process..."
adb shell su -c "am force-stop io.github.nfsmwrecomp" || true

echo "[3] Pushing patched libmain.so to /data/local/tmp..."
adb push build_work/libmain_custom.so /data/local/tmp/libmain.so

echo "[4] Installing patched libmain.so with root..."
adb shell su -c "cp /data/local/tmp/libmain.so ${DEVICE_LIB_DIR}/libmain.so && chmod 755 ${DEVICE_LIB_DIR}/libmain.so && chown system:system ${DEVICE_LIB_DIR}/libmain.so"

echo "[5] Clearing logcat..."
adb logcat -c

echo "[6] Launching GameActivity..."
adb shell su -c "am start -n io.github.nfsmwrecomp/.GameActivity"

echo "[7] Waiting for process..."
sleep 3
PID=$(adb shell su -c "pidof io.github.nfsmwrecomp:juego" || true)
if [ -n "$PID" ]; then
    echo "Game running at PID $PID! Setting OOM score..."
    adb shell su -c "echo -1000 > /proc/$PID/oom_score_adj"
else
    echo "Checking main PID..."
    PID=$(adb shell su -c "pidof io.github.nfsmwrecomp" || true)
    if [ -n "$PID" ]; then
        echo "Process at PID $PID"
        adb shell su -c "echo -1000 > /proc/$PID/oom_score_adj"
    fi
fi

echo "[8] Monitoring..."
