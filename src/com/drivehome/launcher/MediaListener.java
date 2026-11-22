package com.drivehome.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Intentionally empty. Being an enabled notification listener is what allows
 * MediaSessionManager.getActiveSessions() to return other apps' media sessions.
 */
public class MediaListener extends NotificationListenerService {
}
