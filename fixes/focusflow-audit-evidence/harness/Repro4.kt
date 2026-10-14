import com.focusflow.data.Database
import com.focusflow.data.InitPolicy
import com.focusflow.data.models.FocusSession
import java.io.File
import java.time.LocalDateTime

fun main(args: Array<String>) {
    val dbFile = File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db")
    val policy = InitPolicy(busyTimeoutMs = 1000, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 5000)
    when (args[0]) {
        "orphan" -> {
            println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
            println("hasUnfinishedFocusSession() on a fresh DB                    = ${Database.hasUnfinishedFocusSession()}")
            // exactly what FocusSessionService.start() writes at session start (endTime = null, completed = false)
            Database.insertSession(FocusSession(id = "crashed-session", taskId = null, taskName = "Deep work",
                startTime = LocalDateTime.now().minusDays(3), endTime = null, plannedMinutes = 25))
            println("...app is killed / PC loses power mid-session (nothing ever writes end_time)")
            Database.close()
            println("init (next launch) -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
            println("hasUnfinishedFocusSession() 3 days later, after restart      = ${Database.hasUnfinishedFocusSession()}   <- UninstallProtectionService treats this as an ACTIVE session")
            Database.close()
        }
        "txerror" -> {
            // Same pattern as Database.completeTask()/insertSession(): autoCommit=false; try{...; commit()} catch(Exception){rollback}; finally{autoCommit=true}
            val ds = org.sqlite.SQLiteDataSource().apply { url = "jdbc:sqlite:" + File(System.getProperty("user.home"), "tx.db").absolutePath }
            ds.connection.use { c ->
                c.createStatement().use { it.execute("create table tasks(id text primary key, completed int)") ; it.execute("create table daily(d text primary key, n int)") ; it.execute("insert into tasks values('t1',0)") }
                c.autoCommit = false
                try {
                    c.createStatement().use { it.executeUpdate("update tasks set completed=1 where id='t1'") }
                    throw StackOverflowError("simulated Error between the two statements") // an Error, not an Exception
                    @Suppress("UNREACHABLE_CODE")
                    c.commit()
                } catch (e: Exception) {
                    try { c.rollback() } catch (_: Exception) {}
                    throw e
                } catch (t: Throwable) {
                    println("Error escaped 'catch (e: Exception)': ${t.javaClass.simpleName}; finally{} now runs autoCommit=true")
                } finally {
                    c.autoCommit = true
                }
                val done = c.createStatement().use { s -> s.executeQuery("select completed from tasks where id='t1'").use { it.getInt(1) } }
                val daily = c.createStatement().use { s -> s.executeQuery("select count(*) from daily").use { it.getInt(1) } }
                println("after the Error: tasks.completed=$done (should be 0 if rolled back), daily rows=$daily (second statement never ran) -> half-applied transaction was COMMITTED")
            }
        }
    }
}
