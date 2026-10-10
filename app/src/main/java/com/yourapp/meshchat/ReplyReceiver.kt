package com.yourapp.meshchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Handles the notification's inline Reply and swipe-to-dismiss. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChatRepository.init(context)
        when (intent.action) {
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                if (!text.isNullOrEmpty() && !MeshService.sendText(context, text)) {
                    android.widget.Toast.makeText(
                        context, "Mesh isn't running — open the app first", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                // Re-post so the reply appears in the conversation and the spinner stops
                Notifier.showMessages(context)
            }
            Notifier.ACTION_DISMISS -> ChatRepository.clearPending()
        }
    }
}
