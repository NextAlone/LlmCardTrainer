package xyz.nextalone.cardtrainer.util

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Run [block] up to [maxAttempts] times with exponential back-off between
 * attempts. Pass-through on success; rethrows the final exception if every
 * attempt fails.
 *
 * Defaults (3 attempts, 1.5s initial, ×2 factor) give: 1.5s → 3s → fail,
 * roughly 4.5s total added latency in the worst case — acceptable for a
 * single interactive coach request.
 *
 * CancellationException is re-thrown untouched so cooperative cancellation
 * (e.g. composable leaving the composition) does not burn retry attempts.
 */
suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1_500L,
    factor: Double = 2.0,
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable? = null
    var wait = initialDelayMs
    repeat(maxAttempts) { attempt ->
        try {
            return block(attempt)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            lastError = t
            if (attempt == maxAttempts - 1) throw t
            delay(wait)
            wait = (wait * factor).toLong()
        }
    }
    throw lastError ?: IllegalStateException("withRetry exhausted without error")
}
