package com.mobilegamma.cakesync.menu

import kotlin.math.abs
import kotlin.math.roundToLong

/** A column of a price table, e.g. 6" (serves 8–10) or "12 pcs". */
data class SizeCol(val label: String, val serves: String = "")

/** A flavour and its price for each size column (null = not offered). */
data class FlavourRow(val name: String, val prices: List<Double?>)

/** A category's price list: flavours down the side, sizes across the top. */
data class PriceTable(
    val categoryId: String,
    val sizes: List<SizeCol> = emptyList(),
    val flavours: List<FlavourRow> = emptyList(),
) {
    val isSet: Boolean get() = flavours.any { row -> row.prices.any { (it ?: 0.0) > 0.0 } }

    /** The lowest price in the table (the "from" price), or null. */
    fun minPrice(): Double? = flavours.flatMap { it.prices }.filterNotNull().filter { it > 0 }.minOrNull()
}

/** Ready-made table shapes to start from. */
enum class TableTemplate(val label: String, val sizes: List<SizeCol>) {
    ROUND("Round cakes (inches)", listOf(SizeCol("6\"", "8–10"), SizeCol("8\"", "14–18"), SizeCol("10\"", "24–28"), SizeCol("12\"", "35–40"))),
    CUPCAKES("Cupcakes (packs)", listOf(SizeCol("6 pcs"), SizeCol("12 pcs"))),
    PER_KG("By weight", listOf(SizeCol("0.5 kg"), SizeCol("1 kg"), SizeCol("2 kg"))),
    ;

    fun table(categoryId: String) = PriceTable(categoryId, sizes, listOf(FlavourRow("Vanilla", sizes.map { null }), FlavourRow("Chocolate", sizes.map { null })))
}

/** How elaborate a design is; [fromPrice] null means "use the price table". */
data class DesignLevel(val id: String, val name: String, val fromPrice: Double? = null)

/** An option or add-on shown on the menu, e.g. ("Eggless", "+20%") or ("Edible image", "$15 per page"). */
data class Extra(val name: String, val price: String)

/** One design on the menu: a group of photos of the same cake. */
data class MenuItem(
    val id: String,
    val title: String,
    val categoryId: String?,
    val photoIds: List<Long>,
    val heroId: Long,
    val heroUri: String,
    val levelId: String = DesignLevels.SIMPLE,
    /** A fixed price for this design instead of the table / level. */
    val priceOverride: Double? = null,
    /** Blank = generated from the template. */
    val description: String = "",
    val addedAt: Long = 0L,
)

object DesignLevels {
    const val SIMPLE = "simple"
    val defaults = listOf(
        DesignLevel(SIMPLE, "Simple"),
        DesignLevel("fondant", "Fondant"),
        DesignLevel("topper", "3D topper / custom"),
        DesignLevel("tiered", "Two-tier"),
    )
}

/** Menu-wide settings. */
data class MenuSettings(
    val currency: String = "$",
    val title: String = "Our Cakes",
    val note: String = "Made to order · 50% advance to confirm",
    val levels: List<DesignLevel> = DesignLevels.defaults,
    val extras: List<Extra> = emptyList(),
) {
    fun level(id: String?): DesignLevel = levels.firstOrNull { it.id == id } ?: levels.firstOrNull() ?: DesignLevels.defaults.first()
}

object Pricing {

    /** "$1,600", with Indian digit grouping (1,00,000) for ₹ and Rs, otherwise 100,000. */
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

    /**
     * The price shown on a design: its fixed price, else its design level's "from" price, else
     * the lowest price in its category's table. Null when nothing is set yet.
     */
    fun summary(item: MenuItem, table: PriceTable?, settings: MenuSettings): String? =
        summary(item.levelId, item.priceOverride, table, settings)

    fun summary(levelId: String?, override: Double?, table: PriceTable?, settings: MenuSettings): String? {
        if (override != null) return format(override, settings.currency)
        val level = settings.level(levelId)
        val from = level.fromPrice ?: table?.minPrice() ?: return null
        return "from ${format(from, settings.currency)}"
    }

    /** "6" $60 · 8" $75 · 10" $95" for one flavour. */
    fun rowText(table: PriceTable, row: FlavourRow, currency: String): String =
        table.sizes.zip(row.prices).filter { it.second != null }
            .joinToString(" · ") { (size, price) -> "${size.label} ${format(price!!, currency)}" }

    /** Parses a price typed by the user ("75", "$75", "75.50"); null when blank or invalid. */
    fun parsePrice(text: String): Double? = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.takeIf { it > 0 }

    fun trim(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/**
 * Turns a pasted price list into a table. Understands a size header such as
 * `Flavour 6" 8" 10" 12"` followed by rows like `Vanilla $60 $75 $95 $120`, and pack rows
 * such as `Vanilla - 12 $40` (pack size, then price).
 */
object PriceListParser {
    private val sizeToken = Regex("""(\d+(?:\.\d+)?)\s*(?:"|”|″|''|inch(?:es)?|in\b)""", RegexOption.IGNORE_CASE)
    private val number = Regex("""(\$|₹|rs\.?\s*)?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Pair<List<SizeCol>, List<FlavourRow>> {
        var sizes = mutableListOf<SizeCol>()
        val rows = mutableListOf<Pair<String, MutableMap<String, Double>>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val headerSizes = sizeToken.findAll(line).map { it.groupValues[1] }.toList()
            if (headerSizes.isNotEmpty() && number.findAll(line.replace(sizeToken, "")).none()) {
                val cols = headerSizes.map { SizeCol("$it\"") }
                // A new header replaces the sizes; a lone size is the header wrapping onto a new line.
                if (cols.size >= 2) sizes = cols.toMutableList() else sizes += cols
                continue
            }
            val first = number.find(line) ?: continue
            val name = line.substring(0, first.range.first).trim().trimEnd('-', '–', ':', '|').trim()
            if (name.isEmpty() || name.any { it.isDigit() }) continue
            val found = number.findAll(line.substring(first.range.first)).toList()
            val values = found.map { it.groupValues[2].toDouble() }
            val row = rows.firstOrNull { it.first.equals(name, true) } ?: (name to mutableMapOf<String, Double>()).also { rows += it }
            val packRow = found.size == 2 && found[0].groupValues[1].isEmpty() && found[1].groupValues[1].isNotEmpty()
            if (packRow) {
                val label = "${Pricing.trim(values[0])} pcs"
                if (sizes.none { it.label == label }) sizes += SizeCol(label)
                row.second[label] = values[1]
            } else {
                values.forEachIndexed { i, v ->
                    val label = sizes.getOrNull(i)?.label ?: return@forEachIndexed
                    row.second[label] = v
                }
            }
        }
        if (sizes.any { it.label.endsWith("pcs") }) sizes = sizes.sortedBy { it.label.removeSuffix(" pcs").toDoubleOrNull() ?: 0.0 }.toMutableList()
        return sizes to rows.map { (name, prices) -> FlavourRow(name, sizes.map { prices[it.label] }) }
    }
}

/** Names colours from RGB (plain maths so it can be unit tested). */
object ColourNames {
    /** A cake-friendly colour word, or null for greys that shouldn't be named. */
    fun name(r: Int, g: Int, b: Int): String? {
        val max = maxOf(r, g, b) / 255f
        val min = minOf(r, g, b) / 255f
        val v = max
        val s = if (max == 0f) 0f else (max - min) / max
        // Cream and ivory icing reads as white, not yellow.
        if (s < 0.35f && v > 0.75f) {
            val hue = hueOf(r, g, b)
            if (hue in 20f..70f || s < 0.18f) return "White"
        }
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

    private fun hueOf(r: Int, g: Int, b: Int): Float {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val d = max - minOf(rf, gf, bf)
        if (d == 0f) return 0f
        var h = when (max) {
            rf -> 60f * (((gf - bf) / d) % 6f)
            gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        if (h < 0) h += 360f
        return h
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
        val seen = labels.filter { it.second >= 0.6f }.map { it.first.lowercase() }
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
    /** Only near-identical shots count as the same design; different cakes stay apart. */
    const val MAX_DISTANCE = 10
    const val WINDOW_MS = 20 * 60 * 1000L

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
