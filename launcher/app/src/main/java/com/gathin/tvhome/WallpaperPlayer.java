package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private final Context context;
    private final int fixedDim;
    private final boolean dimAuto;
    private final boolean motion;
    private View shade;
    private ObjectAnimator drift;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.context = context.getApplicationContext();
        fixedDim = config.wallpaperDim;
        dimAuto = config.wallpaperDimAuto;
        motion = config.wallpaperMotion;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0 || dimAuto) {
                shade = new View(context);
                setDim(config.wallpaperDim);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // Auto dim and the daylight order need each picture's brightness; measure what is missing.
            if (dimAuto || WallpaperRotation.ORDER_DAYLIGHT.equals(order)) {
                WallpaperStats.measureMissing(context, files, null);
            }
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null || phaseChanged()) first = choose(previous);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                applyDim(first);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = choose(previous);
        }
    }

    void start() {
        active = true;
        update();
        startDrift();
    }

    void stop() {
        active = false;
        if (drift != null) {
            drift.pause();
            // The drift's full-screen hardware layer (8 MB at 1080p) is not needed while an app is in front.
            image.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    void close() {
        active = false;
        closed = true;
        if (drift != null) drift.cancel();
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval) || phaseChanged()) {
            selected = choose(previous);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                image.setImageBitmap(bitmap);
                applyDim(next);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** The next picture for the order; the daylight order picks among the pictures that suit the hour. */
    private File choose(String previous) {
        if (!WallpaperRotation.ORDER_DAYLIGHT.equals(order)) return WallpaperRotation.pick(files, previous, order, random);
        int phase = WallpaperStats.phaseNow(context);
        preferences.edit().putInt("phase", phase).apply();
        return WallpaperLight.pick(files, WallpaperStats.luma(context, files), phase, previous, random);
    }

    /** True when the daylight order has moved into another part of the day since the last pick. */
    private boolean phaseChanged() {
        return WallpaperRotation.ORDER_DAYLIGHT.equals(order)
                && preferences.getInt("phase", -1) != WallpaperStats.phaseNow(context);
    }

    private void applyDim(File shown) {
        if (shade == null) return;
        int dim = dimAuto ? WallpaperStats.autoDim(context, shown) : fixedDim;
        if (dim < 0) {
            // Not measured yet: the theme's dim for now, the measured one from the next picture on.
            dim = fixedDim;
            WallpaperStats.measureMissing(context, java.util.Collections.singletonList(shown), null);
        }
        setDim(dim);
    }

    private void setDim(int percent) {
        shade.setBackgroundColor((percent * 255 / 100) << 24);
    }

    /**
     * wallpaper_motion: a slow push in and sideways drift, back and forth, about a minute each way. It runs on
     * a hardware layer, so each frame only re-composites the picture instead of redrawing it.
     */
    private void startDrift() {
        if (!motion || closed || files.isEmpty()) return;
        if (drift == null) {
            image.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            // At 1.06 the picture overhangs each edge by 3 %, so a 2.5 % sideways drift never shows a black edge.
            drift = ObjectAnimator.ofPropertyValuesHolder(image,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.06f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.06f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -width * 0.025f, width * 0.025f));
            drift.setDuration(unscaled(context, 60_000));
            drift.setRepeatMode(ValueAnimator.REVERSE);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            drift.start();
        } else if (drift.isPaused()) {
            image.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            drift.resume();
        }
    }

    /**
     * A duration that stays in real time when the TV's animator duration scale is changed (e.g. 0.5x for
     * snappier menus): the slow wallpaper and screensaver motion should not speed up with it.
     */
    static long unscaled(Context context, long ms) {
        // ValueAnimator.getDurationScale() is API 33; the setting it mirrors is readable everywhere.
        float scale = android.provider.Settings.Global.getFloat(context.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale > 0f ? (long) (ms / scale) : ms;
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
