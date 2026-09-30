package com.mobilegamma.cakesync.orders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Orders and the order setup, saved as one JSON file. */
data class OrderData(val settings: OrderSettings = OrderSettings(), val orders: List<Order> = emptyList())

class OrderStore(context: Context) {
    private val file = File(context.filesDir, "orders.json")

    @Synchronized
    fun load(): OrderData = runCatching {
        if (!file.exists()) return OrderData()
        val o = JSONObject(file.readText())
        OrderData(settings(o.optJSONObject("settings") ?: JSONObject()), o.optJSONArray("orders").objects().map(::order))
    }.getOrDefault(OrderData())

    @Synchronized
    fun save(data: OrderData) {
        val o = JSONObject().put("settings", settingsJson(data.settings)).put("orders", JSONArray(data.orders.map(::orderJson)))
        val tmp = File(file.parentFile, "orders.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun update(change: (OrderData) -> OrderData): OrderData = synchronized(this) { change(load()).also { save(it) } }

    private fun settings(s: JSONObject) = OrderSettings(
        configured = s.optBoolean("configured"),
        whatsapp = s.optString("whatsapp"),
        messengerPage = s.optString("messengerPage"),
        instagram = s.optString("instagram"),
        email = s.optString("email"),
        advancePercent = s.optInt("advancePercent", 50),
        paymentNote = s.optString("paymentNote"),
        cancelDays = s.optInt("cancelDays", 4),
        leadDays = s.optInt("leadDays", 3),
        delivers = s.optBoolean("delivers"),
        deliveryFee = s.optString("deliveryFee"),
        questions = s.optJSONArray("questions")?.strings()?.toSet() ?: OrderSettings().questions,
        customQuestions = s.optJSONArray("customQuestions")?.strings() ?: emptyList(),
        calendarId = if (s.has("calendarId")) s.getLong("calendarId") else null,
        calendarName = s.optString("calendarName"),
        importBookings = s.optBoolean("importBookings", true),
        bookingLink = s.optString("bookingLink"),
        reminderHours = s.optJSONArray("reminderHours")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(24, 3),
    )

    private fun settingsJson(s: OrderSettings) = JSONObject()
        .put("configured", s.configured).put("whatsapp", s.whatsapp).put("messengerPage", s.messengerPage)
        .put("instagram", s.instagram).put("email", s.email).put("advancePercent", s.advancePercent)
        .put("paymentNote", s.paymentNote).put("cancelDays", s.cancelDays).put("leadDays", s.leadDays)
        .put("delivers", s.delivers).put("deliveryFee", s.deliveryFee)
        .put("questions", JSONArray(s.questions.toList())).put("customQuestions", JSONArray(s.customQuestions))
        .put("calendarName", s.calendarName).put("importBookings", s.importBookings).put("bookingLink", s.bookingLink)
        .put("reminderHours", JSONArray(s.reminderHours))
        .apply { s.calendarId?.let { put("calendarId", it) } }

    private fun order(o: JSONObject) = Order(
        id = o.getString("id"),
        customer = o.optString("customer"),
        contact = o.optString("contact"),
        due = o.optString("due").ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        time = o.optString("time").ifBlank { null }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        delivery = o.optBoolean("delivery"),
        address = o.optString("address"),
        designId = o.optString("designId").ifBlank { null },
        designTitle = o.optString("designTitle"),
        categoryId = o.optString("categoryId").ifBlank { null },
        size = o.optString("size"),
        flavour = o.optString("flavour"),
        levelId = o.optString("levelId").ifBlank { null },
        options = o.optJSONArray("options")?.strings()?.toSet() ?: emptySet(),
        occasion = o.optString("occasion"),
        message = o.optString("message"),
        allergies = o.optString("allergies"),
        notes = o.optString("notes"),
        answers = o.optJSONObject("answers")?.let { a -> a.keys().asSequence().associateWith { a.getString(it) } } ?: emptyMap(),
        references = o.optJSONArray("references")?.strings() ?: emptyList(),
        price = if (o.has("price")) o.getDouble("price") else null,
        advancePaid = o.optDouble("advancePaid", 0.0),
        status = runCatching { OrderStatus.valueOf(o.getString("status")) }.getOrDefault(OrderStatus.ENQUIRY),
        calendarEventId = if (o.has("calendarEventId")) o.getLong("calendarEventId") else null,
        fromBooking = o.optBoolean("fromBooking"),
        createdAt = o.optLong("createdAt"),
    )

    private fun orderJson(o: Order) = JSONObject()
        .put("id", o.id).put("customer", o.customer).put("contact", o.contact)
        .put("due", o.due?.toString() ?: "").put("time", o.time?.toString() ?: "")
        .put("delivery", o.delivery).put("address", o.address).put("designId", o.designId ?: "")
        .put("designTitle", o.designTitle).put("categoryId", o.categoryId ?: "").put("size", o.size)
        .put("flavour", o.flavour).put("levelId", o.levelId ?: "").put("options", JSONArray(o.options.toList()))
        .put("occasion", o.occasion).put("message", o.message).put("allergies", o.allergies).put("notes", o.notes)
        .put("answers", JSONObject(o.answers)).put("references", JSONArray(o.references))
        .put("advancePaid", o.advancePaid).put("status", o.status.name).put("createdAt", o.createdAt).put("fromBooking", o.fromBooking)
        .apply {
            o.price?.let { put("price", it) }
            o.calendarEventId?.let { put("calendarEventId", it) }
        }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
