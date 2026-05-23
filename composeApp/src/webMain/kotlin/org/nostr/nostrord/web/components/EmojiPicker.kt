package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.useState
import web.cssom.ClassName

private val EMOJI_CATEGORIES: List<Pair<String, List<String>>> =
    listOf(
        "Smileys" to
            ("😀😃😄😁😆😅🤣😂🙂🙃😉😊😇🥰😍🤩😘😗😋😜🤪😝🤗🤭🤔🤐😐😑😶😏😒🙄😬😌😔😪😴😷🤒🤕🤢🤮🥵🥶😵🤯🤠🥳😎🤓🧐😕😟🙁😮😯😲😳🥺😦😧😨😰😢😭😱😖😞😓😩😫🥱")
                .map { it.toString() },
        "Gestures" to
            "👍👎👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋🤝🙏✊👊🤛🤜👏🙌👐🤲💪🫶".map { it.toString() },
        "Hearts" to "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝".map { it.toString() },
        "Animals" to "🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐦🦄🐝🦋🐢🐍🐙🦀🐠🐬🐳🦈🐊".map { it.toString() },
        "Food" to "🍏🍎🍐🍊🍋🍌🍉🍇🍓🫐🍒🍑🥭🍍🥑🍅🌽🥕🍞🧀🍔🍟🍕🌭🌮🍣🍦🍩🍪🎂☕🍺🍷".map { it.toString() },
        "Fun" to "⚽🏀🏈⚾🎾🎱🎮🎲🎯🎸🎹🎤🎧🎉🎊🎁🏆🥇🔥⭐🌟✨⚡💧🌈🚀💻📱💡🔑".map { it.toString() },
        "Symbols" to "✅❌❓❗💯🔴🟠🟡🟢🔵🟣⚫⚪➕➖✔️🆗🆒🚫⚠️".map { it.toString() },
    )

external interface EmojiPickerProps : Props {
    var onPick: (String) -> Unit
    var onClose: () -> Unit
}

/**
 * Reusable categorized emoji picker (overlay popover). Used by the chat composer and
 * for reacting to messages with any emoji.
 */
val EmojiPicker =
    FC<EmojiPickerProps> { props ->
        val (categoryIndex, setCategoryIndex) = useState { 0 }

        div {
            className = ClassName("emoji-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("emoji-popover")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("emoji-tabs")
                    EMOJI_CATEGORIES.forEachIndexed { index, (_, emojis) ->
                        button {
                            key = index.toString()
                            className = ClassName(if (index == categoryIndex) "emoji-tab active" else "emoji-tab")
                            onClick = { setCategoryIndex(index) }
                            +emojis.first()
                        }
                    }
                }

                div {
                    className = ClassName("emoji-grid")
                    EMOJI_CATEGORIES[categoryIndex].second.forEach { emoji ->
                        button {
                            key = emoji
                            className = ClassName("emoji-cell")
                            onClick = {
                                props.onPick(emoji)
                                props.onClose()
                            }
                            +emoji
                        }
                    }
                }
            }
        }
    }
