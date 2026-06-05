package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * In-chat search bar shown under the group header when search is active. Pure prop-driven:
 * a text field, a "current / total" match counter, previous / next match arrows, and a close
 * button. Match computation, navigation and highlighting live in the parent screen.
 *
 * Sizing mirrors the web `.chat-search-bar`: a compact 36dp input (not a Material OutlinedTextField,
 * whose ~56dp min height + floating-label space made the desktop bar noticeably taller than the web)
 * with 40dp icon buttons, so both platforms read at the same height.
 */
@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    // 1-based position of the current match (0 when there are no matches).
    currentPosition: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // "Search older messages" affordance: shown whenever there's a query and the relay still has
    // older history to page through (regardless of how many matches the loaded window already
    // holds). onSearchOlder paginates on demand until history is exhausted or the user cancels;
    // while running, isSearchingOlder shows a cancellable progress row.
    canSearchOlder: Boolean = false,
    isSearchingOlder: Boolean = false,
    onSearchOlder: () -> Unit = {},
    onCancelSearchOlder: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var inputFocused by remember { mutableStateOf(false) }

    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .background(NostrordColors.BackgroundDark),
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier =
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .focusRequester(focusRequester)
                    // Esc closes the search (web parity: its onKeyDown handles Escape).
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusChanged { inputFocused = it.isFocused }
                    .background(NostrordColors.Surface, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (inputFocused) NostrordColors.Primary else NostrordColors.SurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = NostrordColors.TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(NostrordColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text("Search messages", color = NostrordColors.TextMuted, fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                },
            )

            val counter =
                when {
                    query.trim().length < 2 -> ""
                    matchCount == 0 -> "No matches"
                    else -> "$currentPosition / $matchCount"
                }
            if (counter.isNotEmpty()) {
                Text(
                    text = counter,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            IconButton(
                onClick = onPrev,
                enabled = matchCount > 0,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Previous match",
                    tint = if (matchCount > 0) NostrordColors.TextSecondary else NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onNext,
                enabled = matchCount > 0,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Next match",
                    tint = if (matchCount > 0) NostrordColors.TextSecondary else NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close search",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Second row: search the not-yet-loaded history on demand (or a cancellable progress line).
        // Mirrors web .chat-search-older: 13sp text, the primary action right-aligned (the web btn
        // uses margin-left:auto). A clickable Text, not a Material TextButton, keeps it compact.
        if (isSearchingOlder) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = NostrordColors.Primary,
                )
                Text(
                    text = "Searching older messages",
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Cancel",
                    color = NostrordColors.Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                    Modifier
                        .clickable(onClick = onCancelSearchOlder)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        } else if (canSearchOlder) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search older messages",
                    color = NostrordColors.Primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                    Modifier
                        .clickable(onClick = onSearchOlder)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}
