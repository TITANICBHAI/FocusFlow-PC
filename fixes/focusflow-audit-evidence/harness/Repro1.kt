import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import java.io.File

// Repro 1: call the REAL Database.init twice in one JVM, exactly like Main.kt does on first
// launch and on "Retry". Prints what each call returns / throws.
fun main() {
    val db = File(File(System.getProperty("user.home"), ".focusflow"), "focusflow.db") // defaultDbFile()
    println("java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} tmpdir=${System.getProperty("org.sqlite.tmpdir")}")
    for (attempt in 1..2) {
        println("=== Database.init attempt #$attempt ===")
        try {
            val r = Database.init(dbFile = db, allowRecovery = false)
            println("returned: ${r::class.simpleName}")
            if (r is DbInitResult.Failed) {
                var t: Throwable? = r.cause
                var depth = 0
                while (t != null && depth < 6) {
                    println("  " + (if (depth == 0) "cause     : " else "Caused by : ") + t.javaClass.name + ": " + t.message)
                    t = t.cause; depth++
                }
            }
        } catch (t: Throwable) {
            println("ESCAPED init(): ${t.javaClass.name}: ${t.message}")
            println("  is Exception=${t is Exception}  is Error=${t is Error}  (Main.kt catches only Exception)")
            t.stackTrace.take(7).forEach { println("    at $it") }
        }
    }
    println("CrashReporter.reportCritical calls = " + com.focusflow.services.CrashReporter.reported)
}
