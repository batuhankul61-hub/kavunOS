package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.text.Collator;

/**
 * Home's tiles: the TV apps (LEANBACK_LAUNCHER), dock apps first in their configured order, the rest by name,
 * then the launcher's own Wallpapers and Settings tiles; once the user has moved tiles on Home, their order. Built off the main thread and kept in memory, so Home
 * coming back (or rebuilding itself) shows its tiles in the first frame instead of querying the package
 * manager and decoding every banner again.
 */
final class HomeApps {

    static final String OWN_WALLPAPERS = "@wallpapers";
    static final String OWN_SETTINGS = "@settings";
    private static final String ORDER_KEY = "home_order";

    static final class Tile {
        final String key;
        final String label;
        final ComponentName component;
        /** 16:9 artwork, or null when the app has none and the tile shows its icon. */
        final Drawable banner;
        final Drawable icon;
        final boolean leanback;

        Tile(String key, String label, ComponentName component, Drawable banner, Drawable icon, boolean leanback) {
            this.key = key;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
        }
    }

    private static List<Tile> cache;
    private static String cacheKey;

    private HomeApps() {
    }

    /** The tiles from the last load, or null when there is none for this configuration. */
    static synchronized List<Tile> cached(Config config) {
        return key(config).equals(cacheKey) ? cache : null;
    }

    /** Drops the cache: an app was installed, updated or removed. */
    static synchronized void invalidate() {
        cache = null;
        cacheKey = null;
    }

    /** Loads every tile; slow (package manager and banner decoding), call it off the main thread. */
    static List<Tile> load(Context context, Config config) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> hidden = new HashSet<>(config.hiddenApps);
        Set<String> seen = new HashSet<>();
        List<Tile> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            String label = config.appLabels.containsKey(pkg) ? config.appLabels.get(pkg)
                    : cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            Drawable banner = banner(context, pm, ri);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            apps.add(new Tile(pkg, label, new ComponentName(pkg, ri.activityInfo.name), banner, icon, true));
        }
        // Dock apps first, in the configured order; the rest by name in the TV's language.
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(apps, (a, b) -> {
            int da = config.dockApps.indexOf(a.key), db = config.dockApps.indexOf(b.key);
            if (da >= 0 || db >= 0) return da < 0 ? 1 : db < 0 ? -1 : Integer.compare(da, db);
            return collator.compare(a.label, b.label);
        });
        apps.add(new Tile(OWN_WALLPAPERS, context.getString(R.string.wallpaper_manage_title),
                new ComponentName(context, WallpaperActivity.class),
                context.getDrawable(R.drawable.banner_wallpapers), null, false));
        apps.add(new Tile(OWN_SETTINGS, context.getString(R.string.home_settings_tile),
                new ComponentName(context, LauncherSettingsActivity.class),
                context.getDrawable(R.drawable.banner_settings), null, false));
        // The order the user arranged on Home wins; tiles it does not know yet (new apps) follow, as on iOS.
        List<String> order = savedOrder(context);
        if (!order.isEmpty()) {
            List<Tile> byDefault = new ArrayList<>(apps);
            Collections.sort(apps, (a, b) -> {
                int oa = order.indexOf(a.key), ob = order.indexOf(b.key);
                if (oa < 0) oa = order.size() + byDefault.indexOf(a);
                if (ob < 0) ob = order.size() + byDefault.indexOf(b);
                return Integer.compare(oa, ob);
            });
        }
        synchronized (HomeApps.class) {
            cache = Collections.unmodifiableList(apps);
            cacheKey = key(config);
        }
        return cache;
    }

    /** Saves the order the tiles were arranged in on Home, and keeps the cache in step with it. */
    static void saveOrder(Context context, List<Tile> tiles) {
        StringBuilder keys = new StringBuilder();
        for (Tile t : tiles) keys.append(t.key).append('\n');
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString(ORDER_KEY, keys.toString()).apply();
        synchronized (HomeApps.class) {
            if (cache != null) cache = Collections.unmodifiableList(new ArrayList<>(tiles));
        }
    }

    private static List<String> savedOrder(Context context) {
        String saved = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(ORDER_KEY, "");
        List<String> keys = new ArrayList<>();
        for (String key : saved.split("\n")) if (!key.isEmpty()) keys.add(key);
        return keys;
    }

    private static Drawable banner(Context context, PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // xhdpi artwork is sharp at the focus zoom without decoding a 4K banner for a 250 px tile.
                banner = resources.getDrawableForDensity(id, DisplayMetrics.DENSITY_XHIGH, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        if (banner != null && banner.getIntrinsicWidth() > 0 && banner.getIntrinsicHeight() > 0
                && banner.getIntrinsicWidth() < banner.getIntrinsicHeight() * 1.4f) banner = null;
        return banner;
    }

    /**
     * An app's label as a tile shows it: control and format characters (bidi overrides, zero width joiners),
     * line and paragraph separators become spaces, runs of spaces collapse, at most 40 characters (never
     * cutting a surrogate pair), the package name when nothing is left. An app cannot redraw its name as
     * something else.
     */
    static String cleanLabel(String s, String fallback) {
        if (s == null) return fallback;
        StringBuilder b = new StringBuilder(Math.min(s.length(), 48));
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            boolean blank = c == ' ' || Character.isISOControl(c) || Character.isWhitespace(c) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SPACE_SEPARATOR;
            if (blank) {
                if (!space) b.append(' ');
                space = true;
                continue;
            }
            boolean pair = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            if (b.length() + (pair ? 2 : 1) > 40) break;
            b.append(c);
            if (pair) b.append(s.charAt(++i));
            space = false;
        }
        String out = b.toString().trim();
        return out.isEmpty() ? fallback : out;
    }

    private static String key(Config config) {
        return config.dockApps + "|" + config.hiddenApps + "|" + config.appLabels;
    }
}
