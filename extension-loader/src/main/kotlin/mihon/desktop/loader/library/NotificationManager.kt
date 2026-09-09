package mihon.desktop.loader.library

import mihon.desktop.loader.log.Logger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Posts a notification to the operating system (tray icon / notification
 * banner). The seam between [NotificationManager] and AWT: production runs
 * [AwtSystemNotifier], tests inject fakes.
 */
fun interface SystemNotifier {
    fun show(title: String, message: String)
}

/**
 * Simple notification system for the desktop app.
 * Collects notifications and allows the UI to observe them.
 *
 * In addition to the in-app list, every [notify] call is forwarded to the OS
 * via [systemNotifier] (an AWT tray notifier by default) unless disabled with
 * [setSystemNotificationsEnabled]. OS delivery is best-effort: any failure is
 * logged at debug and never propagates -- the in-app notification always
 * lands.
 */
object NotificationManager {
    private const val TAG = "NotificationManager"
    private const val MAX_NOTIFICATIONS = 50

    /** Source of unique notification ids; strictly increasing for the process lifetime. */
    private val nextId = AtomicLong()

    data class Notification(
        /** Monotonic, unique even for notifications created in the same millisecond. */
        val id: Long = nextId.incrementAndGet(),
        /**
         * Final title (plain [notify]), or the title key of the localized
         * [notify] overload — resolved via [resolver] at display time.
         */
        val title: String,
        /** Final message (plain [notify]), or the message key (see [title]). */
        val message: String,
        /**
         * Format args for the message key; null marks a plain-string
         * notification whose title/message are already final.
         */
        val messageArgs: List<Any?>? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val read: Boolean = false,
    )

    private val _notifications = CopyOnWriteArrayList<Notification>()
    val notifications: List<Notification> get() = _notifications.toList()

    private val listeners = CopyOnWriteArrayList<NotificationListener>()

    /** OS-delivery seam; default is the AWT tray notifier (a no-op when unsupported). */
    @Volatile
    private var systemNotifier: SystemNotifier? = AwtSystemNotifier()

    /** Gate for the "Show system notifications" setting; when false, in-app only. */
    @Volatile
    private var systemNotificationsEnabled: Boolean = true

    /**
     * Resolves the localized [notify] overload's keys against the active
     * locale. This module cannot depend on the app's string table, so the app
     * installs the seam at startup: `resolver = { key, args -> Strings.get(key, *args) }`.
     * Default null = identity: the raw key is shown (tests, headless runs).
     */
    @Volatile
    var resolver: ((key: String, args: Array<Any?>) -> String)? = null

    fun interface NotificationListener {
        fun onNotificationAdded(notification: Notification)
    }

    /** Replaces the OS-delivery seam. Null disables OS notifications entirely (tests). */
    fun setSystemNotifier(notifier: SystemNotifier?) {
        systemNotifier = notifier
    }

    /**
     * Test-only: restores the default [AwtSystemNotifier] after a test
     * disabled OS delivery with [setSystemNotifier](null). The seam is
     * process-global, so an unrestored null leaks into every later test in
     * the same JVM. Constructing [AwtSystemNotifier] touches no AWT state
     * (tray work is lazy), so this is safe in headless environments.
     */
    internal fun resetForTest() {
        systemNotifier = AwtSystemNotifier()
    }

    /** Test-only: the currently installed OS-delivery seam, for restore assertions. */
    internal fun currentSystemNotifier(): SystemNotifier? = systemNotifier

    fun setSystemNotificationsEnabled(enabled: Boolean) {
        systemNotificationsEnabled = enabled
    }

    fun addListener(listener: NotificationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NotificationListener) {
        listeners.remove(listener)
    }

    /** Posts a notification with already-final strings (dynamic content: errors, file names). */
    fun notify(title: String, message: String) {
        dispatch(Notification(title = title, message = message))
    }

    /**
     * Posts a localizable notification: keys + args are stored raw and
     * resolved through [resolver] at display time (in-app list) and post time
     * (OS banner), so the notification follows the active language even
     * after a live locale switch. Note: a bare two-argument call binds to the
     * plain [notify] overload (fixed arity beats vararg), so the key path
     * always carries at least one arg — or a spread empty array.
     */
    fun notify(titleKey: String, messageKey: String, vararg args: Any?) {
        dispatch(Notification(title = titleKey, message = messageKey, messageArgs = args.toList()))
    }

    /** Localized display title; plain notifications pass their title through. */
    fun displayTitle(notification: Notification): String =
        if (notification.messageArgs == null) notification.title
        else resolveKey(notification.title, emptyArray())

    /** Localized display message; plain notifications pass their message through. */
    fun displayMessage(notification: Notification): String =
        if (notification.messageArgs == null) notification.message
        else resolveKey(notification.message, notification.messageArgs.toTypedArray())

    private fun resolveKey(key: String, args: Array<Any?>): String =
        resolver?.invoke(key, args) ?: key

    private fun dispatch(notification: Notification) {
        // Add + trim under one lock: two concurrent notifies racing between
        // size-check and removeAt could otherwise trip IndexOutOfBounds.
        synchronized(_notifications) {
            _notifications.add(0, notification)
            // Keep only the most recent MAX_NOTIFICATIONS entries.
            if (_notifications.size > MAX_NOTIFICATIONS) {
                _notifications.subList(MAX_NOTIFICATIONS, _notifications.size).clear()
            }
        }
        listeners.forEach { it.onNotificationAdded(notification) }
        postSystemNotification(notification)
    }

    /** Best-effort OS delivery; failures are silent (debug log) per the spec. */
    private fun postSystemNotification(notification: Notification) {
        if (!systemNotificationsEnabled) return
        val notifier = systemNotifier ?: return
        runCatching { notifier.show(displayTitle(notification), displayMessage(notification)) }
            .onFailure { Logger.d(TAG, "System notification failed: ${it.message}") }
    }

    fun markAsRead(id: Long) {
        val index = _notifications.indexOfFirst { it.id == id }
        if (index >= 0) {
            _notifications[index] = _notifications[index].copy(read = true)
        }
    }

    fun markAllAsRead() {
        _notifications.replaceAll { it.copy(read = true) }
    }

    fun clear() {
        _notifications.clear()
    }

    fun unreadCount(): Int = _notifications.count { !it.read }
}
