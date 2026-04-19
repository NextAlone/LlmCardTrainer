package xyz.nextalone.cardtrainer.util

/** Wall-clock epoch millis. Both targets (Android / Desktop JVM) back this
 *  with java.lang.System.currentTimeMillis(). */
expect fun nowEpochMs(): Long
