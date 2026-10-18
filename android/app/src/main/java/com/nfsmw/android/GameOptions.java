package com.nfsmw.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The graphics options of the launcher. Each one is a game cvar: GameActivity passes them on the command
 * line, which wins over nfsmw.toml. Values must be ones the cvar allows (nfsmw_ajustes_graficos.cpp and
 * friends), or the game ignores them.
 */
final class GameOptions {
    static final class Option {
        final String key;
        final String title;
        final String cvar;
        final String[] values;
        final String[] labels;
        final String defaultValue;

        Option(String key, String title, String cvar, String defaultValue, String[] values, String[] labels) {
            this.key = key;
            this.title = title;
            this.cvar = cvar;
            this.defaultValue = defaultValue;
            this.values = values;
            this.labels = labels;
        }

        String label(String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return labels[i];
                }
            }
            return value;
        }
    }

    static final String RESOLUTION = "resolution";
    static final String FPS = "fps";
    static final String RENDERER = "renderer";

    static final Option[] GRAPHICS = {
            new Option(RENDERER, "Renderer", "nfsmw_renderizador", "nativo",
                    new String[] {"nativo", "xenos"},
                    new String[] {"Native (Vulkan)", "Compatibility · experimental"}),
            new Option("gpu_stability", "Graphics Stability", "nfsmw_consultas_oclusion", "auto",
                    new String[] {"auto", "off", "on"},
                    new String[] {"Automatic · Mali guard", "Maximum compatibility · disable sun flare",
                            "Full effects · experimental"}),
            new Option(RESOLUTION, "Internal Resolution", "nfsmw_resolucion_interna", "1280x720",
                    new String[] {"1280x720", "1024x576", "640x360", "1920x1080"},
                    new String[] {"1280x720 · balanced", "1024x576 · performance", "640x360 · maximum performance",
                            "1920x1080 · maximum quality"}),
            new Option(FPS, "FPS Limit", "nfsmw_limite_fps", "60",
                    new String[] {"60", "30", "90", "120"},
                    new String[] {"60 FPS", "30 FPS · battery saver", "90 FPS · experimental",
                            "120 FPS · experimental"}),
            new Option("aa", "Antialiasing", "nfsmw_antialiasing", "apagado",
                    new String[] {"apagado", "fxaa"},
                    new String[] {"Disabled", "FXAA"}),
            new Option("shadows", "Shadows", "nfsmw_sombras_cada", "1",
                    new String[] {"1", "2"},
                    new String[] {"Every frame", "Every 2 frames · more performance"}),
            new Option("car_reflections", "Car Reflections", "nfsmw_cubemap_caras_max", "6",
                    new String[] {"6", "2", "1"},
                    new String[] {"High", "Medium", "Low · more performance"}),
            new Option("road_reflection", "Wet Road Reflections", "nfsmw_reflejo_carretera", "true",
                    new String[] {"true", "false"},
                    new String[] {"Enabled", "Disabled · more performance"}),
            new Option("sky", "Sky Glow", "nfsmw_resplandor_cielo", "natural",
                    new String[] {"natural", "original", "suave"},
                    new String[] {"Natural", "Original (Xbox 360)", "Soft"}),
            new Option("filter", "Post-Processing Filter", "nfsmw_posproceso", "apagado",
                    new String[] {"apagado", "cine", "vivo", "calido", "frio", "sepia", "noir", "crt"},
                    new String[] {"No filter", "Cinema", "Vivid", "Warm", "Cool", "Sepia", "Noir",
                            "CRT"}),
    };

    static final Option[] AUDIO_SYSTEM = {
            new Option("volume", "Game Volume", "audio_ganancia_pct", "100",
                    new String[] {"100", "125", "150", "200"},
                    new String[] {"Normal", "High", "Very high", "Maximum · may clip"}),
            new Option("audio_buffer", "Audio Buffer", "audio_sdl_bomba_cola", "12",
                    new String[] {"12", "20"},
                    new String[] {"Normal · 64 ms", "Large · 107 ms, if crackling"}),
            new Option("language", "Text Language", "nfsmw_idioma", "-1",
                    new String[] {"-1", "0", "4", "1", "2", "3", "5", "6", "7", "12", "13"},
                    new String[] {"Game edition default", "English", "Español", "Français", "Deutsch", "Italiano",
                            "Nederlands", "Svenska", "Dansk", "Polski", "Suomi"}),
    };

    static final Option[] ALL = combine(GRAPHICS, AUDIO_SYSTEM);

    private static Option[] combine(Option[] a, Option[] b) {
        Option[] result = new Option[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static final String PREFS = "nfsmw_game";

    private GameOptions() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Option find(String key) {
        for (Option o : ALL) {
            if (o.key.equals(key)) {
                return o;
            }
        }
        throw new IllegalArgumentException(key);
    }

    static String get(Context context, String key) {
        Option o = find(key);
        String value = prefs(context).getString(key, o.defaultValue);
        for (String allowed : o.values) {
            if (allowed.equals(value)) {
                return value;
            }
        }
        return o.defaultValue;
    }

    static void set(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    static List<String> arguments(Context context) {
        List<String> args = new ArrayList<>();
        for (Option o : ALL) {
            args.add("--" + o.cvar + "=" + get(context, o.key));
        }
        if ("xenos".equals(get(context, RENDERER))) {
            // Use ordinary descriptor sets and host framebuffers on older drivers.
            // Xenos also decompresses unsupported BC texture formats on the GPU.
            args.add("--vulkan_native_shader_features=false");
            args.add("--render_target_path_vulkan=fbo");
            args.add("--vulkan_require_geometry_shader=false");
            args.add("--vulkan_require_fill_mode_non_solid=false");
            args.add("--async_shader_compilation=false");
            args.add("--nfsmw_d3d_registros_nativo=false");
            args.add("--nfsmw_d3d_marcador=false");
            args.add("--nfsmw_d3d_marcador_registro=false");
            args.add("--nfsmw_d3d_efectos_nativo=false");
            args.add("--nfsmw_render_sin_mosaico=false");
            args.add("--nfsmw_material_nativo=false");
            args.add("--nfsmw_visible_nativo=false");
            args.add("--nfsmw_matrices_nativo=false");
            args.add("--nfsmw_eview_nativo=false");
            args.add("--nfsmw_escenario_nativo=false");
            args.add("--nfsmw_efecto_pasada_nativo=false");
            args.add("--nfsmw_pegamento_nativo=false");
        }
        GpuDrivers.arguments(context, args);
        return args;
    }
}
