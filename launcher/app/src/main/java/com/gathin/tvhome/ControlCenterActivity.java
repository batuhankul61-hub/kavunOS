package com.gathin.tvhome;

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
import android.content.pm.PackageManager;
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
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/**
 * Control Center, tvOS style: a glass panel that slides in from the right over whatever is on screen, opened
 * by holding Home (HomeKeyService, control_center_long_home); Home held again, or Back, closes it. Tiles: Wi-Fi, Bluetooth,
 * close background apps (with the memory it freed), start the screensaver, Wallpapers and Settings; the time
 * and free memory on top.
 */
public final class ControlCenterActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private View scrim;
    private LinearLayout panel;
    private TextView memory;
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0x80000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(32), dp(40), dp(32), dp(32));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xF01C1C1E);
        // A floating card inset from every edge, as on tvOS; it also clears the overscan of older TVs.
        glass.setCornerRadius(dp(30));
        panel.setBackground(glass);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(400),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        panelParams.setMargins(0, dp(36), dp(48), dp(36));
        root.addView(panel, panelParams);

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

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        panel.addView(grid);
        View first = tile(grid, R.drawable.cc_wifi, getString(R.string.cc_wifi), wifiName(),
                () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        tile(grid, R.drawable.cc_bluetooth, getString(R.string.cc_bluetooth), bluetoothName(), this::bluetooth);
        tile(grid, R.drawable.cc_close_apps, getString(R.string.cc_close_apps), null, this::closeApps);
        tile(grid, R.drawable.cc_moon, getString(R.string.cc_screensaver), null, this::screensaver);
        tile(grid, R.drawable.cc_photo, getString(R.string.wallpaper_manage_title), null,
                () -> open(new Intent(this, WallpaperActivity.class)));
        tile(grid, R.drawable.cc_settings, getString(R.string.home_settings_tile), null,
                () -> open(new Intent(this, LauncherSettingsActivity.class)));

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        memory = new TextView(this);
        style(memory, 14, 0x99FFFFFF, R.font.inter_regular);
        panel.addView(memory);
        showMemory();

        setContentView(root);
        // Slide in from the right on the iOS-style curve, over a fading dim.
        panel.setTranslationX(dp(460));
        panel.animate().translationX(0f).setDuration(420).setInterpolator(TopShelf.EASE).start();
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
        panel.animate().translationX(dp(460)).setDuration(300).setInterpolator(TopShelf.EASE)
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
        }
        tile.setOnClickListener(v -> click.run());
        tile.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                .setDuration(160).setInterpolator(TopShelf.EASE).start());
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(160);
        params.height = dp(112);
        params.setMargins(dp(6), dp(6), dp(6), dp(6));
        grid.addView(tile, params);
        return tile;
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
            open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    /** Ends every app that is only cached in the background, as Android itself would under memory pressure. */
    private void closeApps() {
        ActivityManager am = getSystemService(ActivityManager.class);
        long before = availMemory(am);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            if (app.packageName.equals(getPackageName())) continue;
            am.killBackgroundProcesses(app.packageName);
        }
        handler.postDelayed(() -> {
            long freed = Math.max(0, availMemory(am) - before);
            memory.setText(getString(R.string.cc_freed, Formatter.formatShortFileSize(this, freed)));
            handler.postDelayed(this::showMemory, 2500);
        }, 800);
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
        ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        memory.setText(getString(R.string.cc_memory, Formatter.formatShortFileSize(this, info.availMem),
                Formatter.formatShortFileSize(this, info.totalMem)));
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
