import com.focusflow.data.Database
import com.focusflow.data.InitPolicy
import com.focusflow.data.models.Task
import java.io.File
import java.time.LocalDate

// F21 evidence: Database.completeTask is not idempotent. Uses the app's real Database object.
fun main(args: Array<String>) {
    val dbFile = File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db")
    val policy = InitPolicy(busyTimeoutMs = 1000, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 5000)
    println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
    fun today(): String {
        val s = Database.getRecentDayCompletions(1).single()
        return "completed_count=${s.completedCount} total_count=${s.totalCount}"
    }
    Database.upsertTask(Task(id = "t1", title = "write report", scheduledDate = LocalDate.now()))
    println("before any completion              : ${today()}")
    Database.completeTask("t1")
    println("after completeTask(t1) once        : ${today()}")
    // e.g. a double click: the UI launches one IO job per click (TasksScreen.kt:281/304/337, DashboardScreen.kt:504)
    Database.completeTask("t1")
    println("after completeTask(t1) a 2nd time  : ${today()}   <- same task counted twice")
    // task deleted in the meantime / stale card
    Database.completeTask("no-such-task")
    println("after completeTask(no-such-task)   : ${today()}   <- counted although no row was updated")
    val c = Database.getTasks().first { it.id == "t1" }
    println("tasks row t1: completed=${c.completed} completedAt=${c.completedAt}")
    // quirk check: getTotalFocusMinutesToday reads getInt(1) without next()
    println("getTotalFocusMinutesToday() on empty sessions = ${Database.getTotalFocusMinutesToday()} (works only because sqlite-jdbc pre-loads the first row)")
    Database.close()
}
