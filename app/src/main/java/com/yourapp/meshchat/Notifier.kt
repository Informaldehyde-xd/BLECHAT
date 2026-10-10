package com.yourapp.meshchat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

object Notifier {
    const val CH_SERVICE = "mesh_service"
    const val CH_MSG = "mesh_messages"
    const val ID_FG = 1
    const val ID_MSG = 2

    const val ACTION_REPLY = "com.yourapp.meshchat.REPLY"
    const val ACTION_DISMISS = "com.yourapp.meshchat.DISMISS"
    const val ACTION_STOP = "com.yourapp.meshchat.STOP"
    const val KEY_REPLY = "key_reply"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Mesh connection", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The permanent notification that keeps the mesh alive in the background. */
    fun foreground(ctx: Context): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val peers = ChatRepository.peerCount()
        val names = ChatRepository.nearbyNames()
        val text = when {
            peers == 0 -> "Looking for nearby peers…"
            names.isEmpty() -> "$peers peer(s) connected"
            else -> "Connected: " + names.joinToString(", ")
        }
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mesh Chat is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateForeground(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID_FG, foreground(ctx))
    }

    /** Chat-style notification with an inline Reply box, like any messenger. */
    @SuppressLint("MissingPermission")
    fun showMessages(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val pending = ChatRepository.pendingSnapshot()
        if (pending.isEmpty()) return

        val me = Person.Builder().setName("Me").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Mesh Chat")
            .setGroupConversation(true)
        pending.takeLast(10).forEach { m ->
            val who = if (m.isMine) me else Person.Builder().setName(m.sender).build()
            style.addMessage(m.text, m.time, who)
        }

        val replyIntent = Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_REPLY)
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val replyPi = PendingIntent.getBroadcast(ctx, 2, replyIntent, replyFlags)
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyPi
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val dismissPi = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ctx, ReplyReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val last = pending.last()
        val notification = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (last.isMine) "Mesh Chat" else last.sender)
            .setContentText(last.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(dismissPi)
            .addAction(replyAction)
            .build()

        ctx.getSystemService(NotificationManager::class.java).notify(ID_MSG, notification)
    }

    fun cancelMessages(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_MSG)
    }
}
