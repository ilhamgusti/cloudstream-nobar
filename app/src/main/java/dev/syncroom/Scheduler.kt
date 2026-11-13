// Vendored from syncroom@6b4705e (sdk/kotlin) by scripts/sync-syncroom.sh. Do not edit here.
package dev.syncroom

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The SDK is single-threaded: RoomClient and SyncController run everything on
 * one Scheduler (Android: the main looper, so ExoPlayer can be touched
 * directly). Network callbacks are posted onto it. Call the SDK's public
 * methods from the scheduler's thread.
 */
interface Scheduler {
    /** Local time in ms (monotonic, wall-clock based). */
    fun now(): Double
    fun post(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    /** Run blocking work (token fetch) off the scheduler thread. */
    fun io(task: () -> Unit)
}

fun interface Cancellable {
    fun cancel()
}

/** Wall-clock ms that never jumps (wall time at start + monotonic delta). */
object MonotonicClock {
    private val baseWall = System.currentTimeMillis().toDouble()
    private val baseNano = System.nanoTime()
    fun now(): Double = baseWall + (System.nanoTime() - baseNano) / 1e6
}

private val ioPool = Executors.newCachedThreadPool { r -> Thread(r, "syncroom-io").apply { isDaemon = true } }

/** Single dedicated thread — for JVM apps and tests. */
class ExecutorScheduler(
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "syncroom").apply { isDaemon = true } },
) : Scheduler {
    override fun now() = MonotonicClock.now()
    override fun post(task: () -> Unit) {
        exec.execute(guard(task))
    }
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val f = exec.schedule(guard(task), delayMs, TimeUnit.MILLISECONDS)
        return Cancellable { f.cancel(false) }
    }
    override fun io(task: () -> Unit) = ioPool.execute(task)
    fun shutdown() = exec.shutdownNow()
}

/**
 * Adapter for event loops you already have. Android:
 * ```
 * val h = Handler(Looper.getMainLooper())
 * CallbackScheduler(post = { h.post(it) }, postDelayed = { r, ms -> h.postDelayed(r, ms) }, cancel = { h.removeCallbacks(it) })
 * ```
 */
class CallbackScheduler(
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) : Scheduler {
    override fun now() = MonotonicClock.now()
    override fun post(task: () -> Unit) = post(guard(task))
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val r = guard(task)
        postDelayed(r, delayMs)
        return Cancellable { cancel(r) }
    }
    override fun io(task: () -> Unit) = ioPool.execute(task)
}

private fun guard(task: () -> Unit) = Runnable {
    try {
        task()
    } catch (e: Throwable) {
        System.err.println("[syncroom] task failed: $e")
    }
}
