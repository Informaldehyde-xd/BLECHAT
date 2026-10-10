package com.yourapp.meshchat

import android.content.Context
import java.util.UUID

/** Small wrapper around SharedPreferences for the user's identity and settings. */
object Prefs {
    private const val FILE = "meshchat_prefs"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stable random id, created once and kept across launches. */
    fun nodeId(c: Context): String {
        val sp = sp(c)
        return sp.getString("node_id", null) ?: UUID.randomUUID().toString().take(6).also {
            sp.edit().putString("node_id", it).apply()
        }
    }

    fun name(c: Context): String = sp(c).getString("name", "") ?: ""

    fun setName(c: Context, name: String) = sp(c).edit().putString("name", name.trim()).apply()

    /** Name shown to others; falls back to a short id until the user picks one. */
    fun displayName(c: Context): String = name(c).ifBlank { "User-" + nodeId(c).take(4) }

    fun wifi(c: Context): Boolean = sp(c).getBoolean("wifi", false)

    fun setWifi(c: Context, on: Boolean) = sp(c).edit().putBoolean("wifi", on).apply()

    fun batteryAsked(c: Context): Boolean = sp(c).getBoolean("battery_asked", false)

    fun setBatteryAsked(c: Context) = sp(c).edit().putBoolean("battery_asked", true).apply()

    // ----- terminal background -----

    /** Solid background colour; black by default. */
    fun bgColor(c: Context): Int = sp(c).getInt("bg_color", 0xFF000000.toInt())

    fun bgIsImage(c: Context): Boolean = sp(c).getBoolean("bg_image", false)

    fun setBgColor(c: Context, color: Int) =
        sp(c).edit().putInt("bg_color", color).putBoolean("bg_image", false).apply()

    fun setBgImage(c: Context, on: Boolean) = sp(c).edit().putBoolean("bg_image", on).apply()
}
