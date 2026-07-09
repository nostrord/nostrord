package org.nostr.nostrord.utils

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    @Test
    fun secondAcquireForwardsArgvToPrimaryAndBailsOut() {
        val dir = File(System.getProperty("java.io.tmpdir"), "nostrord-si-test-${System.nanoTime()}")
        try {
            val received = ArrayList<String>()
            val latch = CountDownLatch(2)
            val primary =
                SingleInstance.acquireOrForward(emptyArray(), dir) { msg ->
                    synchronized(received) { received.add(msg) }
                    latch.countDown()
                }
            assertTrue(primary, "first caller must own the instance")

            // Same-JVM relock throws OverlappingFileLockException, which lands in the
            // same forward path a real second process takes on a lock miss.
            val uri = "nostrord://open?relay=groups.0xchat.com&group=chachi"
            assertFalse(SingleInstance.acquireOrForward(arrayOf(uri), dir) { }, "second caller must forward and exit")
            // No argv = plain relaunch: forwards an empty focus request.
            assertFalse(SingleInstance.acquireOrForward(emptyArray(), dir) { })

            assertTrue(latch.await(5, TimeUnit.SECONDS), "primary never received the forwarded messages")
            synchronized(received) { assertEquals(listOf(uri, ""), received) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
