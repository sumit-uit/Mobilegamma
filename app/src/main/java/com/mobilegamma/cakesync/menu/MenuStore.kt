package com.mobilegamma.cakesync.menu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Currency
import java.util.Locale

/** Everything about the menu, saved as one small JSON file. */
data class MenuData(
    val settings: MenuSettings = MenuSettings(),
    val tables: List<PriceTable> = emptyList(),
    val items: List<MenuItem> = emptyList(),
    /** Photos the user swiped away, so their designs aren't suggested again. */
    val skipped: Set<Long> = emptySet(),
) {
    fun table(categoryId: String?): PriceTable? = tables.firstOrNull { it.categoryId == categoryId }

    /** Photos already on the menu or skipped. */
    val handled: Set<Long> get() = skipped + items.flatMap { it.photoIds }

    fun price(item: MenuItem): String? = Pricing.summary(item, table(item.categoryId), settings)
}

class MenuStore(context: Context) {
    private val file = File(context.filesDir, "menu.json")

    /** The phone's currency symbol, e.g. "$" in Canada, "₹" in India. */
    private val localCurrency = runCatching { Currency.getInstance(Locale.getDefault()).getSymbol(Locale.getDefault()) }
        .getOrNull()?.takeIf { it.length <= 3 } ?: "$"

    @Synchronized
    fun load(): MenuData = runCatching {
        if (!file.exists()) return MenuData(settings = MenuSettings(currency = localCurrency))
        val o = JSONObject(file.readText())
        val s = o.optJSONObject("settings") ?: JSONObject()
        val defaults = MenuSettings(currency = localCurrency)
        MenuData(
            settings = MenuSettings(
                currency = s.optString("currency", defaults.currency),
                title = s.optString("title", defaults.title),
                note = s.optString("note", defaults.note),
                levels = s.optJSONArray("levels")?.objects()?.map { l ->
                    DesignLevel(l.getString("id"), l.getString("name"), if (l.has("from")) l.getDouble("from") else null)
                }?.ifEmpty { null } ?: defaults.levels,
                extras = s.optJSONArray("extras")?.objects()?.map { e -> Extra(e.getString("name"), e.optString("price")) } ?: emptyList(),
            ),
            tables = o.optJSONArray("tables").objects().map { t ->
                val sizes = t.optJSONArray("sizes").objects().map { SizeCol(it.getString("label"), it.optString("serves")) }
                PriceTable(
                    categoryId = t.getString("categoryId"),
                    sizes = sizes,
                    flavours = t.optJSONArray("flavours").objects().map { f ->
                        val prices = f.optJSONArray("prices")
                        FlavourRow(f.getString("name"), sizes.indices.map { i ->
                            if (prices == null || i >= prices.length() || prices.isNull(i)) null else prices.getDouble(i)
                        })
                    },
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
                    levelId = i.optString("levelId", DesignLevels.SIMPLE),
                    priceOverride = if (i.has("priceOverride")) i.getDouble("priceOverride") else null,
                    description = i.optString("description"),
                    addedAt = i.optLong("addedAt"),
                )
            },
            skipped = o.optJSONArray("skipped")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet(),
        )
    }.getOrDefault(MenuData(settings = MenuSettings(currency = localCurrency)))

    @Synchronized
    fun save(data: MenuData) {
        val st = data.settings
        val o = JSONObject()
        o.put("settings", JSONObject().put("currency", st.currency).put("title", st.title).put("note", st.note)
            .put("levels", JSONArray(st.levels.map { l -> JSONObject().put("id", l.id).put("name", l.name).apply { l.fromPrice?.let { put("from", it) } } }))
            .put("extras", JSONArray(st.extras.map { e -> JSONObject().put("name", e.name).put("price", e.price) })))
        o.put("tables", JSONArray(data.tables.map { t ->
            JSONObject().put("categoryId", t.categoryId)
                .put("sizes", JSONArray(t.sizes.map { JSONObject().put("label", it.label).put("serves", it.serves) }))
                .put("flavours", JSONArray(t.flavours.map { f ->
                    JSONObject().put("name", f.name).put("prices", JSONArray().apply { f.prices.forEach { put(it ?: JSONObject.NULL) } })
                }))
        }))
        o.put("items", JSONArray(data.items.map { i ->
            JSONObject().put("id", i.id).put("title", i.title).put("categoryId", i.categoryId ?: "")
                .put("photoIds", JSONArray(i.photoIds)).put("heroId", i.heroId).put("heroUri", i.heroUri)
                .put("levelId", i.levelId).put("description", i.description).put("addedAt", i.addedAt)
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
