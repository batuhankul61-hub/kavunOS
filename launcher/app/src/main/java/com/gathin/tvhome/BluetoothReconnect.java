package com.gathin.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Reconnects a paired Bluetooth speaker after boot. Android tries the last A2DP device once, for about 30 s,
 * right after Bluetooth comes on; a speaker switched on later stays disconnected until it is picked again in
 * Settings. While bt_autoconnect is on, this asks A2DP to connect every paired audio device every
 * RETRY_MS for WINDOW_MS after the launcher starts, and stops as soon as one is connected.
 *
 * BluetoothA2dp.connect is hidden but unrestricted (greylist) up to Android 11, so it is called through
 * reflection; on later versions it is a privileged API and the call simply fails and is logged.
 */
final class BluetoothReconnect {

    private static final String TAG = "BluetoothReconnect";
    private static final long RETRY_MS = 15_000;
    private static final long WINDOW_MS = 10 * 60_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static long until;
    private static BluetoothA2dp a2dp;

    private BluetoothReconnect() {
    }

    /** Starts (or extends) the retry window. Safe to call on every Home start. */
    static void start(Context context) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        boolean running = SystemClock.elapsedRealtime() < until;
        until = SystemClock.elapsedRealtime() + WINDOW_MS;
        if (running) return;
        Context app = context.getApplicationContext();
        if (a2dp != null) {
            handler.post(BluetoothReconnect::tick);
            return;
        }
        try {
            adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dp = (BluetoothA2dp) proxy;
                    handler.post(BluetoothReconnect::tick);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                }
            }, BluetoothProfile.A2DP);
        } catch (SecurityException e) {
            Log.w(TAG, "no Bluetooth permission", e);
        }
    }

    // Runs where the legacy BLUETOOTH/BLUETOOTH_ADMIN grants apply (maxSdkVersion 30 in the manifest); on
    // Android 12+ without BLUETOOTH_CONNECT these calls throw SecurityException, caught below, and it gives up.
    @android.annotation.SuppressLint("MissingPermission")
    private static void tick() {
        if (a2dp == null || SystemClock.elapsedRealtime() >= until) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            List<BluetoothDevice> connected = a2dp.getConnectedDevices();
            if (!connected.isEmpty()) {
                until = 0;
                return;
            }
            if (adapter.isEnabled()) {
                Method connect = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    BluetoothClass c = d.getBluetoothClass();
                    if (c == null || c.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                    connect.invoke(a2dp, d);
                }
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            Log.w(TAG, "connect failed, giving up", e);
            until = 0;
            return;
        }
        handler.postDelayed(BluetoothReconnect::tick, RETRY_MS);
    }
}
