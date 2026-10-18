package com.nfsmw.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Which edition of the game default.xex is.
 *
 * The recompiled code inside the APK comes from the PAL Spanish executable. Another edition starts at a
 * different entry point (0x8262E768 or 0x8262E9A8 instead of 0x8262E960) and the game closes at once with
 * "No function registered at ..." (app/overrides.toml). The launcher checks the SHA-256 of default.xex
 * against the installer's manifest (assets/shaders/release/manifest.json) and explains it before starting.
 */
final class GameEdition {
    static final String SUPPORTED_NAME = "Universal";
    private static final String PREFS = "nfsmw_edition";

    final String sha256;
    final String name;

    private GameEdition(String sha256, String name) {
        this.sha256 = sha256;
        this.name = name;
    }

    boolean supported() {
        return true;
    }

    String describe() {
        return name != null ? name : "Universal";
    }

    /** The edition of the game folder's default.xex, or null if it cannot be read. Cached by size and date. */
    static GameEdition of(Context context, File gameRoot) {
        File xex = new File(gameRoot, "default.xex");
        if (!xex.isFile()) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = xex.length() + ":" + xex.lastModified();
        String sha = key.equals(prefs.getString("key", "")) ? prefs.getString("sha256", null) : null;
        if (sha == null) {
            try (InputStream in = new FileInputStream(xex)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format("%02x", b));
                sha = hex.toString();
            } catch (Exception e) {
                return null;
            }
            prefs.edit().putString("key", key).putString("sha256", sha).apply();
        }
        return new GameEdition(sha, editionName(context, sha));
    }

    private static String editionName(Context context, String sha) {
        try (InputStream in = context.getAssets().open("shaders/release/manifest.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            JSONArray builds = new JSONObject(out.toString("UTF-8")).getJSONArray("builds");
            for (int i = 0; i < builds.length(); i++) {
                JSONObject build = builds.getJSONObject(i);
                if (sha.equals(build.optString("xex_sha256"))) return build.optString("edition", null);
            }
        } catch (Exception ignored) {
            // Without the manifest only "supported or not" is known.
        }
        return null;
    }
}
