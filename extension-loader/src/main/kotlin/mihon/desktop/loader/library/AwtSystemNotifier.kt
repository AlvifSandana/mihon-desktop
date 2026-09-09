package mihon.desktop.loader.library

import mihon.desktop.loader.log.Logger
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * [SystemNotifier] that posts to the OS notification area via AWT's
 * [SystemTray].
 *
 * - All AWT work runs on the EDT ([EventQueue.invokeLater]), as AWT requires.
 * - The [TrayIcon] is created and registered lazily and cached on success;
 *   a failed attempt (e.g. a transient `tray.add` rejection) is retried on
 *   the next [show] -- one bad moment must not disable notifications for the
 *   whole session.
 * - Headless / tray-less environments (CI, tests, some Linux WMs) are covered
 *   by [SystemTray.isSupported], and every AWT call is wrapped so any failure
 *   (no tray, `tray.add` rejected, missing toolkit) degrades to a silent
 *   no-op -- the in-app notification list is unaffected.
 * - The tray icon is a small programmatic "M" tile, so no resource files are
 *   needed and the app stays a single jar.
 */
class AwtSystemNotifier : SystemNotifier {
    private var trayIcon: TrayIcon? = null

    override fun show(title: String, message: String) {
        EventQueue.invokeLater {
            val icon = runCatching { obtainTrayIcon() }
                .onFailure { Logger.d(TAG, "Tray icon unavailable: ${it.message}") }
                .getOrNull()
                ?: return@invokeLater
            runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.INFO) }
                .onFailure { Logger.d(TAG, "System notification failed: ${it.message}") }
        }
    }

    /**
     * Runs on the EDT; returns the cached tray icon, or registers a new one.
     * A non-null [trayIcon] means a previous attempt succeeded, so failure
     * paths leave it null and the next [show] retries.
     */
    @Synchronized
    private fun obtainTrayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        if (!SystemTray.isSupported()) return null
        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(createIcon(), TRAY_TOOLTIP).apply { isImageAutoSize = true }
        return runCatching {
            tray.add(icon)
            trayIcon = icon
            icon
        }.onFailure {
            Logger.d(TAG, "Adding tray icon failed: ${it.message}")
            trayIcon = null
        }.getOrNull()
    }

    /** 16x16 app-purple tile with a white "M", drawn without any resource files. */
    private fun createIcon(): Image {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color(0xFF60548E.toInt())
            g.fillRect(0, 0, size, size)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            val metrics = g.fontMetrics
            val x = (size - metrics.stringWidth("M")) / 2
            val y = (size - metrics.height) / 2 + metrics.ascent
            g.drawString("M", x, y)
        } finally {
            g.dispose()
        }
        return image
    }

    private companion object {
        const val TAG = "AwtSystemNotifier"
        const val TRAY_TOOLTIP = "Mihon Desktop"
    }
}
