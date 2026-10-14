package com.focusflow.data

import com.focusflow.testutil.StatementLeakAssert
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DatabaseStatementHygieneTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )
        assertIs<DbInitResult.Ready>(
            Database.init(
                dbFile = tempDir.resolve("statement-hygiene.db").toFile(),
                allowRecovery = false
            )
        )
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun f7aRepeatedProcessMonitorCacheReadsDoNotGrowTheStatementRegistry() {
        StatementLeakAssert.assertStable(repetitions = 1_000) {
            Database.getEnabledBlockProcesses()
            Database.isKeywordBlockerEnabled()
            Database.getBlockedKeywords()
            Database.getEnabledNetworkCutoffRules()
            Database.getBlockRules()
        }
    }

    @Test
    fun f7bSourceScannerBalancesCallsAndIgnoresCommentsAndStrings() {
        val sample = """
            val example = "createStatement() inside a string"
            // createStatement() inside a line comment
            connection.createStatement(
                /* nested /* createStatement() */ comment with ) */
            ).use { }
            connection.createStatement(/* this ) is only a comment */).executeQuery(")")
        """.trimIndent()

        assertEquals(listOf(6), unwrappedCreateStatementLines(sample))
    }

    @Test
    fun f7bDatabaseSourceHasNoUnscopedCreateStatementCalls() {
        val sourcePath = Path.of("src/main/kotlin/com/focusflow/data/Database.kt")
        val source = sourcePath.toFile().readText()
        val unwrappedLines = unwrappedCreateStatementLines(source)

        assertTrue(
            unwrappedLines.isEmpty(),
            "Unwrapped createStatement() calls at Database.kt lines: $unwrappedLines"
        )
    }

    @Test
    fun f7cDashboardAndStatsReadsLeaveTheStatementRegistryUnchanged() {
        val today = LocalDate.now()
        val weekStart = today.minusDays(6)

        StatementLeakAssert.assertStable(repetitions = 1) {
            // Dashboard reads
            Database.getDailyAllowances()
            Database.getTasksForDate(today)
            Database.getCurrentStreak()
            Database.getTotalFocusMinutesToday()
            Database.getSetting("daily_focus_goal")
            Database.getSetting("user_name")
            Database.getTemptationsInRange(today.toString(), today.toString())
            Database.getSetting("last_seen_version")

            // Stats reads
            Database.getFocusMinutesByDay(7)
            Database.getTasksInRange(weekStart, today)
            Database.getTemptationLog(7)
            Database.getSessionsInDateRange(weekStart, today)
            Database.getAllTimeFocusMinutes()
            Database.getAllTimeFocusSessions()
            Database.getBestStreak()
            Database.getRecentDayCompletions(84)
            Database.getTasks()
            Database.getRecentSessions(9_999)
            Database.getTasksForDate(today)
            Database.getSessionsInDateRange(today, today)
            Database.getTotalFocusMinutesToday()
            Database.getTemptationLog(1)
            Database.getCurrentStreak()
            Database.getSetting("daily_focus_goal")
        }
    }

    private fun unwrappedCreateStatementLines(source: String): List<Int> {
        val code = maskCommentsAndStrings(source)
        val callPattern = Regex("""\bcreateStatement\s*\(""")

        return callPattern.findAll(code).mapNotNull { call ->
            val openIndex = call.range.last
            val closeIndex = findMatchingParen(code, openIndex)
                ?: error("Unbalanced createStatement() call starting at source offset $openIndex")
            var next = closeIndex + 1
            while (next < code.length && code[next].isWhitespace()) next++
            val useCallFollows = code.startsWith(".use", next) &&
                (next + 4 == code.length || !code[next + 4].isJavaIdentifierPart())
            if (useCallFollows) {
                null
            } else {
                source.take(call.range.first).count { it == '\n' } + 1
            }
        }.toList()
    }

    private fun findMatchingParen(source: String, openIndex: Int): Int? {
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun maskCommentsAndStrings(source: String): String {
        val masked = source.toCharArray()
        fun blank(start: Int, endExclusive: Int) {
            for (index in start until endExclusive) {
                if (masked[index] != '\n' && masked[index] != '\r') masked[index] = ' '
            }
        }

        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    blank(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val start = index
                    var depth = 1
                    index += 2
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> {
                                depth++
                                index += 2
                            }
                            source.startsWith("*/", index) -> {
                                depth--
                                index += 2
                            }
                            else -> index++
                        }
                    }
                    blank(start, index)
                }
                source.startsWith("\"\"\"", index) -> {
                    val start = index
                    val closing = source.indexOf("\"\"\"", index + 3)
                    index = if (closing < 0) source.length else closing + 3
                    blank(start, index)
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val start = index
                    val quote = source[index]
                    index++
                    while (index < source.length) {
                        if (source[index] == '\\') {
                            index = (index + 2).coerceAtMost(source.length)
                        } else if (source[index++] == quote) {
                            break
                        }
                    }
                    blank(start, index)
                }
                else -> index++
            }
        }
        return String(masked)
    }
}
