package org.nostr.nostrord.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    quietZone: Dp = 12.dp,
    lightColor: Color = Color.White,
) {
    Box(
        modifier = modifier
            .background(lightColor, RoundedCornerShape(8.dp))
            .padding(quietZone)
    ) {
        Image(
            painter = rememberQrCodePainter(data),
            contentDescription = "QR Code",
            modifier = Modifier.size(size)
        )
    }
}
