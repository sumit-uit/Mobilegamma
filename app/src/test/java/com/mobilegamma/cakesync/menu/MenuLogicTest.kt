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

    @Test
    fun perKgPricesWithThemeExtra() {
        val card = PriceCard("cake", PriceMode.PER_KG, price = 800.0, sizes = listOf(2.0, 0.5, 1.0), themeExtra = 300.0)
        assertEquals(listOf("0.5 kg" to 400.0, "1 kg" to 800.0, "2 kg" to 1600.0), Pricing.options(card, Tier.STANDARD, null))
        assertEquals("from ₹700", Pricing.summary(card, Tier.THEME, null, "₹"))
        assertEquals("0.5 kg ₹400 · 1 kg ₹800 · 2 kg ₹1,600", Pricing.details(card, Tier.STANDARD, null, "₹"))
        // A fixed price wins over the rules.
        assertEquals("₹2,500", Pricing.summary(card, Tier.THEME, 2500.0, "₹"))
        // No price set yet.
        assertNull(Pricing.summary(PriceCard("cake"), Tier.STANDARD, null, "₹"))
    }

    @Test
    fun boxesForCupcakes() {
        val card = PriceCard("cup", PriceMode.EACH, price = 60.0, sizes = listOf(1.0, 6.0, 12.0))
        assertEquals("1 piece ₹60 · Box of 6 ₹360 · Box of 12 ₹720", Pricing.details(card, Tier.STANDARD, null, "₹"))
        assertEquals(listOf(0.5, 1.0, 2.0), Pricing.parseSizes("2, 0.5 ,1, x, -3, 1"))
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
