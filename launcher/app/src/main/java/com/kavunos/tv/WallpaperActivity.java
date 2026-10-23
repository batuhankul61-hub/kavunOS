package com.kavunos.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Wallpapers screen, tvOS style: on the left a large preview of the focused picture (dimmed as Home would
 * dim it) over a list of settings; on the right the collection as a grid of thumbnails, without names. OK on a
 * setting opens a short list of its choices; OK on a picture offers a full-screen preview, showing only that
 * one, leaving it out of the rotation and deleting it. Also the app grid's Wallpapers tile.
 */
public final class WallpaperActivity extends TvScreen {

    private static final int COLUMNS = 3;
    private static final int[] MINUTES = {15, 30, 60, 180, 720, 1440};
    private static final int[] DIMS = {0, 10, 20, 35, 50, 70};
    private static final int[] DREAM_SECONDS = {30, 60, 180, 600};
    static final String DREAM_SECONDS_KEY = "dream_seconds";
    static final int DEFAULT_DREAM_SECONDS = 60;
    private static final String FIXED = "fixed";
    private static final String DREAM_SETTING = "screensaver_components";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private SharedPreferences choices;
    private WallpaperLibrary library;
    private Config config;
    private final List<File> images = new ArrayList<>();
    private Set<String> hidden;

    private ImageView backdrop, preview;
    private View previewShade;
    private TextView previewNote, subtitle;
    private RecyclerView grid;
    private Adapter adapter;
    private File previewed;
    private PhoneUpload phone;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        library = new WallpaperLibrary(this);
        config = Config.load(this);
        build();
        reload();
        WallpaperStats.measureMissing(this, images, () -> main.post(() -> {
            if (!isFinishing()) showPreview(previewed);
        }));
    }

    @Override
    protected void onDestroy() {
        if (phone != null) phone.stop();
        decoder.shutdownNow();
        super.onDestroy();
    }

    // ---- layout ----

    private void build() {
        initScreen();
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, match());
        View veil = new View(this);
        GradientDrawable veilPaint = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0050608, 0xD9050608, 0xC0050608});
        veil.setBackground(veilPaint);
        root.addView(veil, match());

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(48), dp(36), dp(32), 0);
        root.addView(columns, match());

        // Left: title, preview, settings.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        columns.addView(left, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = text(getString(R.string.wallpaper_manage_title), 30, Color.WHITE, R.font.inter_semibold);
        left.addView(title);
        subtitle = text("", 14, 0x99FFFFFF, R.font.inter_regular);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        left.addView(subtitle);

        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(dp(16));
        cardShape.setColor(0xFF15171A);
        card.setBackground(cardShape);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(preview, match());
        previewShade = new View(this);
        card.addView(previewShade, match());
        previewNote = text("", 12, Color.WHITE, R.font.inter_regular);
        previewNote.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(10));
        pill.setColor(0xB3000000);
        previewNote.setBackground(pill);
        FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        noteParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        card.addView(previewNote, noteParams);
        left.addView(card, new LinearLayout.LayoutParams(dp(330), dp(186)));

        // The list scrolls inside its own box under the preview, never over it; the fading edges show there
        // is more above or below.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(28));
        scroll.setPadding(0, 0, 0, dp(24));
        scroll.setClipToPadding(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(14);
        left.addView(scroll, scrollParams);
        addRows(list);
        // Down on the last setting stays there; only Right goes over to the pictures.
        View last = rows.get(rows.size() - 1).view;
        last.setId(View.generateViewId());
        last.setNextFocusDownId(last.getId());

        // Right: the grid.
        grid = new RecyclerView(this);
        grid.setLayoutManager(new CenteringGrid(this));
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(dp(28), dp(56), dp(16), dp(48));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        columns.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        main.post(() -> rows.get(0).view.requestFocus());
    }

    private void addRows(LinearLayout list) {
        row(list, R.string.wallpaper_row_mode, this::modeLabel, () -> sheet(getString(R.string.wallpaper_row_mode),
                labels(R.string.wallpaper_mode_shuffle, R.string.wallpaper_mode_sequential,
                        R.string.wallpaper_mode_daylight, R.string.wallpaper_mode_fixed),
                modeIndex(), i -> setMode(new String[]{WallpaperRotation.ORDER_SHUFFLE,
                        WallpaperRotation.ORDER_SEQUENTIAL, WallpaperRotation.ORDER_DAYLIGHT, FIXED}[i])));
        row(list, R.string.wallpaper_row_interval, () -> every(currentMinutes()), () -> {
            List<String> labels = new ArrayList<>();
            for (int m : MINUTES) labels.add(every(m));
            sheet(getString(R.string.wallpaper_row_interval), labels, indexOf(MINUTES, currentMinutes()), i -> {
                choices.edit().putInt(Config.WALLPAPER_MINUTES_KEY, MINUTES[i]).apply();
                changed();
            });
        });
        row(list, R.string.wallpaper_row_dim, this::dimLabel, () -> {
            List<String> labels = new ArrayList<>();
            labels.add(getString(R.string.wallpaper_dim_auto));
            for (int d : DIMS) labels.add(getString(R.string.wallpaper_dim_percent, d));
            int at = dimAuto() ? 0 : indexOf(DIMS, currentDim()) + 1;
            sheet(getString(R.string.wallpaper_row_dim), labels, at, i -> {
                SharedPreferences.Editor e = choices.edit().putBoolean(Config.WALLPAPER_DIM_AUTO_KEY, i == 0);
                if (i > 0) e.putInt(Config.WALLPAPER_DIM_KEY, DIMS[i - 1]);
                e.apply();
                changed();
            });
        });
        row(list, R.string.wallpaper_row_dream, this::dreamLabel, () -> sheet(getString(R.string.wallpaper_row_dream),
                labels(R.string.dream_wallpapers_name, R.string.dream_name), dreamIndex(), this::setDream));
        row(list, R.string.wallpaper_row_dream_interval, () -> seconds(dreamSeconds()), () -> {
            List<String> labels = new ArrayList<>();
            for (int s : DREAM_SECONDS) labels.add(seconds(s));
            sheet(getString(R.string.wallpaper_row_dream_interval), labels, indexOf(DREAM_SECONDS, dreamSeconds()),
                    i -> {
                        choices.edit().putInt(DREAM_SECONDS_KEY, DREAM_SECONDS[i]).apply();
                        refreshRows();
                    });
        });
        if (getResources().getBoolean(R.bool.phone_upload)) {
            row(list, R.string.wallpaper_add_from_phone, null, this::openPhone);
        }
        row(list, R.string.wallpaper_show_all, null, () -> {
            library.showAll();
            reload();
        });
        row(list, R.string.wallpaper_reset, null, () -> {
            choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).remove(Config.WALLPAPER_ORDER_KEY)
                    .remove(Config.WALLPAPER_MINUTES_KEY).remove(Config.WALLPAPER_DIM_KEY)
                    .remove(Config.WALLPAPER_DIM_AUTO_KEY).apply();
            changed();
        });
    }

    // ---- state ----

    private void reload() {
        images.clear();
        images.addAll(library.all());
        hidden = library.hidden();
        adapter.notifyDataSetChanged();
        int inRotation = 0;
        for (File image : images) if (!hidden.contains(image.getName())) inRotation++;
        subtitle.setText(getString(R.string.wallpaper_summary, inRotation, images.size()));
        refreshRows();
        if (previewed == null || !previewed.exists()) previewed = library.showing();
        showPreview(previewed);
        showBackdrop(library.showing());
    }

    private void changed() {
        Config.wallpaperChanged();
        config = Config.load(this);
        refreshRows();
        adapter.notifyDataSetChanged();
        showPreview(previewed);
    }

    @Override
    protected void refreshRows() {
        super.refreshRows();
        boolean rotating = modeIndex() != 3 && !"none".equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null));
        rows.get(1).view.setAlpha(rotating ? 1f : 0.45f);
    }

    private int modeIndex() {
        if (choices.getString(Config.WALLPAPER_CHOICE_KEY, null) != null) return 3;
        String order = choices.getString(Config.WALLPAPER_ORDER_KEY, config.wallpaperOrder);
        if (WallpaperRotation.ORDER_SEQUENTIAL.equals(order)) return 1;
        if (WallpaperRotation.ORDER_DAYLIGHT.equals(order)) return 2;
        return 0;
    }

    private String modeLabel() {
        if ("none".equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) return getString(R.string.wallpaper_none);
        return getString(new int[]{R.string.wallpaper_mode_shuffle, R.string.wallpaper_mode_sequential,
                R.string.wallpaper_mode_daylight, R.string.wallpaper_mode_fixed}[modeIndex()]);
    }

    private void setMode(String mode) {
        SharedPreferences.Editor editor = choices.edit();
        if (FIXED.equals(mode)) {
            File keep = previewed != null ? previewed : library.showing();
            if (keep == null) return;
            editor.putString(Config.WALLPAPER_CHOICE_KEY, keep.getName());
        } else {
            editor.remove(Config.WALLPAPER_CHOICE_KEY).putString(Config.WALLPAPER_ORDER_KEY, mode);
        }
        editor.apply();
        changed();
        if (WallpaperRotation.ORDER_DAYLIGHT.equals(mode)) WallpaperStats.measureMissing(this, images, null);
    }

    private int currentMinutes() {
        int chosen = choices.getInt(Config.WALLPAPER_MINUTES_KEY, -1);
        if (chosen > 0) return chosen;
        return config.wallpaperRotationMinutes > 0 ? config.wallpaperRotationMinutes : Config.DEFAULT_ROTATION_MINUTES;
    }

    private int currentDim() {
        return config.wallpaperDim;
    }

    private boolean dimAuto() {
        return config.wallpaperDimAuto;
    }

    private String dimLabel() {
        return dimAuto() ? getString(R.string.wallpaper_dim_auto) : getString(R.string.wallpaper_dim_percent, currentDim());
    }

    /** Dim for a picture as Home would apply it. */
    private int dimFor(File image) {
        if (!dimAuto() || image == null) return currentDim();
        int auto = WallpaperStats.autoDim(this, image);
        return auto >= 0 ? auto : currentDim();
    }

    private String every(int minutes) {
        if (minutes >= 1440 && minutes % 1440 == 0) return getString(R.string.wallpaper_every_days, minutes / 1440);
        if (minutes >= 60 && minutes % 60 == 0) return getString(R.string.wallpaper_every_hours, minutes / 60);
        return getString(R.string.wallpaper_every_minutes, minutes);
    }

    private String seconds(int s) {
        return s >= 60 ? getString(R.string.wallpaper_every_minutes, s / 60) : getString(R.string.wallpaper_every_seconds, s);
    }

    private int dreamSeconds() {
        return choices.getInt(DREAM_SECONDS_KEY, DEFAULT_DREAM_SECONDS);
    }

    private int dreamIndex() {
        String current = Settings.Secure.getString(getContentResolver(), DREAM_SETTING);
        if (current == null) return -1;
        if (current.contains(WallpaperDream.class.getName())) return 0;
        if (current.contains(ClockDream.class.getName())) return 1;
        return -1;
    }

    private String dreamLabel() {
        int index = dreamIndex();
        return getString(index == 0 ? R.string.dream_wallpapers_short : index == 1 ? R.string.dream_name
                : R.string.dream_other);
    }

    /** Sets the TV's screensaver when the launcher may (granted WRITE_SECURE_SETTINGS); else opens the picker. */
    private void setDream(int index) {
        String component = getPackageName() + "/" + (index == 0 ? WallpaperDream.class : ClockDream.class).getName();
        try {
            Settings.Secure.putString(getContentResolver(), DREAM_SETTING, component);
            Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
            refreshRows();
        } catch (SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            } catch (ActivityNotFoundException missing) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRows();
    }

    // ---- preview and backdrop ----

    private void showPreview(File image) {
        previewed = image;
        previewShade.setBackgroundColor((dimFor(image) * 255 / 100) << 24);
        boolean showing = image != null && image.getName().equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null));
        boolean isHidden = image != null && hidden != null && hidden.contains(image.getName());
        previewNote.setText(showing ? getString(R.string.wallpaper_showing_badge)
                : isHidden ? getString(R.string.wallpaper_hidden_badge) : "");
        previewNote.setVisibility(showing || isHidden ? View.VISIBLE : View.GONE);
        if (image == null) {
            preview.setImageDrawable(null);
            return;
        }
        String key = image.getPath() + "@640";
        Bitmap cached = thumbs.get(key);
        if (cached != null) {
            preview.setImageBitmap(cached);
            return;
        }
        decode(image, 640, key, bitmap -> {
            if (image.equals(previewed)) preview.setImageBitmap(bitmap);
        });
    }

    /** The picture Home shows, decoded tiny and scaled up: a soft blur without a blur API. */
    private void showBackdrop(File image) {
        if (image == null) return;
        decode(image, 48, image.getPath() + "@48", backdrop::setImageBitmap);
    }

    private interface Decoded {
        void accept(Bitmap bitmap);
    }

    private void decode(File image, int width, String key, Decoded done) {
        if (decoder.isShutdown()) return;
        decoder.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
            Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeFile(image.getPath(), opts);
            } catch (OutOfMemoryError e) {
                return;
            }
            if (bitmap == null) return;
            Bitmap result = bitmap;
            main.post(() -> {
                thumbs.put(key, result);
                if (!isFinishing()) done.accept(result);
            });
        });
    }

    // ---- sheets ----

    private void tileActions(File image) {
        String name = image.getName();
        boolean inCollection = "wallpapers".equals(image.getParentFile().getName());
        boolean isHidden = hidden.contains(name);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.wallpaper_preview));
        actions.add(() -> fullScreen(image));
        labels.add(getString(R.string.wallpaper_show));
        actions.add(() -> {
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name).apply();
            changed();
        });
        if (inCollection) {
            labels.add(getString(isHidden ? R.string.wallpaper_unhide : R.string.wallpaper_hide));
            actions.add(() -> {
                library.setHidden(name, !isHidden);
                reload();
            });
        }
        labels.add(getString(R.string.wallpaper_delete));
        actions.add(() -> confirmDelete(image));
        sheet(null, labels, -1, i -> actions.get(i).run());
    }

    private void confirmDelete(File image) {
        int index = images.indexOf(image);
        sheet(getString(R.string.wallpaper_delete_confirm),
                labels(R.string.wallpaper_delete, android.R.string.cancel), 1, i -> {
                    if (i != 0) return;
                    if (library.delete(image.getName())) {
                        thumbs.remove(image.getPath() + "@320");
                        if (image.equals(previewed)) previewed = null;
                        reload();
                        focusTile(Math.min(index, images.size() - 1));
                    }
                });
    }

    private void fullScreen(File image) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setFocusable(true);
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(view, match());
        View shade = new View(this);
        shade.setBackgroundColor((dimFor(image) * 255 / 100) << 24);
        frame.addView(shade, match());
        openOverlay(frame, frame);
        decode(image, getResources().getDisplayMetrics().widthPixels, image.getPath() + "@full", bitmap -> {
            if (overlayOpen() && frame.isAttachedToWindow()) view.setImageBitmap(bitmap);
            thumbs.remove(image.getPath() + "@full");
        });
    }

    private void openPhone() {
        if (phone != null) phone.stop();
        phone = new PhoneUpload(this, () -> main.post(this::reload));
        String url = phone.start();
        LinearLayout card = card(dp(460));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView head = text(getString(R.string.wallpaper_add_from_phone), 22, Color.WHITE, R.font.inter_semibold);
        head.setPadding(0, dp(6), 0, dp(14));
        card.addView(head);
        if (url == null) {
            card.addView(text(getString(R.string.phone_upload_no_network), 16, 0xCCFFFFFF, R.font.inter_regular));
        } else {
            ImageView qr = new ImageView(this);
            int size = dp(220);
            qr.setImageBitmap(PhoneUpload.qr(url, size));
            GradientDrawable white = new GradientDrawable();
            white.setColor(Color.WHITE);
            white.setCornerRadius(dp(14));
            qr.setBackground(white);
            qr.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.addView(qr, new LinearLayout.LayoutParams(size + dp(20), size + dp(20)));
            TextView hint = text(getString(R.string.phone_upload_hint, url), 14, 0xB3FFFFFF, R.font.inter_regular);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(8), dp(16), dp(8), dp(8));
            card.addView(hint);
        }
        TextView close = text(getString(R.string.close), 18, 0, R.font.inter_regular);
        close.setTextColor(getColorStateList(R.color.wall_row_text));
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setClickable(true);
        close.setBackgroundResource(R.drawable.wall_row_bg);
        close.setOnClickListener(v -> closeOverlay());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(200), dp(46));
        closeParams.topMargin = dp(8);
        card.addView(close, closeParams);
        openOverlay(card, close);
    }

    @Override
    protected void onOverlayClosed() {
        if (phone != null) {
            phone.stop();
            phone = null;
        }
    }

    // ---- grid ----

    private void focusTile(int position) {
        if (position < 0) return;
        grid.scrollToPosition(position);
        grid.post(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private final class Adapter extends RecyclerView.Adapter<Tile> {
        @NonNull
        @Override
        public Tile onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(WallpaperActivity.this);
            frame.setFocusable(true);
            frame.setClickable(true);
            frame.setForeground(getDrawable(R.drawable.wall_tile_fg));
            frame.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(14));
            shape.setColor(0xFF1B1D21);
            frame.setBackground(shape);
            int width = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / COLUMNS - dp(16);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(dp(80), width * 9 / 16));
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
            frame.setLayoutParams(params);
            ImageView image = new ImageView(WallpaperActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, match());
            TextView badge = text("", 12, Color.WHITE, R.font.inter_regular);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(10));
            pill.setColor(0xB3000000);
            badge.setBackground(pill);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            badgeParams.setMargins(dp(8), dp(8), dp(8), dp(8));
            frame.addView(badge, badgeParams);
            frame.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).translationZ(has ? dp(8) : 0)
                        .setDuration(150).start();
                if (has) {
                    Tile tile = (Tile) grid.getChildViewHolder(v);
                    int at = tile.getBindingAdapterPosition();
                    if (at != RecyclerView.NO_POSITION) showPreview(images.get(at));
                }
            });
            return new Tile(frame, image, badge);
        }

        @Override
        public void onBindViewHolder(@NonNull Tile tile, int position) {
            File image = images.get(position);
            String name = image.getName();
            boolean isHidden = hidden.contains(name);
            boolean showing = name.equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null));
            String key = image.getPath() + "@320";
            tile.image.setTag(key);
            Bitmap cached = thumbs.get(key);
            tile.image.setImageBitmap(cached);
            if (cached == null) decode(image, 320, key, bitmap -> {
                if (key.equals(tile.image.getTag())) tile.image.setImageBitmap(bitmap);
            });
            tile.image.setAlpha(isHidden ? 0.35f : 1f);
            tile.badge.setText(showing ? getString(R.string.wallpaper_showing_badge)
                    : isHidden ? getString(R.string.wallpaper_hidden_badge) : "");
            tile.badge.setVisibility(showing || isHidden ? View.VISIBLE : View.GONE);
            // Spoken by a screen reader only; nothing on screen shows file names.
            tile.itemView.setContentDescription(name);
            tile.itemView.setOnClickListener(v -> {
                int at = tile.getBindingAdapterPosition();
                if (at == RecyclerView.NO_POSITION) return;
                returnFocus = v;
                tileActions(images.get(at));
            });
        }

        @Override
        public int getItemCount() {
            return images.size();
        }
    }

    private static final class Tile extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView badge;

        Tile(FrameLayout root, ImageView image, TextView badge) {
            super(root);
            this.image = image;
            this.badge = badge;
        }
    }

    /** Scrolls the focused row to the middle, so a row half under an edge never keeps the focus. */
    private static final class CenteringGrid extends GridLayoutManager {
        CenteringGrid(Context context) {
            super(context, COLUMNS);
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                     @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            int dy = (child.getTop() + child.getHeight() / 2) - parent.getHeight() / 2;
            if (dy == 0) return false;
            if (immediate) parent.scrollBy(0, dy);
            else parent.smoothScrollBy(0, dy);
            return true;
        }
    }
}
