---
description: Fetch a NIP specification and orient an implementation in Nostrord
---

Fetch and explain NIP-$ARGUMENTS from the Nostr protocol, then orient how to implement it in this codebase.

1. **Get the specification** from https://github.com/nostr-protocol/nips/blob/master/$ARGUMENTS.md (read with WebFetch, summarize the key parts).

2. **Show the protocol essentials**:
   - Event kind(s) used
   - Required and optional tags
   - Message flow (REQ / EVENT / CLOSE / AUTH semantics if relevant)
   - Verification or validation rules

3. **Check current implementation status** in this repo:
   ```bash
   grep -rE "NIP-?$ARGUMENTS|nip$ARGUMENTS|kind\s*=\s*\d+" composeApp/src/commonMain/kotlin/org/nostr/nostrord/ | head -20
   ```
   Report whether the NIP is partially implemented, not implemented, or already present.

4. **Orient the implementation**:
   - Which existing files would host the new code (e.g., `nostr/Nip04.kt` for crypto-style NIPs, `network/managers/` for stateful flows, `auth/NostrSigner.kt` for signer NIPs).
   - Whether the NIP needs an `expect/actual` (any crypto / platform API) or stays pure-Kotlin in `commonMain`.
   - Concrete event construction example with the right `kind`, `tags`, and `content` shape.
   - Subscription filter shape, if the client needs to fetch this kind.
   - Test fixtures to add (parse a real event from a known relay, round-trip sign/verify).

## Example Usage

```
/nip 01    # Basic protocol
/nip 29    # Relay-based groups (the project's primary protocol)
/nip 44    # Versioned encryption
/nip 46    # Remote signer (bunker)
/nip 65    # Outbox relay metadata
```
