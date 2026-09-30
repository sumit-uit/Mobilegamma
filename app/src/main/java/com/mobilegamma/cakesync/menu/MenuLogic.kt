package com.mobilegamma.cakesync.menu

import kotlin.math.abs
import kotlin.math.roundToLong

/** How a category is priced. */
enum class PriceMode(val label: String) { PER_KG("Per kg"), EACH("Each / box") }

/** Price tier of a design: normal, or themed/custom work that costs extra. */
enum class Tier(val label: String) { STANDARD("Standard"), THEME("Theme / custom") }

/**
 * A category's price rules, set once. For [PriceMode.PER_KG] [price] is per kg and [sizes]
 * are weights; for [PriceMode.EACH] [price] is per piece and [sizes] are box counts.
 */
data class PriceCard(
    val categoryId: String,
    val mode: PriceMode = PriceMode.PER_KG,
    val price: Double = 0.0,
    val sizes: List<Double> = listOf(0.5, 1.0, 2.0),
    /** Added to every size for [Tier.THEME] designs. */
    val themeExtra: Double = 0.0,
) {
    val isSet: Boolean get() = price > 0.0
}

/** One design on the menu: a group of photos of the same cake. */
data class MenuItem(
    val id: String,
    val title: String,
    val categoryId: String?,
    val photoIds: List<Long>,
    val heroId: Long,
    val heroUri: String,
    val tier: Tier = Tier.STANDARD,
    /** A fixed price for this design instead of the category's rules. */
    val priceOverride: Double? = null,
    /** Blank = generated from the template. */
    val description: String = "",
    val addedAt: Long = 0L,
)

/** Menu-wide settings. */
data class MenuSettings(
    val currency: String = "₹",
    val title: String = "Our Cakes",
    val note: String = "Made to order · Eggless on request · Order 48 hours ahead",
)

object Pricing {

    /** "₹1,600", with Indian digit grouping (1,00,000) for ₹ and Rs, otherwise 100,000. */
    fun format(amount: Double, currency: String): String {
        val whole = amount.roundToLong()
        val digits = abs(whole).toString()
        val indian = currency.trim().let { it == "₹" || it.equals("Rs", true) || it.equals("Rs.", true) || it.equals("INR", true) }
        val grouped = if (digits.length <= 3) digits else if (indian) {
            val head = digits.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
        } else {
            digits.reversed().chunked(3).joinToString(",").reversed()
        }
        val sign = if (whole < 0) "-" else ""
        val space = if (currency.length > 1 && currency.last().isLetter()) " " else ""
        return "$sign$currency$space$grouped"
    }

    /** "0.5 kg", "1 kg"; "1 piece", "Box of 6". */
    fun sizeLabel(mode: PriceMode, size: Double): String = when (mode) {
        PriceMode.PER_KG -> "${trim(size)} kg"
        PriceMode.EACH -> if (size <= 1.0) "1 piece" else "Box of ${trim(size)}"
    }

    /** Every size with its price, e.g. [("0.5 kg", 400), ("1 kg", 800)]. */
    fun options(card: PriceCard?, tier: Tier, override: Double?): List<Pair<String, Double>> {
        if (override != null) return listOf("Price" to override)
        if (card == null || !card.isSet) return emptyList()
        val extra = if (tier == Tier.THEME) card.themeExtra else 0.0
        return card.sizes.sorted().map { size -> sizeLabel(card.mode, size) to card.price * size + extra }
    }

    /** "from ₹400", "₹1,500" or null when no price is set. */
    fun summary(card: PriceCard?, tier: Tier, override: Double?, currency: String): String? {
        val options = options(card, tier, override)
        if (options.isEmpty()) return null
        val min = options.minOf { it.second }
        return if (options.size == 1) format(min, currency) else "from ${format(min, currency)}"
    }

    /** "0.5 kg ₹400 · 1 kg ₹800 · 2 kg ₹1,600" */
    fun details(card: PriceCard?, tier: Tier, override: Double?, currency: String): String? =
        options(card, tier, override).takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { (label, price) -> if (label == "Price") format(price, currency) else "$label ${format(price, currency)}" }

    /** Parses "0.5, 1, 2" into sizes; ignores anything that isn't a positive number. */
    fun parseSizes(text: String): List<Double> =
        text.split(',', ';', ' ').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }.distinct().sorted()

    fun trim(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/** Names colours from RGB (plain maths so it can be unit tested). */
object ColourNames {
    /** A cake-friendly colour word, or null for greys that shouldn't be named. */
    fun name(r: Int, g: Int, b: Int): String? {
        val max = maxOf(r, g, b) / 255f
        val min = minOf(r, g, b) / 255f
        val v = max
        val s = if (max == 0f) 0f else (max - min) / max
        if (s < 0.18f) return when {
            v > 0.78f -> "White"
            v < 0.2f -> "Black"
            else -> null
        }
        val d = max - min
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        var h = when (max) {
            rf -> 60f * (((gf - bf) / d) % 6f)
            gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        if (h < 0) h += 360f
        return when {
            (h < 20f || h >= 330f) && s < 0.55f && v > 0.7f -> "Pink"
            h >= 290f && h < 330f -> "Pink"
            h < 45f && v < 0.6f -> "Chocolate"
            h < 15f || h >= 330f -> "Red"
            h < 45f -> "Orange"
            h < 70f -> "Yellow"
            h < 170f -> "Green"
            h < 260f -> "Blue"
            else -> "Purple"
        }
    }

    /** The most common named colour among [pixels] (ARGB ints), ignoring black backgrounds. */
    fun dominant(pixels: IntArray): String? = pixels.asSequence()
        .mapNotNull { p -> name((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) }
        .filter { it != "Black" }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
}

/** Builds friendly design names and descriptions from what the scanner saw. */
object MenuNamer {
    private val occasions = listOf(
        "Wedding" to listOf("wedding", "bride", "bridegroom"),
        "Christmas" to listOf("christmas"),
        "Birthday" to listOf("birthday", "candle", "party", "balloon", "event"),
    )
    private val decorations = listOf(
        "Floral" to listOf("flower", "petal", "rose", "floristry", "flowering plant"),
        "Fruit" to listOf("fruit", "strawberry", "berry", "cherry", "blueberry"),
        "Theme" to listOf("toy", "cartoon", "doll", "figurine", "action figure"),
        "Chocolate" to listOf("chocolate", "cocoa"),
    )

    /** e.g. "Pink Floral Birthday Cake". [labels] are (label, confidence). */
    fun title(labels: List<Pair<String, Float>>, colour: String?, categoryName: String): String {
        val seen = labels.filter { it.second >= 0.5f }.map { it.first.lowercase() }
        fun find(table: List<Pair<String, List<String>>>) =
            table.firstOrNull { (_, words) -> seen.any { label -> words.any { label == it || label.contains(it) } } }?.first
        val decoration = find(decorations)
        val occasion = find(occasions)
        val noun = categoryName.trim().ifBlank { "Cake" }
        val colourWord = colour?.takeUnless { it == decoration || noun.contains(it, ignoreCase = true) }
        val parts = listOfNotNull(colourWord, decoration, occasion).distinct()
        return if (parts.isEmpty()) "Classic $noun" else (parts + noun).joinToString(" ")
    }

    /** Description from the template: title, business, sizes and the menu note. */
    fun description(title: String, business: String, prices: String?, note: String): String =
        listOfNotNull(
            if (business.isNotBlank()) "$title by $business." else "$title.",
            prices,
            note.trim().ifBlank { null },
        ).joinToString(" ")

    /** Keeps titles unique: "Pink Cake", "Pink Cake 2", ... */
    fun unique(title: String, taken: Collection<String>): String {
        if (title !in taken) return title
        var n = 2
        while ("$title $n" in taken) n++
        return "$title $n"
    }
}

/** What the grouper needs to know about each photo. */
data class DesignInput(
    val id: Long,
    val takenAt: Long,
    val hash: Long?,
    val sharpness: Double?,
    val orderTag: String?,
)

/**
 * Groups photos of the same cake into designs: photos tagged with the same order, and
 * look-alike photos (similar dHash) taken close together.
 */
object DesignGrouper {
    const val MAX_DISTANCE = 16
    const val WINDOW_MS = 45 * 60 * 1000L

    fun group(inputs: List<DesignInput>, maxDistance: Int = MAX_DISTANCE, windowMs: Long = WINDOW_MS): List<List<DesignInput>> {
        val parent = IntArray(inputs.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        inputs.withIndex().filter { it.value.orderTag != null }.groupBy { it.value.orderTag }.values.forEach { same ->
            same.zipWithNext { a, b -> union(a.index, b.index) }
        }
        val byTime = inputs.indices.sortedBy { inputs[it].takenAt }
        for ((pos, i) in byTime.withIndex()) {
            val a = inputs[i]
            if (a.hash == null) continue
            for (j in byTime.drop(pos + 1)) {
                val b = inputs[j]
                if (b.takenAt - a.takenAt > windowMs) break
                if (b.hash != null && java.lang.Long.bitCount(a.hash xor b.hash) <= maxDistance) union(i, j)
            }
        }
        return inputs.indices.groupBy { find(it) }.values
            .map { members -> members.map { inputs[it] }.sortedBy { it.takenAt } }
            .sortedByDescending { group -> group.maxOf { it.takenAt } }
    }

    /** The sharpest photo of a design (or the first one when sharpness is unknown). */
    fun hero(group: List<DesignInput>): DesignInput = group.maxByOrNull { it.sharpness ?: -1.0 } ?: group.first()
}
