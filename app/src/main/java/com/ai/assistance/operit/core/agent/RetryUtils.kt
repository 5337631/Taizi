package com.ai.assistance.operit.core.agent

import kotlin.math.min
import kotlin.random.Random

/**
 * Jittered exponential backoff for decorrelated retries.
 * Ported from upstream hermes-agent/agent/retry_utils.py
 *
 * Replaces fixed exponential backoff with jittered delays to prevent
 * thundering-herd retry spikes when multiple sessions hit the same
 * rate-limited provider concurrently.
 */
object RetryUtils {

    private val jitterLock = Any()
    private var jitterCounter = 0L

    /**
     * Compute a jittered exponential backoff delay.
     *
     * @param attempt 1-based retry attempt number
     * @param baseDelay Base delay in seconds for attempt 1 (default 5.0)
     * @param maxDelay Maximum delay cap in seconds (default 120.0)
     * @param jitterRatio Fraction of computed delay to use as random jitter (default 0.5)
     * @return Delay in seconds: min(base * 2^(attempt-1), max_delay) + jitter
     */
    fun jitteredBackoff(
        attempt: Int,
        baseDelay: Double = 5.0,
        maxDelay: Double = 120.0,
        jitterRatio: Double = 0.5
    ): Double {
        synchronized(jitterLock) {
            jitterCounter++
        }
        val tick = jitterCounter

        val exponent = maxOf(0, attempt - 1)
        val delay = if (exponent >= 63 || baseDelay <= 0.0) {
            maxDelay
        } else {
            min(baseDelay * (1L shl exponent).toDouble(), maxDelay)
        }

        // Seed from time + counter for decorrelation
        val seed = (System.nanoTime() xor (tick * 0x9E3779B9L)) and 0xFFFFFFFFL
        val rng = Random(seed.toInt())
        val jitter = rng.nextDouble(0.0, jitterRatio * delay)

        return delay + jitter
    }

    /**
     * Sleep for the jittered backoff duration.
     */
    fun sleepBackoff(
        attempt: Int,
        baseDelay: Double = 5.0,
        maxDelay: Double = 120.0,
        jitterRatio: Double = 0.5
    ) {
        val delaySeconds = jitteredBackoff(attempt, baseDelay, maxDelay, jitterRatio)
        Thread.sleep((delaySeconds * 1000).toLong())
    }
}
