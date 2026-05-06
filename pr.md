# feat: group mentions via % autocomplete

Typing `%` in the message input opens a group picker (mirroring the existing `@` user mention system). Selecting a group inserts `%GroupName` into the text, which is encoded as `nostr:naddr1...` on send. Received `naddr` kind:39000 entities are decoded back to `%GroupName` for display in chat.

- `Nip19.encodeNaddr` — new function to encode NIP-29 group references; uses the relay's own pubkey from the cached NIP-11 metadata (`relayMetadata`), falling back to 32 zero bytes if not yet available
- `GroupMentionPopup` — new autocomplete popup with group icon, name and relay, filtered by name/id
- `GroupLinkCard` — fixed fallback to use group icon pattern (rounded square + color + initial) instead of Jdenticon; added image error-state tracking
- `MessageInput` — unified `findMentionContext` handles both `@` and `%` triggers; `MentionVisualTransformation` highlights group mentions alongside user mentions
- `GroupScreen` — derives `availableGroups` from `groupsByRelay` (relay-accurate) and encodes mentions on send
