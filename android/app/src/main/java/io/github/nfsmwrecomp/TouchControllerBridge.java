package io.github.nfsmwrecomp;

/**
 * JNI bridge matching Victor's libmain.so exports for virtual gamepad input and menu interaction.
 */
public final class TouchControllerBridge {
    private TouchControllerBridge() {}

    public static native void setState(int buttons, float lx, float ly, float rx, float ry, float lt, float rt);
    public static native int contexto();
    public static native String contextoDiagnostico();
    public static native String menuDiagnostico();
    public static native boolean menuEsLista();
    public static native boolean menuRotar();
    public static native int menuRumbo();
    public static native int menuTocar(float x, float y);
    public static native void pausarJuego(boolean pausa);

    public static void trySetState(int buttons, float lx, float ly, float rx, float ry, float lt, float rt) {
        try {
            setState(buttons, lx, ly, rx, ry, lt, rt);
        } catch (UnsatisfiedLinkError ignored) {
            // Native game library not loaded yet
        }
    }

    public static int tryContexto() {
        try {
            return contexto();
        } catch (UnsatisfiedLinkError ignored) {
            return 0;
        }
    }
}
