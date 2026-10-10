package com.yourapp.meshchat

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val sender: String,
    val text: String,
    val isMine: Boolean,
    val time: Long = System.currentTimeMillis()
)

/** Terminal-style lines:  [12:41] alice> hello   (name green, text white). */
class MessageAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onLongPress: (ChatMessage) -> Unit = {}
) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    companion object {
        const val GREEN = 0xFF33FF33.toInt()   // peer names
        const val CYAN = 0xFF4DD0E1.toInt()    // your own name
        const val GRAY = 0xFF7A7A7A.toInt()    // timestamps
        const val WHITE = 0xFFFFFFFF.toInt()   // message text
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val sb = SpannableStringBuilder()
        fun add(part: String, color: Int) {
            val start = sb.length
            sb.append(part)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add("[${timeFormat.format(Date(msg.time))}] ", GRAY)
        add(msg.sender, if (msg.isMine) CYAN else GREEN)
        add("> ", GREEN)
        add(msg.text, WHITE)
        holder.text.text = sb
        holder.itemView.setOnLongClickListener {
            onLongPress(msg)
            true
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setAll(list: List<ChatMessage>) {
        messages.clear()
        messages.addAll(list)
        notifyDataSetChanged()
    }
}
