package org.nostr.nostrord.ui.components.emoji

/**
 * The small set of one-tap reactions shown as a horizontal row on top of the
 * message context menu (Telegram-style). Picking one sends that reaction
 * immediately; the trailing "open picker" affordance reaches the full set.
 *
 * Shared by the native (Compose) and web (React) context menus so both
 * platforms offer the same quick reactions.
 */
val QuickReactions: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
