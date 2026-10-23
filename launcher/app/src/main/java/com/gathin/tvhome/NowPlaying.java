package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.Collections;
import java.util.List;

/**
 * What the TV's apps are playing, from their media sessions (readable through {@link MediaListener}), and when
 * Home last opened each app. Both feed the preview card on Home and Control Center's Now Playing.
 */
final class NowPlaying {

    static final class Media {
        final String pkg, title, subtitle;
        final Bitmap art;
        final boolean playing;
        final MediaController controller;

        Media(String pkg, String title, String subtitle, Bitmap art, boolean playing, MediaController controller) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.playing = playing;
            this.controller = controller;
        }
    }

    private static final String OPENED = "last_opened";

    private NowPlaying() {
    }

    /** Every media session the system lists, most recent first; empty without the listener grant. */
    static List<MediaController> sessions(Context context) {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return Collections.emptyList();
        try {
            return manager.getActiveSessions(new ComponentName(context, MediaListener.class));
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
    }

    /** The session of one app, or null when it has none (or nothing titled). */
    static Media of(Context context, String pkg) {
        for (MediaController c : sessions(context)) if (pkg.equals(c.getPackageName())) return media(c);
        return null;
    }

    /** The session that is playing, else the most recent one with a title, else null. */
    static Media current(Context context) {
        Media fallback = null;
        for (MediaController c : sessions(context)) {
            Media m = media(c);
            if (m == null) continue;
            if (m.playing) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    private static Media media(MediaController c) {
        MediaMetadata meta = c.getMetadata();
        if (meta == null) return null;
        String title = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) return null;
        String subtitle = text(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        PlaybackState state = c.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        return new Media(c.getPackageName(), title, subtitle, art, playing, c);
    }

    private static String text(MediaMetadata meta, String... keys) {
        for (String key : keys) {
            CharSequence value = meta.getText(key);
            if (value != null && value.toString().trim().length() > 0) return value.toString().trim();
        }
        return null;
    }

    static void opened(Context context, String pkg) {
        prefs(context).edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** When Home last opened the app, or 0. */
    static long lastOpened(Context context, String pkg) {
        return prefs(context).getLong(pkg, 0);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(OPENED, Context.MODE_PRIVATE);
    }
}
