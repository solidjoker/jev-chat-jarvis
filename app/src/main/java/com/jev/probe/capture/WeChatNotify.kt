package com.jev.probe.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * WeChat's notification capture channel (v1.5). WeChat hides its node tree from
 * accessibility and FLAG_SECUREs its chat screens for many accounts, so the two
 * channels the other apps use (tree read, screenshot+OCR) both die there. But
 * every incoming message still surfaces as a system notification, and a
 * NotificationListenerService is a plain OS facility: no screenshot, no
 * WeChat-internal state, nothing its risk control watches.
 *
 * What this channel can and cannot see:
 * - Only INCOMING messages (notifications are never posted for my own sends),
 *   and only while WeChat is not actively open in that conversation.
 * - 1:1 chat: title = contact name, text = message. Group: title = group name,
 *   text = "sender: message" (kept verbatim — the judge model sees the sender
 *   inline, same shape WeChat itself shows).
 *
 * The store is process-global so the listener service and the accessibility
 * service (which owns the overlay and the analysis pipeline) can share it even
 * though the system binds them independently.
 */
object WeChatNotifyStore {

    private const val TAG = "JEVASSIST"

    /** Bounded: notifications are a live feed, not a history we keep. */
    private const val CAP = 24

    private class NotifMsg(val title: String, val text: String)

    private val lock = Any()
    private val recent = ArrayDeque<NotifMsg>()

    /**
     * Fired once per genuinely new message, on the posting (main) thread.
     * [ChatCaptureService] connects this to its analysis pipeline on service
     * connect and disconnects on destroy — a stale service instance must never
     * receive captures.
     */
    @Volatile var onNewMessage: ((title: String, text: String) -> Unit)? = null

    fun add(title: String, text: String) {
        synchronized(lock) {
            // WeChat reposts a notification (with the same text) as the
            // conversation's unread state changes; only a NEW text is a new
            // message worth analyzing.
            val last = recent.lastOrNull()
            if (last != null && last.title == title && last.text == text) return
            recent.addLast(NotifMsg(title, text))
            while (recent.size > CAP) recent.removeFirst()
        }
        Log.i(TAG, "wechat notif: title.len=${title.length} text.len=${text.length}")
        onNewMessage?.invoke(title, text)
    }

    /**
     * Build a snapshot from what the notifications hold for one conversation,
     * or across all of them when [title] is null. Everything is "other":
     * notifications only ever carry incoming messages. Null when nothing is
     * buffered.
     */
    fun snapshotFor(title: String?): ChatSnapshot? = synchronized(lock) {
        val msgs = recent
            .filter { title == null || it.title.trim() == title.trim() }
            .takeLast(12)
            .map { Msg("other", it.text) }
        if (msgs.isEmpty()) return null
        ChatSnapshot(
            title ?: recent.lastOrNull()?.title,
            msgs,
            note = NOTE
        )
    }

    private const val NOTE = "微信通知读取：只有对方消息，无历史与我方消息"
}

/**
 * The system-bound listener feeding [WeChatNotifyStore]. Needs the user to
 * grant notification access in system settings; until then it simply never
 * gets bound and the store stays empty. Only `com.tencent.mm` is looked at —
 * every other app's notifications are ignored entirely.
 */
class WeChatNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != PKG_WECHAT) return
        if (sbn.isOngoing) return
        val n: Notification = sbn.notification ?: return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            // MessagingStyle notifications carry the texts in EXTRA_MESSAGES;
            // the newest one is the message this post is about.
            runCatching {
                @Suppress("DEPRECATION")
                val arr = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                text = arr?.lastOrNull()?.let {
                    (it as? Notification.MessagingStyle.Message)?.text?.toString()
                }?.trim().orEmpty()
            }
        }
        if (title.isEmpty() || text.isEmpty()) return
        WeChatNotifyStore.add(title, text)
    }

    companion object { private const val PKG_WECHAT = "com.tencent.mm" }
}
