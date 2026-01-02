package org.nostr.nostrord.ui

sealed class Screen {
    data object Home : Screen()
    data object PAGE1 : Screen()
    data object RelaySettings : Screen()
    data object Profile : Screen()
    data object EditProfile : Screen()
    data class Group(val groupId: String, val groupName: String?) : Screen()
    object NostrLogin : Screen()
    object BackupPrivateKey : Screen()
}
