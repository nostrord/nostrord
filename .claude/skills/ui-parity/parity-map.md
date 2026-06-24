# UI parity map (Compose ↔ web)

Mechanical reference for keeping the Compose (native) and React/DOM (web) UIs in sync.
Screen logic is shared in a **commonMain ViewModel**; each platform is layout only. When you
touch one platform's screen, open the other from here and mirror structure + states + tokens.

Paths are under `composeApp/src/`. Compose UI = `uiComposeMain/`, web UI = `webMain/`,
shared VM = `commonMain/`.

## Screen ↔ files ↔ shared ViewModel

| Screen | Compose (uiComposeMain) | Web (webMain) | Shared VM (commonMain) |
|---|---|---|---|
| App root / cold-start gate | `App.kt` | `web/WebApp.kt` | `AppViewModel.kt` |
| Logged-in shell (nav, sidebars, rail) | `App.kt` + `ui/components/*` | `web/AppShell.kt` | none yet (composes per-feature VMs) |
| Home / relay group picker | `ui/screens/home/HomeScreen.kt` (+ `HomeScreenDesktop.kt`, `HomeScreenMobile.kt`, `ManageRelayContent.kt`) | `web/screens/HomeScreen.kt` | `ui/screens/home/HomeViewModel.kt` |
| Login | `ui/screens/login/` (`*Screen` + `components/ExtensionLoginTab.kt`, `PrivateKeyLoginTab.kt`, `BunkerLoginTab.kt`) | `web/screens/LoginScreen.kt` + `BunkerQr.kt` + `modals/AddAccountSheet.kt` (logout helper: `web/auth/WebAuth.kt`) | `ui/screens/login/LoginViewModel.kt` |
| Group / chat | `ui/screens/group/GroupScreen.kt` (+ `ui/components/chat/MessageItem.kt`) | `web/screens/ChatScreen.kt` (+ `ChatItems.kt`) | `ui/screens/group/GroupViewModel.kt` |
| Notifications | `ui/screens/notifications/NotificationsScreen.kt` | `web/screens/NotificationsScreen.kt` | `ui/screens/notifications/NotificationsViewModel.kt` |
| Settings | `ui/screens/settings/SettingsScreen.kt` (+ `RelayNip65PanelContent.kt`) | `web/screens/SettingsScreen.kt` (panels: Profile/Backup/Relays/Media/Notifications/Security/Experimental) | profile panel → `ui/screens/profile/EditProfileViewModel.kt`; other panels read settings stores directly |
| User profile (view, #/u/:pubkey) | `ui/screens/profile/ProfilePageScreen.kt` | `web/screens/ProfilePage.kt` (+ `web/modals/UserProfileModal.kt` quick view) | `ui/screens/profile/ProfilePageViewModel.kt` |
| Edit profile | `ui/screens/profile/EditProfileScreen.kt` | web settings `ProfilePanel` (in `SettingsScreen.kt`) | `ui/screens/profile/EditProfileViewModel.kt` |
| Relay | `ui/screens/relay/` | no dedicated web screen yet | `ui/screens/relay/RelayViewModel.kt` |
| Onboarding | `ui/screens/onboarding/` | `web/screens/OnboardingScreen.kt` | none (pure layout) |
| Backup keys | `ui/screens/backup/` | web settings `BackupPanel` | none |

NOTE: web `ChatScreen.kt` is one file with several FCs (main chat, `ChatComposer`, `MessageRow`,
`QuotedEvent`, `GroupLinkCard`). Only the main FC + composer consume `GroupViewModel`; sub-FCs
read global flows / props.

## Primitive & token mapping

Source of truth for color/dimens is **commonMain** `ui/theme/ColorTokens.kt` (colors, ARGB
Long) and `ui/theme/DimenTokens.kt` (spacing + radius, px). Both UIs derive — never hardcode.

| Concern | Compose | Web |
|---|---|---|
| Color | `NostrordColors.X` (`ui/theme/Colors.kt`) | `var(--color-x)` in CSS, or `WebColors.X` inline (`web/theme/WebColors.kt`) |
| Spacing | `Spacing.xxs..xxxl` (`ui/theme/Spacing.kt`) | `var(--space-xxs..xxxl)` (injected by `web/theme/ColorTokensCss.kt`) |
| Corner radius | `NostrordShapes.radius*` (`ui/theme/Shape.kt`) | `var(--radius-none/sm/md/lg/xl/full)` |
| Typography | `NostrordTypography.*` (`ui/theme/Typography.kt`) | CSS `font-size`/`font-weight` — **NOT tokenized; diverges by design** |
| State flow read | `flow.collectAsState()` | `useStateFlow(flow)` (`web/bridge/FlowHooks.kt`) |
| ViewModel instance | `viewModel { VM(repo) }` / `viewModel(key = k) { VM(repo, k) }` | `useViewModel { VM(repo) }` / `useViewModel(key) { VM(repo, key) }` (`web/bridge/ViewModelHook.kt`) |
| Fire-and-forget action | a `VM` method using `viewModelScope.launch` | call the VM method; for actions that must survive navigation (leave/delete group, send), the web sometimes uses `launchApp {}` (`web/bridge/AppScope.kt`, app-lifetime) instead — see Scope below |
| Avatar | `ProfileAvatar` / `OptimizedSmallAvatar` | `WebAvatar` (`web/components/`) |
| Identicon | `Jdenticon` | `Jdenticon` (`web/components/`) |
| Icon | `Icons.*` (material) | `Ic.*` (`web/components/Icon.kt` — hand-curated 24dp paths; filled unless native uses Outlined) |
| List | `LazyColumn { items(...) }` | `div { ...forEach { } }` (or virtualized later) |
| Responsive | `BoxWithConstraints` breakpoints: Compact `<600dp`, Medium `600–840`, Large `>840` | CSS media queries / dual-render pattern (e.g. `home-header-mobile` vs `home-header-desktop`) |

## Scope semantics (matters for parity of *actions*)

- A VM method on `viewModelScope` is **cancelled when the screen leaves / the VM key changes**.
  This matches Compose (`viewModel`) and is correct for fetches and most actions.
- `launchApp {}` (web) is **app-lifetime** — use it only when an action must complete even if
  the component unmounts immediately after (e.g. leave/delete group followed by navigation away,
  or a send that should survive a group switch). Moving these to `viewModelScope` would cancel
  them mid-flight.

## Conventions

- Web `*Screen` files reference their Compose counterpart by **file** at the top (not line
  number — line numbers rot). Keep that.
- No em-dash in user-visible strings (native + web). All UI strings in English.
- Definition of done for a UI change = both platforms updated (or the deferred side explicitly
  flagged) + `compileKotlinJvm` + `compileKotlinJs` (+ `:composeApp:jvmTest` if a VM changed).
