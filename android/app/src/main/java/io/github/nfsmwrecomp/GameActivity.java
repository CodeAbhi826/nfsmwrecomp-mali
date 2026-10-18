package io.github.nfsmwrecomp;

/**
 * JNI bridge matching Victor's libmain.so exports for native FPS and stretch settings.
 */
public final class GameActivity {
    private GameActivity() {}

    public static native float nativeFps();
    public static native float nativeMsPorFotograma();

    public static float tryGetFps() {
        try {
            return nativeFps();
        } catch (UnsatisfiedLinkError ignored) {
            return 0.0f;
        }
    }

    public static float tryGetMs() {
        try {
            return nativeMsPorFotograma();
        } catch (UnsatisfiedLinkError ignored) {
            return 0.0f;
        }
    }
}
