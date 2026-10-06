package com.mobilegamma.cakesync.orders

import com.mobilegamma.cakesync.menu.DesignLevel
import com.mobilegamma.cakesync.menu.Extra
import com.mobilegamma.cakesync.menu.PriceTable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A price table as shown on the order form, e.g. "Round cakes" with its sizes and flavours. */
data class FormTable(val name: String, val table: PriceTable)

/**
 * The customer order form: a web page (docs/order in this repo) that customers fill in on
 * their phone. Everything it shows (questions, sizes, flavours, prices, contacts) travels in
 * the link itself, compressed, so no server stores anything. When the customer taps Send, the
 * page opens WhatsApp or email with the filled-in order, in the same "Label: value" lines
 * [OrderMessage.toOrder] reads back.
 */
object OrderForm {
    const val BASE_URL = "https://sumit-uit.github.io/cakesync/order/"

    fun json(
        settings: OrderSettings,
        business: String,
        currency: String,
        tables: List<FormTable>,
        levels: List<DesignLevel>,
        extras: List<Extra>,
        design: String = "",
    ): String {
        val o = JSONObject()
        o.put("v", 1)
        fun opt(key: String, value: String) { if (value.isNotBlank()) o.put(key, value.trim()) }
        opt("b", business)
        opt("c", currency)
        opt("wa", settings.whatsapp.filter { it.isDigit() })
        opt("em", settings.email)
        opt("ig", settings.instagram.trim().removePrefix("@"))
        opt("fb", settings.messengerPage)
        opt("web", settings.website)
        opt("book", settings.bookingLink)
        opt("menu", settings.menuLink)
        opt("d", design)
        o.put("q", JSONArray(Question.entries.filter { settings.asks(it) }.map { it.key }))
        settings.customQuestions.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { o.put("cq", JSONArray(it.map(String::trim))) }
        o.put("lead", settings.leadDays)
        if (settings.delivers) {
            o.put("del", true)
            opt("df", settings.deliveryFee)
        }
        if (settings.advancePercent > 0) o.put("adv", settings.advancePercent)
        opt("pay", settings.paymentNote)
        if (settings.cancelDays > 0) o.put("cancel", settings.cancelDays)
        val t = JSONArray()
        tables.filter { it.table.isSet }.forEach { ft ->
            t.put(
                JSONObject()
                    .put("n", ft.name)
                    .put("s", JSONArray(ft.table.sizes.map { JSONArray(listOf(it.label, it.serves)) }))
                    .put("f", JSONArray(ft.table.flavours.filter { it.name.isNotBlank() }.map { row -> JSONArray(listOf(row.name) + row.prices.map { it ?: JSONObject.NULL }) })),
            )
        }
        if (t.length() > 0) o.put("t", t)
        levels.takeIf { it.size > 1 }?.let { o.put("l", JSONArray(it.map { l -> JSONArray(listOf(l.name, l.fromPrice ?: JSONObject.NULL)) })) }
        extras.filter { it.name.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { o.put("x", JSONArray(it.map { e -> JSONArray(listOf(e.name, e.price)) })) }
        return o.toString()
    }

    fun link(json: String, base: String = BASE_URL): String = base + "#z" + encode(json)

    /** Raw deflate, then URL-safe base64: what the page's DecompressionStream("deflate-raw") reads. */
    fun encode(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decode(data: String): String {
        val inflater = Inflater(true)
        inflater.setInput(Base64.getUrlDecoder().decode(data))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }

    fun instagramUrl(handle: String): String? =
        handle.trim().removePrefix("@").takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://instagram.com/$it" }

    fun facebookUrl(page: String): String? =
        page.trim().takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://facebook.com/" + it.replace(" ", "") }

    fun webUrl(site: String): String? =
        site.trim().takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }

    /** The message sent to a customer: the form link plus the bakery's other links. */
    fun shareText(settings: OrderSettings, business: String, formLink: String, design: String = ""): String = buildString {
        append("🎂 ")
        append(if (design.isNotBlank()) "Order the $design" else "Order a cake")
        if (business.isNotBlank()) append(" from $business")
        append("\n\n📝 Fill in our quick order form (1 minute):\n$formLink\n")
        links(settings).takeIf { it.isNotEmpty() }?.let { append("\n$it") }
    }.trimEnd()

    /** The bakery's booking, menu and social links, one per line ("" when there are none). */
    fun links(settings: OrderSettings): String = listOfNotNull(
        webUrl(settings.bookingLink)?.let { "📅 Book a pickup time: $it" },
        webUrl(settings.menuLink)?.let { "📋 Menu & prices: $it" },
        instagramUrl(settings.instagram)?.let { "📷 Instagram: $it" },
        facebookUrl(settings.messengerPage)?.let { "👍 Facebook: $it" },
        webUrl(settings.website)?.let { "🌐 $it" },
    ).joinToString("\n")
}
