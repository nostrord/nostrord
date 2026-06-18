# NIP-46 bunker connection: diagnosis and fix plan

Bunker (remote signer) accounts connect slower than before, sometimes need an
app restart after add-account, and intermittently fail to load private NIP-29
group data, while local-key and NIP-07 accounts work fine. These are three
interacting problems with one root: **the bunker signer is a single serialized
connection, and the recent NIP-17 DM feature floods it at the worst moment.**

## Diagnosis

### 1. NIP-17 DMs starve AUTH at startup (the regression)
- `Nip17.unwrap` does **two `signer.nip44Decrypt` calls per gift wrap** (wrap,
  then seal). Local/NIP-07 decrypt in-process; **bunker pays a remote round-trip
  each time**.
- `NostrRepository` gift-wrap handler launches `dmManager.ingestGiftWrap` for
  every kind:1059 under a `Semaphore(3)`. A backlog of dozens of unread DMs means
  ~2x that many bunker round-trips fired at boot, all serialized on the one
  signer connection.
- The kind:22242 NIP-42 AUTH event that private group relays require is also
  signed through that same connection, so it queues behind the DM backlog. The
  relay closes the unauthenticated socket in ~5-10s, so private groups never load.
- Commit `987b6468` (NIP-17 decrypt throttle) was a partial step; the semaphore
  caps concurrency but does not give AUTH priority.

### 2. Bunker relays connect in the background (ordering)
- `AuthManager.installBunkerClient` (switch / restore path) opens the signer
  relays in `authScope.launch` after `_isLoggedIn = true`. Group relays start
  sending AUTH challenges before the bunker is reachable.
- A recovery collector in `NostrRepository` reconnects group relays when the
  bunker becomes ready, **but it only fires when `isBunkerVerifying` flips** —
  and interactive `loginWithBunker` never sets verifying, so **add-account gets
  no safety net** and needs a manual restart.

### 3. AUTH has no priority lane and a 120s timeout
- `Nip46Client.sendRequest` uses a 120s timeout per round-trip. Good for a normal
  sign, useless when the group relay drops the socket in seconds. AUTH and DM
  decrypts share the same FIFO request channel with no prioritization.

## Plan

### P0 — defer DMs behind AUTH at boot (DONE)
Hold a bunker account's DM gift-wrap backlog until the active relay's AUTH is
signed, so AUTH wins the signer at cold start.
- `NostrRepository` gift-wrap handler: for `NostrSigner.Bunker`, await
  `primaryClient.awaitAuthOrTimeout(DM_INGEST_AUTH_GRACE_MS)` before taking the
  decrypt semaphore. `awaitAuthOrTimeout` returns the instant AUTH completes, so
  private-group relays unblock immediately; the 10s grace only caps the wait for
  relays that need no AUTH (public). Local/NIP-07 skip the wait.
- Trade-off: a bunker account whose primary relay is public delays its DM backlog
  by up to the grace at cold start. Acceptable: old DMs are not urgent, and the
  acute case (private NIP-29 relay) returns early.

### P1 — make the recovery cover the interactive add path (DONE)
Root of the "add-account needs restart": the bunker-ready recovery collector
reacts to `_bunkerState` turning Connected, which the **synchronous**
`loginWithBunker` sets mid-swap, *before* `finishLoginInit` wires up the new
account's relays. So the collector reconnected the old account's relays and
raced the swap's own reconnect, leaving the primary unauthenticated; only a
restart (which goes through `restoreBunkerSession`, where the flags flip *after*
group setup) recovered.

- Extracted the collector body into `recoverBunkerGroupRelays()` (idempotent:
  reconnect only when the primary has not AUTH'd; `ensureJoinedRelaysConnected`
  always safe to re-run).
- `bunkerLoginInProgress` flag suppresses the collector while `loginWithBunker` /
  `completeNostrConnectLogin` are wiring up the new account, killing the harmful
  mid-swap reconnect.
- After those flows finish, an explicit delayed `recoverBunkerGroupRelays()` runs
  the recovery at the right time, so a first bunker add loads private groups
  without a restart.

Dropped the original "await `connectRelaysOnly` before group-load" idea: blocking
the switch/startup on the signer relay would regress the #85 non-blocking design
("a hung or offline bunker never blocks startup"). The correctly-timed recovery
achieves the same outcome (private groups load once the signer is reachable)
without the block.

### P2 — AUTH resilience in steady state (TODO)
- Give AUTH a priority lane in `Nip46Client.sendRequest` (or a small reserved
  concurrency budget) so a later private-group open isn't starved by in-flight
  DM decrypts.
- When a private-group REQ returns CLOSED "auth-required" and the signer was not
  ready, retry just the AUTH + REQ once the signer is ready (targeted, no full
  relay reconnect).
- Use a short dedicated timeout for AUTH round-trips so a stuck bunker does not
  hold the group socket for 120s.

## Key references
- `nostr/Nip17.kt` — `unwrap` (two NIP-44 decrypts per gift wrap)
- `network/NostrRepository.kt` — gift-wrap handler, `dmDecryptSemaphore`,
  `DM_INGEST_AUTH_GRACE_MS`, bunker-ready recovery collector
- `network/AuthManager.kt` — `loginWithBunker`, `useAccount`,
  `installBunkerClient`, `restoreBunkerSession`
- `network/NostrGroupClient.kt` — `awaitAuthOrTimeout`, `requiresAuth`
- `network/managers/SessionManager.kt` — `handleAuthChallenge` (signs kind:22242)
- `nostr/Nip46Client.kt` (+ platform actuals) — `sendRequest`, `signEvent`,
  `nip44Decrypt`, relay connection lifecycle
