package mihon.desktop.loader.log

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logDir: File

    @Before
    fun setup() {
        logDir = tempFolder.newFolder("logs")
        Logger.init(logDir)
        Logger.setMinLevel(Logger.Level.DEBUG)
    }

    @Test
    fun `init creates log directory`() {
        assertTrue(logDir.exists())
        assertTrue(logDir.isDirectory)
    }

    @Test
    fun `log creates log file`() {
        Logger.i("TestTag", "Test message")

        val logFiles = logDir.listFiles()
        assertNotNull(logFiles)
        assertTrue(logFiles!!.isNotEmpty())
    }

    @Test
    fun `log file contains message`() {
        Logger.i("TestTag", "Test message")

        val logFile = logDir.listFiles()?.firstOrNull()
        assertNotNull(logFile)
        val content = logFile?.readText() ?: ""
        assertTrue(content.contains("TestTag"))
        assertTrue(content.contains("Test message"))
    }

    @Test
    fun `different levels produce different tags`() {
        Logger.d("Tag", "Debug")
        Logger.i("Tag", "Info")
        Logger.w("Tag", "Warn")
        Logger.e("Tag", "Error")

        val logFile = logDir.listFiles()?.firstOrNull()
        val content = logFile?.readText() ?: ""
        assertTrue(content.contains("D/Tag"))
        assertTrue(content.contains("I/Tag"))
        assertTrue(content.contains("W/Tag"))
        assertTrue(content.contains("E/Tag"))
    }

    @Test
    fun `setMinLevel filters out lower levels`() {
        Logger.setMinLevel(Logger.Level.WARN)

        Logger.d("Tag", "Debug")
        Logger.i("Tag", "Info")
        Logger.w("Tag", "Warn")
        Logger.e("Tag", "Error")

        val logFile = logDir.listFiles()?.firstOrNull()
        val content = logFile?.readText() ?: ""
        assertFalse(content.contains("D/Tag"))
        assertFalse(content.contains("I/Tag"))
        assertTrue(content.contains("W/Tag"))
        assertTrue(content.contains("E/Tag"))
    }

    @Test
    fun `error with throwable logs stack trace`() {
        val exception = RuntimeException("Test exception")
        Logger.e("Tag", "Error occurred", exception)

        val logFile = logDir.listFiles()?.firstOrNull()
        val content = logFile?.readText() ?: ""
        assertTrue(content.contains("Error occurred"))
        assertTrue(content.contains("RuntimeException"))
    }
}
