package com.kavunos.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/**
 * Control Center, tvOS style: a glass panel that slides in from the right over whatever is on screen, opened
 * by holding Home (HomeKeyService, control_center_long_home); Home held again, or Back, closes it. Tiles: Wi-Fi, Bluetooth,
 * close background apps (showing free memory, then what closing freed), start the screensaver, Wallpapers and
 * Settings; the time on top. The content scrolls, so the focused tile stays on screen under Now Playing.
 */
public final class ControlCenterActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private View scrim;
    private LinearLayout panel;
    private View slider;
    private TextView memory;
    private boolean closingApps;
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0x4D000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout glassPanel = new FrameLayout(this);
        GradientDrawable glass = new GradientDrawable();
        // All but solid (99 %): the app behind no longer shows through the tiles and text.
        glass.setColor(0xFC1C1C1E);
        // Flush with the right edge, full height; only the left corners are rounded.
        float r = dp(32);
        glass.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        glassPanel.setBackground(glass);
        root.addView(glassPanel, new FrameLayout.LayoutParams(dp(424), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        slider = glassPanel;
        // The content scrolls under the glass: with Now Playing the last tiles sit below the screen, and the
        // ScrollView brings each focused tile into view.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(40));
        glassPanel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // The panel reaches the screen edge, its content keeps clear of it: older TVs crop (overscan) a few
        // percent of every edge, so the right and bottom padding are larger.
        panel.setPadding(dp(32), dp(40), dp(56), dp(44));
        scroll.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        style(time, 44, 0xFFFFFFFF, R.font.inter_light);
        panel.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        style(date, 16, 0xB3FFFFFF, R.font.inter_regular);
        date.setPadding(0, dp(4), 0, dp(24));
        panel.addView(date);

        NowPlaying.Media media = NowPlaying.current(this);
        if (media != null) panel.addView(nowPlaying(media));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        panel.addView(grid);
        View first = tile(grid, R.drawable.cc_wifi, getString(R.string.cc_wifi), wifiName(),
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        tile(grid, R.drawable.cc_bluetooth, getString(R.string.cc_bluetooth), bluetoothName(), this::bluetooth);
        memory = (TextView) tile(grid, R.drawable.cc_close_apps, getString(R.string.cc_close_apps), "", this::closeApps)
                .getTag();
        tile(grid, R.drawable.cc_moon, getString(R.string.cc_screensaver), null, this::screensaver);
        tile(grid, R.drawable.cc_photo, getString(R.string.wallpaper_manage_title), null,
                () -> open(new Intent(this, WallpaperActivity.class)));
        tile(grid, R.drawable.cc_settings, getString(R.string.home_settings_tile), null,
                () -> open(new Intent(this, LauncherSettingsActivity.class)));
        showMemory();

        setContentView(root);
        // Slide in from the right on the iOS-style curve, over a fading dim.
        slider.setTranslationX(dp(424));
        slider.animate().translationX(0f).setDuration(420).setInterpolator(TopShelf.EASE).start();
        scrim.animate().alpha(1f).setDuration(300).start();
        first.post(first::requestFocus);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opened again (Home held again): close.
        close();
    }

    @Override
    public void onBackPressed() {
        close();
    }


    private void close() {
        if (closing) return;
        closing = true;
        slider.animate().translationX(dp(424)).setDuration(300).setInterpolator(TopShelf.EASE)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
        scrim.animate().alpha(0f).setDuration(280).start();
    }

    // ---- tiles ----

    private View tile(GridLayout grid, int icon, String title, String detail, Runnable click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(16), dp(16), dp(16), dp(14));
        tile.setBackgroundResource(R.drawable.cc_tile_bg);
        tile.setFocusable(true);
        tile.setClickable(true);
        ColorStateList ink = getColorStateList(R.color.wall_row_text);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ink);
        image.setDuplicateParentStateEnabled(true);
        tile.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
        View push = new View(this);
        tile.addView(push, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView name = new TextView(this);
        style(name, 15, 0, R.font.inter_semibold);
        name.setTextColor(ink);
        name.setDuplicateParentStateEnabled(true);
        name.setText(title);
        name.setSingleLine(true);
        tile.addView(name);
        if (detail != null) {
            TextView sub = new TextView(this);
            style(sub, 13, 0, R.font.inter_regular);
            sub.setTextColor(getColorStateList(R.color.wall_row_value));
            sub.setDuplicateParentStateEnabled(true);
            sub.setText(detail);
            sub.setSingleLine(true);
            sub.setPadding(0, dp(2), 0, 0);
            tile.addView(sub);
            tile.setTag(sub);
        }
        tile.setOnClickListener(v -> click.run());
        tile.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                .setDuration(160).setInterpolator(TopShelf.EASE).start());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(160);
        params.height = dp(100);
        params.setMargins(dp(6), dp(6), dp(6), dp(6));
        grid.addView(tile, params);
        return tile;
    }

    /** Now Playing, iOS style: artwork, title and artist, and previous / play-pause / next for the session. */
    private View nowPlaying(NowPlaying.Media media) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0x33FFFFFF);
        box.setBackground(bg);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (media.art != null) cover.setImageBitmap(media.art);
        else cover.setImageResource(R.drawable.cc_play);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        top.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(12), 0, 0, 0);
        top.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        style(title, 15, 0xFFFFFFFF, R.font.inter_semibold);
        title.setText(media.title);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(title);
        if (media.subtitle != null) {
            TextView sub = new TextView(this);
            style(sub, 13, 0xB3FFFFFF, R.font.inter_regular);
            sub.setText(media.subtitle);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(3), 0, 0);
            lines.addView(sub);
        }
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, 0);
        box.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.media.session.MediaController.TransportControls t = media.controller.getTransportControls();
        control(controls, R.drawable.cc_prev, t::skipToPrevious);
        ImageView[] play = new ImageView[1];
        boolean[] playing = {media.playing};
        play[0] = control(controls, media.playing ? R.drawable.cc_pause : R.drawable.cc_play, () -> {
            if (playing[0]) t.pause();
            else t.play();
            playing[0] = !playing[0];
            play[0].setImageResource(playing[0] ? R.drawable.cc_pause : R.drawable.cc_play);
        });
        control(controls, R.drawable.cc_next, t::skipToNext);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(332), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(6), 0, dp(6), dp(6));
        box.setLayoutParams(params);
        return box;
    }

    private ImageView control(LinearLayout row, int icon, Runnable click) {
        ImageView button = new ImageView(this);
        button.setImageResource(icon);
        button.setImageTintList(getColorStateList(R.color.wall_row_text));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackgroundResource(R.drawable.cc_tile_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(44));
        params.setMargins(dp(6), 0, dp(6), 0);
        row.addView(button, params);
        return button;
    }

    private String wifiName() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return getString(R.string.cc_not_connected);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        String ssid = info == null ? null : info.getSSID();
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) return getString(R.string.cc_connected);
        return ssid.replace("\"", "");
    }

    @SuppressLint("MissingPermission")   // legacy BLUETOOTH grant up to Android 11; failures fall back below
    private String bluetoothName() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return getString(R.string.off);
        try {
            if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED) {
                // The speaker's name: the bonded audio device that is connected.
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                for (BluetoothDevice d : bonded) {
                    if (d.getBluetoothClass() != null
                            && d.getBluetoothClass().getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) {
                        return d.getName();
                    }
                }
                return getString(R.string.cc_connected);
            }
        } catch (SecurityException ignored) {
        }
        return getString(R.string.cc_not_connected);
    }

    @SuppressLint("MissingPermission")
    private void bluetooth() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean connected = adapter != null && adapter.isEnabled()
                && adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED;
        if (!connected && adapter != null && adapter.isEnabled()) {
            BluetoothReconnect.start(this);
            android.widget.Toast.makeText(this, R.string.cc_connecting, android.widget.Toast.LENGTH_SHORT).show();
        } else {
            bluetoothScreen();
        }
    }

    /**
     * Android TV keeps Bluetooth under Remotes & accessories, a part of its settings that nothing outside can open,
     * and many builds (Xiaomi's among them) have no ACTION_BLUETOOTH_SETTINGS screen. The pairing screen ("Add
     * accessory") is the one that opens, then that intent, then the settings home.
     */
    private void bluetoothScreen() {
        Intent[] tries = {
                new Intent().setComponent(new ComponentName("com.android.tv.settings",
                        "com.android.tv.settings.accessories.AddAccessoryActivity")),
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent intent : tries) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        android.widget.Toast.makeText(this, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Ends every app that is only cached in the background, as Android itself would under memory pressure, and
     * says on the tile what that freed. Apps on screen or playing (a foreground service) keep running: Android
     * does not let a launcher end those.
     */
    private void closeApps() {
        if (closingApps) return;
        closingApps = true;
        memory.setText(R.string.cc_closing);
        ActivityManager am = getSystemService(ActivityManager.class);
        long before = availMemory(am);
        // A couple of hundred binder calls: off the main thread, so the panel never stutters.
        new Thread(() -> {
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo app : apps) {
                if (app.packageName.equals(getPackageName())) continue;
                am.killBackgroundProcesses(app.packageName);
            }
            handler.postDelayed(() -> {
                closingApps = false;
                long freed = Math.max(0, availMemory(am) - before);
                memory.setText(freed < (8L << 20) ? getString(R.string.cc_nothing_to_close)
                        : getString(R.string.cc_freed, Formatter.formatShortFileSize(this, freed)));
                handler.postDelayed(this::showMemory, 3000);
            }, 600);
        }, "close-apps").start();
    }

    private void screensaver() {
        try {
            startActivity(new Intent().setComponent(
                    new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            open(new Intent(Settings.ACTION_DREAM_SETTINGS));
        }
    }

    private void showMemory() {
        if (closingApps) return;
        memory.setText(getString(R.string.cc_free,
                Formatter.formatShortFileSize(this, availMemory(getSystemService(ActivityManager.class)))));
    }

    private static long availMemory(ActivityManager am) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            android.widget.Toast.makeText(this, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void style(TextView view, int sp, int color, int font) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) view.setTextColor(color);
        view.setTypeface(getResources().getFont(font));
        view.setIncludeFontPadding(false);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
