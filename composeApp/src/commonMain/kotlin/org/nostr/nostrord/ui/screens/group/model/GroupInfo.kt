package org.nostr.nostrord.ui.screens.group.model

data class GroupInfo(
    val id: String,
    val name: String,
    val picture: String?,
    val relay: String
)
