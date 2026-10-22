package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the wallpaper collection, one picture at a time with a slow push in (Ken Burns) and a two-second
 * cross-fade, the thin clock and date in a corner that moves with every picture. Honours the Wallpapers
 * screen: pictures left out of the rotation are skipped, the daylight order keeps to the hour, and the photo
 * time is its "Screensaver photo" setting. With no pictures it is the clock on black.
 */
public class WallpaperDream extends DreamService {

    private static final long FADE_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private FrameLayout root;
    private final ImageView[] layers = new ImageView[2];
    private View shade;
    private LinearLayout clock;
    private int front;
    private List<File> files;
    private String order;
    private String previous = "";
    private long showMs;

    private final Runnable next = this::showNext;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        WallpaperLibrary library = new WallpaperLibrary(this);
        files = library.inRotation();
        Config config = Config.load(this);
        order = config.wallpaperOrder;
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        showMs = choices.getInt(WallpaperActivity.DREAM_SECONDS_KEY, WallpaperActivity.DEFAULT_DREAM_SECONDS) * 1000L;
        if (WallpaperRotation.ORDER_DAYLIGHT.equals(order)) WallpaperStats.measureMissing(this, files, null);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            layers[i] = new ImageView(this);
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            layers[i].setAlpha(0f);
            layers[i].setLayerType(View.LAYER_TYPE_HARDWARE, null);
            root.addView(layers[i], match());
        }
        shade = new View(this);
        root.addView(shade, match());
        clock = buildClock();
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        int margin = dp(64);
        clockParams.setMargins(margin, margin, margin, margin);
        root.addView(clock, clockParams);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        showNext();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(next);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(next);
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void showNext() {
        if (files.isEmpty() || decoder.isShutdown()) return;
        File pick = WallpaperRotation.ORDER_DAYLIGHT.equals(order)
                ? WallpaperLight.pick(files, WallpaperStats.luma(this, files), WallpaperStats.phaseNow(this), previous, random)
                : WallpaperRotation.pick(files, previous, WallpaperRotation.ORDER_SHUFFLE, random);
        if (pick == null) return;
        previous = pick.getName();
        int width = getResources().getDisplayMetrics().widthPixels;
        decoder.execute(() -> {
            Bitmap bitmap = decode(pick, width);
            handler.post(() -> {
                if (bitmap == null) {
                    files.remove(pick);
                    showNext();
                    return;
                }
                show(bitmap, pick);
                handler.postDelayed(next, showMs);
            });
        });
    }

    private void show(Bitmap bitmap, File image) {
        ImageView incoming = layers[1 - front];
        ImageView outgoing = layers[front];
        front = 1 - front;
        incoming.setImageBitmap(bitmap);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setTranslationX(0f);
        float drift = (random.nextBoolean() ? 1 : -1) * getResources().getDisplayMetrics().widthPixels * 0.02f;
        ObjectAnimator push = ObjectAnimator.ofPropertyValuesHolder(incoming,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, drift));
        push.setDuration(WallpaperPlayer.unscaled(this, showMs + FADE_MS * 2));
        push.setInterpolator(new LinearInterpolator());
        push.start();
        long fade = WallpaperPlayer.unscaled(this, FADE_MS);
        incoming.animate().alpha(1f).setDuration(fade).start();
        outgoing.animate().alpha(0f).setDuration(fade).withEndAction(() -> outgoing.setImageDrawable(null)).start();
        // Enough dim for the clock to read, at least a little so the screen stays restful.
        int dim = Math.max(15, WallpaperStats.autoDim(this, image));
        shade.animate().cancel();
        shade.setBackgroundColor((dim * 255 / 100) << 24);
        moveClock();
    }

    /** Swaps the clock between the bottom corners now and then, so it never burns in. */
    private void moveClock() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clock.getLayoutParams();
        params.gravity = Gravity.BOTTOM | (random.nextBoolean() ? Gravity.START : Gravity.END);
        clock.setGravity(params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK);
        clock.setLayoutParams(params);
    }

    private LinearLayout buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
        time.setTextColor(0xF2FFFFFF);
        time.setTypeface(getResources().getFont(R.font.outfit_extralight));
        time.setIncludeFontPadding(false);
        time.setShadowLayer(dp(12), 0, 0, 0x66000000);
        box.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        date.setTextColor(0xCCFFFFFF);
        date.setTypeface(getResources().getFont(R.font.outfit_regular));
        date.setShadowLayer(dp(8), 0, 0, 0x66000000);
        date.setPadding(dp(4), dp(4), dp(4), 0);
        box.addView(date);
        return box;
    }

    private static Bitmap decode(File file, int width) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
