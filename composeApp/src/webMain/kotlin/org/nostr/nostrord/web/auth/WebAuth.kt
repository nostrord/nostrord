package org.nostr.nostrord.web.auth

import org.nostr.nostrord.di.AppModule

/**
 * Web auth helper. Login and add-account now go through the shared LoginViewModel
 * (commonMain), consumed by LoginScreen / BunkerQr / AddAccountSheet via useViewModel —
 * the nsec/hex parsing and the repository calls live there, validated identically on
 * every platform. Only logout remains here, used by the account menu in AppShell.
 */
object WebAuth {
    suspend fun logout() {
        AppModule.nostrRepository.logout()
    }
}
