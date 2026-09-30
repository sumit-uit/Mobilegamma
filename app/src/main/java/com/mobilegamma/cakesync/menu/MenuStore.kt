package com.mobilegamma.cakesync.menu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything about the menu, saved as one small JSON file. */
data class MenuData(
    val settings: MenuSettings = MenuSettings(),
    val cards: List<PriceCard> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    /** Photos the user swiped away, so their designs aren't suggested again. */
    val skipped: Set<Long> = emptySet(),
) {
    fun card(categoryId: String?): PriceCard? = cards.firstOrNull { it.categoryId == categoryId }

    /** Photos already on the menu or skipped. */
    val handled: Set<Long> get() = skipped + items.flatMap { it.photoIds }
}

class MenuStore(context: Context) {
    private val file = File(context.filesDir, "menu.json")

    @Synchronized
    fun load(): MenuData = runCatching {
        if (!file.exists()) return MenuData()
        val o = JSONObject(file.readText())
        val s = o.optJSONObject("settings") ?: JSONObject()
        val defaults = MenuSettings()
        MenuData(
            settings = MenuSettings(
                currency = s.optString("currency", defaults.currency),
                title = s.optString("title", defaults.title),
                note = s.optString("note", defaults.note),
            ),
            cards = o.optJSONArray("cards").objects().map { c ->
                PriceCard(
                    categoryId = c.getString("categoryId"),
                    mode = runCatching { PriceMode.valueOf(c.getString("mode")) }.getOrDefault(PriceMode.PER_KG),
                    price = c.optDouble("price", 0.0),
                    sizes = c.optJSONArray("sizes")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: listOf(0.5, 1.0, 2.0),
                    themeExtra = c.optDouble("themeExtra", 0.0),
                )
            },
            items = o.optJSONArray("items").objects().map { i ->
                MenuItem(
                    id = i.getString("id"),
                    title = i.getString("title"),
                    categoryId = i.optString("categoryId").ifBlank { null },
                    photoIds = i.optJSONArray("photoIds")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
                    heroId = i.getLong("heroId"),
                    heroUri = i.getString("heroUri"),
                    tier = runCatching { Tier.valueOf(i.getString("tier")) }.getOrDefault(Tier.STANDARD),
                    priceOverride = if (i.has("priceOverride")) i.getDouble("priceOverride") else null,
                    description = i.optString("description"),
                    addedAt = i.optLong("addedAt"),
                )
            },
            skipped = o.optJSONArray("skipped")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet(),
        )
    }.getOrDefault(MenuData())

    @Synchronized
    fun save(data: MenuData) {
        val o = JSONObject()
        o.put("settings", JSONObject().put("currency", data.settings.currency).put("title", data.settings.title).put("note", data.settings.note))
        o.put("cards", JSONArray(data.cards.map { c ->
            JSONObject().put("categoryId", c.categoryId).put("mode", c.mode.name).put("price", c.price)
                .put("sizes", JSONArray(c.sizes)).put("themeExtra", c.themeExtra)
        }))
        o.put("items", JSONArray(data.items.map { i ->
            JSONObject().put("id", i.id).put("title", i.title).put("categoryId", i.categoryId ?: "")
                .put("photoIds", JSONArray(i.photoIds)).put("heroId", i.heroId).put("heroUri", i.heroUri)
                .put("tier", i.tier.name).put("description", i.description).put("addedAt", i.addedAt)
                .apply { i.priceOverride?.let { put("priceOverride", it) } }
        }))
        o.put("skipped", JSONArray(data.skipped.toList()))
        val tmp = File(file.parentFile, "menu.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun update(change: (MenuData) -> MenuData): MenuData = synchronized(this) { change(load()).also { save(it) } }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
