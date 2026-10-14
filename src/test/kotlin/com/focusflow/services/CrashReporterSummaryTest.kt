package com.focusflow.services

import com.focusflow.testutil.FakeTelemetrySink
import com.focusflow.testutil.TempHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterSummaryTest {
    @Test
    fun summaryIsRootCauseFirstAndIncludesOnlyThreeDeepestFrames() {
        val root = IllegalArgumentException("deepest F3 root marker").apply {
            stackTrace = Array(60) { index ->
                StackTraceElement("example.Root", "rootFrame$index", "Root.kt", index + 1)
            }
        }
        val middle = IllegalStateException("middle wrapper", root)
        val outer = RuntimeException("outer wrapper", middle).apply {
            stackTrace = arrayOf(
                StackTraceElement("example.Outer", "outerFrame", "Outer.kt", 1)
            )
        }

        val summary = CrashReporter.rootCauseSummary(outer, maxChars = 1200)

        assertTrue(summary.length <= 1200)
        assertTrue(summary.startsWith("Root cause: java.lang.IllegalArgumentException"))
        assertTrue(summary.contains("deepest F3 root marker"))
        assertTrue(summary.contains("rootFrame0"))
        assertTrue(summary.contains("rootFrame2"))
        assertFalse(summary.contains("rootFrame3"))
        assertFalse(summary.contains("outerFrame"))
    }

    @Test
    fun selfReferentialCauseDoesNotLoop() {
        val summary = CrashReporter.rootCauseSummary(SelfCausedThrowable(), maxChars = 500)

        assertTrue(summary.length <= 500)
        assertTrue(summary.contains("self-cause marker"))
        assertTrue(summary.contains("[cause cycle omitted]"))
    }

    @Test
    fun criticalAndFatalPayloadsRemainValidJsonForDiagnosticText() {
        TempHome.withHome {
            assertTrue(TelemetryConsent.save(TelemetryConsentState.GRANTED))
            val fake = FakeTelemetrySink(expectedPosts = 2)
            val previousSink = CrashReporter.telemetrySinkOverrideForTest
            try {
                CrashReporter.telemetrySinkOverrideForTest = fake
                val source = """source "quoted" \server\path
second line café"""
                val message = """diagnostic "quote" \server\db
second line café"""
                val failure = IllegalStateException(message)

                CrashReporter.reportCritical(source, message, failure)
                CrashReporter.sendToDiscord(failure, source)

                assertTrue(fake.awaitPosts(), "Both reports should reach the no-network fake")
                val payloads = fake.payloads()
                assertEquals(2, payloads.size)
                payloads.forEach { payload ->
                    assertTrue(JsonSyntax.isValid(payload), "Invalid telemetry JSON: $payload")
                    assertTrue(payload.contains("café"))
                    assertFalse(payload.contains("Stack Trace"))
                }
            } finally {
                CrashReporter.telemetrySinkOverrideForTest = previousSink
            }
        }
    }

    private class SelfCausedThrowable : Throwable("self-cause marker") {
        override val cause: Throwable
            get() = this
    }

    private object JsonSyntax {
        fun isValid(input: String): Boolean = try {
            Parser(input).parseDocument()
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        private class Parser(private val input: String) {
            private var index = 0

            fun parseDocument() {
                parseValue()
                skipWhitespace()
                require(index == input.length)
            }

            private fun parseValue() {
                skipWhitespace()
                require(index < input.length)
                when (input[index]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> parseLiteral("true")
                    'f' -> parseLiteral("false")
                    'n' -> parseLiteral("null")
                    else -> parseNumber()
                }
            }

            private fun parseObject() {
                expect('{')
                skipWhitespace()
                if (consume('}')) return
                do {
                    skipWhitespace()
                    parseString()
                    skipWhitespace()
                    expect(':')
                    parseValue()
                    skipWhitespace()
                } while (consume(','))
                expect('}')
            }

            private fun parseArray() {
                expect('[')
                skipWhitespace()
                if (consume(']')) return
                do {
                    parseValue()
                    skipWhitespace()
                } while (consume(','))
                expect(']')
            }

            private fun parseString() {
                expect('"')
                while (index < input.length) {
                    val character = input[index++]
                    when {
                        character == '"' -> return
                        character == '\\' -> parseEscape()
                        character.code < 0x20 -> throw IllegalArgumentException("control character")
                    }
                }
                throw IllegalArgumentException("unterminated string")
            }

            private fun parseEscape() {
                require(index < input.length)
                when (input[index++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        require(index < input.length && input[index].digitToIntOrNull(16) != null)
                        index++
                    }
                    else -> throw IllegalArgumentException("invalid escape")
                }
            }

            private fun parseNumber() {
                consume('-')
                if (!consume('0')) {
                    requireDigits()
                }
                if (consume('.')) requireDigits()
                if (consume('e') || consume('E')) {
                    if (!consume('+')) consume('-')
                    requireDigits()
                }
            }

            private fun requireDigits() {
                val start = index
                while (index < input.length && input[index].isDigit()) index++
                require(index > start)
            }

            private fun parseLiteral(literal: String) {
                require(input.regionMatches(index, literal, 0, literal.length))
                index += literal.length
            }

            private fun expect(character: Char) {
                require(consume(character))
            }

            private fun consume(character: Char): Boolean {
                if (index >= input.length || input[index] != character) return false
                index++
                return true
            }

            private fun skipWhitespace() {
                while (index < input.length && input[index] in charArrayOf(' ', '\n', '\r', '\t')) {
                    index++
                }
            }
        }
    }
}
