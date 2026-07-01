package org.nostr.nostrord.web.components

/**
 * Native-feeling pull-to-refresh for the mobile web app.
 *
 * The document is locked (`html, body { overflow: hidden }`) so the browser's own
 * pull-to-refresh never fires; the app owns its scroll regions. This installs the gesture
 * manually: when the touched scroll region is at its very top and the finger drags down, a
 * circular spinner is pulled in from above with resistance, and releasing past the threshold
 * reloads the page (like any website's pull-to-refresh).
 *
 * Call once from the app root (`WebApp`). Touch-only (no-op on pointer devices). Implemented
 * as inline JS for the same reasons as [installGlobalModalFocusTrap]: the indicator is driven
 * by direct transform writes per touch frame (no React re-render), which is what keeps the pull
 * smooth, and the DOM here (createElement / closest / scrollTop) is trivial.
 *
 * The chat message list is excluded: it has its own "load older messages" pagination at the
 * top, so a pull there must not hijack into a full reload.
 */
fun installPullToRefresh() {
    js(
        """
        (function() {
            if (window.__ptrInstalled) return;
            window.__ptrInstalled = true;
            if (!('ontouchstart' in window)) return;

            var THRESHOLD = 72;   // px of pull (after resistance) that triggers a refresh
            var MAX = 110;        // visual cap on how far the indicator travels
            var RESIST = 0.5;     // finger-to-indicator ratio, for the rubber-band feel

            var ind = document.createElement('div');
            ind.className = 'ptr-indicator';
            var spin = document.createElement('div');
            spin.className = 'ptr-spinner';
            ind.appendChild(spin);
            document.body.appendChild(ind);

            var startY = 0, dist = 0, pulling = false, refreshing = false, scroller = null;

            function scrollableAncestor(el) {
                while (el && el !== document.body && el !== document.documentElement) {
                    var oy = window.getComputedStyle(el).overflowY;
                    if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight) return el;
                    el = el.parentElement;
                }
                return null;
            }

            function render(d) {
                dist = d;
                var p = Math.min(d, MAX);
                ind.style.transform = 'translateX(-50%) translateY(' + p + 'px)';
                ind.style.opacity = Math.min(1, d / THRESHOLD);
                if (!refreshing) spin.style.transform = 'rotate(' + (d * 2.4) + 'deg)';
                if (d >= THRESHOLD) ind.classList.add('ptr-ready'); else ind.classList.remove('ptr-ready');
            }

            document.addEventListener('touchstart', function(e) {
                if (refreshing || e.touches.length !== 1) { pulling = false; return; }
                if (e.target.closest && e.target.closest('.chat-messages')) { pulling = false; return; }
                scroller = scrollableAncestor(e.target);
                if (scroller && scroller.scrollTop > 0) { pulling = false; return; }
                startY = e.touches[0].clientY;
                pulling = true;
                ind.style.transition = 'none';
            }, { passive: true });

            document.addEventListener('touchmove', function(e) {
                if (!pulling || refreshing) return;
                if (scroller && scroller.scrollTop > 0) { pulling = false; if (dist) render(0); return; }
                var dy = e.touches[0].clientY - startY;
                if (dy <= 0) { if (dist) render(0); return; }
                // Own the gesture: stop any native overscroll while the spinner follows the finger.
                e.preventDefault();
                render(dy * RESIST);
            }, { passive: false });

            function settle() {
                ind.style.transition = 'transform 0.2s ease, opacity 0.2s ease';
                if (dist >= THRESHOLD) {
                    refreshing = true;
                    spin.style.transform = '';
                    ind.classList.add('ptr-refreshing');
                    ind.style.transform = 'translateX(-50%) translateY(' + THRESHOLD + 'px)';
                    ind.style.opacity = '1';
                    setTimeout(function() { window.location.reload(); }, 150);
                } else {
                    render(0);
                }
            }

            document.addEventListener('touchend', function() {
                if (!pulling || refreshing) return;
                pulling = false;
                settle();
            }, { passive: true });

            document.addEventListener('touchcancel', function() {
                if (!pulling || refreshing) return;
                pulling = false;
                ind.style.transition = 'transform 0.2s ease, opacity 0.2s ease';
                render(0);
            }, { passive: true });
        })();
        """,
    )
}
