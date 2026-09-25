package com.unifiedledger.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MUST FIX B: JVM evidence for the process-wide stable-storage open lock mechanism. The production
 * [openAndroidStableStorageLedger] wraps its whole sequence in [withAndroidStableStorageOpenLock];
 * this pins the mutual-exclusion property that prevents a rotation-time NonCancellable open from
 * racing the recreated composition's open (two concurrent copies/opens of the same generation).
 *
 * The test is deterministic: the holder signals it is inside, the contender signals it is about to
 * call, the test waits a bounded settle window, then releases the holder. Without the lock the
 * contender would enter while the holder is inside and [maxObservedConcurrency] would reach 2.
 */
class AndroidStableStorageOpenLockTest {
    @Test
    fun theOpenLockSerializesConcurrentEntrants() {
        val holderInside = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val contenderReady = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val completed = CountDownLatch(2)

        fun enter() {
            val now = active.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, now) }
        }

        val holder =
            Thread {
                withAndroidStableStorageOpenLock {
                    enter()
                    holderInside.countDown()
                    releaseHolder.await(30, TimeUnit.SECONDS)
                    active.decrementAndGet()
                }
                completed.countDown()
            }
        val contender =
            Thread {
                contenderReady.countDown()
                withAndroidStableStorageOpenLock {
                    enter()
                    active.decrementAndGet()
                }
                completed.countDown()
            }

        holder.start()
        assertTrue(holderInside.await(30, TimeUnit.SECONDS), "the holder did not enter the lock")
        contender.start()
        assertTrue(contenderReady.await(30, TimeUnit.SECONDS), "the contender did not start")
        // Bounded settle: give the contender time to enter if the lock were absent. With the lock
        // it is blocked, so concurrency stays 1.
        Thread.sleep(500)
        assertEquals(1, maxObservedConcurrency.get(), "the open lock must serialize entrants")

        releaseHolder.countDown()
        assertTrue(completed.await(30, TimeUnit.SECONDS), "both entrants did not complete")
        assertEquals(1, maxObservedConcurrency.get(), "concurrency must never exceed one")
    }
}
