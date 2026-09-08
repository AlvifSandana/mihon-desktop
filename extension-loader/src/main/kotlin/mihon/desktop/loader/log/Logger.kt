package mihon.desktop.loader.log

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple logging system for the desktop app.
 * Writes to console and optionally to a log file.
 */
object Logger {
    enum class Level(val tag: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
    }

    private var minLevel = Level.DEBUG
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(logDir: File = File(System.getProperty("user.home"), ".mihon-desktop/logs")) {
        logDir.mkdirs()
        val logFileName = "mihon-${fileDateFormat.format(Date())}.log"
        logFile = File(logDir, logFileName)
    }

    fun setMinLevel(level: Level) {
        minLevel = level
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        if (level.ordinal < minLevel.ordinal) return

        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp ${level.tag}/$tag: $message"

        // Console output
        println(logLine)
        throwable?.printStackTrace()

        // File output
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.appendLine(logLine)
                    throwable?.let { t ->
                        StringWriter().use { sw ->
                            t.printStackTrace(PrintWriter(sw))
                            writer.appendLine(sw.toString())
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore file write errors
            }
        }
    }

    private class StringWriter : java.io.StringWriter() {
        override fun close() {} // Don't close - we need to read the buffer
    }
}
