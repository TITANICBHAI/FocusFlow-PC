import com.focusflow.data.Database
import com.focusflow.data.InitPolicy
import com.focusflow.data.models.Task
import com.focusflow.services.GlobalPin
import com.focusflow.services.NuclearPin
import com.focusflow.services.SessionPin
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private fun hr(s: String) = println("\n=== $s ===")

private fun py(code: String, vararg args: String): String {
    val p = ProcessBuilder(listOf("python3", "-I", "-c", code) + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    return out
}

private fun rssKb(): Long =
    File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()

private fun stmtCount(): Int {
    val f = Database::class.java.getDeclaredField("conn"); f.isAccessible = true
    val c = f.get(null) as org.sqlite.SQLiteConnection
    val s = org.sqlite.core.DB::class.java.getDeclaredField("stmts"); s.isAccessible = true
    return (s.get(c.database) as Set<*>).size
}

fun main() {
    val home = File(System.getProperty("user.home"))
    val dbFile = File(home, ".focusflow/focusflow.db")
    val oldPin = "correct-horse-battery-1"
    val policy = InitPolicy(busyTimeoutMs = 300)

    hr("A. PIN helpers while the DB is NOT open (before init / after a failed init)")
    println("Database.isReady                 = ${Database.isReady}")
    println("GlobalPin.isSet()                = ${GlobalPin.isSet()}")
    println("GlobalPin.verify(\"anything\")     = ${GlobalPin.verify("anything")}    (true == access granted)")
    println("SessionPin.verify(\"anything\")    = ${SessionPin.verify("anything")}")
    println("NuclearPin.verify(\"anything\")    = ${NuclearPin.verify("anything")}")

    hr("B. PIN set -> DB handle lost (Database.close() stands in for any condition that makes getSetting() return null)")
    println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
    GlobalPin.set(oldPin)
    println("DB open   : isSet=${GlobalPin.isSet()}  verify(correct)=${GlobalPin.verify(oldPin)}  verify(wrong)=${GlobalPin.verify("wrong-wrong-wrong")}")
    Database.close()
    println("DB closed : isSet=${GlobalPin.isSet()}  verify(wrong)=${GlobalPin.verify("wrong-wrong-wrong")}  clear(wrong)=${GlobalPin.clear("wrong-wrong-wrong")}")
    println("hash on disk meanwhile: " + py("""
import sqlite3,sys
print(sqlite3.connect(sys.argv[1]).execute("select substr(value,1,12)||'...' from settings where key='global_pin_hash'").fetchone())
""", dbFile.absolutePath))

    hr("C. setSetting() swallows SQLITE_BUSY (another process holds the write lock; busy_timeout=300ms)")
    Database.init(dbFile, policy, allowRecovery = false)
    val locker = ProcessBuilder(listOf("python3", "-I", "-c", """
import sqlite3,sys,time
c=sqlite3.connect(sys.argv[1], isolation_level=None, timeout=0)
c.execute('BEGIN IMMEDIATE')
print('locked', flush=True)
time.sleep(5)
""", dbFile.absolutePath)).redirectErrorStream(true).start()
    locker.inputStream.bufferedReader().readLine()
    val t0 = System.nanoTime()
    val newPin = try { GlobalPin.autoGenerate() } catch (t: Throwable) { println("autoGenerate THREW ${t.javaClass.name}"); "" }
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("autoGenerate() returned a ${newPin.length}-char PIN after ${ms} ms and threw nothing (UI would now show it to the user)")
    locker.waitFor()
    println("after the lock is released: verify(newPin)=${GlobalPin.verify(newPin)}   verify(oldPin)=${GlobalPin.verify(oldPin)}")

    hr("D. Telemetry opt-out vs. the expression in CrashReporter.reportCritical()/sendToDiscord()")
    Database.setSetting("crash_reports_enabled", "false")
    println("DB open   : getSetting=${Database.getSetting("crash_reports_enabled")}  -> optedIn=${Database.getSetting("crash_reports_enabled") != "false"}")
    Database.close()
    println("DB closed : getSetting=${Database.getSetting("crash_reports_enabled")}  -> optedIn=${Database.getSetting("crash_reports_enabled") != "false"}   (report is SENT)")
    println("value on disk: " + py("""
import sqlite3,sys
print(sqlite3.connect(sys.argv[1]).execute("select value from settings where key='crash_reports_enabled'").fetchone())
""", dbFile.absolutePath))

    hr("E. Statement leak: DB.stmts after the five reads ProcessMonitor.refreshCaches() runs every 2 s")
    Database.init(dbFile, policy, allowRecovery = false)
    println("baseline DB.stmts = ${stmtCount()}")
    val probes: List<Pair<String, () -> Unit>> = listOf(
        "getEnabledBlockProcesses()" to { Database.getEnabledBlockProcesses() },
        "isKeywordBlockerEnabled()" to { Database.isKeywordBlockerEnabled() },
        "getBlockedKeywords()" to { Database.getBlockedKeywords() },
        "getEnabledNetworkCutoffRules()" to { Database.getEnabledNetworkCutoffRules() },
        "getBlockRules()" to { Database.getBlockRules() },
        "getSetting(\"x\")  [prepareStatement.use]" to { Database.getSetting("x") },
    )
    var perRefresh = 0.0
    for ((name, fn) in probes) {
        val before = stmtCount()
        repeat(100) { fn() }
        val grew = stmtCount() - before
        println("  %-44s 100 calls -> DB.stmts +%d".format(name, grew))
        if (!name.startsWith("getSetting")) perRefresh += grew / 100.0
    }
    println("leaked statements per refreshCaches() = $perRefresh")
    println("at one refresh / 2 s: %.0f per hour, %.0f per day".format(perRefresh * 1800, perRefresh * 43200))
    val rss0 = rssKb(); val s0 = stmtCount()
    repeat(5000) { probes.take(5).forEach { it.second() } }   // 5,000 refreshes ~= 2h47m of app uptime
    println("after 5,000 refreshes (~2h47m of uptime): DB.stmts %d -> %d, process RSS %d MB -> %d MB".format(s0, stmtCount(), rss0 / 1024, rssKb() / 1024))
    val tc = System.nanoTime(); Database.close(); println("Database.close() with that backlog took ${(System.nanoTime() - tc) / 1_000_000} ms")

    hr("F. RecurringTaskService.generateForToday() logic (copied verbatim, `today` injected) against the real DB")
    Database.init(dbFile, policy, allowRecovery = false)
    fun gen(today: LocalDate) {
        val templates = Database.getRecurringTemplates()
        val existing = Database.getTasksForDate(today)
            .map { "${it.title.lowercase().trim()}|${it.scheduledTime?.toString() ?: ""}" }.toSet()
        for (template in templates) {
            val dedupKey = "${template.title.lowercase().trim()}|${template.scheduledTime?.toString() ?: ""}"
            if (dedupKey in existing) continue
            Database.upsertTask(template.copy(
                id = UUID.randomUUID().toString(), scheduledDate = today, scheduledTime = template.scheduledTime,
                completed = false, skipped = false, createdAt = LocalDateTime.now()))
        }
    }
    val d0 = LocalDate.of(2026, 1, 1)
    Database.upsertTask(Task(id = UUID.randomUUID().toString(), title = "Read 20 pages", scheduledDate = d0,
        scheduledTime = "09:00", recurring = true, recurringType = "daily", createdAt = LocalDateTime.now()))
    println("user creates ONE daily recurring task on $d0")
    for (n in 1..12) {
        val day = d0.plusDays(n.toLong())
        gen(day)
        println("  day +%-2d (%s): tasks shown for that day = %-5d | rows with recurring=1 now = %d".format(
            n, day, Database.getTasksForDate(day).size, Database.getRecurringTemplates().size))
    }
    Database.close()
}
