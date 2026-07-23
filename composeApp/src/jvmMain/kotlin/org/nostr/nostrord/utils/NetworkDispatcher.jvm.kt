package org.nostr.nostrord.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val networkClientDispatcher: CoroutineDispatcher = Dispatchers.IO
