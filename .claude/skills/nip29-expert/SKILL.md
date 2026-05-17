---
name: nip29-expert
description: Use when implementing, debugging, or extending NIP-29 group functionality in Nostrord (group join/leave, member management, message kinds, relay-side state). Covers what NIP-29 relays do and do NOT serve, the moderation event kinds, and the project's existing manager classes that handle them.
---

# NIP-29 Expert

NIP-29 is "relay-based groups": the **relay** holds group state (members, admins, metadata) rather than the client. This makes it different from public Nostr relays in important ways that change how you write code.

## What NIP-29 relays DO serve

| Kind | Event | Purpose |
|---|---|---|
| 9 | Group message | Chat message inside a group |
| 11 | Group thread root | Top-level thread post |
| 12 | Group thread reply | Threaded reply |
| 39000 | Group metadata | Name, picture, about, public/private, open/closed |
| 39001 | Group admins | List of admin pubkeys + roles |
| 39002 | Group members | List of member pubkeys |
| 39003 | Group roles | Defined roles |
| 9000-9020 | Moderation events | put-user, remove-user, edit-metadata, delete-event, etc. |

## What NIP-29 relays DO NOT serve

**kind:0 (user metadata / profiles).** This is a frequent trap. A NIP-29 relay does NOT serve kind:0 even for its own members. To get a member's profile, you must query general-purpose relays (relay.damus.io, nos.lol, etc.) or use the outbox model (NIP-65 to find the user's write relays).

Never include the primary NIP-29 relay in a kind:0 REQ. It will either ignore the filter or CLOSE the subscription with an error.

## Project-specific patterns in Nostrord

### Where the code lives

- `network/NostrGroupClient.kt`: per-relay WebSocket wrapper. `send()` is `suspend`.
- `network/managers/GroupManager.kt`: in-memory group cache, joined-group tracking per relay.
- `network/managers/MetadataManager.kt`: kind:0 fetching (general-purpose, NOT from NIP-29 relays).
- `network/managers/OutboxManager.kt`: NIP-65 outbox + kind:10009 (user's joined group list).
- `storage/SecureStorage.kt`: persisted joined-group lists per account, per relay.

### AUTH and restricted groups

NIP-29 relays use NIP-42 AUTH to gate access. When a user is not yet admitted to a private group, the relay returns `CLOSED` with `auth-required` or `restricted`. The codebase has dedicated handling:

- `handleAuthChallenge` in the connection layer responds to AUTH.
- After AUTH succeeds, pending REQs are resubscribed.
- Restricted markers are persisted to `SecureStorage` with a TTL so the next session does not blast the relay with REQs that will be CLOSED.

When implementing a new NIP-29-aware flow, always assume some events will be gated behind AUTH and the first attempt may CLOSE.

### Per-account scoping

Joined-group state, kind:10009 timestamps, and last-read cursors are **per-account**. Use the `*For(pubkey)` extension functions in `SecureStorage` (e.g., `saveJoinedGroupsForRelay(pubkey, relayUrl, ...)`, `loadKind10009Timestamp(pubkey)`). Never use the legacy global slots in new code; they exist only for one-shot migration.

### Restricted-groups cache

Groups that the relay CLOSED with `restricted` for the current pubkey are persisted with a 7-day TTL (`RESTRICTED_GROUPS_TTL_S`). This avoids re-issuing the same doomed REQs on reconnect, which on pyramid-style relays would CLOSE the entire batch and starve the non-restricted groups of metadata.

## Implementation checklist when adding a new group-related feature

1. Is the event kind in the NIP-29 range (9, 11, 12, 39000s, 9000-9020)? If yes, route through `NostrGroupClient`. If it is kind:0, route through `MetadataManager` to a general-purpose relay.
2. Does the action require admin role? Check `39001` (admins) before publishing.
3. Will the relay AUTH-gate this? Test on a private group; expect `CLOSED auth-required` on first try.
4. Is the state per-account? Use `*For(pubkey)` storage helpers.
5. Does the new event invalidate cached restricted markers? (e.g., a successful join means the user is no longer restricted for that group; clear that group's restricted marker.)

## Reference relays

- `wss://groups.0xchat.com` (popular, NIP-29 compliant)
- `wss://relay.groups.nip29.com` (reference implementation)
- Test private/closed groups against these to validate AUTH and restricted handling.

## Anti-patterns to avoid

- Including the primary NIP-29 relay in any kind:0 REQ
- Using legacy global storage slots (`saveRelayList`, `saveCurrentRelayUrl`, etc.) in new code
- Assuming AUTH always succeeds before publishing a moderation event
- Forgetting to clear the restricted marker on a successful join
- Re-fetching the full group list on every reconnect when it is cached (use the lazy/eager mode flag in `SecureStorage.isGroupFetchLazy`)
