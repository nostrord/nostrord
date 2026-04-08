package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.nostr.nostrord.network.createNip11HttpClient
import org.nostr.nostrord.ui.theme.NostrordColors

private data class YouTubeOEmbed(
    val title: String,
    val authorName: String
)

private suspend fun fetchYouTubeOEmbed(url: String): YouTubeOEmbed? {
    return try {
        val client = createNip11HttpClient()
        val response = client.get("https://www.youtube.com/oembed?url=$url&format=json")
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        YouTubeOEmbed(
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            authorName = obj["author_name"]?.jsonPrimitive?.content ?: ""
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * YouTube link preview card following Amethyst's pattern:
 * thumbnail image, title, author/description, and domain label.
 * Clicking opens the URL externally in the browser.
 */
@Composable
fun YouTubeLinkCard(
    videoId: String,
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
    val context = LocalPlatformContext.current

    var oembed by remember(videoId) { mutableStateOf<YouTubeOEmbed?>(null) }
    LaunchedEffect(videoId) {
        oembed = fetchYouTubeOEmbed(url)
    }

    Column(
        modifier = modifier
            .widthIn(max = 400.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = oembed?.title ?: "YouTube video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            if (oembed?.title?.isNotEmpty() == true) {
                Text(
                    text = oembed!!.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = NostrordColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (oembed?.authorName?.isNotEmpty() == true) {
                Text(
                    text = oembed!!.authorName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NostrordColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Text(
                text = "youtube.com",
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
