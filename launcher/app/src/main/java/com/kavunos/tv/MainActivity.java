package com.kavunos.tv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
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
 * focus stops: a Home left alone draws nothing.
 */
public class MainActivity extends Activity {

    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.kavunos.tv.action.LAUNCHER_SETTINGS";
    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
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
    /** The tile being moved (held OK), or null. */
    private Holder moving;
    private boolean okReleased;
    private ObjectAnimator wiggle;
    private TextView moveHint;
    /** The open tile menu (held OK), or null; its rows, what each runs, and the chosen one. */
    private LinearLayout menu;
    private final List<LinearLayout> menuRows = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private int menuIndex;

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
                // Exact whenever the first row is laid out: summing dy alone drifted when the grid was laid out
                // again (returning from another app), which left Home dimmed with the clock hidden at the top.
                scrolled = scrollOffset(rv, dy);
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

        moveHint = new TextView(this);
        moveHint.setText(R.string.home_move_hint);
        moveHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        moveHint.setTypeface(getResources().getFont(R.font.inter_medium));
        moveHint.setTextColor(Color.WHITE);
        moveHint.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(0xE61C1C1E);
        moveHint.setBackground(pill);
        moveHint.setAlpha(0f);
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(30);
        root.addView(moveHint, hintParams);

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

    // ---- moving tiles ----

    /**
     * Hold OK on a tile, as on tvOS: it lifts and wiggles, the arrows move it through the grid while the others
     * slide aside, and OK or Back drops it. Only the lifted tile animates; the order is saved for good.
     */
    private void startMove(Holder holder) {
        if (moving != null) return;
        moving = holder;
        okReleased = false;
        if (shelf != null) shelf.show(null);
        DefaultItemAnimator slide = new DefaultItemAnimator();
        slide.setMoveDuration(220);
        grid.setItemAnimator(slide);
        holder.card.animate().scaleX(1.18f).scaleY(1.18f).translationZ(dp(18)).setDuration(200)
                .setInterpolator(TopShelf.EASE).start();
        wiggle = ObjectAnimator.ofFloat(holder.card, View.ROTATION, -1.3f, 1.3f);
        wiggle.setDuration(140);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
        moveHint.setVisibility(View.VISIBLE);
        moveHint.animate().alpha(1f).setDuration(200).start();
    }

    private void stopMove() {
        if (moving == null) return;
        View card = moving.card;
        moving = null;
        wiggle.cancel();
        card.animate().rotation(0f).scaleX(card.isFocused() ? FOCUS_SCALE : 1f).scaleY(card.isFocused() ? FOCUS_SCALE : 1f)
                .translationZ(card.isFocused() ? dp(10) : 0f).setDuration(200).setInterpolator(TopShelf.EASE).start();
        // Back to no item animator (nothing runs per frame) once the last slide has finished.
        handler.postDelayed(() -> {
            if (moving == null) grid.setItemAnimator(null);
        }, 300);
        moveHint.animate().alpha(0f).setDuration(160).withEndAction(() -> moveHint.setVisibility(View.GONE)).start();
        HomeApps.saveOrder(this, tiles);
    }

    /** Moves the lifted tile one step (±1 along the row, ±columns between rows) and keeps it on screen. */
    private void moveBy(int delta) {
        int from = moving.getBindingAdapterPosition();
        if (from == RecyclerView.NO_POSITION) return;
        int to = from + delta;
        // Down onto a last row that ends earlier: the tile goes to its end.
        if (delta > 0 && to >= tiles.size() && from / columns < (tiles.size() - 1) / columns) to = tiles.size() - 1;
        if (to < 0 || to >= tiles.size() || to == from) return;
        tiles.add(to, tiles.remove(from));
        adapter.notifyItemMoved(from, to);
        int row = to / columns;
        int target = row == 0 ? 0 : Math.max(0, grid.getPaddingTop() + row * (tileH + dp(58))
                - getResources().getDisplayMetrics().heightPixels / 3);
        if (target != scrolled) grid.smoothScrollBy(0, target - scrolled, new DecelerateInterpolator(2f));
    }

    // ---- tile menu ----

    /**
     * Hold OK on a tile: a glass menu rises beside it, like an iPhone's context menu: Move, App info, Hide
     * from Home, Uninstall (apps the user installed; Android asks to confirm). Up and Down choose, OK runs,
     * Back closes. It takes no focus, so the grid underneath stays exactly as it was.
     */
    private void openMenu(Holder holder) {
        if (menu != null || moving != null) return;
        int at = holder.getBindingAdapterPosition();
        HomeApps.Tile tile = tiles.get(at);
        if (shelf != null) shelf.show(null);
        menuActions.clear();
        menuRows.clear();
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(22));
        glass.setColor(0xF21C1C1E);
        glass.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        menu.setBackground(glass);
        menu.setElevation(dp(24));

        menuRow(R.drawable.menu_move, R.string.home_menu_move, false, () -> startMove(holder));
        if (tile.leanback) {
            menuRow(R.drawable.menu_info, R.string.home_menu_info, false, () -> details(tile));
            menuRow(R.drawable.menu_hide, R.string.home_menu_hide, false, () -> {
                HomeApps.setHidden(this, tile.key, true);
                loadTiles();
            });
            if (removable(tile.key)) {
                menuRow(R.drawable.menu_uninstall, R.string.home_menu_uninstall, true, () -> uninstall(tile.key));
            }
        } else if (HomeApps.OWN_SETTINGS.equals(tile.key)) {
            menuRow(R.drawable.menu_info, R.string.tv_settings, false, () -> details(tile));
        }
        menuIndex = 0;
        paintMenu();

        // Above the tile when there is room (the first row sits low), else below it; inside the safe edges.
        int width = getResources().getDisplayMetrics().widthPixels;
        int menuW = dp(320);
        menu.measure(View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int menuH = menu.getMeasuredHeight();
        int[] loc = new int[2];
        holder.card.getLocationInWindow(loc);
        int x = Math.max(dp(56), Math.min(loc[0], width - dp(56) - menuW));
        boolean above = loc[1] - menuH - dp(28) >= dp(40);
        int y = above ? loc[1] - menuH - dp(28) : loc[1] + holder.card.getHeight() + dp(28);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = x;
        params.topMargin = y;
        root.addView(menu, params);
        menu.setPivotX(Math.max(0, Math.min(menuW, loc[0] + holder.card.getWidth() / 2 - x)));
        menu.setPivotY(above ? menuH : 0);
        menu.setAlpha(0f);
        menu.setScaleX(0.85f);
        menu.setScaleY(0.85f);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(TopShelf.EASE).start();
        okReleased = false;
    }

    private void menuRow(int icon, int label, boolean destructive, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        row.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTypeface(getResources().getFont(R.font.inter_medium));
        text.setSingleLine(true);
        text.setPadding(dp(14), 0, 0, 0);
        row.addView(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        row.setTag(destructive);
        menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        menuRows.add(row);
        menuActions.add(action);
    }

    /** The chosen row turns white, as the focus does everywhere else; Uninstall stays red. */
    private void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            LinearLayout row = menuRows.get(i);
            boolean on = i == menuIndex;
            int ink = Boolean.TRUE.equals(row.getTag()) ? 0xFFFF453A : on ? 0xFF1C1C1E : Color.WHITE;
            ((GradientDrawable) row.getBackground()).setColor(on ? Color.WHITE : Color.TRANSPARENT);
            ((ImageView) row.getChildAt(0)).setImageTintList(ColorStateList.valueOf(ink));
            ((TextView) row.getChildAt(1)).setTextColor(ink);
        }
    }

    private void closeMenu() {
        if (menu == null) return;
        View closing = menu;
        menu = null;
        closing.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).setInterpolator(TopShelf.EASE)
                .withEndAction(() -> root.removeView(closing)).start();
    }

    /** Apps the user installed; the TV's own apps cannot be removed, only their updates. */
    private boolean removable(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void uninstall(String pkg) {
        try {
            // Android's own dialog confirms; Home reloads from the package broadcast when the app is gone.
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private boolean menuKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = isOk(code);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // The release of the hold that opened the menu does not choose anything.
            if (ok) okReleased = true;
            return ok || code == KeyEvent.KEYCODE_BACK || isArrow(code) || super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = menuIndex + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < menuRows.size()) {
                menuIndex = next;
                paintMenu();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            closeMenu();
            return true;
        }
        if (ok) {
            if (okReleased && event.getRepeatCount() == 0) {
                Runnable action = menuActions.get(menuIndex);
                closeMenu();
                action.run();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (menu != null) return menuKey(event);
        if (moving == null) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        boolean ok = isOk(code);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // The release of the hold that lifted the tile does not drop it.
            if (ok) okReleased = true;
            return ok || code == KeyEvent.KEYCODE_BACK || isArrow(code) || super.dispatchKeyEvent(event);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: moveBy(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: moveBy(-columns); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveBy(columns); return true;
            case KeyEvent.KEYCODE_BACK: stopMove(); return true;
            default:
                if (ok) {
                    if (okReleased && event.getRepeatCount() == 0) stopMove();
                    return true;
                }
                return super.dispatchKeyEvent(event);
        }
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** App info from the tile menu: the app's system page (Settings tile: the TV's own settings). */
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
        stopMove();
        closeMenu();
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
        stopMove();
        closeMenu();
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
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the first row.
        if (scrolled > 0) toTop();
    }

    /**
     * Remote buttons on Home only; inside apps every button still goes to the app. Hiremco's remote sends F-keys
     * for its coloured and SAT buttons, so those are taken alongside the standard codes.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) return super.onKeyDown(keyCode, event);
        Holder focused = focusedHolder();
        int at = focused == null ? RecyclerView.NO_POSITION : focused.getBindingAdapterPosition();
        switch (keyCode) {
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_F10:
                startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (focused != null && at != RecyclerView.NO_POSITION) openMenu(focused);
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (at != RecyclerView.NO_POSITION) details(tiles.get(at));
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_F9:
                startActivity(new Intent(this, ControlCenterActivity.class));
                return true;
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_F12:
                startActivity(new Intent(this, WallpaperActivity.class));
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_F11:
                startScreensaver();
                return true;
            case KeyEvent.KEYCODE_F1:      // SAT
            case KeyEvent.KEYCODE_GUIDE:   // EPG
                return openPackage(LIVE_TV_PACKAGE);
            case KeyEvent.KEYCODE_DEL:     // LAST
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                return openLast();
            case KeyEvent.KEYCODE_ZENKAKU_HANKAKU:   // Favorite
            case KeyEvent.KEYCODE_BOOKMARK:
                if (at > 0) {
                    tiles.add(0, tiles.remove(at));
                    adapter.notifyItemMoved(at, 0);
                    HomeApps.saveOrder(this, tiles);
                    toTop();
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (at != RecyclerView.NO_POSITION) focusTile(Math.min(tiles.size() - 1, at + 2 * columns));
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (at != RecyclerView.NO_POSITION) {
                    if (at - 2 * columns < columns) toTop();
                    else focusTile(at - 2 * columns);
                }
                return true;
            default:
                // 1–9 and 0 open the first ten apps, the way a channel number picks a channel.
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    int n = keyCode == KeyEvent.KEYCODE_0 ? 9 : keyCode - KeyEvent.KEYCODE_1;
                    if (n < tiles.size()) {
                        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(n);
                        launch(tiles.get(n), h != null ? ((Holder) h).card : grid);
                    }
                    return true;
                }
                return super.onKeyDown(keyCode, event);
        }
    }

    /** The satellite / live TV app on boxes that have one (Hiremco: android.dtv). */
    private static final String LIVE_TV_PACKAGE = "android.dtv";

    private Holder focusedHolder() {
        View focus = grid.getFocusedChild();
        return focus == null ? null : (Holder) grid.findContainingViewHolder(focus);
    }

    private boolean openPackage(String pkg) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            returning = true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        return true;
    }

    /** LAST: back to the app opened most recently from Home. */
    private boolean openLast() {
        HomeApps.Tile last = null;
        long when = 0;
        for (HomeApps.Tile t : tiles) {
            long opened = t.leanback ? NowPlaying.lastOpened(this, t.key) : 0;
            if (opened > when) {
                when = opened;
                last = t;
            }
        }
        if (last == null) return false;
        int at = tiles.indexOf(last);
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(at);
        launch(last, h != null ? ((Holder) h).card : grid);
        return true;
    }

    private void startScreensaver() {
        try {
            startActivity(new Intent().setComponent(new android.content.ComponentName("com.android.systemui",
                    "com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
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
            int dy = position < columns ? child.getTop() - restTop(child)
                    : child.getTop() - getResources().getDisplayMetrics().heightPixels / 3;
            if (dy == 0) return false;
            if (immediate) parent.scrollBy(0, dy);
            else parent.smoothScrollBy(0, dy, new DecelerateInterpolator(2f));
            return true;
        }

        /** Where a first-row tile sits when the grid is at the top. */
        int restTop(View child) {
            return getPaddingTop() + getTopDecorationHeight(child)
                    + ((ViewGroup.MarginLayoutParams) child.getLayoutParams()).topMargin;
        }
    }

    /**
     * How far the grid is scrolled: exact from the first row while it is laid out (RecyclerView's own offset is an
     * estimate that falls short in a grid), else summed from the scroll steps.
     */
    private int scrollOffset(RecyclerView rv, int dy) {
        Layout layout = (Layout) rv.getLayoutManager();
        View first = layout == null ? null : layout.findViewByPosition(0);
        return first != null ? layout.restTop(first) - first.getTop() : scrolled + dy;
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
                if (holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION) openMenu(holder);
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
