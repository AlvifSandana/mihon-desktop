package mihon.desktop.loader.library

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationManagerTest {

    /** Fake [SystemNotifier] recording every OS-delivery call. */
    private class RecordingNotifier : SystemNotifier {
        val posted = mutableListOf<Pair<String, String>>()
        override fun show(title: String, message: String) {
            posted += title to message
        }
    }

    @Before
    fun setup() {
        NotificationManager.clear()
        // Hermetic per-test state: no OS delivery unless a test opts in, the
        // setting gate back to its default, and no key resolver installed.
        NotificationManager.setSystemNotificationsEnabled(true)
        NotificationManager.setSystemNotifier(null)
        NotificationManager.resolver = null
    }

    @After
    fun tearDown() {
        NotificationManager.setSystemNotificationsEnabled(true)
        NotificationManager.setSystemNotifier(null)
        NotificationManager.resolver = null
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

        val notifications = NotificationManager.notifications
        assertEquals(50, notifications.size)
        // The 10 oldest are the ones evicted.
        assertEquals("Title 60", notifications.first().title)
        assertEquals("Title 11", notifications.last().title)
    }

    @Test
    fun `notification ids are unique and monotonic even within the same millisecond`() {
        // Rapid-fire notifies almost certainly share a millisecond timestamp;
        // ids must still be unique (LazyColumn key + markAsRead target).
        repeat(100) { NotificationManager.notify("T$it", "M$it") }

        val ids = NotificationManager.notifications.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // List is newest-first and ids strictly increase with creation time.
        assertEquals(ids.sortedDescending(), ids)
    }

    // ── System-notification delivery ────────────────────────────────────

    @Test
    fun `system notification is posted when enabled`() {
        val notifier = RecordingNotifier()
        NotificationManager.setSystemNotifier(notifier)

        NotificationManager.notify("OS Title", "OS Message")

        assertEquals(listOf("OS Title" to "OS Message"), notifier.posted)
        // In-app delivery still happens alongside.
        assertEquals(1, NotificationManager.notifications.size)
    }

    @Test
    fun `system notification is skipped when setting is off`() {
        val notifier = RecordingNotifier()
        NotificationManager.setSystemNotifier(notifier)
        NotificationManager.setSystemNotificationsEnabled(false)

        NotificationManager.notify("OS Title", "OS Message")

        assertTrue(notifier.posted.isEmpty())
        // In-app only.
        assertEquals(1, NotificationManager.notifications.size)
    }

    @Test
    fun `system notifications resume after re-enabling`() {
        val notifier = RecordingNotifier()
        NotificationManager.setSystemNotifier(notifier)
        NotificationManager.setSystemNotificationsEnabled(false)
        NotificationManager.notify("A", "1")
        NotificationManager.setSystemNotificationsEnabled(true)
        NotificationManager.notify("B", "2")

        assertEquals(listOf("B" to "2"), notifier.posted)
    }

    @Test
    fun `failing system notifier does not break in-app delivery`() {
        NotificationManager.setSystemNotifier { _, _ -> error("AWT exploded") }

        NotificationManager.notify("T", "M")

        assertEquals(1, NotificationManager.notifications.size)
        assertEquals("T", NotificationManager.notifications[0].title)
    }

    @Test
    fun `null system notifier keeps in-app delivery working`() {
        NotificationManager.setSystemNotifier(null)

        NotificationManager.notify("T", "M")

        assertEquals(1, NotificationManager.notifications.size)
    }

    @Test
    fun `cap at 50 is unchanged with system notifications on`() {
        val notifier = RecordingNotifier()
        NotificationManager.setSystemNotifier(notifier)

        for (i in 1..60) {
            NotificationManager.notify("Title $i", "Message $i")
        }

        assertEquals(50, NotificationManager.notifications.size)
        assertEquals(60, notifier.posted.size)
    }

    // ── Key-based (localizable) notifications ───────────────────────────

    @Test
    fun `key-based notify stores keys and args raw`() {
        NotificationManager.notify("notif_title_key", "notif_message_key", 3, "auto")

        val n = NotificationManager.notifications.single()
        assertEquals("notif_title_key", n.title)
        assertEquals("notif_message_key", n.message)
        assertEquals(listOf(3, "auto"), n.messageArgs)
    }

    @Test
    fun `key-based notify with a single arg takes the localized path`() {
        NotificationManager.notify("notif_title_key", "notif_message_key", 3)

        val n = NotificationManager.notifications.single()
        assertEquals(listOf(3), n.messageArgs)
    }

    @Test
    fun `two-argument call binds to the plain overload`() {
        // Fixed-arity beats vararg in Kotlin overload resolution: a call
        // with exactly (title, message) is always the plain path, so
        // existing callers can never be re-interpreted as keys.
        NotificationManager.notify("title.key", "message.key")

        val n = NotificationManager.notifications.single()
        assertNull(n.messageArgs)
        assertEquals("title.key", NotificationManager.displayTitle(n))
        assertEquals("message.key", NotificationManager.displayMessage(n))
    }

    @Test
    fun `displayTitle and displayMessage resolve keys through the resolver`() {
        NotificationManager.resolver = { key, args -> "$key[${args.joinToString(",")}]" }
        NotificationManager.notify("title.key", "message.key", 7, "auto")

        val n = NotificationManager.notifications.single()
        assertEquals("title.key[]", NotificationManager.displayTitle(n))
        assertEquals("message.key[7,auto]", NotificationManager.displayMessage(n))
    }

    @Test
    fun `without a resolver the raw key is shown`() {
        NotificationManager.notify("title.key", "message.key", 7)

        val n = NotificationManager.notifications.single()
        assertEquals("title.key", NotificationManager.displayTitle(n))
        assertEquals("message.key", NotificationManager.displayMessage(n))
    }

    @Test
    fun `plain notify bypasses the resolver`() {
        NotificationManager.resolver = { key, _ -> "resolved:$key" }

        NotificationManager.notify("T", "M")

        val n = NotificationManager.notifications.single()
        assertEquals("T", NotificationManager.displayTitle(n))
        assertEquals("M", NotificationManager.displayMessage(n))
    }

    @Test
    fun `OS delivery resolves keys via the resolver`() {
        val notifier = RecordingNotifier()
        NotificationManager.setSystemNotifier(notifier)
        NotificationManager.resolver = { key, args -> "$key:${args.size}" }

        NotificationManager.notify("title.key", "message.key", 7)

        // OS banner sees resolved strings, never raw keys.
        assertEquals(listOf("title.key:0" to "message.key:1"), notifier.posted)
    }
}
