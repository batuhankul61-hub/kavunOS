package com.kavunos.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * What a plain APK install needs to be ready without a computer: the free wallpapers bundled in assets/wallpapers
 * and the default look are put in place on the first start (only when no theme was pushed), and Home asks for the
 * one setting a launcher cannot take for itself, the Home button (see SetupActivity).
 */
final class FirstRun {

    private static final String TAG = "kavunOS.FirstRun";
    private static final String PREFS = "first_run";
    private static final String DONE = "done";
    static final String SETUP_DISMISSED = "setup_dismissed";

    /** The look kavunOS ships with: the bundled wallpapers by daylight, changing every half hour, dimmed to suit. */
    private static final String DEFAULT_CONFIG = "{\n"
            + "  \"wallpaper\": true,\n"
            + "  \"wallpaper_rotation_minutes\": 30,\n"
            + "  \"wallpaper_order\": \"daylight\",\n"
            + "  \"wallpaper_dim\": \"auto\",\n"
            + "  \"ambient_glow\": false\n"
            + "}\n";

    private FirstRun() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** On the first start only: the default config and wallpapers, unless a theme is already there. */
    static void prepare(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(DONE, false)) return;
        File dir = Config.dir(context);
        if (dir == null) return;   // storage not mounted yet: try again on the next start
        File config = new File(dir, Config.FILE_NAME);
        File wallpapers = new File(dir, ThemeFiles.WALLPAPERS);
        String[] existing = wallpapers.list();
        if (!config.exists() && (existing == null || existing.length == 0)) {
            try {
                if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) throw new IOException("cannot create " + wallpapers);
                String[] names = context.getAssets().list(ThemeFiles.WALLPAPERS);
                if (names != null) {
                    for (String name : names) copy(context, ThemeFiles.WALLPAPERS + "/" + name, new File(wallpapers, name));
                }
                try (OutputStream out = new FileOutputStream(config)) {
                    out.write(DEFAULT_CONFIG.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w(TAG, "could not set up the default wallpapers: " + e.getMessage());
                return;
            }
        }
        prefs.edit().putBoolean(DONE, true).apply();
    }

    private static void copy(Context context, String asset, File target) throws IOException {
        try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
    }

    /** True when the remote's Home button already opens kavunOS: it is the default Home, or HomeKeyService is on. */
    static boolean homeReady(Context context) {
        return isDefaultHome(context) || homeKeyServiceOn(context);
    }

    static boolean isDefaultHome(Context context) {
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return home != null && home.activityInfo != null
                && context.getPackageName().equals(home.activityInfo.packageName);
    }

    static boolean homeKeyServiceOn(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName service = new ComponentName(context, HomeKeyService.class);
        for (String entry : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(entry);
            if (service.equals(c)) return true;
        }
        return false;
    }
}
