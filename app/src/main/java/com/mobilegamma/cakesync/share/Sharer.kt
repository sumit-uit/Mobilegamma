package com.mobilegamma.cakesync.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands photos and videos to Instagram, WhatsApp or any app through Android's share
 * feature. No Meta API: the user presses Post in the other app. The caption is also put
 * on the clipboard because Instagram ignores captions passed by other apps.
 */
object Sharer {

    enum class Target(val label: String, val packages: List<String>) {
        INSTAGRAM("Instagram", listOf("com.instagram.android")),
        WHATSAPP("WhatsApp", listOf("com.whatsapp.w4b", "com.whatsapp")),
        OTHER("Other apps…", emptyList()),
    }

    /** Result shown to the user, e.g. "Caption copied — paste it in Instagram". */
    fun share(context: Context, items: List<Pair<Uri, String>>, caption: String, target: Target): String {
        if (items.isEmpty()) return "Nothing to share"
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (caption.isNotBlank()) clipboard.setPrimaryClip(ClipData.newPlainText("Caption", caption))

        val uris = ArrayList(items.map { it.first })
        val mimes = items.map { it.second }
        val type = when {
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.apply {
            this.type = type
            if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            // Grant the receiving app read access to every item.
            clipData = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val installed = target.packages.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
        val intent = if (installed != null) {
            send.setPackage(installed)
        } else {
            Intent.createChooser(send, "Share ${items.size} item(s)")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return when {
            target != Target.OTHER && installed == null -> "${target.label} isn't installed, so pick an app instead. Caption copied."
            target == Target.INSTAGRAM -> "Caption copied: paste it in Instagram before posting"
            else -> "Shared ${items.size} item(s). Caption copied too."
        }
    }
}
