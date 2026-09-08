package mihon.desktop.loader.library

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationManagerTest {

    @Before
    fun setup() {
        NotificationManager.clear()
    }

    @Test
    fun `notify adds notification to list`() {
        NotificationManager.notify("Test Title", "Test Message")

        val notifications = NotificationManager.notifications
        assertEquals(1, notifications.size)
        assertEquals("Test Title", notifications[0].title)
        assertEquals("Test Message", notifications[0].message)
    }

    @Test
    fun `multiple notifications are added in order`() {
        NotificationManager.notify("First", "Message 1")
        NotificationManager.notify("Second", "Message 2")
        NotificationManager.notify("Third", "Message 3")

        val notifications = NotificationManager.notifications
        assertEquals(3, notifications.size)
        assertEquals("Third", notifications[0].title) // Most recent first
        assertEquals("Second", notifications[1].title)
        assertEquals("First", notifications[2].title)
    }

    @Test
    fun `markAsRead marks notification as read`() {
        NotificationManager.notify("Test", "Message")
        val id = NotificationManager.notifications[0].id

        NotificationManager.markAsRead(id)

        assertTrue(NotificationManager.notifications[0].read)
    }

    @Test
    fun `markAllAsRead marks all notifications as read`() {
        NotificationManager.notify("First", "Message 1")
        NotificationManager.notify("Second", "Message 2")

        NotificationManager.markAllAsRead()

        assertTrue(NotificationManager.notifications.all { it.read })
    }

    @Test
    fun `unreadCount returns correct count`() {
        assertEquals(0, NotificationManager.unreadCount())

        NotificationManager.notify("First", "Message 1")
        NotificationManager.notify("Second", "Message 2")
        assertEquals(2, NotificationManager.unreadCount())

        val id = NotificationManager.notifications[0].id
        NotificationManager.markAsRead(id)
        assertEquals(1, NotificationManager.unreadCount())
    }

    @Test
    fun `clear removes all notifications`() {
        NotificationManager.notify("First", "Message 1")
        NotificationManager.notify("Second", "Message 2")

        NotificationManager.clear()

        assertEquals(0, NotificationManager.notifications.size)
        assertEquals(0, NotificationManager.unreadCount())
    }

    @Test
    fun `listener receives notifications`() {
        var receivedNotification: NotificationManager.Notification? = null
        val listener = NotificationManager.NotificationListener { notification ->
            receivedNotification = notification
        }

        NotificationManager.addListener(listener)
        NotificationManager.notify("Test", "Message")

        assertNotNull(receivedNotification)
        assertEquals("Test", receivedNotification?.title)

        NotificationManager.removeListener(listener)
    }

    @Test
    fun `notifications are capped at 50`() {
        for (i in 1..60) {
            NotificationManager.notify("Title $i", "Message $i")
        }

        assertTrue(NotificationManager.notifications.size <= 50)
    }
}
