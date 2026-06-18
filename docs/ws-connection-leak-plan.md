# Web WebSocket connection leak + optimization plan

Symptom: log in with NIP-46, then log out (or add another NIP-46 account via QR),
and the QR add fails with "Connection failed: Failed to connect to any relay".
Only an app restart (page reload) fixes it. The QR should just show and let you
log in.

## Diagnosis (verified)

The earlier `fix(nip46): drop dead relays from the nostrconnect listen` is
correct: it surfaces a real connection failure instead of a silent hang. The
failure is the browser refusing new WebSockets because the tab is at its limit
(Firefox ~200 global, Chrome ~255/host). Two mechanisms push it there:

1. **Transient post-logout window** (login -> logout -> QR): `Nip46Client.disconnect()`
   closes its relay sockets asynchronously, so sockets to nos.lol / damus are
   still CLOSING when the QR reopens sockets to the same hosts. Mitigated by the
   committed retry in `LoginViewModel.startQrSession` (rides out the window).

2. **Persistent warm-swap leak** (login -> add account, no logout): the account
   swap disposes the previous signer (`AccountSession.cancel()` -> `signer.dispose()`
   -> `nip46Client.disconnect()`) but `reloadForActiveAccount` / `applyActiveAccountChange`
   do NOT close the previous account's pooled relay sockets that the new account
   does not reuse by URL. They accumulate across switches.

3. **Duplication** (see `ws-duplication-audit.md`): 66 connections for 32 relays;
   metadata subscriptions left open (no CLOSE on EOSE timeout); the bunker opens
   its own sockets to general relays the pool also holds. Raises the total faster.

Confirmed NOT the cause (agent false positives, ruled out by reading):
- `ActiveAccountManager.activate` does dispose the previous signer (= disconnect
  its bunker) via `previous.cancel()`.
- `Nip46Client.disconnect()`'s `clientScope.launch { client.disconnect() }` after
  `cancelChildren()` DOES run (`cancelChildren` does not cancel the SupervisorJob),
  so sockets do close; it is just asynchronous.

## Plan

### Phase 0 RESULT (done)
Reproduced with `/tmp/nostrord-ws-audit/ws_cycle.py` (login with a throwaway local
key, count relay WS, logout, repeat). Measured on the dev server:
- Before fix: login opens 5 relay WS; **logout closes 0** (cumulative closes=0);
  residual climbs every cycle -> browser WS limit -> "Failed to connect".
- Root cause: the web "Sign out" calls `AccountManager.removeAccount`, not
  `repo.logout()`. With no fallback account it ran `authManager.logout()` +
  `applyActiveAccountChange(null)` but **never `connectionManager.clearAll()`**, so
  every relay socket stayed open.
- After fixing `removeAccount` to route the no-fallback case through
  `repo.logout()`: closes 0 -> 5, residual 6 -> 2 per cycle.
- Remaining residual (2/cycle): `purplepag.es` + `www.nostr.ltd` (outbox discovery
  relays). `OutboxManager` runs on `appScope` and `OutboxManager.clear()` does not
  cancel its in-flight `getOrConnectRelay` launches, so an outbox job reopens pool
  sockets AFTER `clearAll`. -> Phase 1/2 target below.

### Phase 0 — tooling
Harness in `/tmp/nostrord-ws-audit/` (`ws_cycle.py`: login/logout cycles, asserts
WS count does not grow; `ws_explore.py` / `ws_*_probe.py`: DOM + WS inspection).
Drive the running dev server (http://localhost:8080) with Python Playwright
(`~/.local/lib/python3.13/site-packages/playwright`, chromium in
`~/.cache/ms-playwright`). Headed `launch_persistent_context` so a NIP-46 login
survives runs. Track `page.on("websocket")` opens/closes; run N login/logout (and
add-account) cycles and assert the live-WS count returns to a baseline instead of
growing monotonically. This both proves the leak and becomes the regression test.
Tooling lives in `/tmp/nostrord-ws-audit/`.
Caveat: dev server is `jsBrowserDevelopmentRun --continuous`; `compileKotlinJs`
does NOT update the served bundle. Edit the `.kt` under `src/`, let the continuous
build rebuild (watch the served `composeApp.js` Content-Length), then reload.

### Phase 1 — Close the teardown leaks (commonMain + js)
- [DONE] Full logout (`removeAccount` no-fallback) routes through `repo.logout()`
  so `clearAll()` closes the relay sockets.
- [TODO] Residual: cancel `OutboxManager`'s in-flight connection launches on
  `clear()` (track its jobs / give it a child Job under appScope) so outbox
  discovery does not reopen `purplepag.es` / `www.nostr.ltd` after `clearAll`.
- Warm-swap / account switch: close the previous account's pool + primary sockets
  that the new account will not reuse (targeted teardown in `applyActiveAccountChange`
  or `reloadForActiveAccount`), instead of leaving them open.
- `Nip46Client.disconnect()`: also CLOSE the durable `nip46-resp-*` subscription
  and cancel the QR background connect loop; ensure `client.close()` runs.
- Confirm `cancelQrSession` / `onCleared` fully tear down an abandoned listen client.

### Phase 2 — Optimize connections (audit Findings 2-4)
- Metadata: send CLOSE after EOSE / timeout (stop leaking metadata subs); keep one
  pooled socket per URL reused.
- Route group-metadata re-subscription through `MuxSubscriptionTracker` to avoid
  duplicate REQ / sockets on navigation.
- Reduce the bunker <-> pool overlap on general relays.

### Phase 3 — Verify with Playwright
Re-run the Phase 0 script: assert stable WS count after logout and that QR add
works after a logout without a restart.

The committed `startQrSession` retry stays as a safety net for the transient
window; ending the leak comes from Phases 1-2.
