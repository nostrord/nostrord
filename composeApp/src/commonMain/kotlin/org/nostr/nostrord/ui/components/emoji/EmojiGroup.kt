package org.nostr.nostrord.ui.components.emoji

/**
 * Emoji categories, shared by the native Compose picker and the web React picker. Pure data —
 * the per-platform category icon is mapped separately (e.g. native `EmojiGroup.toIcon()`).
 * [groupKey] matches the Unicode "Group" name used to bucket [EmojiData].
 */
enum class EmojiGroup(val label: String, val groupKey: String) {
    RECENT("Recent", ""),
    SMILEYS("Smileys", "Smileys & Emotion"),
    PEOPLE("People", "People & Body"),
    NATURE("Nature", "Animals & Nature"),
    FOOD("Food", "Food & Drink"),
    TRAVEL("Travel", "Travel & Places"),
    ACTIVITIES("Activities", "Activities"),
    OBJECTS("Objects", "Objects"),
    SYMBOLS("Symbols", "Symbols"),
    FLAGS("Flags", "Flags"),
}
