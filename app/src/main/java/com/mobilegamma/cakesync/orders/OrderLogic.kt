package com.mobilegamma.cakesync.orders

import com.mobilegamma.cakesync.menu.DesignLevel
import com.mobilegamma.cakesync.menu.Extra
import com.mobilegamma.cakesync.menu.MenuSettings
import com.mobilegamma.cakesync.menu.PriceTable
import com.mobilegamma.cakesync.menu.Pricing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Where an order is in its life. */
enum class OrderStatus(val label: String, val emoji: String) {
    ENQUIRY("Enquiry", "💬"),
    QUOTED("Quoted", "🧾"),
    CONFIRMED("Confirmed", "✅"),
    READY("Ready", "📦"),
    DONE("Delivered", "🎉"),
    CANCELLED("Cancelled", "✕"),
    ;

    val active: Boolean get() = this != DONE && this != CANCELLED
}

/** Questions a bakery can choose to ask on its order form. */
enum class Question(val key: String, val label: String, val emoji: String, val onByDefault: Boolean) {
    OCCASION("occasion", "Occasion", "🎉", true),
    MESSAGE("message", "Message on cake", "✍️", true),
    EGGLESS("eggless", "Eggless", "🥚", true),
    ALLERGIES("allergies", "Allergies", "⚠️", false),
    TIME("time", "Pickup / delivery time", "⏰", true),
    DELIVERY("delivery", "Pickup or delivery (address)", "🚗", true),
    REFERENCE("reference", "Reference photo", "📷", true),
}

/** How the bakery takes orders: set once during setup, editable in Settings. */
data class OrderSettings(
    val configured: Boolean = false,
    val whatsapp: String = "",
    val messengerPage: String = "",
    val instagram: String = "",
    val email: String = "",
    val advancePercent: Int = 50,
    /** How to pay the advance, e.g. "Interac e-Transfer to orders@example.com". */
    val paymentNote: String = "",
    val cancelDays: Int = 4,
    val leadDays: Int = 3,
    val delivers: Boolean = false,
    val deliveryFee: String = "",
    val questions: Set<String> = Question.entries.filter { it.onByDefault }.map { it.key }.toSet(),
    val customQuestions: List<String> = emptyList(),
    /** Calendar (from the phone's calendars, e.g. the user's Google Calendar) for orders; null = none. */
    val calendarId: Long? = null,
    val calendarName: String = "",
    /** Also show bookings from this calendar as possible orders to import. */
    val importBookings: Boolean = true,
    /** The bakery's Google Calendar booking page, shared with customers. */
    val bookingLink: String = "",
    /** A link to the bakery's menu (their website's menu page, a shared PDF, …). */
    val menuLink: String = "",
    val website: String = "",
    /** Reminders before each order, in hours. */
    val reminderHours: List<Int> = listOf(24, 3),
) {
    fun asks(q: Question) = q.key in questions
}

/** One customer order. */
data class Order(
    val id: String,
    val customer: String = "",
    val contact: String = "",
    val due: LocalDate? = null,
    val time: LocalTime? = null,
    val delivery: Boolean = false,
    val address: String = "",
    val designId: String? = null,
    val designTitle: String = "",
    val categoryId: String? = null,
    val size: String = "",
    val flavour: String = "",
    val levelId: String? = null,
    /** Names of chosen options/extras from the menu (e.g. "Eggless"). */
    val options: Set<String> = emptySet(),
    val occasion: String = "",
    val message: String = "",
    val allergies: String = "",
    val notes: String = "",
    /** Answers to the bakery's own questions. */
    val answers: Map<String, String> = emptyMap(),
    val references: List<String> = emptyList(),
    /** Price agreed; null = use the calculated price. */
    val price: Double? = null,
    val advancePaid: Double = 0.0,
    val status: OrderStatus = OrderStatus.ENQUIRY,
    val calendarEventId: Long? = null,
    /** Imported from a calendar booking: the event belongs to the booking, don't delete it. */
    val fromBooking: Boolean = false,
    val createdAt: Long = 0L,
) {
    /** "8" Rasmalai · Fondant" */
    fun cakeSummary(levelName: String?): String = listOfNotNull(
        size.ifBlank { null },
        flavour.ifBlank { null },
        levelName?.takeUnless { it.equals("Simple", true) },
    ).joinToString(" ").ifBlank { designTitle.ifBlank { "Cake" } }
}

/** Works out prices from the menu. */
object Quote {

    /** The base cake price from the price table for the order's flavour and size. */
    fun basePrice(order: Order, table: PriceTable?): Double? {
        table ?: return null
        val col = table.sizes.indexOfFirst { it.label.equals(order.size, true) }.takeIf { it >= 0 } ?: return null
        val row = table.flavours.firstOrNull { it.name.equals(order.flavour, true) } ?: return null
        return row.prices.getOrNull(col)
    }

    /** A percentage in an extra's price text, e.g. "+20%" → 20. */
    fun percentOf(extra: Extra): Double? = Regex("""(\d+(?:\.\d+)?)\s*%""").find(extra.price)?.groupValues?.get(1)?.toDouble()

    /** A fixed amount in an extra's price text, e.g. "$15 per page" → 15 (ranges use the low end). */
    fun amountOf(extra: Extra): Double? =
        if (percentOf(extra) != null) null else Regex("""(\d+(?:\.\d+)?)""").find(extra.price)?.groupValues?.get(1)?.toDouble()

    /**
     * Base price (at least the design level's "from" price) plus chosen options: percentages
     * apply to the base, fixed amounts are added. Null when the base isn't known.
     */
    fun calculate(order: Order, table: PriceTable?, menu: MenuSettings): Double? {
        val level = menu.levels.firstOrNull { it.id == order.levelId }
        val base = listOfNotNull(basePrice(order, table), level?.fromPrice).maxOrNull() ?: return null
        val chosen = menu.extras.filter { it.name in order.options }
        val percent = chosen.mapNotNull { percentOf(it) }.sum()
        val fixed = chosen.mapNotNull { amountOf(it) }.sum()
        return base * (1 + percent / 100.0) + fixed
    }

    fun total(order: Order, table: PriceTable?, menu: MenuSettings): Double? = order.price ?: calculate(order, table, menu)

    fun advance(total: Double, settings: OrderSettings): Double = total * settings.advancePercent / 100.0
}

/** Formats dates for people ("Sat 12 Oct") and for order messages. */
object OrderDates {
    fun pretty(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " +
            date.dayOfMonth + " " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    fun time(t: LocalTime): String = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * Understands "12 Oct", "Oct 12", "October 12th", "2026-10-12", "12/10" (day first, or
     * month first when the first number can't be a day... it is ambiguous, so day first),
     * "today" and "tomorrow". Returns the next such date on or after [today].
     */
    fun parse(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        if (t.startsWith("today")) return today
        if (t.startsWith("tomorrow")) return today.plusDays(1)
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(t)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        fun next(month: Int, day: Int, year: Int? = null): LocalDate? = runCatching {
            if (year != null) return LocalDate.of(if (year < 100) 2000 + year else year, month, day)
            val d = LocalDate.of(today.year, month, day)
            if (d.isBefore(today)) d.plusYears(1) else d
        }.getOrNull()
        val monthIdx = months.indexOfFirst { t.contains(it) }
        if (monthIdx >= 0) {
            val day = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\b""").find(t)?.groupValues?.get(1)?.toInt() ?: return null
            val year = Regex("""\b(\d{4})\b""").find(t)?.groupValues?.get(1)?.toInt()
            return next(monthIdx + 1, day, year)
        }
        Regex("""(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?""").find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].toIntOrNull()
            return if (a > 12) next(b, a, year) else if (b > 12) next(a, b, year) else next(b, a, year)
        }
        return null
    }

    /** "5 pm", "17:30", "10:30am" → a time. */
    fun parseTime(text: String): LocalTime? {
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].lowercase().replace(".", "")
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return runCatching { LocalTime.of(h, min) }.getOrNull()
    }
}

/**
 * The order message customers fill in (sent to them as a template, or pre-filled by an
 * "Order" link) and the parser that reads it back when the baker shares it into the app.
 */
object OrderMessage {
    const val NAME = "Name"
    const val PHONE = "Phone"
    const val DESIGN = "Design"
    const val SIZE = "Size"
    const val FLAVOUR = "Flavour"
    const val DATE = "Date needed"
    /** Lines only the web order form adds. */
    const val STYLE = "Style"
    const val EXTRAS = "Extras"

    /** The lines of the form, in order, as (emoji, label). */
    fun fields(settings: OrderSettings): List<Pair<String, String>> = buildList {
        add("🙋" to NAME)
        add("📞" to PHONE)
        add("🎂" to DESIGN)
        add("📏" to SIZE)
        add("🍰" to FLAVOUR)
        Question.entries.filter { settings.asks(it) && it != Question.REFERENCE }.forEach { add(it.emoji to it.label) }
        add("📅" to DATE)
        settings.customQuestions.filter { it.isNotBlank() }.forEach { add("❓" to it.trim()) }
    }

    /** The template text, optionally pre-filled with a design, size and flavour. */
    fun template(settings: OrderSettings, business: String, design: String = "", size: String = "", flavour: String = ""): String =
        buildString {
            append("Hi ${business.ifBlank { "there" }}! I'd like to order a cake:\n")
            fields(settings).forEach { (emoji, label) ->
                val value = when (label) {
                    DESIGN -> design
                    SIZE -> size
                    FLAVOUR -> flavour
                    else -> ""
                }
                append("$emoji $label: $value\n")
            }
            if (settings.asks(Question.REFERENCE)) append("📷 (attach a reference photo if you have one)\n")
        }.trimEnd()

    /** Reads "Label: value" lines from a filled-in message (emoji and case don't matter). */
    fun parseFields(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val cut = line.indexOf(':')
            if (cut <= 0) return@forEach
            val label = line.substring(0, cut).filter { it.isLetterOrDigit() || it == ' ' || it == '/' || it == '(' || it == ')' }.trim()
            val value = line.substring(cut + 1).trim()
            if (label.isNotEmpty() && value.isNotEmpty()) out[label.lowercase()] = value
        }
        return out
    }

    /** A new order from a filled-in message; unknown lines go into the notes. */
    fun toOrder(
        text: String,
        settings: OrderSettings,
        id: String,
        menuExtras: List<Extra>,
        today: LocalDate = LocalDate.now(),
        levels: List<DesignLevel> = emptyList(),
    ): Order {
        val f = parseFields(text)
        fun get(label: String) = f[label.lowercase()].orEmpty()
        val yes = setOf("yes", "y", "yeah", "yep", "eggless", "true")
        val eggless = get(Question.EGGLESS.label).lowercase().trim() in yes
        val deliveryText = get(Question.DELIVERY.label)
        val known = (fields(settings).map { it.second.lowercase() } + listOf(STYLE.lowercase(), EXTRAS.lowercase())).toSet()
        val custom = settings.customQuestions.associateWith { q -> get(q) }.filterValues { it.isNotBlank() }
        val extra = f.filterKeys { it !in known }.map { (k, v) -> "$k: $v" }
        val egglessOption = menuExtras.firstOrNull { it.name.contains("eggless", true) }?.name
        val picked = get(EXTRAS).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val extrasChosen = menuExtras.filter { e -> picked.any { it.equals(e.name, true) } }.map { it.name }.toSet()
        val unknownExtras = picked.filter { p -> menuExtras.none { it.name.equals(p, true) } }
        val level = get(STYLE).takeIf { it.isNotBlank() }?.let { s -> levels.firstOrNull { it.name.equals(s, true) } }
        return Order(
            id = id,
            customer = get(NAME),
            contact = get(PHONE),
            due = OrderDates.parse(get(DATE), today),
            time = get(Question.TIME.label).takeIf { it.isNotBlank() }?.let { OrderDates.parseTime(it) },
            delivery = deliveryText.contains("deliver", true),
            address = if (deliveryText.contains("deliver", true)) {
                deliveryText.replace(Regex("""(?i)^\s*deliver(y|ed)?\b\s*(to)?\s*[-:,(]?\s*"""), "").trim(' ', ')', ',')
            } else "",
            designTitle = get(DESIGN),
            size = get(SIZE),
            flavour = get(FLAVOUR),
            levelId = level?.id,
            options = extrasChosen + if (eggless && egglessOption != null) setOf(egglessOption) else emptySet(),
            occasion = get(Question.OCCASION.label),
            message = get(Question.MESSAGE.label),
            allergies = get(Question.ALLERGIES.label),
            answers = custom,
            notes = (extra + unknownExtras.map { "extra: $it" }).joinToString("\n"),
            createdAt = System.currentTimeMillis(),
        )
    }

    /** The quote sent back to the customer. */
    fun quote(order: Order, total: Double?, levelName: String?, menu: MenuSettings, settings: OrderSettings, business: String): String =
        buildString {
            append("Hi ${order.customer.ifBlank { "there" }}, thank you for your order")
            if (business.isNotBlank()) append(" with $business")
            append("! 🎂\n\n")
            append("• Cake: ${order.cakeSummary(levelName)}\n")
            if (order.designTitle.isNotBlank()) append("• Design: ${order.designTitle}\n")
            if (order.options.isNotEmpty()) append("• Options: ${order.options.joinToString(", ")}\n")
            if (order.message.isNotBlank()) append("• Message: ${order.message}\n")
            order.due?.let { d ->
                append("• ${if (order.delivery) "Delivery" else "Pickup"}: ${OrderDates.pretty(d)}")
                order.time?.let { append(", ${OrderDates.time(it)}") }
                append("\n")
            }
            if (order.delivery && order.address.isNotBlank()) append("• Address: ${order.address}\n")
            if (total != null) {
                append("\nTotal: ${Pricing.format(total, menu.currency)}")
                if (order.delivery && settings.deliveryFee.isNotBlank()) append(" + delivery ${settings.deliveryFee}")
                append("\n")
                if (settings.advancePercent > 0) {
                    append("To confirm, please pay a ${settings.advancePercent}% advance: ${Pricing.format(Quote.advance(total, settings), menu.currency)}")
                    if (settings.paymentNote.isNotBlank()) append(" (${settings.paymentNote})")
                    append(".\n")
                }
            }
            if (settings.cancelDays > 0) append("Cancel ${settings.cancelDays} days before for a full refund of the advance.\n")
        }.trimEnd()

    /** Calendar event title, e.g. "🎂 Priya · 8" Rasmalai (Pickup 5:00 PM)". */
    fun eventTitle(order: Order, levelName: String?): String =
        "${order.status.emoji} ${order.customer.ifBlank { "Order" }} · ${order.cakeSummary(levelName)}" +
            (if (order.delivery) " (Delivery)" else " (Pickup)")

    /** Calendar event description with everything needed to bake and hand over. */
    fun eventDescription(order: Order, total: Double?, levelName: String?, menu: MenuSettings): String = buildString {
        append("Status: ${order.status.label}\n")
        if (order.contact.isNotBlank()) append("Contact: ${order.contact}\n")
        append("Cake: ${order.cakeSummary(levelName)}\n")
        if (order.designTitle.isNotBlank()) append("Design: ${order.designTitle}\n")
        if (order.options.isNotEmpty()) append("Options: ${order.options.joinToString(", ")}\n")
        if (order.occasion.isNotBlank()) append("Occasion: ${order.occasion}\n")
        if (order.message.isNotBlank()) append("Message on cake: ${order.message}\n")
        if (order.allergies.isNotBlank()) append("Allergies: ${order.allergies}\n")
        order.answers.forEach { (q, a) -> append("$q: $a\n") }
        if (order.delivery) append("Delivery to: ${order.address}\n")
        total?.let { append("Total: ${Pricing.format(it, menu.currency)} · Advance paid: ${Pricing.format(order.advancePaid, menu.currency)}\n") }
        if (order.notes.isNotBlank()) append("Notes: ${order.notes}\n")
        append("\n(Made with CakeSync)")
    }
}
