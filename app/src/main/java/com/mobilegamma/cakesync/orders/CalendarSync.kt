package com.mobilegamma.cakesync.orders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/** A calendar on the phone (e.g. the user's Google Calendar, synced by Android). */
data class PhoneCalendar(val id: Long, val name: String, val account: String, val isGoogle: Boolean)

/** An upcoming calendar event that may be a customer booking. */
data class Booking(val eventId: Long, val title: String, val description: String, val start: LocalDateTime, val allDay: Boolean)

/**
 * Keeps orders in the phone's calendar. Google Calendar accounts on the phone sync these
 * events to Google Calendar, so no extra Google sign-in is needed.
 */
object CalendarSync {

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Calendars the user can add events to, Google ones first. */
    fun calendars(context: Context): List<PhoneCalendar> {
        if (!hasPermission(context)) return emptyList()
        val out = mutableListOf<PhoneCalendar>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += PhoneCalendar(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getString(3) == "com.google")
            }
        }
        return out.sortedWith(compareByDescending<PhoneCalendar> { it.isGoogle }.thenBy { it.name })
    }

    /**
     * Adds or updates the order's event and returns its id. Orders without a time are all-day
     * events; timed ones last an hour (the pickup / delivery slot).
     */
    fun upsert(context: Context, calendarId: Long, order: Order, title: String, description: String, reminderHours: List<Int>): Long? {
        val date = order.due ?: return order.calendarEventId
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            if (order.delivery && order.address.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, order.address)
            val time = order.time
            if (time == null) {
                val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + 24 * 3600_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val zone = ZoneId.systemDefault()
                val start = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + 3600_000L)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val resolver = context.contentResolver
        val existing = order.calendarEventId
        if (existing != null) {
            val updated = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), values, null, null)
            if (updated > 0) return existing
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        reminderHours.forEach { h ->
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, h * 60)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    fun delete(context: Context, eventId: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }

    /** Events in the next [days] days of [calendarId], e.g. bookings made on a booking page. */
    fun upcoming(context: Context, calendarId: Long, days: Long = 60): List<Booking> {
        if (!hasPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + days * 24 * 3600_000L)
        }.build()
        val out = mutableListOf<Booking>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
            ),
            "${CalendarContract.Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(3)
                val start = if (allDay) {
                    LocalDateTime.ofEpochSecond(begin / 1000, 0, ZoneOffset.UTC)
                } else {
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(begin), ZoneId.systemDefault())
                }
                out += Booking(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", start, allDay)
            }
        }
        return out
    }

    /** A draft order from a calendar booking: name from the title, details from the notes. */
    fun toOrder(booking: Booking, settings: OrderSettings, id: String, extras: List<com.mobilegamma.cakesync.menu.Extra>): Order {
        val parsed = OrderMessage.toOrder(booking.description, settings, id, extras, booking.start.toLocalDate())
        val name = parsed.customer.ifBlank {
            booking.title.substringBefore(" and ").substringBefore(" - ").substringBefore(":").trim()
        }
        return parsed.copy(
            customer = name,
            due = booking.start.toLocalDate(),
            time = if (booking.allDay) null else booking.start.toLocalTime().takeIf { it != LocalTime.MIDNIGHT },
            calendarEventId = booking.eventId,
            fromBooking = true,
            notes = listOf(parsed.notes, "Booked in calendar: ${booking.title}").filter { it.isNotBlank() }.joinToString("\n"),
        )
    }
}
