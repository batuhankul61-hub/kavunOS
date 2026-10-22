package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The wallpaper collection as the Wallpapers screen, the phone page and the screensaver see it: the theme's
 * wallpapers/ folder (plus a single wallpaper.jpg), which pictures are left out of the rotation, and deleting.
 * Safe to call from the phone server's thread: preferences are thread-safe and every change ends in
 * {@link Config#wallpaperChanged()}.
 */
final class WallpaperLibrary {

    private final Context context;
    private final SharedPreferences choices;

    WallpaperLibrary(Context context) {
        this.context = context.getApplicationContext();
        this.choices = this.context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    File folder() {
        File dir = Config.dir(context);
        return dir == null ? null : new File(dir, "wallpapers");
    }

    /** Every picture, the theme's single wallpaper.jpg first. */
    List<File> all() {
        List<File> images = new ArrayList<>(WallpaperRotation.images(folder()));
        File dir = Config.dir(context);
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile() && single.length() > 0) images.add(0, single);
        return images;
    }

    /** The pictures that take part in the rotation (all of them when every one is left out). */
    List<File> inRotation() {
        return WallpaperRotation.visible(WallpaperRotation.images(folder()), hidden());
    }

    File find(String name) {
        return WallpaperRotation.find(all(), name);
    }

    Set<String> hidden() {
        return new HashSet<>(choices.getStringSet(Config.WALLPAPER_HIDDEN_KEY, new HashSet<>()));
    }

    boolean isHidden(String name) {
        return hidden().contains(name);
    }

    /** False for a name that is not in the collection, or the single wallpaper.jpg (never rotated). */
    boolean setHidden(String name, boolean hide) {
        File image = WallpaperRotation.find(WallpaperRotation.images(folder()), name);
        if (image == null) return false;
        Set<String> set = hidden();
        if (hide) set.add(name);
        else set.remove(name);
        choices.edit().putStringSet(Config.WALLPAPER_HIDDEN_KEY, set).apply();
        Config.wallpaperChanged();
        return true;
    }

    void showAll() {
        choices.edit().remove(Config.WALLPAPER_HIDDEN_KEY).apply();
        Config.wallpaperChanged();
    }

    boolean delete(String name) {
        File image = find(name);
        if (image == null || !image.delete()) return false;
        SharedPreferences.Editor editor = choices.edit();
        Set<String> set = hidden();
        if (set.remove(name)) editor.putStringSet(Config.WALLPAPER_HIDDEN_KEY, set);
        if (name.equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) editor.remove(Config.WALLPAPER_CHOICE_KEY);
        editor.apply();
        Config.wallpaperChanged();
        return true;
    }

    /** The picture Home shows now (WallpaperPlayer's record), or null. */
    File showing() {
        String pick = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        File picked = pick == null ? null : find(pick);
        if (picked != null) return picked;
        String shown = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
        File current = find(shown);
        if (current != null) return current;
        List<File> rotation = inRotation();
        return rotation.isEmpty() ? null : rotation.get(0);
    }
}
