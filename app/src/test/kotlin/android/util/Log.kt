package android.util

/**
 * Unit-test shadow for the android.jar stub, whose methods all throw
 * "not mocked" RuntimeExceptions. Returning defaults lets JVM tests exercise
 * production paths that call [AuroraLog.debug]; diagnostic logging stays off.
 * Only [isLoggable] and [d] are shadowed here — every other Log method still
 * throws "not mocked" if a test path calls it.
 */
object Log {
    const val DEBUG = 3

    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = false

    @JvmStatic
    fun d(tag: String?, msg: String?): Int = 0
}
