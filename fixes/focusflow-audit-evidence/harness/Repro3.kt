import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.InitPolicy
import java.io.File

private fun rssKb(): Long =
    File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()

private fun heapUsedMb(): Long { System.gc(); System.gc(); val r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) / 1048576 }

private fun stmtCount(): Int {
    val f = Database::class.java.getDeclaredField("conn"); f.isAccessible = true
    val c = f.get(null) as org.sqlite.SQLiteConnection
    val s = org.sqlite.core.DB::class.java.getDeclaredField("stmts"); s.isAccessible = true
    return (s.get(c.database) as Set<*>).size
}

fun main(args: Array<String>) {
    val mode = args[0]
    val dbFile = File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db")
    val policy = InitPolicy(busyTimeoutMs = 1000, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 5000)
    when (mode) {
        "prep" -> { println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}"); Database.setSetting("load_test", "0"); Database.close() }
        "race" -> {
            // Emulates the --uninstall wizard JVM calling Database.init() while another process is using the DB.
            val n = args[1].toInt()
            val results = linkedMapOf<String, Int>()
            repeat(n) {
                val r = Database.init(dbFile, policy, allowRecovery = false)
                val key = when (r) {
                    is DbInitResult.Ready -> "Ready"
                    is DbInitResult.Busy -> "Busy: ${r.cause.message}"
                    is DbInitResult.Failed -> "Failed: ${r.cause.javaClass.simpleName}: ${r.cause.message}".take(150)
                }
                results.merge(key, 1, Int::plus)
                Database.close()
            }
            results.forEach { (k, v) -> println("  %4d x %s".format(v, k)) }
        }
        "control", "leak" -> {
            println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
            val h0 = heapUsedMb(); val r0 = rssKb()
            if (mode == "control") repeat(15000) { Database.getSetting("x") }
            else repeat(5000) { Database.getEnabledBlockProcesses(); Database.getEnabledNetworkCutoffRules(); Database.getBlockRules() } // 3 leaking reads x 5000 = 15,000 statements
            val h1 = heapUsedMb(); val r1 = rssKb()
            println("%s: DB.stmts=%d  heap(after GC) %d -> %d MB   RSS %d -> %d MB   (RSS-heap growth = %d MB)".format(
                mode, stmtCount(), h0, h1, r0 / 1024, r1 / 1024, (r1 - r0) / 1024 - (h1 - h0)))
            Database.close()
        }
        "sum" -> {
            println("init -> ${Database.init(dbFile, policy, allowRecovery = false)::class.simpleName}")
            println("getTotalFocusMinutesToday() on empty DB = ${Database.getTotalFocusMinutesToday()}")
            Database.close()
        }
    }
}
