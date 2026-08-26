package com.focusflow.data

import com.focusflow.data.models.*
import java.util.UUID
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Database {

    private val dtFmt   = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    private lateinit var connection: Connection

    /** True once the DB has been opened and migrated successfully. */
    val isReady: Boolean get() = ::connection.isInitialized

    /**
     * Stores the exception from the most recent failed tryOpenAndMigrate() call so
     * init() can distinguish SQLITE_BUSY (another instance running) from real corruption.
     */
    @Volatile private var lastOpenFailure: Exception? = null

    fun init() {
        val dbDir  = java.io.File(System.getProperty("user.home") + "/.focusflow")
        val dbFile = java.io.File(dbDir, "focusflow.db")
        dbDir.mkdirs()

        // First attempt — open existing DB
        if (!tryOpenAndMigrate(dbFile)) {
            // SQLITE_BUSY means another FocusFlow instance already has the file open.
            // The database is valid — do NOT back it up or delete it. Log and return;
            // the app runs with empty/default settings until the other instance exits.
            val failure = lastOpenFailure
            if (failure is org.sqlite.SQLiteException &&
                failure.resultCode == org.sqlite.SQLiteErrorCode.SQLITE_BUSY) {
                java.io.File(System.getProperty("user.home") + "/.focusflow/crash.log")
                    .also { it.parentFile?.mkdirs() }
                    .appendText("[${java.time.LocalDateTime.now()}] DB locked (SQLITE_BUSY) — another FocusFlow instance may be running. Starting with empty/default settings.\n\n")
                return
            }
            // Any other failure (corruption, I/O error) — back up and start fresh
            safeBackupBrokenDb(dbDir, dbFile)
            // Second attempt — fresh DB
            if (!tryOpenAndMigrate(dbFile)) {
                // Absolute last resort: delete everything and create blank
                dbFile.delete()
                tryOpenAndMigrate(dbFile)
            }
        }
    }

    private fun tryOpenAndMigrate(dbFile: java.io.File): Boolean {
        var localConn: java.sql.Connection? = null
        return try {
            // Set busy_timeout at the driver level via SQLiteConfig so the handler
            // is registered before ANY statement executes — including journal_mode=WAL
            // which requires an exclusive lock during a rollback→WAL transition.
            // Setting it via PRAGMA on a live connection does NOT register the handler
            // through the sqlite-jdbc driver; the default handler fires SQLITE_BUSY
            // immediately, making the PRAGMA statement-based approach ineffective for
            // the very first contended operation.
            val config = org.sqlite.SQLiteConfig()
            config.setBusyTimeout(10_000)   // 10 s — enough for a prior JVM to release
            val ds = SQLiteDataSource(config)
            ds.url = "jdbc:sqlite:${dbFile.absolutePath}"
            localConn = ds.connection
            localConn.autoCommit = true

            localConn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            // In WAL mode, synchronous=NORMAL is crash-safe (no DB corruption risk) and
            // avoids the per-commit fsync of the default FULL mode. Data is pushed to the
            // OS page cache instantly; the OS batches the physical flush asynchronously.
            // This eliminates micro-stutters on every temptation log / session tick write.
            localConn.createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }

            // Checkpoint & truncate any leftover WAL files from a previous crash/uninstall.
            // Best-effort only: wal_checkpoint(TRUNCATE) returns SQLITE_BUSY immediately
            // (bypassing the busy timeout) when another connection has an open read
            // transaction in WAL mode. Swallow the error — the WAL will self-checkpoint
            // on the next successful launch or when the blocking connection closes.
            try {
                localConn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            } catch (_: Exception) { /* best-effort — see comment above */ }

            // Integrity check — catches bit-flipped or half-written databases
            val integrity = localConn.createStatement()
                .executeQuery("PRAGMA quick_check")
                .use { rs -> if (rs.next()) rs.getString(1) else "error" }
            if (integrity != "ok") {
                localConn.close()
                localConn = null
                return false
            }

            connection = localConn
            migrate()
            true
        } catch (e: Exception) {
            lastOpenFailure = e
            // Close any connection opened during this attempt to prevent a leak.
            // If migrate() threw after `connection = localConn`, closing here is correct —
            // the caller (init()) will retry and reassign the field to a fresh connection.
            try { localConn?.close() } catch (_: Exception) {}
            val logFile = java.io.File(
                System.getProperty("user.home") + "/.focusflow/crash.log"
            )
            logFile.parentFile?.mkdirs()
            logFile.appendText(
                "[${java.time.LocalDateTime.now()}] DB open failed: ${e.message}\n${e.stackTraceToString()}\n\n"
            )
            // DB open failure means the app runs with no user settings / block rules.
            // This is critical: all enforcement may be inactive. Alert Discord so we
            // know which users are affected and can investigate schema corruption.
            com.focusflow.services.CrashReporter.reportCritical(
                source    = "Database.open",
                message   = "Database failed to open — app will run with default/empty settings. All enforcement rules may be inactive.\n**Cause:** ${e.message}",
                throwable = e
            )
            false
        }
    }

    private fun safeBackupBrokenDb(dbDir: java.io.File, dbFile: java.io.File) {
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        listOf(dbFile, java.io.File(dbDir, "focusflow.db-shm"),
               java.io.File(dbDir, "focusflow.db-wal")).forEach { f ->
            if (f.exists()) {
                val backup = java.io.File(dbDir, "${f.name}.broken_$ts")
                // Best-effort per-file: if the auxiliary -shm or -wal file is still
                // locked by the prior process, skip it rather than propagating the
                // IOException and aborting the entire backup/recovery sequence.
                try {
                    f.copyTo(backup, overwrite = true)
                    f.delete()
                } catch (_: Exception) { /* locked by prior instance — skip */ }
            }
        }
        val logFile = java.io.File(dbDir, "crash.log")
        logFile.appendText(
            "[${java.time.LocalDateTime.now()}] Corrupt DB backed up as focusflow.db.broken_$ts — starting fresh.\n\n"
        )
    }

    // ── Versioned schema migration ─────────────────────────────────────────────
    //
    // PRAGMA user_version tracks which migrations have been applied.
    // Every new schema change gets its own numbered migrate_vN() function.
    // Never edit an existing migrate_vN() — add a new one and bump TARGET_VERSION.
    //
    private val TARGET_VERSION = 6

    private fun migrate() {
        val current = connection.createStatement()
            .executeQuery("PRAGMA user_version")
            .use { rs -> if (rs.next()) rs.getInt(1) else 0 }

        if (current >= TARGET_VERSION) return

        // Wrap all migration steps in a single transaction — if any step fails
        // mid-way the schema is rolled back to the pre-migration state and
        // user_version is NOT bumped, so the next launch retries cleanly.
        connection.autoCommit = false
        try {
            if (current < 1) migrateV1()
            if (current < 2) migrateV2()
            if (current < 3) migrateV3()
            if (current < 4) migrateV4()
            if (current < 5) migrateV5()
            if (current < 6) migrateV6()

            // Bump stored version only after ALL steps succeed
            connection.createStatement()
                .executeUpdate("PRAGMA user_version = $TARGET_VERSION")
            connection.commit()
        } catch (e: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            val logFile = java.io.File(
                System.getProperty("user.home") + "/.focusflow/crash.log"
            )
            logFile.parentFile?.mkdirs()
            logFile.appendText(
                "[${java.time.LocalDateTime.now()}] Migration failed at schema v$current: ${e.message}\n${e.stackTraceToString()}\n\n"
            )
            throw e  // Propagate so init() triggers recovery flow
        } finally {
            connection.autoCommit = true
        }
    }

    // v1 — full baseline schema (all original tables + additive column guards + indexes)
    // Safe on existing DBs: CREATE TABLE IF NOT EXISTS leaves existing data intact.
    private fun migrateV1() {
        connection.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT DEFAULT '',
                    duration_minutes INTEGER DEFAULT 25,
                    scheduled_date TEXT,
                    scheduled_time TEXT,
                    completed INTEGER DEFAULT 0,
                    skipped INTEGER DEFAULT 0,
                    recurring INTEGER DEFAULT 0,
                    recurring_type TEXT,
                    priority TEXT DEFAULT 'medium',
                    tags TEXT DEFAULT '',
                    created_at TEXT NOT NULL,
                    completed_at TEXT,
                    focus_mode INTEGER DEFAULT 0,
                    focus_intensity TEXT DEFAULT 'standard'
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS focus_sessions (
                    id TEXT PRIMARY KEY,
                    task_id TEXT,
                    task_name TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT,
                    planned_minutes INTEGER NOT NULL,
                    actual_minutes INTEGER DEFAULT 0,
                    completed INTEGER DEFAULT 0,
                    interrupted INTEGER DEFAULT 0,
                    notes TEXT DEFAULT ''
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS block_rules (
                    id TEXT PRIMARY KEY,
                    process_name TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    enabled INTEGER DEFAULT 1,
                    block_network INTEGER DEFAULT 0
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS block_schedules (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    days_of_week TEXT NOT NULL,
                    start_hour INTEGER NOT NULL,
                    start_minute INTEGER NOT NULL,
                    end_hour INTEGER NOT NULL,
                    end_minute INTEGER NOT NULL,
                    enabled INTEGER DEFAULT 1,
                    process_names TEXT DEFAULT ''
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_allowances (
                    process_name TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    allowance_minutes INTEGER NOT NULL
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_notes (
                    date TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    mood INTEGER DEFAULT 3,
                    updated_at TEXT NOT NULL
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS temptation_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    process_name TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    timestamp TEXT NOT NULL
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_completions (
                    date TEXT PRIMARY KEY,
                    completed_count INTEGER DEFAULT 0,
                    total_count INTEGER DEFAULT 0,
                    focus_minutes INTEGER DEFAULT 0
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS habits (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    emoji TEXT DEFAULT '✅',
                    created_at TEXT NOT NULL
                )
            """.trimIndent())

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS habit_entries (
                    habit_id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    done INTEGER DEFAULT 1,
                    PRIMARY KEY (habit_id, date)
                )
            """.trimIndent())

            // Additive column guards — safe on DBs that already have these columns
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN skipped INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN focus_mode INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN focus_intensity TEXT DEFAULT 'standard'") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE daily_completions ADD COLUMN total_count INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE daily_completions ADD COLUMN focus_minutes INTEGER DEFAULT 0") } catch (_: Exception) {}

            // Indexes
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tasks_date ON tasks(scheduled_date)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_start ON focus_sessions(start_time)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_temptation_ts ON temptation_log(timestamp)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_notes_date ON daily_notes(date)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_habit_entries ON habit_entries(habit_id, date)")
        }
    }

    // v2 — onboarding preset tracking
    // Adds block_rule_source column so we can later know which rules came from presets vs manual picks.
    private fun migrateV2() {
        connection.createStatement().use { st ->
            try {
                st.executeUpdate("ALTER TABLE block_rules ADD COLUMN source TEXT DEFAULT 'manual'")
            } catch (_: Exception) {}
        }
    }

    // v3 — per-task focus blocked apps + require-pin flag
    private fun migrateV3() {
        connection.createStatement().use { st ->
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN focus_blocked_apps TEXT DEFAULT ''") } catch (_: Exception) {}
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN focus_require_pin INTEGER DEFAULT 0") } catch (_: Exception) {}
        }
    }

    // v5 — user-created block presets
    private fun migrateV5() {
        connection.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS custom_block_presets (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    emoji TEXT NOT NULL DEFAULT '🚫',
                    process_names TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL
                )
            """.trimIndent())
        }
    }

    // v6 — persist daily allowance usage so a reboot does not reset the counter
    private fun migrateV6() {
        connection.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS daily_usage (
                    date TEXT NOT NULL,
                    process_name TEXT NOT NULL,
                    seconds_used INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (date, process_name)
                )
            """.trimIndent())
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_daily_usage_date ON daily_usage(date)")
        }
    }

    // v4 — network cutoff rules (domain + keyword, with optional per-app targeting)
    private fun migrateV4() {
        connection.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS network_cutoff_rules (
                    id TEXT PRIMARY KEY,
                    pattern TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    target_process TEXT,
                    target_display_name TEXT,
                    enabled INTEGER DEFAULT 1
                )
            """.trimIndent())
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_net_rules_mode ON network_cutoff_rules(mode)")
        }
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    @Synchronized fun getTasks(date: LocalDate? = null): List<Task> {
        val sql = if (date != null)
            "SELECT * FROM tasks WHERE scheduled_date = ? ORDER BY scheduled_time ASC NULLS LAST, created_at DESC"
        else
            "SELECT * FROM tasks ORDER BY created_at DESC"
        return connection.prepareStatement(sql).use { ps ->
            if (date != null) ps.setString(1, date.format(dateFmt))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Task>()
                while (rs.next()) list.add(rowToTask(rs))
                list
            }
        }
    }

    @Synchronized fun getTasksForDate(date: LocalDate): List<Task> {
        return connection.prepareStatement(
            "SELECT * FROM tasks WHERE scheduled_date = ? ORDER BY scheduled_time ASC NULLS LAST"
        ).use { ps ->
            ps.setString(1, date.format(dateFmt))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Task>()
                while (rs.next()) list.add(rowToTask(rs))
                list
            }
        }
    }

    @Synchronized fun getTasksInRange(startDate: LocalDate, endDate: LocalDate): List<Task> {
        return connection.prepareStatement(
            "SELECT * FROM tasks WHERE scheduled_date BETWEEN ? AND ? ORDER BY scheduled_date ASC, scheduled_time ASC NULLS LAST"
        ).use { ps ->
            ps.setString(1, startDate.format(dateFmt))
            ps.setString(2, endDate.format(dateFmt))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Task>()
                while (rs.next()) list.add(rowToTask(rs))
                list
            }
        }
    }

    @Synchronized fun upsertTask(task: Task) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO tasks
            (id, title, description, duration_minutes, scheduled_date, scheduled_time,
             completed, skipped, recurring, recurring_type, priority, tags, created_at, completed_at,
             focus_mode, focus_intensity, focus_blocked_apps, focus_require_pin)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, task.id)
            ps.setString(2, task.title)
            ps.setString(3, task.description)
            ps.setInt(4, task.durationMinutes)
            ps.setString(5, task.scheduledDate?.format(dateFmt))
            ps.setString(6, task.scheduledTime)
            ps.setInt(7, if (task.completed) 1 else 0)
            ps.setInt(8, if (task.skipped) 1 else 0)
            ps.setInt(9, if (task.recurring) 1 else 0)
            ps.setString(10, task.recurringType)
            ps.setString(11, task.priority)
            ps.setString(12, task.tags.joinToString(","))
            ps.setString(13, task.createdAt.format(dtFmt))
            ps.setString(14, task.completedAt?.format(dtFmt))
            ps.setInt(15, if (task.focusMode) 1 else 0)
            ps.setString(16, task.focusIntensity)
            ps.setString(17, task.focusBlockedApps.joinToString(","))
            ps.setInt(18, if (task.focusRequirePin) 1 else 0)
            ps.executeUpdate()
        }
    }

    @Synchronized fun deleteTask(id: String) {
        connection.prepareStatement("DELETE FROM tasks WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    @Synchronized fun completeTask(id: String) {
        val now = LocalDateTime.now().format(dtFmt)
        // Transaction: task update + daily completion counter must succeed together.
        // A crash between the two statements would leave the task marked complete but
        // the daily streak/completion count forever stale.
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "UPDATE tasks SET completed = 1, completed_at = ? WHERE id = ?"
            ).use { ps -> ps.setString(1, now); ps.setString(2, id); ps.executeUpdate() }
            recordDailyCompletion(LocalDate.now())
            connection.commit()
        } catch (e: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized fun skipTask(id: String) {
        connection.prepareStatement("UPDATE tasks SET skipped = 1 WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    private fun recordDailyCompletion(date: LocalDate) {
        val key = date.format(dateFmt)
        connection.prepareStatement("""
            INSERT INTO daily_completions (date, completed_count, total_count) VALUES (?, 1, 1)
            ON CONFLICT(date) DO UPDATE SET completed_count = completed_count + 1,
            total_count = MAX(total_count, completed_count + 1)
        """.trimIndent()).use { ps -> ps.setString(1, key); ps.executeUpdate() }
    }

    @Synchronized fun updateDailyFocusMinutes(date: LocalDate) {
        val key = date.format(dateFmt)
        // Recalculate the daily total from all completed sessions rather than
        // incrementing. Incrementing causes double-counting when insertSession is
        // called more than once for the same session (e.g. on re-save / retry).
        // The `minutes` parameter was removed: it was declared and passed by the
        // caller but silently discarded here, creating a misleading API contract.
        val total = connection.prepareStatement(
            "SELECT COALESCE(SUM(actual_minutes), 0) FROM focus_sessions" +
            " WHERE DATE(start_time) = ? AND completed = 1 AND actual_minutes > 0"
        ).use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        connection.prepareStatement("""
            INSERT INTO daily_completions (date, focus_minutes) VALUES (?, ?)
            ON CONFLICT(date) DO UPDATE SET focus_minutes = excluded.focus_minutes
        """.trimIndent()).use { ps ->
            ps.setString(1, key); ps.setInt(2, total)
            ps.executeUpdate()
        }
    }

    /** Safe backup using SQLite's VACUUM INTO — produces a WAL-free, fully
     *  consistent snapshot even while the DB is actively used. */
    @Synchronized fun vacuumInto(destPath: String) {
        connection.createStatement().use { st ->
            st.execute("VACUUM INTO '${destPath.replace("'", "''")}'")
        }
    }

    @Synchronized fun clearAllTasks() {
        connection.createStatement().executeUpdate("DELETE FROM tasks")
    }

    @Synchronized fun getRecurringTemplates(): List<Task> {
        return connection.prepareStatement(
            "SELECT * FROM tasks WHERE recurring = 1 ORDER BY created_at ASC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Task>()
                while (rs.next()) list.add(rowToTask(rs))
                list
            }
        }
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Synchronized fun insertSession(session: FocusSession) {
        // Transaction: the session row and the daily-focus-minutes update must be atomic.
        // A crash between them leaves the session saved but the daily total permanently stale.
        connection.autoCommit = false
        try {
            connection.prepareStatement("""
                INSERT OR REPLACE INTO focus_sessions
                (id, task_id, task_name, start_time, end_time, planned_minutes,
                 actual_minutes, completed, interrupted, notes)
                VALUES (?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()).use { ps ->
                ps.setString(1, session.id)
                ps.setString(2, session.taskId)
                ps.setString(3, session.taskName)
                ps.setString(4, session.startTime.format(dtFmt))
                ps.setString(5, session.endTime?.format(dtFmt))
                ps.setInt(6, session.plannedMinutes)
                ps.setInt(7, session.actualMinutes)
                ps.setInt(8, if (session.completed) 1 else 0)
                ps.setInt(9, if (session.interrupted) 1 else 0)
                ps.setString(10, session.notes)
                ps.executeUpdate()
            }
            if (session.completed && session.actualMinutes > 0) {
                updateDailyFocusMinutes(session.startTime.toLocalDate())
            }
            connection.commit()
        } catch (e: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized fun getRecentSessions(limit: Int = 50): List<FocusSession> {
        return connection.prepareStatement(
            "SELECT * FROM focus_sessions ORDER BY start_time DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<FocusSession>()
                while (rs.next()) list.add(rowToSession(rs))
                list
            }
        }
    }

    @Synchronized fun getSessionsInDateRange(start: LocalDate, end: LocalDate): List<FocusSession> {
        return connection.prepareStatement(
            "SELECT * FROM focus_sessions WHERE DATE(start_time) BETWEEN ? AND ? ORDER BY start_time DESC"
        ).use { ps ->
            ps.setString(1, start.format(dateFmt)); ps.setString(2, end.format(dateFmt))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<FocusSession>()
                while (rs.next()) list.add(rowToSession(rs))
                list
            }
        }
    }

    @Synchronized fun getTotalFocusMinutesToday(): Int {
        val today = LocalDate.now().format(dateFmt)
        return connection.prepareStatement(
            "SELECT COALESCE(SUM(actual_minutes), 0) FROM focus_sessions WHERE DATE(start_time) = ? AND actual_minutes > 0"
        ).use { ps ->
            ps.setString(1, today)
            ps.executeQuery().use { it.getInt(1) }
        }
    }

    @Synchronized fun getAllTimeFocusMinutes(): Int {
        return connection.createStatement().executeQuery(
            "SELECT COALESCE(SUM(actual_minutes), 0) FROM focus_sessions WHERE completed = 1"
        ).use { if (it.next()) it.getInt(1) else 0 }
    }

    @Synchronized fun getAllTimeFocusSessions(): Int {
        return connection.createStatement().executeQuery(
            "SELECT COUNT(*) FROM focus_sessions WHERE completed = 1"
        ).use { if (it.next()) it.getInt(1) else 0 }
    }

    @Synchronized fun getFocusMinutesByDay(days: Int = 7): List<DayFocusStats> {
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { daysAgo ->
            val date    = today.minusDays(daysAgo.toLong())
            val dateStr = date.format(dateFmt)
            val result  = connection.prepareStatement("""
                SELECT COALESCE(SUM(actual_minutes), 0) AS mins, COUNT(*) AS cnt
                FROM focus_sessions WHERE DATE(start_time) = ? AND completed = 1
            """.trimIndent()).use { ps ->
                ps.setString(1, dateStr)
                ps.executeQuery().use { rs ->
                    if (rs.next()) Pair(rs.getInt("mins"), rs.getInt("cnt")) else Pair(0, 0)
                }
            }
            DayFocusStats(date = date, totalMinutes = result.first, sessionsCount = result.second)
        }
    }

    @Synchronized fun getRecentDayCompletions(days: Int = 84): List<DayCompletionStats> {
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { d ->
            val date    = today.minusDays(d.toLong())
            val dateStr = date.format(dateFmt)
            val row = connection.prepareStatement(
                "SELECT completed_count, total_count, focus_minutes FROM daily_completions WHERE date = ?"
            ).use { ps ->
                ps.setString(1, dateStr)
                ps.executeQuery().use { rs ->
                    if (rs.next())
                        Triple(rs.getInt("completed_count"), rs.getInt("total_count"), rs.getInt("focus_minutes"))
                    else Triple(0, 0, 0)
                }
            }
            DayCompletionStats(date, row.first, row.second, row.third)
        }
    }

    @Synchronized fun clearAllSessions() {
        connection.createStatement().executeUpdate("DELETE FROM focus_sessions")
        connection.createStatement().executeUpdate("DELETE FROM daily_completions")
    }

    // ── Block Rules ───────────────────────────────────────────────────────────

    @Synchronized fun getBlockRules(): List<BlockRule> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM block_rules ORDER BY display_name"
        ).use { rs ->
            val list = mutableListOf<BlockRule>(); while (rs.next()) list.add(rowToBlockRule(rs)); list
        // Defensive dedup: legacy DBs may have rows with duplicate IDs or duplicate
        // process_name values (pre-UNIQUE-constraint schema). distinctBy on both fields
        // ensures the LazyColumn key is always unique even on corrupt/migrated data.
        }.distinctBy { it.id }.distinctBy { it.processName }
    }

    @Synchronized fun getEnabledBlockProcesses(): Set<String> {
        return connection.createStatement().executeQuery(
            "SELECT process_name FROM block_rules WHERE enabled = 1"
        ).use { rs ->
            val set = mutableSetOf<String>(); while (rs.next()) set.add(rs.getString("process_name").lowercase()); set
        }
    }

    @Synchronized fun upsertBlockRule(rule: BlockRule) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO block_rules (id, process_name, display_name, enabled, block_network)
            VALUES (?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, rule.id); ps.setString(2, rule.processName)
            ps.setString(3, rule.displayName); ps.setInt(4, if (rule.enabled) 1 else 0)
            ps.setInt(5, if (rule.blockNetwork) 1 else 0); ps.executeUpdate()
        }
    }

    @Synchronized fun deleteBlockRule(id: String) {
        connection.prepareStatement("DELETE FROM block_rules WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    // ── Block Schedules ───────────────────────────────────────────────────────

    @Synchronized fun getBlockSchedules(): List<BlockSchedule> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM block_schedules ORDER BY name"
        ).use { rs ->
            val list = mutableListOf<BlockSchedule>()
            while (rs.next()) list.add(rowToSchedule(rs))
            list
        }
    }

    @Synchronized fun upsertBlockSchedule(s: BlockSchedule) {
        // Keep legacy invalid rows readable so they can be disabled or deleted,
        // but never allow an enabled schedule with an unrepresentable time to
        // be written back to the database.
        if (s.enabled) {
            require(s.hasValidTimeRange()) {
                "Enabled block schedule has invalid time: " +
                    "${s.startHour}:${s.startMinute}–${s.endHour}:${s.endMinute}"
            }
        }
        connection.prepareStatement("""
            INSERT OR REPLACE INTO block_schedules
            (id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, enabled, process_names)
            VALUES (?,?,?,?,?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, s.id); ps.setString(2, s.name)
            ps.setString(3, s.daysOfWeek.joinToString(","))
            ps.setInt(4, s.startHour); ps.setInt(5, s.startMinute)
            ps.setInt(6, s.endHour); ps.setInt(7, s.endMinute)
            ps.setInt(8, if (s.enabled) 1 else 0)
            ps.setString(9, s.processNames.joinToString(","))
            ps.executeUpdate()
        }
    }

    @Synchronized fun deleteBlockSchedule(id: String) {
        connection.prepareStatement("DELETE FROM block_schedules WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    // ── Daily Allowances ──────────────────────────────────────────────────────

    @Synchronized fun getDailyAllowances(): List<DailyAllowance> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM daily_allowances ORDER BY display_name"
        ).use { rs ->
            val list = mutableListOf<DailyAllowance>()
            while (rs.next()) list.add(DailyAllowance(
                rs.getString("process_name"),
                rs.getString("display_name"),
                rs.getInt("allowance_minutes")
            ))
            list
        // Defensive dedup: legacy DBs may have process_name duplicates if the table
        // was originally created without the PRIMARY KEY constraint. Dedup here
        // prevents the Compose key("discord.exe") crash in DashboardScreen.
        }.distinctBy { it.processName }
    }

    @Synchronized fun upsertDailyAllowance(a: DailyAllowance) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO daily_allowances (process_name, display_name, allowance_minutes)
            VALUES (?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, a.processName); ps.setString(2, a.displayName)
            ps.setInt(3, a.allowanceMinutes); ps.executeUpdate()
        }
    }

    @Synchronized fun deleteDailyAllowance(processName: String) {
        connection.prepareStatement("DELETE FROM daily_allowances WHERE process_name = ?").use { ps ->
            ps.setString(1, processName); ps.executeUpdate()
        }
    }

    // ── Daily Usage (persists allowance counters across reboots) ──────────────

    @Synchronized fun getDailyUsage(date: LocalDate): Map<String, Long> {
        if (!isReady) return emptyMap()
        return connection.prepareStatement(
            "SELECT process_name, seconds_used FROM daily_usage WHERE date = ?"
        ).use { ps ->
            ps.setString(1, date.format(dateFmt))
            ps.executeQuery().use { rs ->
                val map = mutableMapOf<String, Long>()
                while (rs.next()) map[rs.getString("process_name")] = rs.getLong("seconds_used")
                map
            }
        }
    }

    @Synchronized fun upsertDailyUsage(date: LocalDate, processName: String, seconds: Long) {
        if (!isReady) return
        connection.prepareStatement("""
            INSERT OR REPLACE INTO daily_usage (date, process_name, seconds_used)
            VALUES (?, ?, ?)
        """.trimIndent()).use { ps ->
            ps.setString(1, date.format(dateFmt))
            ps.setString(2, processName)
            ps.setLong(3, seconds)
            ps.executeUpdate()
        }
    }

    @Synchronized fun deleteDailyUsageBefore(date: LocalDate) {
        if (!isReady) return
        connection.prepareStatement("DELETE FROM daily_usage WHERE date < ?").use { ps ->
            ps.setString(1, date.format(dateFmt))
            ps.executeUpdate()
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    @Synchronized fun getSetting(key: String): String? {
        if (!isReady) return null
        return try {
            connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
            }
        } catch (_: Exception) { null }
    }

    @Synchronized fun setSetting(key: String, value: String) {
        if (!isReady) return
        try {
            connection.prepareStatement("INSERT OR REPLACE INTO settings (key, value) VALUES (?,?)").use { ps ->
                ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
            }
        } catch (_: Exception) {}
    }

    // ── Keyword Blocker ───────────────────────────────────────────────────────

    fun getBlockedKeywords(): List<String> {
        val raw = getSetting("blocked_keywords") ?: return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Synchronized fun setBlockedKeywords(keywords: List<String>) {
        setSetting("blocked_keywords", keywords.joinToString(","))
    }

    fun isKeywordBlockerEnabled(): Boolean = getSetting("keyword_blocker_enabled") == "true"
    fun setKeywordBlockerEnabled(enabled: Boolean) = setSetting("keyword_blocker_enabled", if (enabled) "true" else "false")

    // ── Streak ────────────────────────────────────────────────────────────────

    @Synchronized fun getCurrentStreak(): Int {
        val rows = connection.createStatement().executeQuery(
            "SELECT date FROM daily_completions WHERE completed_count > 0 ORDER BY date DESC LIMIT 90"
        ).use { rs -> val l = mutableListOf<LocalDate>(); while (rs.next()) l.add(LocalDate.parse(rs.getString("date"), dateFmt)); l }
        if (rows.isEmpty()) return 0
        var streak = 0
        var expected = LocalDate.now()
        for (date in rows) {
            if (date == expected) { streak++; expected = expected.minusDays(1) }
            else if (date == LocalDate.now().minusDays(1) && streak == 0) { expected = date.minusDays(1); streak++ }
            else break
        }
        return streak
    }

    @Synchronized fun getBestStreak(): Int {
        val rows = connection.createStatement().executeQuery(
            "SELECT date FROM daily_completions WHERE completed_count > 0 ORDER BY date ASC"
        ).use { rs -> val l = mutableListOf<LocalDate>(); while (rs.next()) l.add(LocalDate.parse(rs.getString("date"), dateFmt)); l }
        if (rows.isEmpty()) return 0
        var best = 0; var current = 0; var prev: LocalDate? = null
        for (date in rows) {
            current = if (prev != null && date == prev.plusDays(1)) current + 1 else 1
            if (current > best) best = current
            prev = date
        }
        return best
    }

    // ── Temptation Log ────────────────────────────────────────────────────────

    @Synchronized fun logTemptation(processName: String, displayName: String) {
        connection.prepareStatement(
            "INSERT INTO temptation_log (process_name, display_name, timestamp) VALUES (?,?,?)"
        ).use { ps ->
            ps.setString(1, processName); ps.setString(2, displayName)
            ps.setString(3, LocalDateTime.now().format(dtFmt)); ps.executeUpdate()
        }
        connection.createStatement().executeUpdate(
            "DELETE FROM temptation_log WHERE id NOT IN (SELECT id FROM temptation_log ORDER BY id DESC LIMIT 1000)"
        )
    }

    @Synchronized fun getTemptationLog(sinceDays: Int = 7): List<TemptationEntry> {
        val cutoff = LocalDateTime.now().minusDays(sinceDays.toLong()).format(dtFmt)
        return connection.prepareStatement(
            "SELECT * FROM temptation_log WHERE timestamp >= ? ORDER BY timestamp DESC"
        ).use { ps ->
            ps.setString(1, cutoff)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<TemptationEntry>()
                while (rs.next()) list.add(TemptationEntry(
                    rs.getString("process_name"), rs.getString("display_name"),
                    LocalDateTime.parse(rs.getString("timestamp"), dtFmt)
                ))
                list
            }
        }
    }

    @Synchronized fun clearTemptationLog() {
        connection.createStatement().executeUpdate("DELETE FROM temptation_log")
    }

    // ── Daily Notes ───────────────────────────────────────────────────────────

    @Synchronized fun getNote(date: LocalDate): DailyNote? {
        return connection.prepareStatement("SELECT * FROM daily_notes WHERE date = ?").use { ps ->
            ps.setString(1, date.format(dateFmt))
            ps.executeQuery().use { rs ->
                if (rs.next()) DailyNote(
                    LocalDate.parse(rs.getString("date"), dateFmt),
                    rs.getString("content"), rs.getInt("mood"),
                    LocalDateTime.parse(rs.getString("updated_at"), dtFmt)
                ) else null
            }
        }
    }

    @Synchronized fun upsertNote(note: DailyNote) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO daily_notes (date, content, mood, updated_at) VALUES (?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, note.date.format(dateFmt)); ps.setString(2, note.content)
            ps.setInt(3, note.mood); ps.setString(4, note.updatedAt.format(dtFmt))
            ps.executeUpdate()
        }
    }

    @Synchronized fun clearNotes() {
        connection.createStatement().executeUpdate("DELETE FROM daily_notes")
    }

    // ── Weekly Report ─────────────────────────────────────────────────────────

    @Synchronized fun getSessionsInRange(startDate: String, endDate: String): List<FocusSession> {
        return connection.prepareStatement(
            "SELECT * FROM focus_sessions WHERE DATE(start_time) BETWEEN ? AND ? ORDER BY start_time ASC"
        ).use { ps ->
            ps.setString(1, startDate); ps.setString(2, endDate)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<FocusSession>()
                while (rs.next()) list.add(rowToSession(rs))
                list
            }
        }
    }

    @Synchronized fun getCompletedTasksInRange(startDate: String, endDate: String): Int {
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM tasks WHERE completed = 1 AND DATE(completed_at) BETWEEN ? AND ?"
        ).use { ps ->
            ps.setString(1, startDate); ps.setString(2, endDate)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
    }

    @Synchronized fun getTemptationsInRange(startDate: String, endDate: String): Int {
        return connection.prepareStatement(
            "SELECT COUNT(*) FROM temptation_log WHERE DATE(timestamp) BETWEEN ? AND ?"
        ).use { ps ->
            ps.setString(1, startDate); ps.setString(2, endDate)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
    }

    @Synchronized fun getTemptationBreakdownInRange(
        startDate: String,
        endDate: String,
        limit: Int = 5
    ): List<Pair<String, Int>> {
        return connection.prepareStatement(
            """SELECT display_name, COUNT(*) AS cnt
               FROM temptation_log
               WHERE DATE(timestamp) BETWEEN ? AND ?
               GROUP BY display_name
               ORDER BY cnt DESC
               LIMIT ?"""
        ).use { ps ->
            ps.setString(1, startDate)
            ps.setString(2, endDate)
            ps.setInt(3, limit)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<Pair<String, Int>>()
                while (rs.next()) list.add(rs.getString("display_name") to rs.getInt("cnt"))
                list
            }
        }
    }

    // ── Habits ────────────────────────────────────────────────────────────────

    @Synchronized fun getHabits(): List<Habit> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM habits ORDER BY created_at ASC"
        ).use { rs ->
            val list = mutableListOf<Habit>()
            while (rs.next()) list.add(Habit(
                id        = rs.getString("id"),
                name      = rs.getString("name"),
                emoji     = rs.getString("emoji") ?: "✅",
                createdAt = LocalDate.parse(rs.getString("created_at"), dateFmt)
            ))
            list
        }
    }

    @Synchronized fun upsertHabit(habit: Habit) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO habits (id, name, emoji, created_at) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setString(1, habit.id)
            ps.setString(2, habit.name)
            ps.setString(3, habit.emoji)
            ps.setString(4, habit.createdAt.format(dateFmt))
            ps.executeUpdate()
        }
    }

    @Synchronized fun deleteHabit(id: String) {
        // Transaction: habit row + its entries must be deleted atomically.
        // A crash between the two DELETEs leaves orphaned entries that can never be cleaned.
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM habits WHERE id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM habit_entries WHERE habit_id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            connection.commit()
        } catch (e: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized fun getHabitEntries(habitId: String, since: LocalDate): List<HabitEntry> {
        return connection.prepareStatement(
            "SELECT * FROM habit_entries WHERE habit_id = ? AND date >= ? ORDER BY date ASC"
        ).use { ps ->
            ps.setString(1, habitId)
            ps.setString(2, since.format(dateFmt))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<HabitEntry>()
                while (rs.next()) list.add(HabitEntry(
                    habitId = rs.getString("habit_id"),
                    date    = LocalDate.parse(rs.getString("date"), dateFmt),
                    done    = rs.getInt("done") == 1
                ))
                list
            }
        }
    }

    @Synchronized fun setHabitEntry(habitId: String, date: LocalDate, done: Boolean) {
        if (done) {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO habit_entries (habit_id, date, done) VALUES (?,?,1)"
            ).use { ps ->
                ps.setString(1, habitId); ps.setString(2, date.format(dateFmt))
                ps.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                "DELETE FROM habit_entries WHERE habit_id = ? AND date = ?"
            ).use { ps ->
                ps.setString(1, habitId); ps.setString(2, date.format(dateFmt))
                ps.executeUpdate()
            }
        }
    }

    @Synchronized fun getHabitStreak(habitId: String): Int {
        val rows = connection.prepareStatement(
            "SELECT date FROM habit_entries WHERE habit_id = ? AND done = 1 ORDER BY date DESC LIMIT 90"
        ).use { ps ->
            ps.setString(1, habitId)
            ps.executeQuery().use { rs ->
                val l = mutableListOf<LocalDate>()
                while (rs.next()) l.add(LocalDate.parse(rs.getString("date"), dateFmt))
                l
            }
        }
        if (rows.isEmpty()) return 0
        var streak = 0
        var expected = LocalDate.now()
        for (date in rows) {
            if (date == expected) { streak++; expected = expected.minusDays(1) }
            else break
        }
        return streak
    }

    // ── Network Cutoff Rules ──────────────────────────────────────────────────

    @Synchronized fun getNetworkCutoffRules(): List<NetworkCutoffRule> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM network_cutoff_rules ORDER BY pattern"
        ).use { rs ->
            val list = mutableListOf<NetworkCutoffRule>()
            while (rs.next()) list.add(rowToNetworkCutoffRule(rs))
            list
        }
    }

    @Synchronized fun getEnabledNetworkCutoffRules(): List<NetworkCutoffRule> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM network_cutoff_rules WHERE enabled = 1"
        ).use { rs ->
            val list = mutableListOf<NetworkCutoffRule>()
            while (rs.next()) list.add(rowToNetworkCutoffRule(rs))
            list
        }
    }

    @Synchronized fun upsertNetworkCutoffRule(rule: NetworkCutoffRule) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO network_cutoff_rules
            (id, pattern, mode, target_process, target_display_name, enabled)
            VALUES (?,?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, rule.id)
            ps.setString(2, rule.pattern)
            ps.setString(3, rule.mode.name)
            ps.setString(4, rule.targetProcess)
            ps.setString(5, rule.targetDisplayName)
            ps.setInt(6, if (rule.enabled) 1 else 0)
            ps.executeUpdate()
        }
    }

    @Synchronized fun setNetworkCutoffRuleEnabled(id: String, enabled: Boolean) {
        connection.prepareStatement(
            "UPDATE network_cutoff_rules SET enabled = ? WHERE id = ?"
        ).use { ps -> ps.setInt(1, if (enabled) 1 else 0); ps.setString(2, id); ps.executeUpdate() }
    }

    @Synchronized fun deleteNetworkCutoffRule(id: String) {
        connection.prepareStatement("DELETE FROM network_cutoff_rules WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    // ── Custom Block Presets ──────────────────────────────────────────────────

    @Synchronized fun getCustomBlockPresets(): List<CustomBlockPreset> {
        return connection.createStatement().executeQuery(
            "SELECT * FROM custom_block_presets ORDER BY created_at DESC"
        ).use { rs ->
            val list = mutableListOf<CustomBlockPreset>()
            while (rs.next()) list.add(rowToCustomBlockPreset(rs))
            list
        }
    }

    @Synchronized fun upsertCustomBlockPreset(preset: CustomBlockPreset) {
        connection.prepareStatement("""
            INSERT OR REPLACE INTO custom_block_presets
            (id, name, emoji, process_names, created_at)
            VALUES (?,?,?,?,?)
        """.trimIndent()).use { ps ->
            ps.setString(1, preset.id)
            ps.setString(2, preset.name)
            ps.setString(3, preset.emoji)
            ps.setString(4, preset.processNames.joinToString(","))
            ps.setString(5, preset.createdAt.format(dtFmt))
            ps.executeUpdate()
        }
    }

    @Synchronized fun deleteCustomBlockPreset(id: String) {
        connection.prepareStatement("DELETE FROM custom_block_presets WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private fun rowToTask(rs: java.sql.ResultSet): Task = Task(
        id                = rs.getString("id"),
        title             = rs.getString("title"),
        description       = rs.getString("description") ?: "",
        durationMinutes   = rs.getInt("duration_minutes"),
        scheduledDate     = rs.getString("scheduled_date")?.let { LocalDate.parse(it, dateFmt) },
        scheduledTime     = rs.getString("scheduled_time"),
        completed         = rs.getInt("completed") == 1,
        skipped           = rs.getInt("skipped") == 1,
        recurring         = rs.getInt("recurring") == 1,
        recurringType     = rs.getString("recurring_type"),
        priority          = rs.getString("priority") ?: "medium",
        tags              = rs.getString("tags")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        createdAt         = LocalDateTime.parse(rs.getString("created_at"), dtFmt),
        completedAt       = rs.getString("completed_at")?.let { LocalDateTime.parse(it, dtFmt) },
        focusMode         = rs.getInt("focus_mode") == 1,
        focusIntensity    = rs.getString("focus_intensity") ?: "standard",
        focusBlockedApps  = try { rs.getString("focus_blocked_apps")?.split(",")?.filter { it.isNotBlank() } ?: emptyList() } catch (_: Exception) { emptyList() },
        focusRequirePin   = try { rs.getInt("focus_require_pin") == 1 } catch (_: Exception) { false }
    )

    private fun rowToSession(rs: java.sql.ResultSet): FocusSession = FocusSession(
        id              = rs.getString("id"),
        taskId          = rs.getString("task_id"),
        taskName        = rs.getString("task_name"),
        startTime       = LocalDateTime.parse(rs.getString("start_time"), dtFmt),
        endTime         = rs.getString("end_time")?.let { LocalDateTime.parse(it, dtFmt) },
        plannedMinutes  = rs.getInt("planned_minutes"),
        actualMinutes   = rs.getInt("actual_minutes"),
        completed       = rs.getInt("completed") == 1,
        interrupted     = rs.getInt("interrupted") == 1,
        notes           = rs.getString("notes") ?: ""
    )

    private fun rowToBlockRule(rs: java.sql.ResultSet): BlockRule = BlockRule(
        id           = rs.getString("id"),
        processName  = rs.getString("process_name"),
        displayName  = rs.getString("display_name"),
        enabled      = rs.getInt("enabled") == 1,
        blockNetwork = rs.getInt("block_network") == 1
    )

    private fun rowToSchedule(rs: java.sql.ResultSet): BlockSchedule = BlockSchedule(
        id           = rs.getString("id"),
        name         = rs.getString("name"),
        daysOfWeek   = rs.getString("days_of_week").split(",").mapNotNull { it.trim().toIntOrNull() },
        startHour    = rs.getInt("start_hour"),
        startMinute  = rs.getInt("start_minute"),
        endHour      = rs.getInt("end_hour"),
        endMinute    = rs.getInt("end_minute"),
        enabled      = rs.getInt("enabled") == 1,
        processNames = rs.getString("process_names")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    )

    private fun rowToNetworkCutoffRule(rs: java.sql.ResultSet): NetworkCutoffRule = NetworkCutoffRule(
        id                 = rs.getString("id"),
        pattern            = rs.getString("pattern"),
        mode               = try { NetworkRuleMode.valueOf(rs.getString("mode")) } catch (_: Exception) { NetworkRuleMode.DOMAIN },
        targetProcess      = rs.getString("target_process"),
        targetDisplayName  = rs.getString("target_display_name"),
        enabled            = rs.getInt("enabled") == 1
    )

    private fun rowToCustomBlockPreset(rs: java.sql.ResultSet): CustomBlockPreset = CustomBlockPreset(
        id           = rs.getString("id"),
        name         = rs.getString("name"),
        emoji        = rs.getString("emoji") ?: "🚫",
        processNames = rs.getString("process_names")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        createdAt    = LocalDateTime.parse(rs.getString("created_at"), dtFmt)
    )
}
