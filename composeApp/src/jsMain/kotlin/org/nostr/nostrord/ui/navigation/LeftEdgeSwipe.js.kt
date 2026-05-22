package org.nostr.nostrord.ui.navigation

/**
 * Raw-JS left-edge swipe detector (issue #77).
 *
 * The Compose pointer-pass approach loses the gesture to the chat's scrollable on
 * mobile browsers, so we intercept it before Compose by listening on `window` in
 * the CAPTURE phase with `{ passive: false }`. preventDefault keeps Compose from
 * double-handling and stops the browser's own edge gestures.
 */
@Suppress("UnsafeCastFromDynamic")
private fun jsRegisterLeftEdgeSwipe(onOpen: () -> Unit): () -> Unit = js(
    """
        (function() {
            var EDGE_PX = 24;       // gesture must START this close to the left edge
            var TRIGGER_PX = 36;    // ...and move right at least this far
            var startX = 0, startY = 0;
            var tracking = false, fired = false;
            function onStart(e) {
                if (!e.touches || e.touches.length !== 1) { tracking = false; return; }
                var t = e.touches[0];
                fired = false;
                tracking = t.clientX <= EDGE_PX;
                if (tracking) {
                    startX = t.clientX;
                    startY = t.clientY;
                }
            }
            function onMove(e) {
                if (!tracking || fired) return;
                if (!e.touches || e.touches.length !== 1) return;
                var t = e.touches[0];
                var dx = t.clientX - startX;
                var dy = t.clientY - startY;
                if (dx >= TRIGGER_PX && dx > Math.abs(dy)) {
                    fired = true;
                    tracking = false;
                    e.preventDefault();
                    e.stopPropagation();
                    onOpen();
                }
            }
            function onEnd() { tracking = false; fired = false; }
            var opts = { capture: true, passive: false };
            window.addEventListener('touchstart', onStart, opts);
            window.addEventListener('touchmove', onMove, opts);
            window.addEventListener('touchend', onEnd, opts);
            window.addEventListener('touchcancel', onEnd, opts);
            return function() {
                window.removeEventListener('touchstart', onStart, opts);
                window.removeEventListener('touchmove', onMove, opts);
                window.removeEventListener('touchend', onEnd, opts);
                window.removeEventListener('touchcancel', onEnd, opts);
            };
        })()
        """,
)

actual fun registerLeftEdgeSwipeToOpen(onOpen: () -> Unit): () -> Unit = jsRegisterLeftEdgeSwipe(onOpen)
