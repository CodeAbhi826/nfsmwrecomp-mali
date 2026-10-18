package com.nfsmw.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Graphics, display, and runtime options for the launcher.
 * Provides synchronized command-line cvar arguments and standard nfsmw.toml output.
 */
final class GameOptions {
    static final class Option {
        final String key;
        final String title;
        final String cvar;
        final String[] values;
        final String[] labels;
        final String[] shortLabels;
        final String defaultValue;

        Option(String key, String title, String cvar, String defaultValue, String[] values, String[] labels, String[] shortLabels) {
            this.key = key;
            this.title = title;
            this.cvar = cvar;
            this.defaultValue = defaultValue;
            this.values = values;
            this.labels = labels;
            this.shortLabels = shortLabels;
        }

        Option(String key, String title, String cvar, String defaultValue, String[] values, String[] labels) {
            this(key, title, cvar, defaultValue, values, labels, labels);
        }

        String label(String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return labels[i];
                }
            }
            return value;
        }

        String shortLabel(String value) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return shortLabels != null && i < shortLabels.length ? shortLabels[i] : labels[i];
                }
            }
            return value;
        }
    }

    static final String RENDERER = "renderer";
    static final String EDRAM = "edram";
    static final String RESOLUTION = "resolution";
    static final String FPS = "fps";
    static final String AA = "aa";
    static final String ANISOTROPIC = "anisotropic";
    static final String PIPELINE_THREADS = "pipeline_threads";
    static final String TEXTURE_CACHE = "texture_cache";
    static final String STABILITY = "gpu_stability";
    static final String SHADOWS = "shadows";
    static final String CAR_REFLECTIONS = "car_reflections";
    static final String ROAD_REFLECTION = "road_reflection";
    static final String SKY = "sky";
    static final String FILTER = "filter";

    static final Option[] GRAPHICS = {
            new Option(RENDERER, "GPU Plugin / Renderer", "nfsmw_renderizador", "nativo",
                    new String[] {"nativo", "xenos"},
                    new String[] {"Plume (Native Vulkan - Fast)", "Xenos (Emulated Vulkan)"},
                    new String[] {"Plume", "Xenos"}),
            new Option(EDRAM, "EDRAM Render Target", "render_target_path_vulkan", "rtv",
                    new String[] {"rtv"},
                    new String[] {"RTV (FBO Host - Fast / Recommended)"},
                    new String[] {"RTV"}),
            new Option(RESOLUTION, "Output Resolution", "nfsmw_resolucion_interna", "1280x720",
                    new String[] {"1280x720", "960x540", "854x480"},
                    new String[] {"720p (1280x720 - Native Xbox 360)", "540p (960x540 - Performance Mode)", "480p (854x480 - Performance)"},
                    new String[] {"720p", "540p", "480p"}),
            new Option(FPS, "Target Framerate Cap", "nfsmw_limite_fps", "60",
                    new String[] {"60", "30"},
                    new String[] {"60 FPS (Smooth)", "30 FPS (Battery Saver)"},
                    new String[] {"60 FPS", "30 FPS"}),
            new Option(AA, "Anti-Aliasing", "nfsmw_antialiasing", "apagado",
                    new String[] {"apagado", "fxaa"},
                    new String[] {"Disabled (none)", "FXAA"},
                    new String[] {"Off", "FXAA"}),
            new Option(ANISOTROPIC, "Anisotropic Filtering", "anisotropic_override", "0",
                    new String[] {"0", "2", "4", "8", "16"},
                    new String[] {"Disabled (0x)", "2x", "4x", "8x", "16x"},
                    new String[] {"Off", "2x", "4x", "8x", "16x"}),
            new Option(PIPELINE_THREADS, "Pipeline Compilation Threads", "vulkan_pipeline_creation_threads", "2",
                    new String[] {"1", "2", "4"},
                    new String[] {"1 Thread (Battery Saver)", "2 Threads (Recommended - Cool & Stable)", "4 Threads (Fast Compilation)"},
                    new String[] {"1 Thread", "2 Threads", "4 Threads"}),
            new Option(TEXTURE_CACHE, "Texture Cache Memory Limit", "texture_cache_memory_limit_soft", "512",
                    new String[] {"256", "384", "512"},
                    new String[] {"256 MB (Low RAM - Avoids OOM)", "384 MB (Balanced)", "512 MB (Default Xbox 360)"},
                    new String[] {"256 MB", "384 MB", "512 MB"}),
            new Option(STABILITY, "Mali GPU Guard", "nfsmw_consultas_oclusion", "auto",
                    new String[] {"auto", "off", "on"},
                    new String[] {"Automatic (Mali Guard)", "Maximum Compatibility (Disable Flare)", "Full Effects"},
                    new String[] {"Auto", "Compatibility", "Full"}),
            new Option(SHADOWS, "Shadows Frequency", "nfsmw_sombras_cada", "1",
                    new String[] {"1", "2"},
                    new String[] {"Every frame (High)", "Every 2 frames (Performance)"},
                    new String[] {"Every frame", "Every 2 frames"}),
            new Option(CAR_REFLECTIONS, "Car Reflections", "nfsmw_cubemap_caras_max", "6",
                    new String[] {"6", "2", "1"},
                    new String[] {"High (6 faces)", "Medium (2 faces)", "Low (1 face - Fast)"},
                    new String[] {"6 faces", "2 faces", "1 face"}),
            new Option(ROAD_REFLECTION, "Wet Road Reflections", "nfsmw_reflejo_carretera", "true",
                    new String[] {"true", "false"},
                    new String[] {"Enabled", "Disabled"},
                    new String[] {"On", "Off"}),
            new Option(SKY, "Atmospheric Sky Glow", "nfsmw_resplandor_cielo", "natural",
                    new String[] {"natural", "original", "suave"},
                    new String[] {"Natural", "Original (Xbox 360)", "Soft"},
                    new String[] {"Natural", "Original", "Soft"}),
            new Option(FILTER, "Post-Processing Filter", "nfsmw_posproceso", "apagado",
                    new String[] {"apagado", "cine", "vivo", "calido", "frio", "sepia", "noir", "crt"},
                    new String[] {"No filter", "Cinema", "Vivid", "Warm", "Cool", "Sepia", "Noir", "CRT"},
                    new String[] {"None", "Cinema", "Vivid", "Warm", "Cool", "Sepia", "Noir", "CRT"}),
    };

    static final Option[] AUDIO_SYSTEM = {
            new Option("volume", "Game Master Volume", "audio_ganancia_pct", "100",
                    new String[] {"100", "125", "150", "200"},
                    new String[] {"Normal (100%)", "High (125%)", "Very high (150%)", "Maximum (200%)"},
                    new String[] {"100%", "125%", "150%", "200%"}),
            new Option("audio_buffer", "Audio Buffer Latency", "audio_sdl_bomba_cola", "12",
                    new String[] {"12", "20"},
                    new String[] {"Normal (64 ms)", "Large (107 ms, if crackling)"},
                    new String[] {"64 ms", "107 ms"}),
            new Option("language", "In-Game Language", "nfsmw_idioma", "-1",
                    new String[] {"-1", "0", "4", "1", "2", "3", "5", "6", "7", "12", "13"},
                    new String[] {"Game Edition Default", "English", "Español", "Français", "Deutsch", "Italiano",
                            "Nederlands", "Svenska", "Dansk", "Polski", "Suomi"},
                    new String[] {"Default", "English", "Español", "Français", "Deutsch", "Italiano",
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

    private GameOptions() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Option find(String key) {
        for (Option o : ALL) {
            if (o.key.equals(key)) return o;
        }
        throw new IllegalArgumentException(key);
    }

    static String get(Context context, String key) {
        Option o = find(key);
        String value = prefs(context).getString(key, o.defaultValue);
        for (String allowed : o.values) {
            if (allowed.equals(value)) return value;
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
        } else {
            args.add("--nfsmw_render_sin_mosaico=true");
            args.add("--nfsmw_nativo_mosaico_rapido=true");
            args.add("--nfsmw_nativo_resolver_sin_copia=true");
            args.add("--nfsmw_nativo_frontal_perezoso=true");
            args.add("--nfsmw_nativo_saltar_invisibles=true");
            args.add("--nfsmw_nativo_saltar_borrados_repetidos=true");
            args.add("--nfsmw_nativo_z_temprana=true");
            args.add("--nfsmw_nativo_zcull=true");
            args.add("--nfsmw_nativo_simular_vulkan11=true");
            args.add("--nfsmw_nativo_constantes_ubo=true");
            args.add("--nfsmw_nativo_cuatro_conjuntos=true");
            args.add("--nfsmw_nativo_texturas_bc_cpu=true");
        }
        GpuDrivers.arguments(context, args);
        return args;
    }

    static void writeToml(Context context, File targetFolder) {
        if (!targetFolder.exists()) targetFolder.mkdirs();
        File file = new File(targetFolder, "nfsmw.toml");
        StringBuilder sb = new StringBuilder();
        sb.append("# NFSMW Recompiled - Android Configuration\n\n");
        sb.append("[General]\n");
        sb.append("log_level = \"info\"\n");
        sb.append("audio_mute = false\n\n");
        sb.append("[GPU]\n");
        String renderer = get(context, RENDERER);
        sb.append("gpu_plugin = \"").append("xenos".equals(renderer) ? "xenos" : "plume").append("\"\n");
        sb.append("render_target_path_vulkan = \"").append(get(context, EDRAM)).append("\"\n");
        String res = get(context, RESOLUTION);
        sb.append("resolution = \"").append(res).append("\"\n");
        String[] dims = res.split("x");
        if (dims.length == 2) {
            sb.append("video_mode_width = ").append(dims[0]).append("\n");
            sb.append("video_mode_height = ").append(dims[1]).append("\n");
        }
        sb.append("resolution_scale = 1.0\n");
        sb.append("fullscreen = true\n");
        SharedPreferences controls = context.getSharedPreferences("nfsmw_controls", Context.MODE_PRIVATE);
        boolean stretch = controls.getBoolean("stretch", true);
        sb.append("present_letterbox = ").append(!stretch).append("\n");
        sb.append("present_effect = \"bilinear\"\n");
        sb.append("swap_post_effect = \"").append(get(context, AA).equals("apagado") ? "none" : get(context, AA)).append("\"\n");
        sb.append("vsync = ").append(controls.getBoolean("vsync", true)).append("\n");
        sb.append("async_shader_compilation = ").append(controls.getBoolean("async_shaders", true)).append("\n");
        sb.append("vulkan_async_skip_incomplete_frames = ").append(controls.getBoolean("async_skip", true)).append("\n");
        sb.append("vulkan_pipeline_creation_threads = ").append(get(context, PIPELINE_THREADS)).append("\n");
        sb.append("texture_cache_memory_limit_soft = ").append(get(context, TEXTURE_CACHE)).append("\n");
        sb.append("anisotropic_override = ").append(get(context, ANISOTROPIC)).append("\n");
        sb.append("readback_resolve = \"fast\"\n");
        sb.append("gpu_3d_to_2d_texture = true\n\n");
        sb.append("[Game]\n");
        sb.append("black_edition = true\n");
        sb.append("grant_user_privileges = true\n");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("NFSMW", "Failed to write nfsmw.toml", e);
        }
    }
}
