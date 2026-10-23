package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home, tvOS style: the wallpaper full screen, a small clock top right, the apps as one grid whose first row
 * sits low on the screen (the dock apps), and the rest of the apps below it. Moving down scrolls the grid up
 * over a darkening wallpaper; Home or Back brings the first row back.
 *
 * Built for slow TVs: one RecyclerView of plain views, no shadows except the focused tile, the app list
 * cached in memory across rebuilds, banners resolved off the main thread, and nothing animating once the
 * focus stops (the wallpaper drifts only after the remote has been idle for a while).
 */
public class MainActivity extends Activity {

    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** The remote untouched this long starts the wallpaper's slow drift (wallpaper_motion). */
    private static final long IDLE_MS = 20_000;
    private static final float FOCUS_SCALE = 1.1f;
    static final String TOP_SHELF_KEY = "top_shelf";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<HomeApps.Tile> tiles = new ArrayList<>();
    private Config config;
    private float density;
    private int columns;
    private int tileW, tileH, gap, firstRowTop;
    private FrameLayout root;
    private View dim;
    private TextClock clock;
    private RecyclerView grid;
    private Adapter adapter;
    private WallpaperPlayer wallpaperPlayer;
    private TopShelf shelf;
    /** An app was opened from Home: the next resume eases Home back in. */
    private boolean returning;
    /** Config.wallpaperGeneration when this Home was built. */
    private int wallpaperGeneration;
    private int scrolled;

    private final Runnable goIdle = () -> wallpaperPlayer.setIdle(true);

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            HomeApps.invalidate();
            loadTiles();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wallpaperGeneration = Config.wallpaperGeneration;
        config = Config.load(this);
        if (getResources().getBoolean(R.bool.bt_autoconnect)) BluetoothReconnect.start(this);
        density = getResources().getDisplayMetrics().density;
        build();
        List<HomeApps.Tile> cached = HomeApps.cached(config);
        if (cached != null) show(cached);
        else loadTiles();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packages, filter);
        if (openLauncherSettingsIfAsked(getIntent())) return;
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(config.backgroundColor);
        if (config.skyIntensity > 0) {
            SkyView sky = new SkyView(this, null);
            sky.setIntensity(config.skyIntensity);
            root.addView(sky, match());
        }
        wallpaperPlayer = new WallpaperPlayer(this, root, config);

        // A full-screen wallpaper already covers every pixel: no window or root fill under it. On a TV GPU each
        // full-screen layer is a real cost per frame.
        if (config.wallpaperEnabled && !config.wallpapers.isEmpty()) {
            getWindow().setBackgroundDrawable(null);
            root.setBackground(null);
        }
        int side0 = dp(56);
        if (getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean(TOP_SHELF_KEY, true)) {
            shelf = new TopShelf(this, getResources().getDisplayMetrics().heightPixels * 62 / 100, side0, density);
            root.addView(shelf.view());
        }
        // Legibility for the tiles and their names: a soft darkening over the lower part only.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x8C000000}));
        int screenH = getResources().getDisplayMetrics().heightPixels;
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenH * 45 / 100,
                Gravity.BOTTOM));
        // Darkens the wallpaper as the grid scrolls up over it; no view at all is drawn while it is clear.
        dim = new View(this);
        dim.setBackgroundColor(0xD9000000);
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        root.addView(dim, match());

        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        columns = Math.max(4, Math.min(7, config.columns));
        int side = dp(56);
        gap = dp(columns >= 6 ? 22 : 28);
        tileW = (width - 2 * side - (columns - 1) * gap) / columns;
        tileH = tileW * 3 / 5;
        int labelSpace = dp(44);
        firstRowTop = height - dp(56) - labelSpace - tileH;

        grid = new RecyclerView(this);
        grid.setHasFixedSize(true);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setItemAnimator(null);
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(side - gap / 2, firstRowTop - dp(10), side - gap / 2, height / 2);
        grid.setLayoutManager(new Layout(this));
        grid.getRecycledViewPool().setMaxRecycledViews(0, columns * 4);
        grid.setItemViewCacheSize(columns * 2);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrolled += dy;
                float a = Math.max(0f, Math.min(1f, scrolled / (float) (tileH + labelSpace)));
                dim.setAlpha(a);
                dim.setVisibility(a > 0f ? View.VISIBLE : View.GONE);
                // The clock steps aside for the grid, as on tvOS.
                clock.setAlpha(1f - a);
                clock.setVisibility(a < 1f ? View.VISIBLE : View.INVISIBLE);
            }
        });
        root.addView(grid, match());

        clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        clock.setTypeface(getResources().getFont(R.font.inter_medium));
        clock.setTextColor(0xF2FFFFFF);
        clock.setShadowLayer(dp(8), 0, 0, 0x55000000);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clockParams.setMargins(0, dp(30), side, 0);
        root.addView(clock, clockParams);

        setContentView(root);
    }

    // ---- tiles ----

    private void loadTiles() {
        Config current = config;
        loader.execute(() -> {
            List<HomeApps.Tile> loaded = HomeApps.load(getApplicationContext(), current);
            handler.post(() -> {
                if (!isDestroyed()) show(loaded);
            });
        });
    }

    private void show(List<HomeApps.Tile> loaded) {
        String focusedKey = focusedKey();
        tiles.clear();
        tiles.addAll(loaded);
        adapter.notifyDataSetChanged();
        int at = 0;
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).key.equals(focusedKey)) at = i;
        focusTile(at);
    }

    private String focusedKey() {
        View focus = grid.getFocusedChild();
        if (focus == null) return "";
        int at = grid.getChildAdapterPosition(focus);
        return at >= 0 && at < tiles.size() ? tiles.get(at).key : "";
    }

    private void focusTile(int position) {
        if (position < 0 || position >= tiles.size()) return;
        grid.post(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
            else {
                grid.scrollToPosition(position);
                grid.post(() -> {
                    RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
                    if (h != null) h.itemView.requestFocus();
                });
            }
        });
    }

    /** Back to the first row, first tile: Home pressed on Home, or Back from the grid. */
    private void toTop() {
        // The first row is laid out even when scrolled away, so scrollToPosition would not move; scroll by
        // exactly what was scrolled.
        if (scrolled != 0) grid.smoothScrollBy(0, -scrolled, new DecelerateInterpolator(2f));
        focusTile(0);
    }

    private void launch(HomeApps.Tile tile, View from) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(tile.component);
        if (tile.leanback) {
            intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String action = config.launchActions.get(tile.key);
            if (action != null) {
                Intent byAction = new Intent(action).setPackage(tile.key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (getPackageManager().resolveActivity(byAction, 0) != null) intent = byAction;
            }
        }
        // The app grows out of its tile, the way iOS and tvOS open apps.
        Bundle zoom = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
        try {
            if (tile.leanback) NowPlaying.opened(this, tile.key);
            if (HomeApps.OWN_SETTINGS.equals(tile.key)) startActivityForResult(intent, LAUNCHER_SETTINGS_REQUEST, zoom);
            else startActivity(intent, zoom);
            returning = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            HomeApps.invalidate();
            loadTiles();
        }
    }

    /** Hold OK on a tile: the app's system page (Settings tile: the TV's own settings). */
    private void details(HomeApps.Tile tile) {
        Intent intent = HomeApps.OWN_SETTINGS.equals(tile.key) ? new Intent(Settings.ACTION_SETTINGS)
                : tile.leanback ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + tile.key))
                : null;
        if (intent == null) return;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---- lifecycle and keys ----

    @Override
    protected void onResume() {
        super.onResume();
        // Wallpapers changed on the Wallpapers screen or from a phone: rebuild.
        if (wallpaperGeneration != Config.wallpaperGeneration) {
            recreate();
            return;
        }
        wallpaperPlayer.start();
        restartIdle();
        if (returning) {
            returning = false;
            // Home settles back in: the tiles come up from a touch larger and fade in, on one hardware layer.
            grid.setAlpha(0f);
            grid.setScaleX(1.04f);
            grid.setScaleY(1.04f);
            grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(TopShelf.EASE)
                    .withLayer().start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(goIdle);
        wallpaperPlayer.setIdle(false);
        wallpaperPlayer.stop();
        if (shelf != null) shelf.show(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packages);
        wallpaperPlayer.close();
        loader.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        // Home pressed while Home is showing: back to the first row, like tvOS.
        if (hasWindowFocus()) toTop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdle();
    }

    private void restartIdle() {
        handler.removeCallbacks(goIdle);
        wallpaperPlayer.setIdle(false);
        handler.postDelayed(goIdle, IDLE_MS);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the first row.
        if (scrolled > 0) toTop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Another app needs the memory while Home is in the background: drop the banners; Home reloads them.
        if (level >= TRIM_MEMORY_BACKGROUND && !hasWindowFocus()) {
            HomeApps.invalidate();
            grid.getRecycledViewPool().clear();
        }
    }

    // ---- grid ----

    /**
     * Keeps the first row where it sits; any lower row is brought to the upper third of the screen, so the
     * row under the focus is always whole and the scroll is one smooth move per row.
     */
    private final class Layout extends GridLayoutManager {
        Layout(Context context) {
            super(context, columns);
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                     @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            int position = parent.getChildAdapterPosition(child);
            int dy = position < columns ? -scrolled
                    : child.getTop() - getResources().getDisplayMetrics().heightPixels / 3;
            if (dy == 0) return false;
            if (immediate) parent.scrollBy(0, dy);
            else parent.smoothScrollBy(0, dy, new DecelerateInterpolator(2f));
            return true;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        };

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(MainActivity.this);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(tileW + gap, tileH + dp(58));
            cell.setLayoutParams(params);

            FrameLayout card = new FrameLayout(MainActivity.this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setOutlineProvider(rounded);
            card.setClipToOutline(true);
            card.setBackgroundColor(0xFF2C2C2E);
            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(tileW, tileH, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cardParams.topMargin = dp(10);
            cell.addView(card, cardParams);

            ImageView art = new ImageView(MainActivity.this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, match());
            ImageView icon = new ImageView(MainActivity.this);
            int iconSize = Math.min(tileH / 2, dp(48));
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
            iconParams.bottomMargin = dp(10);
            card.addView(icon, iconParams);
            TextView inner = new TextView(MainActivity.this);
            inner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            inner.setTypeface(getResources().getFont(R.font.inter_medium));
            inner.setTextColor(0xE6FFFFFF);
            inner.setSingleLine(true);
            inner.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            innerParams.bottomMargin = dp(8);
            inner.setPadding(dp(8), 0, dp(8), 0);
            card.addView(inner, innerParams);

            TextView label = new TextView(MainActivity.this);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTypeface(getResources().getFont(R.font.inter_semibold));
            label.setTextColor(Color.WHITE);
            label.setShadowLayer(dp(6), 0, 0, 0x66000000);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setAlpha(0f);
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(tileW + gap,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            labelParams.topMargin = dp(10) + tileH + dp(12);
            cell.addView(label, labelParams);

            Holder holder = new Holder(cell, card, art, icon, inner, label);
            card.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f)
                        .translationZ(has ? dp(10) : 0f).setDuration(has ? 160 : 120)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(6) : 0f).setDuration(150).start();
                if (has && shelf != null) {
                    int at = holder.getBindingAdapterPosition();
                    shelf.show(at >= 0 && at < columns && at < tiles.size() ? tiles.get(at) : null);
                }
            });
            card.setOnClickListener(v -> {
                int at = holder.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) launch(tiles.get(at), v);
            });
            card.setOnLongClickListener(v -> {
                int at = holder.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) details(tiles.get(at));
                return true;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HomeApps.Tile tile = tiles.get(position);
            holder.art.setImageDrawable(tile.banner);
            holder.art.setVisibility(tile.banner != null ? View.VISIBLE : View.GONE);
            holder.icon.setImageDrawable(tile.icon);
            holder.icon.setVisibility(tile.banner == null ? View.VISIBLE : View.GONE);
            holder.inner.setText(tile.label);
            holder.inner.setVisibility(tile.banner == null ? View.VISIBLE : View.GONE);
            holder.label.setText(tile.label);
            holder.card.setContentDescription(tile.label);
            boolean focused = holder.card.isFocused();
            holder.card.setScaleX(focused ? FOCUS_SCALE : 1f);
            holder.card.setScaleY(focused ? FOCUS_SCALE : 1f);
            holder.label.setAlpha(focused ? 1f : 0f);
        }

        @Override
        public int getItemCount() {
            return tiles.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView art, icon;
        final TextView inner, label;

        Holder(FrameLayout cell, FrameLayout card, ImageView art, ImageView icon, TextView inner, TextView label) {
            super(cell);
            this.card = card;
            this.art = art;
            this.icon = icon;
            this.inner = inner;
            this.label = label;
        }
    }

    // ---- helpers ----

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
