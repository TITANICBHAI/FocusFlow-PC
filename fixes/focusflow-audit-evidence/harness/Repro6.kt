import com.focusflow.data.Database
import com.focusflow.data.InitPolicy
import java.io.File
import java.time.LocalDate

// F30 evidence: Database.getCurrentStreak() reads "ORDER BY date DESC LIMIT 90", so a streak longer than 90 days can never be reported.
// Rows are inserted with plain JDBC (there is no public API to back-fill daily_completions); the streak itself is computed by the app's real function.
fun main(args: Array<String>) {
    val dbFile = File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db")
    val policy = InitPolicy(busyTimeoutMs = 1000, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 5000)
    println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
    val days = args.firstOrNull()?.toInt() ?: 120
    val today = LocalDate.now()
    org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.absolutePath}" }.connection.use { c ->
        c.prepareStatement("INSERT OR REPLACE INTO daily_completions (date, completed_count, total_count, focus_minutes) VALUES (?, 1, 1, 0)").use { ps ->
            for (i in 0 until days) { ps.setString(1, today.minusDays(i.toLong()).toString()); ps.addBatch() }
            ps.executeBatch()
        }
    }
    println("inserted $days consecutive days ending today into daily_completions")
    println("getCurrentStreak() = ${Database.getCurrentStreak()}   (true streak = $days)")
    println("getBestStreak()    = ${Database.getBestStreak()}")
    Database.close()
}
