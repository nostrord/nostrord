package org.nostr.nostrord.ui.components.media

import javafx.embed.swing.JFXPanel

/**
 * Lazy, one-shot JavaFX toolkit initializer.
 * If JavaFX native libraries (glass GTK/Cocoa/Win32) are missing,
 * [isAvailable] returns false and all media components fall back
 * to thumbnail-preview + external-open.
 */
object JfxHelper {
    val isAvailable: Boolean by lazy {
        try {
            JFXPanel() // initializes the JavaFX toolkit
            true
        } catch (_: Throwable) {
            false
        }
    }
}
