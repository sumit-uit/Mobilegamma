package com.mobilegamma.cakesync.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLogicTest {

    @Test
    fun formatsIndianAndWesternPrices() {
        assertEquals("₹400", Pricing.format(400.0, "₹"))
        assertEquals("₹1,600", Pricing.format(1600.0, "₹"))
        assertEquals("₹1,25,000", Pricing.format(125000.0, "₹"))
        assertEquals("$125,000", Pricing.format(125000.0, "$"))
        assertEquals("Rs 1,200", Pricing.format(1200.0, "Rs"))
    }

    private val cakes = PriceTable(
        "cake",
        listOf(SizeCol("6\"", "8–10"), SizeCol("8\""), SizeCol("10\""), SizeCol("12\"")),
        listOf(
            FlavourRow("Vanilla", listOf(60.0, 75.0, 95.0, 120.0)),
            FlavourRow("Rasmalai", listOf(75.0, 100.0, 130.0, 160.0)),
        ),
    )

    @Test
    fun designPricesFromTableOrLevel() {
        val settings = MenuSettings(currency = "$", levels = listOf(DesignLevel("simple", "Simple"), DesignLevel("fondant", "Fondant", 80.0)))
        val item = MenuItem("d1", "Pink Cake", "cake", listOf(1), 1, "uri")
        assertEquals("from $60", Pricing.summary(item, cakes, settings))
        assertEquals("from $80", Pricing.summary(item.copy(levelId = "fondant"), cakes, settings))
        assertEquals("$150", Pricing.summary(item.copy(priceOverride = 150.0), cakes, settings))
        assertNull(Pricing.summary(item, PriceTable("cake"), settings))
        assertEquals("6\" $60 · 8\" $75 · 10\" $95 · 12\" $120", Pricing.rowText(cakes, cakes.flavours[0], "$"))
        assertEquals(75.5, Pricing.parsePrice("$75.50")!!, 1e-9)
        assertNull(Pricing.parsePrice(""))
    }

    @Test
    fun parsesAPastedCakePriceList() {
        val text = listOf(
            "Cake specialties",
            "Starting price for simple round cake designs, with egg.",
            "Flavour 6\" 8\" 10\"",
            "12\"",
            "Rasmalai \$75 \$100 \$130 \$160",
            "Pineapple \$70 \$85 \$110 \$140",
            "Butterscotch \$70 \$85 \$110 \$130",
            "Rabri Falooda \$95 \$120 \$155 \$200",
        ).joinToString("\n")
        val (sizes, rows) = PriceListParser.parse(text)
        assertEquals(listOf("6\"", "8\"", "10\"", "12\""), sizes.map { it.label })
        assertEquals(listOf("Rasmalai", "Pineapple", "Butterscotch", "Rabri Falooda"), rows.map { it.name })
        assertEquals(listOf(95.0, 120.0, 155.0, 200.0), rows.last().prices)
    }

    @Test
    fun parsesCupcakePacks() {
        val (sizes, rows) = PriceListParser.parse("Rasmalai - 6 \$50\nVanilla - 12 \$40\nChocolate - 12 \$40")
        assertEquals(listOf("6 pcs", "12 pcs"), sizes.map { it.label })
        assertEquals(listOf(50.0, null), rows.first { it.name == "Rasmalai" }.prices)
        assertEquals(listOf(null, 40.0), rows.first { it.name == "Vanilla" }.prices)
    }

    @Test
    fun namesColours() {
        assertEquals("White", ColourNames.name(250, 248, 245))
        assertEquals("White", ColourNames.name(245, 235, 200)) // cream icing
        assertEquals("Yellow", ColourNames.name(240, 200, 40))
        assertEquals("Pink", ColourNames.name(250, 180, 200))
        assertEquals("Red", ColourNames.name(200, 20, 30))
        assertEquals("Chocolate", ColourNames.name(110, 60, 30))
        assertEquals("Blue", ColourNames.name(40, 90, 220))
        assertEquals("Yellow", ColourNames.name(240, 220, 60))
        assertNull(ColourNames.name(128, 128, 128))
        val pixels = IntArray(10) { if (it < 7) 0xFFFAB4C8.toInt() else 0xFF000000.toInt() }
        assertEquals("Pink", ColourNames.dominant(pixels))
    }

    @Test
    fun buildsDesignNames() {
        val labels = listOf("Cake" to 0.95f, "Flower" to 0.7f, "Party" to 0.65f, "Toy" to 0.55f)
        assertEquals("Pink Floral Birthday Cake", MenuNamer.title(labels, "Pink", "Cake"))
        assertEquals("Classic Cupcake", MenuNamer.title(listOf("Food" to 0.9f), null, "Cupcake"))
        // Colour that repeats the decoration isn't doubled.
        assertEquals("Chocolate Cake", MenuNamer.title(listOf("Chocolate" to 0.8f), "Chocolate", "Cake"))
        assertEquals("Pink Cake 3", MenuNamer.unique("Pink Cake", setOf("Pink Cake", "Pink Cake 2")))
        val description = MenuNamer.description("Pink Cake", "Soni Bakes", "1 kg ₹800", "Order 48h ahead")
        assertEquals("Pink Cake by Soni Bakes. 1 kg ₹800 Order 48h ahead", description)
    }

    @Test
    fun groupsPhotosOfTheSameCake() {
        val minute = 60_000L
        val inputs = listOf(
            DesignInput(1, 0, 0b1111L, 10.0, null),
            DesignInput(2, 2 * minute, 0b1110L, 50.0, null),              // looks like 1, taken soon after
            DesignInput(3, 3 * minute, 0x7FFFFFFFFFFFL, 20.0, null),      // looks different
            DesignInput(4, 300 * minute, 0b1111L, 5.0, null),             // looks like 1, but hours later
            DesignInput(5, 400 * minute, null, null, "Order 7"),
            DesignInput(6, 900 * minute, null, null, "Order 7"),          // same order, far apart
        )
        val groups = DesignGrouper.group(inputs).map { g -> g.map { it.id }.toSet() }
        assertTrue(setOf(1L, 2L) in groups)
        assertTrue(setOf(3L) in groups)
        assertTrue(setOf(4L) in groups)
        assertTrue(setOf(5L, 6L) in groups)
        assertEquals(4, groups.size)
        assertEquals(2L, DesignGrouper.hero(inputs.take(2)).id)
    }
}
