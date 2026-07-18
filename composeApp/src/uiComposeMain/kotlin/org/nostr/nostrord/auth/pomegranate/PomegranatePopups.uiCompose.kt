package org.nostr.nostrord.auth.pomegranate

/**
 * Native (android / jvm / ios) pomegranate popups: a WebView the Compose UI hosts, driven
 * through [PomegranateAuthBridge]. Available on every Compose target; the actual WebView is
 * per-platform (see PomegranateAuthWebView). Web keeps its own window.open actual in jsMain.
 */
internal actual object PomegranatePopups {
    actual val isAvailable: Boolean = true

    actual suspend fun awaitTokenFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = PomegranateAuthBridge.await(url, expectedOrigin, PomegranateAuthBridge.Mode.Token)

    actual suspend fun awaitShardFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = PomegranateAuthBridge.await(url, expectedOrigin, PomegranateAuthBridge.Mode.Shard)
}

/** JS expression that pulls the token/shard out of a postMessage payload `d`. */
internal fun pomegranateExtractExpr(mode: PomegranateAuthBridge.Mode): String = when (mode) {
    PomegranateAuthBridge.Mode.Token -> "(d && d.token) ? d.token : ''"
    PomegranateAuthBridge.Mode.Shard -> "(typeof d === 'string') ? d : ''"
}

/**
 * JS glued into every page the auth WebView loads: shims `window.opener` (absent in a native
 * WebView) and an origin-checked `message` listener so the central/operator page's
 * `postMessage` reaches native. Each platform prepends its own `window.__nostrordReceive`
 * before this. Token pages post `{ token }`; recovery pages post a raw shard string.
 */
internal fun pomegranateOpenerInjection(
    mode: PomegranateAuthBridge.Mode,
    expectedOrigin: String,
): String {
    val extract = pomegranateExtractExpr(mode)
    return """
        (function() {
          function fwd(d) { try { var v = $extract; if (v) window.__nostrordReceive(v); } catch (e) {} }
          window.opener = { postMessage: function(data, origin) { fwd(data); } };
          window.addEventListener('message', function(e) { if (e.origin === '$expectedOrigin') fwd(e.data); });
        })();
    """.trimIndent()
}

/**
 * Desktop-Chrome user agent for the auth WebView. Google refuses OAuth from a user agent it
 * recognizes as an embedded webview, so present as a normal browser. Best-effort: Google may
 * still block on other signals, which is why this path is device-validated.
 */
internal const val POMEGRANATE_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * The loopback opener page served in the user's real browser (desktop and Android): a gesture
 * button does window.open(centralUrl), so the central page's native window.opener.postMessage
 * works, then the captured token/shard is POSTed to the single-use [callbackPath].
 *
 * On success it closes the popup and shows a persistent confirmation. To return to the app it
 * tries [returnIntentUrl] (Chrome `intent://` form, honored more readily) then [returnDeepLink]
 * (plain custom scheme) automatically, but Android blocks custom-scheme navigation without a user
 * gesture, so it also renders a "Return to Nostrord" button whose click always works. Desktop
 * passes both null and self-closes the tab instead. Shared verbatim so the origin/source
 * validation and callback logic never fork; only the transport and return params differ.
 */
internal fun pomegranateOpenerPageHtml(
    centralUrl: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    callbackPath: String,
    returnDeepLink: String?,
    returnIntentUrl: String?,
): String {
    val extract = pomegranateExtractExpr(mode)
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Sign in with Google</title>
        <style>
          * { box-sizing: border-box; }
          body { font-family: system-ui, -apple-system, sans-serif; background: #0b0b0f; color: #e8e8ee;
                 display: flex; min-height: 100vh; margin: 0; padding: 20px;
                 align-items: center; justify-content: center; }
          .box { width: 100%; max-width: 420px; text-align: center; }
          h2 { font-size: 22px; margin: 0 0 12px; }
          p { color: #9a9aa6; font-size: 16px; line-height: 1.5; margin: 12px 0; }
          button { display: block; width: 100%; min-height: 48px; font-size: 16px; padding: 12px 20px;
                   border-radius: 10px; border: 0; background: #6d5efc; color: #fff; cursor: pointer; }
        </style>
        </head>
        <body>
          <div class="box">
            <h2>Sign in with Google</h2>
            <p id="status">Click below to continue. A Google window will open.</p>
            <button id="go">Continue with Google</button>
          </div>
          <script>
            var expected = "$expectedOrigin";
            var target = "$centralUrl";
            var cb = "$callbackPath";
            var deepUrl = ${jsString(returnDeepLink)};
            var intentUrl = ${jsString(returnIntentUrl)};
            var popup = null;
            var done = false;
            function extract(d) { return $extract; }
            function closePopup() { if (popup) { try { popup.close(); } catch (e) {} } }
            function goApp() {
              if (intentUrl) { try { window.location.href = intentUrl; return; } catch (e) {} }
              if (deepUrl) { try { window.location.href = deepUrl; } catch (e) {} }
            }
            function showSuccess() {
              var html = '<div class="box"><h2>Signed in.</h2>';
              if (deepUrl || intentUrl) { html += '<p><button id="ret">Return to Nostrord</button></p>'; }
              html += '<p>You can close this tab.</p></div>';
              document.body.innerHTML = html;
              var r = document.getElementById('ret');
              if (r) r.onclick = goApp;
            }
            function finish(v) {
              if (done) return; done = true;
              fetch(cb, { method: 'POST', body: v }).then(function() {
                closePopup();
                showSuccess();
                // Chromium self-closes an externally-opened tab (desktop); Android returns via the
                // deep link. Auto-attempt is best-effort (Android may block it without a gesture),
                // so the Return button above is the reliable path.
                try { window.close(); } catch (e) {}
                if (deepUrl || intentUrl) { setTimeout(goApp, 50); }
              }).catch(function() {
                closePopup();
                document.getElementById('status').textContent = 'Something went wrong. Please close this tab and try again.';
              });
            }
            window.addEventListener('message', function(e) {
              if (e.origin !== expected) return;
              if (popup && e.source !== popup) return;
              var v = extract(e.data);
              if (v) finish(v);
            });
            document.getElementById('go').onclick = function() {
              popup = window.open(target, 'nostrordGoogle');
              document.getElementById('status').textContent = 'Finish signing in in the Google window.';
            };
          </script>
        </body>
        </html>
    """.trimIndent()
}

/** Emits a JS string literal for [value], or the token `null` when it is null. */
private fun jsString(value: String?): String = if (value == null) "null" else "\"$value\""

/** Single-use random loopback callback path, e.g. `/cb/ab12...`, from 16 secure-random bytes. */
internal fun pomegranateCallbackPath(randomBytes: ByteArray): String = "/cb/" + randomBytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
