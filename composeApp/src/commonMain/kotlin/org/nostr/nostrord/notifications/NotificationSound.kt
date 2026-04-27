package org.nostr.nostrord.notifications

/**
 * Play the notification chime. Web uses HTML5 Audio, JVM uses JLayer, Android
 * uses MediaPlayer; iOS is a no-op for now. Browsers may swallow the first call
 * until the user has interacted with the page.
 */
expect fun playNotificationSound()
