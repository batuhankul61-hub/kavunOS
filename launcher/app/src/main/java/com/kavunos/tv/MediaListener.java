package com.kavunos.tv;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher may read the TV's media sessions (what Spotify, YouTube or Netflix is playing):
 * MediaSessionManager.getActiveSessions accepts an enabled notification listener in place of the system-only
 * MEDIA_CONTENT_CONTROL. It reads no notifications. build-and-install grants it with
 * `cmd notification allow_listener com.kavunos.tv/.MediaListener`.
 */
public class MediaListener extends NotificationListenerService {
}
