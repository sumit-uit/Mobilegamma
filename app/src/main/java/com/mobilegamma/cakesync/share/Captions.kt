package com.mobilegamma.cakesync.share

import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.data.Photo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Template captions (no AI): fills a category's caption template with details from the
 * photos being shared.
 *
 * Placeholders: {category}, {order}, {business}, {date}, {hashtags}. Lines whose
 * placeholders are all empty are dropped, so one template works with and without an order.
 */
object Captions {
    const val DEFAULT_TEMPLATE = "{category} 🎂\n{order}\n{business}\n\n{hashtags}"

    val PLACEHOLDERS = listOf("{category}", "{order}", "{business}", "{date}", "{hashtags}")

    fun build(photos: List<Photo>, category: Category?, business: String): String {
        val template = category?.captionTemplate?.ifBlank { null } ?: DEFAULT_TEMPLATE
        val order = photos.mapNotNull { it.orderTag }.distinct().singleOrNull().orEmpty()
        val date = photos.maxOfOrNull { it.takenAtMillis }
            ?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) }.orEmpty()
        val values = mapOf(
            "{category}" to (category?.name ?: ""),
            "{order}" to order,
            "{business}" to business.trim(),
            "{date}" to date,
            "{hashtags}" to normaliseHashtags(category?.hashtags.orEmpty()),
        )
        return template.lines()
            .mapNotNull { line ->
                val used = values.keys.filter { it in line }
                if (used.isNotEmpty() && used.all { values[it].isNullOrBlank() }) return@mapNotNull null
                values.entries.fold(line) { acc, (k, v) -> acc.replace(k, v) }.trimEnd()
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** "cake, homebaker #pune" -> "#cake #homebaker #pune". */
    fun normaliseHashtags(raw: String): String =
        raw.split(Regex("[\\s,]+")).map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
            .distinct().joinToString(" ") { "#$it" }
}
