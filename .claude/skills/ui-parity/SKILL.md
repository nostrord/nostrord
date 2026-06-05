---
name: ui-parity
description: Use when adding, porting, or modifying any screen or reusable UI component in Nostrord, on either the Compose (native) or React/DOM (web) side. Keeps the two UIs structurally in sync and the shared screen logic in commonMain. Covers the screen↔file↔ViewModel map, the Compose↔web primitive/token mapping, and the procedure to avoid implementing only one platform or letting the web layout drift from native.
---

# UI parity (Compose ↔ web)

Nostrord renders twice: **Compose Multiplatform** (android/jvm/ios, `uiComposeMain/`) and
**React/DOM** (web/js, `webMain/`). They are separate render trees and CANNOT share UI code —
but they MUST share behavior and stay structurally aligned. The usual failure mode is changing
one platform and forgetting or drifting the other. This skill exists to stop that.

The companion file **`parity-map.md`** (same folder) has the concrete tables — read it first.

## The one rule

**Screen logic lives in a ViewModel in `commonMain`; each platform is layout only.** Compose
consumes it via `viewModel { }`, web via `useViewModel { }`. If you find logic (state shaping,
actions, error handling) inside a screen file, it probably belongs in the VM so both sides share
it — that is the whole point.

## Procedure (every UI change)

1. **Locate both sides.** Open `parity-map.md`, find the screen, and open BOTH the Compose file
   and the web file, plus the shared VM. Do not work from one side alone.
2. **Logic → commonMain VM.** New/changed behavior goes in the VM (StateFlows + action methods),
   tested in `commonTest`. Both UIs then consume it. Enrich the VM to be the superset both need.
3. **Mirror the layout structure.** When you change layout on one platform, read the other and
   match: element presence + order, the empty / loading / error states, responsive breakpoints,
   and which token each color/space/radius uses. Translate primitives via `parity-map.md`'s
   mapping table — don't guess the counterpart.
4. **Tokens, never literals.** Colors from `ColorTokens` (`var(--color-*)` / `NostrordColors`),
   spacing/radius from `DimenTokens` (`var(--space-*)`/`var(--radius-*)` / `Spacing`/`NostrordShapes`).
   Typography is intentionally NOT shared — don't try to tokenize it.
5. **Mind action scope.** A VM method on `viewModelScope` is cancelled when the screen leaves.
   That's correct for fetches and most actions, but actions that must survive immediate
   navigation (leave/delete group, a send before a group switch) use the web's app-lifetime
   `launchApp {}` on purpose — see "Scope semantics" in `parity-map.md` before moving them.
6. **Definition of done.** Both platforms updated, or the deferred side **explicitly flagged** to
   the user (never silently do one). Then `compileKotlinJvm` + `compileKotlinJs` (+
   `:composeApp:jvmTest` if a VM changed). Build cache can show false `UP-TO-DATE` / fail to pack
   a `.at` file — confirm with `--rerun-tasks --no-build-cache` when in doubt.

## What this can and cannot guarantee

- It eliminates **structural** drift: missing element, wrong order, forgotten empty/error state,
  wrong token, logic implemented on only one side.
- It does NOT guarantee **pixel** parity — two render engines differ and typography diverges by
  design. Final visual fidelity needs running the app (`jsBrowserDevelopmentRun` for web,
  `:composeApp:run` for desktop) and comparing by eye.

## Anti-patterns

- "I'll do the web later." That is exactly how the two diverge. Do both, or flag the deferral.
- Reimplementing a VM's logic inside a web screen (e.g. parsing, error formatting, sessions) when
  the VM already exposes it — consume the VM instead.
- Hardcoding a hex / px instead of a token.
- Trusting a green `compileKotlinJs` that was actually `UP-TO-DATE` from cache after an edit.
