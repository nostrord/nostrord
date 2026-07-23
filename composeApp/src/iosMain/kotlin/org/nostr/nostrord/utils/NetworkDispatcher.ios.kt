package org.nostr.nostrord.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual val networkClientDispatcher: CoroutineDispatcher = Dispatchers.IO
