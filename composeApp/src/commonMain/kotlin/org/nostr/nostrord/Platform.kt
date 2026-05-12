package org.nostr.nostrord

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect val isAndroid: Boolean