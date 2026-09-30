package com.mobilegamma.cakesync.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilegamma.cakesync.menu.MenuData
import com.mobilegamma.cakesync.menu.Pricing
import com.mobilegamma.cakesync.orders.Booking
import com.mobilegamma.cakesync.orders.Order
import com.mobilegamma.cakesync.orders.OrderDates
import com.mobilegamma.cakesync.orders.OrderMessage
import com.mobilegamma.cakesync.orders.OrderSettings
import com.mobilegamma.cakesync.orders.OrderStatus
import com.mobilegamma.cakesync.orders.PhoneCalendar
import com.mobilegamma.cakesync.orders.Question
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Upcoming and past orders, grouped by day. */
@Composable
fun OrdersScreen(state: UiState, viewModel: MainViewModel) {
    val settings = state.orderSettings
    var showPast by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var showBookings by remember { mutableStateOf(false) }
    val menu = remember(state.orders) { viewModel.menuData() }
    val today = LocalDate.now()
    val shown = state.orders
        .filter { o -> if (showPast) !o.status.active || (o.due?.isBefore(today) == true) else o.status.active && (o.due == null || !o.due.isBefore(today)) }
        .sortedWith(compareBy<Order> { it.due ?: LocalDate.MAX }.thenBy { it.time ?: LocalTime.MIDNIGHT })
        .let { if (showPast) it.reversed() else it }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Orders", "Every order, scheduled in your calendar") }
        if (!settings.configured) {
            item {
                Card(onClick = { viewModel.openOrdersSetup() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📦 Set up orders (2 minutes)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tell CakeSync how customers reach you, your advance and cancellation rules, which questions to ask, " +
                                "and which Google Calendar to schedule orders in.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { viewModel.openOrdersSetup() }) { Text("Set up orders") }
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::newOrder) { Text("+ New order") }
                OutlinedButton(onClick = { showBookings = true; viewModel.loadBookings() }, enabled = settings.calendarId != null) { Text("📅 From calendar") }
                OutlinedButton(onClick = { showForm = true }) { Text("📨 Order form") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showPast, onClick = { showPast = false }, label = { Text("Upcoming") }, shape = CircleShape)
                FilterChip(selected = showPast, onClick = { showPast = true }, label = { Text("Past & cancelled") }, shape = CircleShape)
            }
        }
        if (shown.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🧁", fontSize = 48.sp)
                    Text(if (showPast) "No past orders" else "No upcoming orders", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tip: long-press a customer's message in WhatsApp, Messenger or email → Share → CakeSync to add it as an order.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        var lastDate: LocalDate? = null
        var first = true
        shown.forEach { order ->
            if (first || order.due != lastDate) {
                first = false
                val d = order.due
                item(key = "h_${order.id}") {
                    Text(
                        d?.let { OrderDates.pretty(it) } ?: "No date yet",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                lastDate = order.due
            }
            item(key = order.id) { OrderCard(order, menu, viewModel) }
        }
    }

    if (showForm) OrderFormDialog(viewModel) { showForm = false }
    if (showBookings) BookingsDialog(state.bookings, viewModel) { showBookings = false }
}

@Composable
private fun OrderCard(order: Order, menu: MenuData, viewModel: MainViewModel) {
    val level = menu.settings.levels.firstOrNull { it.id == order.levelId }?.name
    val total = viewModel.orderTotal(order, menu)
    Card(onClick = { viewModel.editOrder(order) }, shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(order.customer.ifBlank { "Customer" }, style = MaterialTheme.typography.titleMedium)
                Text(order.cakeSummary(level), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        order.time?.let { (if (order.delivery) "Delivery " else "Pickup ") + OrderDates.time(it) } ?: if (order.delivery) "Delivery" else "Pickup",
                        total?.let { Pricing.format(it, menu.settings.currency) },
                        if (order.advancePaid > 0) "advance ${Pricing.format(order.advancePaid, menu.settings.currency)} paid" else null,
                        if (order.calendarEventId != null) "📅" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${order.status.emoji} ${order.status.label}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** The blank order form, to send to a customer or put in a bio. */
@Composable
private fun OrderFormDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val text = remember { viewModel.orderFormText() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order form") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send this to customers. When they reply, share their message into CakeSync and the order fills in by itself.", style = MaterialTheme.typography.bodySmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
                Channel.entries.forEach { ch ->
                    OutlinedButton(onClick = { viewModel.sendToCustomer(Order("form"), text, ch); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Send by ${ch.label}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BookingsDialog(bookings: List<Booking>, viewModel: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("From your calendar") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Upcoming events in your orders calendar that aren't orders yet, e.g. bookings from your booking page. Tap one to turn it into an order.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (bookings.isEmpty()) Text("Nothing new in the next 60 days.", style = MaterialTheme.typography.bodyMedium)
                bookings.forEach { b ->
                    Card(onClick = { viewModel.importBooking(b); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(b.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                OrderDates.pretty(b.start.toLocalDate()) + if (b.allDay) "" else ", " + OrderDates.time(b.start.toLocalTime()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Full-screen order editor: customer, date, cake, questions, price, status. */
@Composable
fun OrderEditor(order: Order, state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings = state.orderSettings
    val menu = remember { viewModel.menuData() }
    val categories = state.settings?.categories.orEmpty()
    var o by remember(order.id) { mutableStateOf(order.copy(categoryId = order.categoryId ?: categories.firstOrNull()?.id)) }
    var priceText by remember(order.id) { mutableStateOf(order.price?.let { Pricing.trim(it) } ?: "") }
    var advanceText by remember(order.id) { mutableStateOf(if (order.advancePaid > 0) Pricing.trim(order.advancePaid) else "") }
    var toCalendar by remember { mutableStateOf(settings.calendarId != null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    val table = menu.table(o.categoryId)
    val level = menu.settings.levels.firstOrNull { it.id == o.levelId }
    val calculated = viewModel.orderTotal(o.copy(price = null), menu)
    val current = o.copy(price = Pricing.parsePrice(priceText), advancePaid = Pricing.parsePrice(advanceText) ?: 0.0)

    Dialog(onDismissRequest = { viewModel.editOrder(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.editOrder(null) }) { Text("✕", fontSize = 20.sp) }
                    Text(if (state.orders.any { it.id == o.id }) "Order" else "New order", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.saveOrder(current, toCalendar) }) { Text("Save") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Section("🙋", "Customer") {
                        OutlinedTextField(value = o.customer, onValueChange = { o = o.copy(customer = it) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = o.contact, onValueChange = { o = o.copy(contact = it) }, singleLine = true,
                            label = { Text("Phone, email or Instagram") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Section("📅", "When") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                val d = o.due ?: LocalDate.now().plusDays(settings.leadDays.toLong())
                                DatePickerDialog(context, { _, y, m, day -> o = o.copy(due = LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                            }) { Text(o.due?.let { "📅 " + OrderDates.pretty(it) } ?: "📅 Pick date") }
                            OutlinedButton(onClick = {
                                val t = o.time ?: LocalTime.of(12, 0)
                                TimePickerDialog(context, { _, h, m -> o = o.copy(time = LocalTime.of(h, m)) }, t.hour, t.minute, false).show()
                            }) { Text(o.time?.let { "⏰ " + OrderDates.time(it) } ?: "⏰ Time") }
                            if (o.time != null) TextButton(onClick = { o = o.copy(time = null) }) { Text("All day") }
                        }
                        o.due?.let { d ->
                            if (d.isBefore(LocalDate.now().plusDays(settings.leadDays.toLong()))) {
                                Text("⚠️ Less than your ${settings.leadDays}-day notice", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !o.delivery, onClick = { o = o.copy(delivery = false) }, label = { Text("Pickup") })
                            FilterChip(selected = o.delivery, onClick = { o = o.copy(delivery = true) }, label = { Text("Delivery") })
                        }
                        if (o.delivery) {
                            OutlinedTextField(value = o.address, onValueChange = { o = o.copy(address = it) }, label = { Text("Delivery address") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Section("🎂", "Cake") {
                        if (menu.items.isNotEmpty()) {
                            Text("Design from your menu", style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = o.designId == null, onClick = { o = o.copy(designId = null) }, label = { Text("Custom") })
                                menu.items.forEach { item ->
                                    FilterChip(
                                        selected = o.designId == item.id,
                                        onClick = { o = o.copy(designId = item.id, designTitle = item.title, categoryId = item.categoryId ?: o.categoryId, levelId = item.levelId) },
                                        label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(value = o.designTitle, onValueChange = { o = o.copy(designTitle = it) }, singleLine = true, label = { Text("Design / theme") }, modifier = Modifier.fillMaxWidth())
                        if (categories.size > 1) {
                            ChipRow("Category", categories.map { it.id to it.name }, o.categoryId) { o = o.copy(categoryId = it) }
                        }
                        val sizes = table?.sizes?.map { it.label }.orEmpty()
                        if (sizes.isNotEmpty()) ChipRow("Size", sizes.map { it to it }, o.size) { o = o.copy(size = it) }
                        else OutlinedTextField(value = o.size, onValueChange = { o = o.copy(size = it) }, singleLine = true, label = { Text("Size") }, modifier = Modifier.fillMaxWidth())
                        val flavours = table?.flavours?.map { it.name }.orEmpty()
                        if (flavours.isNotEmpty()) ChipRow("Flavour", flavours.map { it to it }, o.flavour) { o = o.copy(flavour = it) }
                        else OutlinedTextField(value = o.flavour, onValueChange = { o = o.copy(flavour = it) }, singleLine = true, label = { Text("Flavour") }, modifier = Modifier.fillMaxWidth())
                        ChipRow("Design level", menu.settings.levels.map { it.id to it.name }, o.levelId) { o = o.copy(levelId = it) }
                        if (menu.settings.extras.isNotEmpty()) {
                            Text("Options", style = MaterialTheme.typography.labelMedium)
                            FlowRowCompat {
                                menu.settings.extras.forEach { e ->
                                    val on = e.name in o.options
                                    FilterChip(selected = on, onClick = { o = o.copy(options = if (on) o.options - e.name else o.options + e.name) }, label = { Text("${e.name} ${e.price}".trim()) })
                                }
                            }
                        }
                    }
                    Section("📝", "Details") {
                        if (settings.asks(Question.OCCASION)) OutlinedTextField(value = o.occasion, onValueChange = { o = o.copy(occasion = it) }, singleLine = true, label = { Text("Occasion") }, modifier = Modifier.fillMaxWidth())
                        if (settings.asks(Question.MESSAGE)) OutlinedTextField(value = o.message, onValueChange = { o = o.copy(message = it) }, singleLine = true, label = { Text("Message on cake") }, modifier = Modifier.fillMaxWidth())
                        if (settings.asks(Question.ALLERGIES)) OutlinedTextField(value = o.allergies, onValueChange = { o = o.copy(allergies = it) }, singleLine = true, label = { Text("Allergies") }, modifier = Modifier.fillMaxWidth())
                        settings.customQuestions.filter { it.isNotBlank() }.forEach { q ->
                            OutlinedTextField(value = o.answers[q].orEmpty(), onValueChange = { o = o.copy(answers = o.answers + (q to it)) }, singleLine = true, label = { Text(q) }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(value = o.notes, onValueChange = { o = o.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                    }
                    Section("💰", "Price") {
                        Text(
                            calculated?.let { "From your menu: ${Pricing.format(it, menu.settings.currency)}" + (level?.name?.let { n -> " ($n)" } ?: "") }
                                ?: "Pick a size and flavour (and set prices in Menu → Prices) to calculate",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = priceText, onValueChange = { priceText = it }, singleLine = true,
                                label = { Text("Agreed price") }, prefix = { Text(menu.settings.currency) },
                                placeholder = { Text(calculated?.let { Pricing.trim(Math.round(it).toDouble()) } ?: "") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = advanceText, onValueChange = { advanceText = it }, singleLine = true,
                                label = { Text("Advance paid") }, prefix = { Text(menu.settings.currency) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Section("📌", "Status") {
                        FlowRowCompat {
                            OrderStatus.entries.forEach { st ->
                                FilterChip(selected = o.status == st, onClick = { o = o.copy(status = st) }, label = { Text("${st.emoji} ${st.label}") })
                            }
                        }
                        SwitchRow(
                            if (settings.calendarId != null) "Keep in ${settings.calendarName.ifBlank { "my calendar" }}" else "Add to calendar (choose one in order setup)",
                            toCalendar && settings.calendarId != null,
                        ) { toCalendar = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showSend = true }, modifier = Modifier.weight(1f)) { Text("🧾 Send quote") }
                        if (state.orders.any { it.id == o.id }) {
                            TextButton(onClick = { if (confirmDelete) viewModel.deleteOrder(o) else confirmDelete = true }) {
                                Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (showSend) {
            val business = remember { viewModel.brandKit().businessName }
            val text = OrderMessage.quote(current, viewModel.orderTotal(current, menu), level?.name, menu.settings, settings, business)
            AlertDialog(
                onDismissRequest = { showSend = false },
                title = { Text("Send quote") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
                        Channel.entries.forEach { ch ->
                            OutlinedButton(onClick = {
                                viewModel.sendToCustomer(current, text, ch)
                                if (o.status == OrderStatus.ENQUIRY) o = o.copy(status = OrderStatus.QUOTED)
                                showSend = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("Send by ${ch.label}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSend = false }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun ChipRow(label: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, text) ->
            FilterChip(selected = selected.equals(value, true), onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

// --- Order setup: asked once (after the intro for new bakeries), editable in Settings ---

@Composable
fun OrderSetupWizard(state: UiState, viewModel: MainViewModel) {
    var calendars by remember { mutableStateOf(viewModel.phoneCalendars()) }
    // Calendar access already granted: start with the first (Google) calendar picked.
    var s by remember {
        val first = calendars.firstOrNull()
        mutableStateOf(state.orderSettings.let { o -> if (o.calendarId == null && first != null) o.copy(calendarId = first.id, calendarName = first.name) else o })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        calendars = viewModel.phoneCalendars()
        calendars.firstOrNull()?.let { c -> if (s.calendarId == null) s = s.copy(calendarId = c.id, calendarName = c.name) }
    }
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val titles = listOf("How customers reach you", "Payment & policies", "Questions to ask", "Google Calendar")

    Dialog(onDismissRequest = { viewModel.openOrdersSetup(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Order setup · ${pager.currentPage + 1} of 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(titles[pager.currentPage], style = MaterialTheme.typography.headlineSmall)
                    }
                    TextButton(onClick = { viewModel.openOrdersSetup(false) }) { Text("Later") }
                }
                HorizontalPager(pager, modifier = Modifier.weight(1f), userScrollEnabled = false) { page ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (page) {
                            0 -> {
                                Text("Where do customers message you? Fill in the ones you use; the order form and quotes use them.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(value = s.whatsapp, onValueChange = { s = s.copy(whatsapp = it) }, singleLine = true, label = { Text("WhatsApp number (with country code)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = s.messengerPage, onValueChange = { s = s.copy(messengerPage = it) }, singleLine = true, label = { Text("Facebook page name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = s.instagram, onValueChange = { s = s.copy(instagram = it) }, singleLine = true, label = { Text("Instagram") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = s.email, onValueChange = { s = s.copy(email = it) }, singleLine = true, label = { Text("Email for orders") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = s.bookingLink, onValueChange = { s = s.copy(bookingLink = it) }, singleLine = true, label = { Text("Booking page link (optional)") }, modifier = Modifier.fillMaxWidth())
                            }
                            1 -> {
                                LabeledNumber("Advance to confirm (%)", s.advancePercent) { s = s.copy(advancePercent = it.coerceIn(0, 100)) }
                                OutlinedTextField(value = s.paymentNote, onValueChange = { s = s.copy(paymentNote = it) }, label = { Text("How to pay, e.g. Interac e-Transfer to …") }, modifier = Modifier.fillMaxWidth())
                                LabeledNumber("Cancel at least … days before for a full refund", s.cancelDays) { s = s.copy(cancelDays = it.coerceIn(0, 60)) }
                                LabeledNumber("Minimum notice for orders (days)", s.leadDays) { s = s.copy(leadDays = it.coerceIn(0, 60)) }
                                SwitchRow("I deliver", s.delivers) { s = s.copy(delivers = it) }
                                if (s.delivers) OutlinedTextField(value = s.deliveryFee, onValueChange = { s = s.copy(deliveryFee = it) }, singleLine = true, label = { Text("Delivery fee, e.g. $10–20 by distance") }, modifier = Modifier.fillMaxWidth())
                            }
                            2 -> {
                                Text("Name, phone, design, size, flavour and date are always asked. Choose the rest:", style = MaterialTheme.typography.bodySmall)
                                Question.entries.forEach { q ->
                                    SwitchRow("${q.emoji} ${q.label}", q.key in s.questions) { on ->
                                        s = s.copy(questions = if (on) s.questions + q.key else s.questions - q.key)
                                    }
                                }
                                Text("Your own questions", style = MaterialTheme.typography.labelLarge)
                                s.customQuestions.forEachIndexed { i, q ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(value = q, onValueChange = { v -> s = s.copy(customQuestions = s.customQuestions.toMutableList().also { it[i] = v }) }, singleLine = true, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { s = s.copy(customQuestions = s.customQuestions.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
                                    }
                                }
                                TextButton(onClick = { s = s.copy(customQuestions = s.customQuestions + "") }) { Text("+ Add a question") }
                                Text("Preview", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    OrderMessage.template(s, "your bakery"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                                )
                            }
                            else -> {
                                Text(
                                    "Every order is scheduled in the calendar you pick, with reminders. Pick your Google Calendar " +
                                        "and it shows on all your devices. Bookings made there can be turned into orders.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (calendars.isEmpty()) {
                                    Button(onClick = { permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) {
                                        Text("Allow calendar access")
                                    }
                                } else {
                                    calendars.forEach { c -> CalendarRow(c, s.calendarId == c.id) { s = s.copy(calendarId = c.id, calendarName = c.name) } }
                                    SwitchRow("Show calendar bookings to turn into orders", s.importBookings) { s = s.copy(importBookings = it) }
                                    Text("Reminders: 1 day and 3 hours before each order.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pager.currentPage > 0) OutlinedButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    val last = pager.currentPage == 3
                    Button(onClick = {
                        if (last) viewModel.saveOrderSettings(s) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) { Text(if (last) "Finish" else "Next") }
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(c: PhoneCalendar, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(c.name + if (c.isGoogle) "  (Google)" else "", style = MaterialTheme.typography.titleSmall)
            Text(c.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabeledNumber(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; it.toIntOrNull()?.let(onChange) }, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

/** Short summary of the order setup for Settings. */
fun orderSetupSummary(s: OrderSettings): String = if (!s.configured) "Not set up yet" else listOfNotNull(
    "${s.advancePercent}% advance",
    if (s.cancelDays > 0) "cancel ${s.cancelDays} days before" else null,
    if (s.calendarId != null) "📅 ${s.calendarName}" else "no calendar",
    "${s.questions.size + s.customQuestions.size} extra questions",
).joinToString(" · ")
