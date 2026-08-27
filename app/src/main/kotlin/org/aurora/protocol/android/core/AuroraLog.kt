package org.aurora.protocol.android.core

import android.util.Log

/**
 * Diagnostic logging for failures that are otherwise swallowed to keep the
 * client fail-closed.
 *
 * Output is off unless it is explicitly enabled at runtime:
 *
 * ```
 * adb shell setprop log.tag.Aurora DEBUG
 * ```
 *
 * so a release build stays silent by default. Only the operation and exception
 * type are recorded — exception messages are deliberately omitted because
 * platform and library failures can embed endpoints or other runtime values.
 * Callers must keep passing generic, non-revealing operation names; this exists
 * so a failure class is diagnosable, not so sensitive context is surfaced.
 */
internal object AuroraLog {
    const val TAG = "Aurora"

    /** Records [error] under [operation] when the tag is enabled for DEBUG. */
    fun debug(operation: String, error: Throwable) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "$operation failed: ${error.javaClass.simpleName}")
        }
    }
}
