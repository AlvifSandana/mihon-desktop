package mihon.desktop.loader.library

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simple notification system for the desktop app.
 * Collects notifications and allows the UI to observe them.
 */
object NotificationManager {
    data class Notification(
        val id: Long = System.currentTimeMillis(),
        val title: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        var read: Boolean = false,
    )

    private val _notifications = CopyOnWriteArrayList<Notification>()
    val notifications: List<Notification> get() = _notifications.toList()

    private val listeners = CopyOnWriteArrayList<NotificationListener>()

    fun interface NotificationListener {
        fun onNotificationAdded(notification: Notification)
    }

    fun addListener(listener: NotificationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NotificationListener) {
        listeners.remove(listener)
    }

    fun notify(title: String, message: String) {
        val notification = Notification(title = title, message = message)
        _notifications.add(0, notification)
        // Keep only last 50 notifications
        while (_notifications.size > 50) {
            _notifications.removeAt(_notifications.lastIndex)
        }
        listeners.forEach { it.onNotificationAdded(notification) }
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
