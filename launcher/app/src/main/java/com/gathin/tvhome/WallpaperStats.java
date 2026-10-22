package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Measured brightness of each wallpaper, cached by name and size: the mean luma of the whole picture, of its
 * top band (where the clock is) and of its bottom band (title and dock). Measuring decodes a 64-pixel-wide
 * copy, a few milliseconds each, on one background thread.
 */
final class WallpaperStats {

    private static final String PREFS = "wallpaper_stats";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WallpaperStats() {
    }

    /** {mean, top, bottom} from the cache, or null when the picture has not been measured yet. */
    static float[] cached(Context context, File image) {
        String value = prefs(context).getString(key(image), null);
        if (value == null) return null;
        String[] parts = value.split(",");
        if (parts.length != 3) return null;
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Mean luma per picture name, for the pictures measured so far. */
    static Map<String, Float> luma(Context context, List<File> images) {
        Map<String, Float> result = new HashMap<>();
        for (File image : images) {
            float[] stats = cached(context, image);
            if (stats != null) result.put(image.getName(), stats[0]);
        }
        return result;
    }

    /** Automatic dim for a picture, or -1 when it has not been measured yet. */
    static int autoDim(Context context, File image) {
        float[] stats = cached(context, image);
        return stats == null ? -1 : WallpaperLight.autoDim(stats[1], stats[2]);
    }

    /** Measures the pictures not in the cache yet, in the background; {@code done} runs on that thread. */
    static void measureMissing(Context context, List<File> images, Runnable done) {
        Context app = context.getApplicationContext();
        List<File> copy = new java.util.ArrayList<>(images);
        worker.execute(() -> {
            SharedPreferences.Editor editor = null;
            for (File image : copy) {
                if (cached(app, image) != null) continue;
                float[] stats = measure(image);
                if (stats == null) continue;
                if (editor == null) editor = prefs(app).edit();
                editor.putString(key(image), stats[0] + "," + stats[1] + "," + stats[2]);
            }
            if (editor != null) editor.apply();
            if (done != null) done.run();
        });
    }

    /** The day phase now, from the configured place or a 07:00-19:00 day. */
    static int phaseNow(Context context) {
        int sunrise = WallpaperLight.DEFAULT_SUNRISE, sunset = WallpaperLight.DEFAULT_SUNSET;
        String lat = context.getString(R.string.latitude), lon = context.getString(R.string.longitude);
        if (!lat.isEmpty() && !lon.isEmpty()) {
            try {
                int[] times = Sun.riseAndSet(LocalDate.now(), Double.parseDouble(lat), Double.parseDouble(lon),
                        ZoneId.systemDefault());
                if (times != null) {
                    sunrise = times[0];
                    sunset = times[1];
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Calendar now = Calendar.getInstance();
        return WallpaperLight.phase(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), sunrise, sunset);
    }

    private static float[] measure(File image) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= 64) opts.inSampleSize *= 2;
            Bitmap decoded = BitmapFactory.decodeFile(image.getPath(), opts);
            if (decoded == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(decoded, 64, 36, true);
            if (small != decoded) decoded.recycle();
            int[] px = new int[64 * 36];
            small.getPixels(px, 0, 64, 0, 0, 64, 36);
            small.recycle();
            double all = 0, top = 0, bottom = 0;
            int topRows = 11, bottomFrom = 20;   // top 30 % and bottom 45 % of the screen
            for (int y = 0; y < 36; y++) {
                for (int x = 0; x < 64; x++) {
                    int c = px[y * 64 + x];
                    double l = (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
                    all += l;
                    if (y < topRows) top += l;
                    if (y >= bottomFrom) bottom += l;
                }
            }
            return new float[]{(float) (all / (64 * 36)), (float) (top / (64 * topRows)),
                    (float) (bottom / (64 * (36 - bottomFrom)))};
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static String key(File image) {
        return image.getName() + ":" + image.length();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
