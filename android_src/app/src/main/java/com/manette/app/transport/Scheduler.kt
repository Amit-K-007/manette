package com.manette.app.transport

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A monotonic millisecond clock. (Named to avoid confusion with android.os.SystemClock.) */
interface MonotonicClock {
    fun nowMs(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/**
 * Runs the transport's timed work on ONE thread, which keeps sending simple (one writer, no races) and lets tests
 * replace real time with a manual scheduler.
 */
interface Scheduler {
    /** Run as soon as possible on the scheduler thread. */
    fun post(task: Runnable)

    fun after(delayMs: Long, task: Runnable)

    /** Run repeatedly every [periodMs] until [shutdown]. */
    fun every(periodMs: Long, task: Runnable)

    fun shutdown()
}

class ExecutorScheduler(threadName: String = "Manette transport") : Scheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, threadName).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1   // input latency matters more than most background work
        }
    }.apply { removeOnCancelPolicy = true }

    // A scheduled executor silently cancels a periodic task that throws once. Never let that happen.
    private fun safe(task: Runnable) = Runnable {
        try {
            task.run()
        } catch (_: Throwable) {
        }
    }

    override fun post(task: Runnable) {
        if (!executor.isShutdown) executor.execute(safe(task))
    }

    override fun after(delayMs: Long, task: Runnable) {
        if (!executor.isShutdown) executor.schedule(safe(task), delayMs, TimeUnit.MILLISECONDS)
    }

    override fun every(periodMs: Long, task: Runnable) {
        if (!executor.isShutdown) executor.scheduleAtFixedRate(safe(task), periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
