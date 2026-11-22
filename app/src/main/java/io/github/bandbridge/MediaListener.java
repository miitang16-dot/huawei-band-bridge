package io.github.bandbridge;

import android.service.notification.NotificationListenerService;

/**
 * Only exists so Android lets us see the active media sessions (title / artist / play state)
 * once the user grants "notification access". We do not read or store any notification.
 */
public class MediaListener extends NotificationListenerService { }
