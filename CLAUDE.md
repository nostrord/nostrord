# CLAUDE.md — Nostrord KMP

Nostr NIP-29 group messaging client. Kotlin Multiplatform targeting Android, JVM Desktop, JS, and WebAssembly.

## First-time setup

```bash
./scripts/install-hooks.sh
```

Points `core.hooksPath` at the repo-tracked hooks (covers all worktrees of this clone). The `pre-commit` hook runs `spotlessApply` on staged Kotlin files and then `spotlessCheck` to guarantee CI won't fail on formatting.

## Build Commands

```bash
# Quick check (use this after any commonMain change)
./gradlew compileKotlinJvm

# Android
./gradlew compileDebugKotlinAndroid

# Desktop (run)
./gradlew :composeApp:run

# Web targets
./gradlew compileKotlinJs
./gradlew compileKotlinWasmJs
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Tests
./gradlew :composeApp:jvmTest       # fast, runs all commonTest on JVM
./gradlew :composeApp:allTests      # all platforms

# Full build
./gradlew build
```

**Rule**: Always run `compileKotlinJvm` before finishing any task. It is the fastest cross-platform check.

## Module Map

```
composeApp/src/
├── commonMain/   Shared business logic, UI (Compose), NIP implementations
├── androidMain/  EncryptedSharedPreferences, Android-specific crypto
├── jvmMain/      Desktop crypto (secp256k1-kmp), JVM Preferences storage
├── jsMain/       NIP-07 browser extension, JS-specific Ktor client
├── wasmJsMain/   WebAssembly target (mirrors jsMain)
└── iosMain/      Darwin Ktor engine, iOS-specific crypto and storage (kept current alongside the others)
```

## Source organization (commonMain)

```
org.nostr.nostrord/
├── auth/          Multi-account: Account, AccountId, AccountStore,
│                  AccountManager, ActiveAccountManager, AccountSession,
│                  AccountSessionFactory, NostrSigner, FallbackChain
├── di/            AppModule (singleton DI container)
├── model/         Shared data models
├── network/
│   ├── managers/  GroupManager, OutboxManager, MetadataManager,
│   │              UnreadManager, ConnectionManager, SessionManager,
│   │              PendingEventManager, LiveCursorStore, AdaptiveConfig,
│   │              RelayMetadataManager, RelayReconnectScheduler,
│   │              EventOrderingBuffer, MuxSubscriptionTracker, etc.
│   ├── outbox/    NIP-65 (Nip65Relay) + event deduplication
│   └── upload/    Blossom / nostr.build uploaders
├── nostr/         Event, KeyPair, Crypto, Bech32, Nip04, Nip07,
│                  Nip11, Nip19, Nip27, Nip44, Nip46Client
├── notifications/ Cross-platform notification dispatch
├── settings/      User-facing feature flags and preferences
├── startup/       Cold-start sequencing
├── storage/       SecureStorage (expect/actual) + per-account helpers
├── ui/
│   ├── components/    Reusable composables (avatars, buttons, chat, accounts, layout, sidebars)
│   ├── navigation/    NavigationHistory
│   ├── screens/       home, group, relay, backup, login, notifications, onboarding, profile, settings
│   ├── theme/         NostrordColors
│   ├── util/          UI helpers
│   └── window/        Window / surface adapters
└── utils/             AppError, epoch helpers, LruCache, etc.
```

## Architecture

**Public facade for the UI layer:**
```
NostrRepository  (use this from Compose / ViewModels)
```

**Auth and signing (post multi-account):**
```
AccountManager  (add / switch / remove accounts)
    │
    ├─ AuthManager (class)  ──── credential load, NIP-42 AUTH, session invalidation
    │
    ├─ ActiveAccountManager  ─── atomic session swap, mutex-protected
    │       │
    │       └─ AccountSession  ─ per-account scope + signer (zeroed on dispose)
    │
    └─ AccountStore  ─────────── persisted account registry + active id

NostrRepository  ──────────────  reads from AccountSession, publishes to relays
SessionManager  ───────────────  NIP-42 AUTH dedup, post-AUTH resubscribe
```

**DI entry point:** `AppModule` singleton.
```kotlin
// Correct
val repo = AppModule.nostrRepository
val groupManager = AppModule.groupManager
val accountManager = AppModule.accountManager

// Wrong — managers depend on shared scope and other managers
GroupManager(connectionManager, scope)
```

**Coroutine scopes:**
- `AppModule.appScope` is **private**. It is passed into each manager at construction time.
- Long-running per-account work runs on `AccountSession.scope`. Cancellation of that scope tears down all per-account jobs at once.
- New code that wants a scope should be a manager (constructor-injected) or run inside the active `AccountSession.scope`.

**State pattern:** Everything is `StateFlow`. Collect in Compose with `.collectAsState()`. One-shot events (snackbars) use `SharedFlow` (see `AppModule.systemMessages`).

**Errors:** `Result<T>` + `AppError` sealed class (in `utils/Result.kt`). Never `throw` across module boundaries.

## Never Do This

```kotlin
// java.* imports in commonMain — Kotlin types only
import java.lang.Integer
import java.util.UUID                 // use kotlin.uuid.Uuid

// GlobalScope — use AppModule manager scopes or AccountSession.scope
GlobalScope.launch { ... }

// AppModule.scope does not exist; appScope is private
AppModule.scope.launch { ... }

// Nip46Client.handleMessage is NOT suspend; wrap suspend work in launch
suspend fun handleMessage() { ... }

// Don't call legacy static companion accessors in new code
NostrRepository.sendMessage(...)      // old pattern, prefer instance methods via AppModule

// Don't write to legacy global storage slots in new code
SecureStorage.saveRelayList(...)      // use saveRelayListFor(pubkey, ...) instead

// Don't include the primary NIP-29 relay in a kind:0 REQ
// (NIP-29 relays do not serve profile metadata)

// Don't catch CancellationException with a bare catch (_: Throwable)
// — rethrow it explicitly first
```

## Multi-account rules

- Use per-account `*For(pubkey)` extension functions in `SecureStorage` (e.g., `saveRelayListFor`, `loadKind10009Timestamp`, `saveCurrentRelayUrlFor`). Legacy global slots remain only for one-shot migration.
- `ActiveAccountManager.activate(session)` is the only correct way to install a new session; it cancels the previous scope and disposes the previous signer under a mutex.
- `NostrSigner.dispose()` zeros private key bytes for `Local` and `Guest`. Don't keep extra copies of `KeyPair.privateKey` around — the dispose-and-zero hardening only covers the in-flight signer.
- Cross-account fallback selection (when a session dies or an active account is removed) goes through `pickFirstSuccess` in `auth/FallbackChain.kt`.

## Platform Restrictions

| Capability | Android | JVM | JS | WASM | iOS |
|---|---|---|---|---|---|
| secp256k1 / Schnorr signing | yes | yes | yes | yes | yes |
| NIP-46 bunker client | yes | yes | yes | yes | yes |
| NIP-07 browser extension (`Nip07.isAvailable()` true) | no | no | yes | yes | no |
| EncryptedSharedPreferences | yes | no | no | no | no |
| `java.security.*` | yes | yes | no | no | no |
| Keychain (iOS secure storage) | no | no | no | no | yes |

All NIP `expect`s have `actual` declarations on every platform (some are stubs that throw or report unavailable). Compilation succeeds across all five targets; runtime availability is what differs.

**Rule:** If a feature requires platform code, add `expect` in `commonMain` and implement `actual` on **all five targets** (android, jvm, js, wasmjs, ios) in the same change. Do not defer any platform: accumulated TODOs become release-blocking work later. If a platform genuinely cannot support the feature (e.g. NIP-07 only exists in browsers), make the `actual` a documented stub that returns `false`/throws, not a missing declaration.

## expect/actual Locations

| Concern | File pattern |
|---|---|
| Crypto (keygen, sign, verify) | `nostr/Crypto.kt` → `*.android.kt`, `*.jvm.kt`, `*.js.kt`, `*.wasmjs.kt`, `*.ios.kt` |
| NIP-04 / NIP-44 encryption | `nostr/Nip04.kt`, `nostr/Nip44.kt` |
| NIP-46 bunker client | `nostr/Nip46Client.kt` |
| NIP-07 browser extension | `nostr/Nip07.kt` (stub on Android/JVM, real on JS/WASM) |
| Secure storage | `storage/SecureStorage.kt` |

## NIP support

| NIP | Where | Purpose |
|---|---|---|
| 01 | `nostr/Event.kt`, `nostr/KeyPair.kt` | Event signing and verification (secp256k1) |
| 04 | `nostr/Nip04.kt` | Legacy encryption |
| 07 | `nostr/Nip07.kt` | Browser extension signer (JS / WASM only at runtime) |
| 11 | `nostr/Nip11.kt` | Relay information document |
| 19 | `nostr/Nip19.kt`, `nostr/Bech32.kt` | Bech32 entities (npub, nsec, naddr, etc.) |
| 27 | `nostr/Nip27.kt` | Text-note references / mentions |
| 29 | `network/managers/GroupManager.kt` + NIP-29 relays | Groups (core feature) |
| 42 | connection layer | AUTH for restricted groups |
| 44 | `nostr/Nip44.kt` | Modern encryption |
| 46 | `nostr/Nip46Client.kt` | Remote signer / bunker |
| 65 | `network/outbox/Nip65Relay.kt` | Outbox relay metadata |

Detailed NIP-29 expertise lives in `.claude/skills/nip29-expert/SKILL.md`.

## Common Patterns

**Sign and send an event:**
```kotlin
val signer = ActiveAccountManager.session.value?.signer
    ?: return Result.Error(AppError.Auth.NotSignedIn)
val signed = signer.signEvent(unsignedEvent)
val message = buildJsonArray { add("EVENT"); add(signed.toJsonObject()) }.toString()
val client = connectionManager.getPrimaryClient()
    ?: return Result.Error(AppError.Network.Disconnected(relayUrl))
client.send(message)  // suspend
```

**Adding a new group operation:**
1. Add the method to `GroupManager` (domain logic).
2. Delegate from `NostrRepository` (pass `signEvent` and `publishJoinedGroups` as lambdas).
3. Expose in the UI via `AppModule.nostrRepository`.

**Adding a new screen:**
1. Add to the `Screen` sealed class in `ui/navigation/`.
2. Create `ui/screens/yourscreen/YourScreen.kt` (with desktop / mobile variants if layout differs).
3. Wire into `App.kt` navigation switch.

**Adding a new persisted setting (per-account):**
1. Add the key + `saveXxxFor(pubkey, ...)` / `loadXxxFor(pubkey, ...)` extension in `storage/SecureStorage.kt`.
2. If migrating from a legacy global slot, write the per-account slot **first**, then set `*_legacy_migrated = true` so a crash mid-migration doesn't lose the legacy data.

**Responsive breakpoints:**
- Compact `< 600dp` — mobile, bottom nav / drawer
- Medium `600–840dp` — tablet, sidebar + 2-column
- Large `> 840dp` — desktop, sidebar + 3-column
- Use `BoxWithConstraints` to switch layouts

## Naming Conventions

| Type | Pattern | Example |
|---|---|---|
| Manager | `*Manager` | `GroupManager`, `SessionManager`, `AccountManager` |
| Repository | `*Repository` | `NostrRepository` |
| Network client | `*Client` | `NostrGroupClient`, `Nip46Client` |
| Screen composable | `*Screen` | `GroupScreen`, `HomeScreen` |
| Modal composable | `*Modal` | `CreateGroupModal`, `AddAccountSheet` |
| Platform file | `Name.platform.kt` | `Crypto.android.kt`, `Nip07.wasmjs.kt` |

## Shared UI Components — Use, Don't Recreate

- `ProfileAvatar` / `OptimizedSmallAvatar` — user avatar with fallback to Jdenticon
- `Jdenticon` — deterministic identicon from pubkey
- `ShimmerEffect` / `SkeletonLoader` — loading placeholders
- `AppButton` — standard button (respects design tokens)
- `InfoCard`, `KeyCard`, `WarningCard` — content cards

## Key Dependencies

| Library | Purpose | Platforms |
|---|---|---|
| Kotlin 2.2.20 | Language | All |
| Compose Multiplatform 1.9.1 | UI | All |
| Ktor 3.0 | WebSocket + HTTP | All |
| kotlinx.coroutines 1.10.2 | Async | All |
| kotlinx.serialization | JSON parsing | All |
| secp256k1-kmp | Elliptic curve crypto | Android + JVM |
| BouncyCastle | Additional crypto | Android + JVM |
| Coil 3.x / Kamel | Image loading (KMP) | All |
| androidx.security-crypto | EncryptedSharedPreferences | Android only |

## Working preferences

- Diagnose, fix, compile once. Avoid reading "neighbouring" files just to be thorough.
- One concern per commit, one concern per PR. Ship the PR the same day when possible.
- Commit message size proportional to diff size: trivial diffs get subject-line only.
- Stage files by name; never `git add -A` (the working tree often has untracked screenshots and planning docs that must not be versioned).
- When asked to plan, show the plan inline in the chat. Do not write plans to files unless asked.
- All user-facing strings must be in English. No Portuguese in UI text.

## Slash commands

- `/nip <n>` — fetches NIP-`<n>` spec, greps the codebase for existing usage, suggests where to add code.

## Skills

- `nip29-expert` — implementing or debugging NIP-29 group functionality.
