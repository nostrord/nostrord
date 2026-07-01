package org.nostr.nostrord.web.components

import kotlinx.coroutines.awaitCancellation
import react.useEffect
import react.useRef
import web.dom.document

/**
 * Stack of the active Escape handlers, innermost (most recently opened modal) last. Escape only ever
 * closes the topmost modal, so a confirm dialog stacked over the manage modal closes by itself
 * instead of taking the modal underneath down with it.
 */
private val escStack = mutableListOf<() -> Unit>()
private var escListenerInstalled = false

private fun ensureEscListener() {
    if (escListenerInstalled) return
    escListenerInstalled = true
    val handler: (dynamic) -> Unit = { e ->
        if (e.key == "Escape") escStack.lastOrNull()?.invoke()
    }
    document.asDynamic().addEventListener("keydown", handler)
}

/**
 * Closes a modal/overlay when the Escape key is pressed.
 *
 * Pushes [onClose] onto a shared [escStack] for the lifetime of the calling component; a single
 * document-level keydown listener fires only the topmost handler, so Escape closes just the
 * innermost open modal (not every modal that happens to be mounted). Document-level (not element
 * focus) so it works no matter where focus sits. The latest [onClose] is read through a ref so it
 * never goes stale without re-pushing on every render.
 *
 * Cleanup follows this wrappers version's model: `useEffect` runs a suspend block whose scope is
 * cancelled on unmount, so [awaitCancellation] + `finally` pops this handler off the stack (same
 * pattern as `useStateFlow`'s `flow.collect`).
 */
fun useEscClose(onClose: () -> Unit) {
    val cb = useRef(onClose)
    cb.current = onClose
    useEffect(Unit) {
        ensureEscListener()
        val entry: () -> Unit = { cb.current?.invoke() }
        escStack.add(entry)
        try {
            awaitCancellation()
        } finally {
            escStack.remove(entry)
        }
    }
}

/**
 * Install a global focus trap for every `.modal-card` opened anywhere in the app.
 *
 * Call once from the app root (`WebApp`). The trap covers any current or future modal that
 * follows the `.modal-card` convention — no per-modal hook to remember.
 *
 * On every newly-opened modal-card:
 *  1. `tabindex="-1"` is added to the card so it is programmatically focusable.
 *  2. Keyboard focus is moved to the first interactive descendant (or the card itself if the
 *     modal has no controls yet), so Tab / Shift+Tab cycle through the modal's own elements.
 *  3. Any subsequent `focusin` that lands outside the topmost open modal is bounced back inside
 *     — this is the actual "trap" (HTML's native Tab algorithm would otherwise move focus to
 *     the next element behind the backdrop).
 *  4. When the last modal closes, focus is returned to the element that was focused before the
 *     first modal opened, so the keyboard journey stays continuous.
 *
 * Implementation is inlined as JS — kotlin-react has no first-class `MutationObserver` binding
 * and the rest of the code (querySelector / contains / focus) is trivial DOM. The selector list
 * matches the HTML focusable-element set (enabled buttons, anchored links, enabled text/select/
 * textarea inputs, and elements with an explicit non-negative `tabindex`).
 */
fun installGlobalModalFocusTrap() {
    js(
        """
        (function() {
            var trapped = null;
            var previouslyFocused = null;
            var SEL = 'button:not([disabled]), a[href], '
                + 'input:not([disabled]):not([type="hidden"]), '
                + 'select:not([disabled]), textarea:not([disabled]), '
                + '[tabindex]:not([tabindex="-1"])';
            function applyTrap() {
                var cards = document.querySelectorAll('.modal-card');
                var top = cards.length ? cards[cards.length - 1] : null;
                if (top === trapped) return;
                if (top && !trapped) {
                    previouslyFocused = document.activeElement;
                } else if (!top && trapped) {
                    if (previouslyFocused && previouslyFocused.focus) previouslyFocused.focus();
                    previouslyFocused = null;
                }
                trapped = top;
                if (top) {
                    top.setAttribute('tabindex', '-1');
                    var first = top.querySelector(SEL);
                    (first || top).focus();
                }
            }
            new MutationObserver(applyTrap)
                .observe(document.body, { childList: true, subtree: true });
            document.addEventListener('focusin', function(e) {
                if (!trapped || trapped.contains(e.target)) return;
                var first = trapped.querySelector(SEL);
                (first || trapped).focus();
            });
            applyTrap();
        })();
        """,
    )
}
