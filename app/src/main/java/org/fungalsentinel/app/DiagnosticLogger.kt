package org.fungalsentinel.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Small local-only rolling log for tester-supplied diagnostics. */
object DiagnosticLogger {
    private const val FILE_NAME = "diagnostic.log"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val RETAIN_BYTES = 1024 * 1024
    private val lock = Any()

    fun info(context: Context, message: String) = append(context, "INFO", message, null)

    fun warning(context: Context, message: String, error: Throwable? = null) =
        append(context, "WARN", message, error)

    fun error(context: Context, message: String, error: Throwable? = null) =
        append(context, "ERROR", message, error)

    fun clear(context: Context): Boolean = synchronized(lock) {
        val file = logFile(context)
        !file.exists() || file.delete()
    }

    fun export(context: Context, output: OutputStream) {
        val snapshot = synchronized(lock) {
            logFile(context).takeIf(File::isFile)?.readText(StandardCharsets.UTF_8).orEmpty()
        }
        output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine("Fungal Sentinel diagnostic log")
            writer.appendLine("Generated: ${timestamp()}")
            writer.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("Local app events only; no RAW images or project names are included.")
            writer.appendLine("---")
            writer.append(snapshot)
        }
    }

    private fun append(context: Context, level: String, message: String, error: Throwable?) = synchronized(lock) {
        runCatching {
            val file = logFile(context)
            rotateIfNeeded(file)
            file.appendText(buildString {
                append(timestamp()).append(' ').append(level).append(' ').append(sanitize(message))
                if (error != null) {
                    // Exception messages can contain user-selected paths, so export only type and stack frames.
                    append(" · ").append(error.javaClass.simpleName)
                    error.stackTrace.take(12).forEach { frame ->
                        append("\n  at ").append(frame.className).append('.').append(frame.methodName)
                            .append('(').append(frame.fileName ?: "Unknown").append(':').append(frame.lineNumber).append(')')
                    }
                }
                append('\n')
            })
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.isFile || file.length() < MAX_BYTES) return
        val text = file.readText(StandardCharsets.UTF_8)
        val tail = text.takeLast(RETAIN_BYTES).substringAfter('\n', text.takeLast(RETAIN_BYTES))
        file.writeText(tail, StandardCharsets.UTF_8)
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("content://\\S+"), "[uri]")
        .replace(Regex("file://\\S+"), "[file]")
        .replace(Regex("(?<![A-Za-z0-9])/(?:[^\\s/]+/)*[^\\s]+"), "[path]")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(2_000)

    private fun logFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
