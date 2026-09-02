package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraLogCallSiteStructureTest {
    @Test
    fun debugCallSitesPassGenericOperationNames() {
        val callSites = debugCallSites()

        assertTrue("expected AuroraLog.debug call sites in main sources", callSites.isNotEmpty())
        callSites.forEach { callSite ->
            val argument = callSite.operationArgument
            val isLiteral = argument.startsWith("\"")
            val isConstantReference = argument.matches(CONSTANT_REFERENCE)
            assertTrue(
                "operation name must be a string literal or constant reference: $callSite",
                isLiteral || isConstantReference,
            )
        }
    }

    @Test
    fun debugCallSitesDoNotInterpolateErrorsIntoOperationNames() {
        debugCallSites().forEach { callSite ->
            REVEALING_TOKENS.forEach { token ->
                assertTrue(
                    "operation name must not embed error details ($token): $callSite",
                    !callSite.operationArgument.contains(token),
                )
            }
        }
    }

    @Test
    fun debugImplementationCannotThrow() {
        val source = mainSourceRoot()
            .resolve("org/aurora/protocol/android/core/AuroraLog.kt")
            .readText()

        assertTrue(
            "AuroraLog.debug must swallow platform logging failures so call sites never throw",
            source.contains("try {") && source.contains("catch (_: Exception)"),
        )
    }

    private fun debugCallSites(): List<CallSite> = mainSourceRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val marker = line.indexOf("AuroraLog.debug(")
                if (marker < 0) {
                    null
                } else {
                    val arguments = line.substring(marker + "AuroraLog.debug(".length)
                    CallSite(
                        location = "${file.name}:${index + 1}",
                        operationArgument = arguments.substringBefore(",").trim(),
                    )
                }
            }
        }
        .toList()

    private fun mainSourceRoot(): File = listOf("src/main/kotlin", "app/src/main/kotlin")
        .map(::File)
        .firstOrNull(File::isDirectory)
        ?: throw IllegalStateException("main source directory is unavailable")

    private data class CallSite(val location: String, val operationArgument: String) {
        override fun toString(): String = location
    }

    private companion object {
        val CONSTANT_REFERENCE = Regex("[A-Za-z_][A-Za-z0-9_.]*")

        // Throwable details that must never appear inside the operation-name
        // argument: interpolating them can surface endpoints or other runtime
        // values in logs. Plain words inside a literal name (for example
        // "teardown failure reporting") are fine; only interpolation and
        // error-member access are rejected.
        val REVEALING_TOKENS = listOf(
            "\$error", "\${error",
            "\$failure", "\${failure",
            "\$exception", "\${exception",
            "\$throwable", "\${throwable",
            "\$it", "\${it",
            ".message", ".stackTrace", ".toString()",
        )
    }
}
