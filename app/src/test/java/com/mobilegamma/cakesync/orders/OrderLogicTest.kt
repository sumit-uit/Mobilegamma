package com.mobilegamma.cakesync.orders

import com.mobilegamma.cakesync.menu.DesignLevel
import com.mobilegamma.cakesync.menu.Extra
import com.mobilegamma.cakesync.menu.FlavourRow
import com.mobilegamma.cakesync.menu.MenuSettings
import com.mobilegamma.cakesync.menu.PriceTable
import com.mobilegamma.cakesync.menu.SizeCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OrderLogicTest {
    private val table = PriceTable(
        "cake",
        listOf(SizeCol("6\""), SizeCol("8\""), SizeCol("10\"")),
        listOf(FlavourRow("Vanilla", listOf(60.0, 75.0, 95.0)), FlavourRow("Rasmalai", listOf(75.0, 100.0, 130.0))),
    )
    private val menu = MenuSettings(
        currency = "$",
        levels = listOf(DesignLevel("simple", "Simple"), DesignLevel("fondant", "Fondant", 80.0)),
        extras = listOf(Extra("Eggless", "+20%"), Extra("Edible image", "$15 per page"), Extra("Fruit chunks", "$5–10")),
    )
    private val settings = OrderSettings(configured = true, advancePercent = 50, paymentNote = "Interac e-Transfer", cancelDays = 4)

    @Test
    fun pricesFromTableLevelAndOptions() {
        val order = Order("1", size = "8\"", flavour = "Rasmalai", levelId = "simple")
        assertEquals(100.0, Quote.calculate(order, table, menu)!!, 1e-9)
        // Eggless +20% on the base, edible image +$15.
        assertEquals(135.0, Quote.calculate(order.copy(options = setOf("Eggless", "Edible image")), table, menu)!!, 1e-9)
        // Fondant starts at $80, so a $60 vanilla 6" becomes $80.
        assertEquals(80.0, Quote.calculate(Order("2", size = "6\"", flavour = "Vanilla", levelId = "fondant"), table, menu)!!, 1e-9)
        // Unknown size: no price.
        assertNull(Quote.calculate(Order("3", size = "14\"", flavour = "Vanilla"), table, menu))
        // An agreed price wins.
        assertEquals(150.0, Quote.total(order.copy(price = 150.0), table, menu)!!, 1e-9)
        assertEquals(50.0, Quote.advance(100.0, settings), 1e-9)
        assertEquals(5.0, Quote.amountOf(menu.extras[2])!!, 1e-9)
    }

    @Test
    fun parsesDatesAndTimes() {
        val today = LocalDate.of(2026, 9, 30)
        assertEquals(LocalDate.of(2026, 10, 12), OrderDates.parse("12 Oct", today))
        assertEquals(LocalDate.of(2026, 10, 12), OrderDates.parse("October 12th", today))
        assertEquals(LocalDate.of(2027, 3, 5), OrderDates.parse("Mar 5", today)) // already passed this year
        assertEquals(LocalDate.of(2026, 10, 1), OrderDates.parse("tomorrow", today))
        assertEquals(LocalDate.of(2026, 11, 20), OrderDates.parse("2026-11-20", today))
        assertEquals(LocalDate.of(2026, 10, 25), OrderDates.parse("25/10", today))
        assertNull(OrderDates.parse("soon", today))
        assertEquals(LocalTime.of(17, 30), OrderDates.parseTime("5:30 pm"))
        assertEquals(LocalTime.of(10, 0), OrderDates.parseTime("10am"))
    }

    @Test
    fun templateRoundTrip() {
        val template = OrderMessage.template(settings.copy(customQuestions = listOf("Number of guests")), "Soni Bakes", design = "Pink Floral Cake")
        assertTrue(template.contains("Design: Pink Floral Cake"))
        assertTrue(template.contains("Number of guests:"))
        val reply = template
            .replace("Name: ", "Name: Priya")
            .replace("Phone: ", "Phone: +1 416 555 0100")
            .replace("Size: ", "Size: 8\"")
            .replace("Flavour: ", "Flavour: Rasmalai")
            .replace("Eggless: ", "Eggless: Yes")
            .replace("Message on cake: ", "Message on cake: Happy 5th Birthday Aarav")
            .replace("Date needed: ", "Date needed: Oct 12")
            .replace("Pickup / delivery time: ", "Pickup / delivery time: 4 pm")
            .replace("Pickup or delivery (address): ", "Pickup or delivery (address): Delivery to 12 King St")
            .replace("Number of guests: ", "Number of guests: 20")
        val order = OrderMessage.toOrder(reply, settings.copy(customQuestions = listOf("Number of guests")), "o1", menu.extras, LocalDate.of(2026, 9, 30))
        assertEquals("Priya", order.customer)
        assertEquals("+1 416 555 0100", order.contact)
        assertEquals("Pink Floral Cake", order.designTitle)
        assertEquals("8\"", order.size)
        assertEquals("Rasmalai", order.flavour)
        assertEquals(setOf("Eggless"), order.options)
        assertEquals("Happy 5th Birthday Aarav", order.message)
        assertEquals(LocalDate.of(2026, 10, 12), order.due)
        assertEquals(LocalTime.of(16, 0), order.time)
        assertTrue(order.delivery)
        assertEquals("12 King St", order.address)
        assertEquals("20", order.answers["Number of guests"])
    }

    @Test
    fun quoteMessageHasTotalAdvanceAndPolicy() {
        val order = Order("1", customer = "Priya", size = "8\"", flavour = "Rasmalai", due = LocalDate.of(2026, 10, 12), options = setOf("Eggless"))
        val text = OrderMessage.quote(order, 120.0, null, menu, settings, "Soni Bakes")
        assertTrue(text, text.contains("Hi Priya"))
        assertTrue(text, text.contains("Total: $120"))
        assertTrue(text, text.contains("50% advance: $60 (Interac e-Transfer)"))
        assertTrue(text, text.contains("Cancel 4 days before"))
        val title = OrderMessage.eventTitle(order, null)
        assertEquals("💬 Priya · 8\" Rasmalai (Pickup)", title)
        assertFalse(OrderStatus.DONE.active)
    }
}
