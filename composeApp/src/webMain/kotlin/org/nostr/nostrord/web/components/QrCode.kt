package org.nostr.nostrord.web.components

/** qrcode-generator (CommonJS `module.exports = qrcode`): builder with a pure-JS GIF data URL. */
@JsModule("qrcode-generator")
@JsNonModule
external fun qrcode(typeNumber: Int, errorCorrectionLevel: String): QrGen

external interface QrGen {
    fun addData(data: String)

    fun make()

    /** Returns a `data:image/gif;base64,…` URL (no canvas required). */
    fun createDataURL(cellSize: Int, margin: Int): String
}

/** Encode [text] into a QR-code data URL suitable for an `<img src>`. */
fun qrDataUrl(text: String): String {
    val qr = qrcode(0, "M")
    qr.addData(text)
    qr.make()
    return qr.createDataURL(6, 8)
}
