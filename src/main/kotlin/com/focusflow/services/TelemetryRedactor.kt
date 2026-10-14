package com.focusflow.services

internal data class TelemetryRedactionContext(
    val userHome: String,
    val userName: String,
    val hostNames: List<String>,
)

/**
 * Removes local profile and computer identifiers before a telemetry payload
 * reaches any sink. If the system values needed for redaction cannot be read,
 * the caller receives null and must not send the payload.
 */
internal object TelemetryRedactor {
    fun redactForCurrentUser(payload: String): String? {
        val context = try {
            val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                ?: return null
            val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
                ?: return null
            val hosts = listOfNotNull(
                System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() },
                System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() },
            ).distinct()
            TelemetryRedactionContext(home, user, hosts)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (_: Throwable) {
            return null
        }
        return redact(payload, context)
    }

    internal fun redact(payload: String, context: TelemetryRedactionContext): String {
        val values = buildList {
            add(context.userHome to "<home>")
            add(context.userName to "<user>")
            context.hostNames.forEach { add(it to "<host>") }
        }.filter { it.first.isNotBlank() }
            .distinctBy { it.first.lowercase() }
            .sortedByDescending { it.first.length }

        return values.fold(payload) { text, (value, replacement) ->
            Regex(pathAwarePattern(value), RegexOption.IGNORE_CASE).replace(text, replacement)
        }
    }

    private fun pathAwarePattern(value: String): String = buildString(value.length * 2) {
        value.forEach { character ->
            if (character == '/' || character == '\\') {
                append("[\\\\/]")
            } else {
                append(Regex.escape(character.toString()))
            }
        }
    }
}
