package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.AppStartState
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.components.layout.AppFrame
import org.nostr.nostrord.ui.components.navigation.MinimalTitleBar
import org.nostr.nostrord.ui.navigation.clearBrowserUrlQuery
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.login.components.UnlockAccountDialog
import org.nostr.nostrord.ui.screens.onboarding.OnboardingFlowScreen
import org.nostr.nostrord.ui.theme.AppFonts
import org.nostr.nostrord.ui.theme.ColorTokens
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.rememberInterFontFamily
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls

/**
 * Main application entry point.
 *
 * ARCHITECTURE:
 * 1. Bootstrap Phase: Initialize repository, resolve startup state
 * 2. Render Phase: Display UI based on resolved state
 *
 * The startup state is computed ONCE before any content UI is rendered.
 * Navigation state is initialized from the resolved startup state.
 * This prevents any screen flicker or navigation corrections.
 *
 * Screen size breakpoints:
 * - < 600dp: Mobile (no server rail, bottom navigation)
 * - >= 600dp: Desktop (server rail + sidebars)
 */
@Composable
fun App() {
    val vm = viewModel { AppViewModel(AppModule.nostrRepository) }
    val isInitialized by vm.isInitialized.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val isBunkerVerifying by vm.isBunkerVerifying.collectAsState()

    // Phase 2: Compute startup state synchronously from current values
    // isBunkerVerifying keeps the app in Initializing (loading) while the signer
    // confirms the restored session — avoids showing main UI before auth is confirmed.
    val startupState: AppStartState =
        remember(isInitialized, isLoggedIn, isBunkerVerifying) {
            if (isBunkerVerifying) {
                AppStartState.Initializing
            } else {
                StartupResolver.resolve(isInitialized, isLoggedIn)
            }
        }

    // Resolve the user's theme preference (Dark / Light / System) into the active
    // palette before anything reads NostrordColors. Snapshot-state write, so every
    // color usage below recomposes when the preference or the OS theme changes.
    val appTheme by AppModule.appearanceSettings.theme.collectAsState()
    NostrordColors.apply(appTheme, systemDark = isSystemInDarkTheme())

    // Install Inter as the app face before any content renders: NostrordTypography
    // reads AppFonts.defaultFontFamily, and the Material typography below covers
    // Text() calls that rely on LocalTextStyle instead of NostrordTypography.
    val interFamily = rememberInterFontFamily()
    remember(interFamily) { AppFonts.setDefaultFontFamily(interFamily) }
    val appTypography = remember(interFamily) { materialTypographyWith(interFamily) }

    MaterialTheme(colorScheme = nostrordColorScheme(), typography = appTypography) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val hasWindowControls = LocalDesktopWindowControls.current != null

            // Phase 3: Render based on resolved startup state
            when (startupState) {
                is AppStartState.Initializing -> {
                    val loadingMessage =
                        when {
                            isBunkerVerifying && !isLoggedIn -> "Logging out..."
                            isBunkerVerifying -> "Reconnecting to signer..."
                            else -> null
                        }
                    if (hasWindowControls) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            LoadingScreen(Modifier.weight(1f), message = loadingMessage)
                        }
                    } else {
                        LoadingScreen(message = loadingMessage)
                    }
                }

                is AppStartState.Unauthenticated -> {
                    // Drop any leftover ?relay=…&group=… query from the previous
                    // session. The deep-link handler is mounted only while logged
                    // in, so without this the login screen would still show the
                    // ex-account's deep link in the address bar. No-op on
                    // native platforms.
                    LaunchedEffect(Unit) { clearBrowserUrlQuery() }
                    // Not logged in - show login
                    if (hasWindowControls) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            NostrLoginScreen(modifier = Modifier.weight(1f)) {
                                // After login, the startupState will recompute due to isLoggedIn change
                            }
                        }
                    } else {
                        NostrLoginScreen {
                            // After login, the startupState will recompute due to isLoggedIn change
                        }
                    }
                }

                is AppStartState.Authenticated -> {
                    // New-design flow: an account whose kind:10009 lists no groups goes
                    // through the onboarding wizard; everyone else lands on the AppFrame
                    // home.
                    val needsOnboarding by vm.needsOnboarding.collectAsState()
                    val onboardingSkipped by vm.onboardingSkipped.collectAsState()
                    // Keeps the wizard up after a group join so several can be joined.
                    val stayInOnboarding by vm.stayInOnboarding.collectAsState()
                    val content: @Composable (Modifier) -> Unit =
                        if ((needsOnboarding || stayInOnboarding) && !onboardingSkipped) {
                            { m ->
                                OnboardingFlowScreen(
                                    onSkip = vm::skipOnboarding,
                                    onJoin = vm::joinGroupFromInput,
                                    onJoinGroup = { relayUrl, groupId ->
                                        vm.keepOnboarding()
                                        vm.joinGroupFromInput("$relayUrl'$groupId") {}
                                    },
                                    modifier = m,
                                )
                            }
                        } else {
                            { m -> Box(m) { AppFrame() } }
                        }
                    if (hasWindowControls) {
                        Column(Modifier.fillMaxSize()) {
                            MinimalTitleBar()
                            content(Modifier.weight(1f))
                        }
                    } else {
                        content(Modifier.fillMaxSize())
                    }
                }
            }

            // NIP-49 unlock gate: a password-protected account blocked session
            // restore; ask for the password over whatever screen is showing.
            val pendingUnlock by AppModule.nostrRepository.pendingUnlockAccount.collectAsState()
            pendingUnlock?.let { UnlockAccountDialog(it) }
        }
    }
}

/** Material typography with every style on the given family (Inter app-wide). */
private fun materialTypographyWith(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily),
    )
}

/**
 * Material scheme over the active NostrordColors palette. A function (not a cached val)
 * so the snapshot reads happen inside composition and the scheme follows theme switches.
 */
@Composable
private fun nostrordColorScheme() = if (NostrordColors.IsDark) {
    darkColorScheme(
        primary = NostrordColors.Primary,
        onPrimary = NostrordColors.TextPrimary,
        primaryContainer = NostrordColors.PrimaryVariant,
        onPrimaryContainer = NostrordColors.TextPrimary,
        background = NostrordColors.Background,
        onBackground = NostrordColors.TextContent,
        surface = NostrordColors.Surface,
        onSurface = NostrordColors.TextContent,
        surfaceVariant = NostrordColors.SurfaceVariant,
        onSurfaceVariant = NostrordColors.TextSecondary,
        error = NostrordColors.Error,
        onError = NostrordColors.TextPrimary,
        outline = NostrordColors.Divider,
    )
} else {
    lightColorScheme(
        primary = NostrordColors.Primary,
        // White on brand violet, same as dark (the brand color does not change per theme)
        onPrimary = Color(ColorTokens.TextPrimary),
        primaryContainer = NostrordColors.PrimaryVariant,
        onPrimaryContainer = Color(ColorTokens.TextPrimary),
        background = NostrordColors.Background,
        onBackground = NostrordColors.TextContent,
        surface = NostrordColors.Surface,
        onSurface = NostrordColors.TextContent,
        surfaceVariant = NostrordColors.SurfaceVariant,
        onSurfaceVariant = NostrordColors.TextSecondary,
        error = NostrordColors.Error,
        onError = Color(ColorTokens.TextPrimary),
        outline = NostrordColors.Divider,
    )
}

/** Plain background during bootstrap — HTML shell handles the spinner on web. */
@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(
        modifier =
        modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (message != null) {
            Text(
                text = message,
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Main authenticated app with navigation.
 *
 * CRITICAL: The initialScreen parameter is the RESOLVED startup screen.
 * It is used as the initial value for currentScreen state.
 * This ensures no navigation corrections are needed after render.
 *
 * @param initialScreen The screen to start with - computed during bootstrap
 */
