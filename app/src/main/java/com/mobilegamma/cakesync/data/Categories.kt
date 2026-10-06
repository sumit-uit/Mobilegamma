package com.mobilegamma.cakesync.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Something the user photographs and wants sorted, e.g. Cake, Cupcakes, Jewellery.
 * A photo belongs to the category whose labels it matches most strongly.
 */
data class Category(
    val id: String,
    val name: String,
    /** Comma-separated ML Kit labels, e.g. "Cake, Icing". */
    val labels: String,
    /** Minimum ML Kit confidence (0..1) for a label to count. */
    val threshold: Float,
    /** Top-level Drive folder for this category's uploads. */
    val driveFolder: String,
    /** Hashtags added to captions, e.g. "#homebaker #cake #customcakes". */
    val hashtags: String = "",
    /** Caption template; see [Captions] for the {placeholders}. Blank = default template. */
    val captionTemplate: String = "",
) {
    fun labelSet(): Set<String> =
        labels.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Highest confidence among this category's labels (0 if none). */
    fun score(found: List<Pair<String, Float>>): Float {
        val wanted = labelSet()
        return found.filter { it.first.lowercase() in wanted }.maxOfOrNull { it.second } ?: 0f
    }
}

/** The category a photo matches, if any, and the best score seen across categories. */
data class Classification(val category: Category?, val score: Float) {
    val isMatch: Boolean get() = category != null
}

/** The user's categories, stored as JSON in the app preferences. */
class Categories(context: Context) {
    private val prefs = context.getSharedPreferences("cakesync", Context.MODE_PRIVATE)
    private val settings = Settings(context)

    fun all(): List<Category> {
        val json = prefs.getString(KEY, null)
            ?: return listOf(defaultFromOldSettings()).also { save(it) }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Category(
                id = o.getString("id"),
                name = o.getString("name"),
                labels = o.getString("labels"),
                threshold = o.getDouble("threshold").toFloat(),
                driveFolder = o.getString("driveFolder"),
                hashtags = o.optString("hashtags", ""),
                captionTemplate = o.optString("captionTemplate", ""),
            )
        }.ifEmpty { listOf(defaultFromOldSettings()) }
    }

    fun save(categories: List<Category>) {
        val array = JSONArray()
        categories.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("labels", it.labels)
                    .put("threshold", it.threshold.toDouble())
                    .put("driveFolder", it.driveFolder)
                    .put("hashtags", it.hashtags)
                    .put("captionTemplate", it.captionTemplate)
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    fun upsert(category: Category) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == category.id }
        if (i >= 0) list[i] = category else list += category
        save(list)
    }

    fun delete(id: String) {
        val list = all().filterNot { it.id == id }
        if (list.isNotEmpty()) save(list) // always keep at least one category
    }

    fun byId(id: String?): Category? = all().firstOrNull { it.id == id }

    /** Picks the category whose labels match most strongly, above its own threshold. */
    fun classify(found: List<Pair<String, Float>>, categories: List<Category> = all()): Classification {
        val scored = categories.map { it to it.score(found) }
        val best = scored.filter { (c, s) -> s > 0f && s >= c.threshold }.maxByOrNull { it.second }
        return Classification(best?.first, best?.second ?: (scored.maxOfOrNull { it.second } ?: 0f))
    }

    // The first category carries over the single "Cake" setup from earlier versions, so
    // existing matches and the existing Drive folder stay the same.
    private fun defaultFromOldSettings() = Category(
        id = DEFAULT_ID,
        name = "Cake",
        labels = settings.targetLabels,
        threshold = settings.threshold,
        driveFolder = settings.driveFolderName,
    )

    companion object {
        const val DEFAULT_ID = "default"
        private const val KEY = "categories"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
