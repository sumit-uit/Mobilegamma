package com.mobilegamma.cakesync.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.edit.Backdrop
import com.mobilegamma.cakesync.menu.CardEntry
import com.mobilegamma.cakesync.menu.CardOptions
import com.mobilegamma.cakesync.menu.CardSize
import com.mobilegamma.cakesync.menu.MenuCard
import com.mobilegamma.cakesync.menu.MenuData
import com.mobilegamma.cakesync.menu.MenuItem
import com.mobilegamma.cakesync.menu.MenuNamer
import com.mobilegamma.cakesync.menu.PriceCard
import com.mobilegamma.cakesync.menu.PriceMode
import com.mobilegamma.cakesync.menu.Pricing
import com.mobilegamma.cakesync.menu.Tier
import com.mobilegamma.cakesync.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class MenuTab(val label: String) { NEW("New designs"), MENU("My menu"), PRICES("Prices") }

/** Build a menu: swipe through new designs, set prices once, make a shareable menu card. */
@Composable
fun MenuScreen(state: UiState, viewModel: MainViewModel, onClose: () -> Unit) {
    val menu = state.menu ?: return
    val categories = state.settings?.categories.orEmpty()
    var tab by remember { mutableStateOf(if (menu.data.cards.none { it.isSet }) MenuTab.PRICES else MenuTab.NEW) }
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    var makingCard by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                    Text("Menu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = { makingCard = true }, enabled = menu.data.items.isNotEmpty()) { Text("📋 Menu card") }
                }
                Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTab.entries.forEach { t ->
                        val count = when (t) {
                            MenuTab.NEW -> " (${menu.suggestions.size})"
                            MenuTab.MENU -> " (${menu.data.items.size})"
                            MenuTab.PRICES -> ""
                        }
                        FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label + count) }, shape = CircleShape)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        MenuTab.NEW -> SwipeDeck(menu, categories, viewModel, onGoToPrices = { tab = MenuTab.PRICES })
                        MenuTab.MENU -> MenuGrid(menu.data, onEdit = { editing = it })
                        MenuTab.PRICES -> PriceEditor(menu.data, categories, viewModel, onSaved = { tab = MenuTab.NEW })
                    }
                }
            }
        }
        editing?.let { item ->
            MenuItemDialog(
                item = item,
                data = menu.data,
                businessName = remember { viewModel.brandKit().businessName },
                onDismiss = { editing = null },
                onSave = { viewModel.updateMenuItem(it); editing = null },
                onRemove = { viewModel.removeMenuItem(item); editing = null },
            )
        }
        if (makingCard) MenuCardDialog(menu.data, viewModel) { makingCard = false }
    }
}

// --- New designs: one card at a time, swipe right to add, left to skip ---

@Composable
private fun SwipeDeck(menu: MenuUi, categories: List<Category>, viewModel: MainViewModel, onGoToPrices: () -> Unit) {
    if (menu.loading) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Text("Finding your cake designs…", modifier = Modifier.padding(top = 12.dp))
        }
        return
    }
    val current = menu.suggestions.firstOrNull()
    if (current == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Text("🎉", fontSize = 56.sp)
            Text("All caught up", style = MaterialTheme.typography.titleLarge)
            Text(
                "New cake photos appear here after each scan, grouped by design. Your menu has ${menu.data.items.size} design(s).",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val card = menu.data.card(current.categoryId)
    var title by remember(current) { mutableStateOf(current.title) }
    var tier by remember(current) { mutableStateOf(Tier.STANDARD) }
    val offset = remember(current) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun decide(add: Boolean) {
        scope.launch {
            offset.animateTo(if (add) 1400f else -1400f)
            if (add) viewModel.addDesign(current, title, tier) else viewModel.skipDesign(current)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "${menu.suggestions.size} design(s) to review · swipe right to add, left to skip",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { rotationZ = offset.value / 60f }
                .pointerInput(current) {
                    detectDragGestures(
                        onDragEnd = {
                            when {
                                offset.value > 300f -> decide(true)
                                offset.value < -300f -> decide(false)
                                else -> scope.launch { offset.animateTo(0f) }
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + drag.x) }
                    }
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = current.hero.uri, contentDescription = current.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                val badge = when {
                    offset.value > 80f -> "ADD ✓" to Color(0xFF2E7D32)
                    offset.value < -80f -> "SKIP ✕" to Color(0xFFC62828)
                    else -> null
                }
                badge?.let { (text, colour) ->
                    Text(
                        text, color = Color.White, fontSize = 28.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).clip(RoundedCornerShape(12.dp))
                            .background(colour).padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
                if (current.photos.size > 1) {
                    LazyRow(
                        Modifier.align(Alignment.BottomStart).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(current.photos.take(6), key = { it.mediaId }) { p ->
                            AsyncImage(
                                model = p.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
                Text(
                    "${current.photos.size} photo(s)",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(CircleShape)
                        .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it }, singleLine = true,
            label = { Text("Name on the menu") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tier.entries.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t.label) }) }
            Spacer(Modifier.weight(1f))
            val price = Pricing.summary(card, tier, null, menu.data.settings.currency)
            if (price != null) Text(price, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onGoToPrices) { Text("Set prices") }
        }
        Text(
            categories.firstOrNull { it.id == current.categoryId }?.name?.let { "Category: $it" } ?: "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { decide(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("✕  Skip") }
            Button(onClick = { decide(true) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("✓  Add to menu") }
        }
    }
}

// --- My menu ---

@Composable
private fun MenuGrid(data: MenuData, onEdit: (MenuItem) -> Unit) {
    if (data.items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Text("📋", fontSize = 56.sp)
            Text("Your menu is empty", style = MaterialTheme.typography.titleLarge)
            Text("Add designs from New designs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Tap a design to rename it, change its price or remove it.", style = MaterialTheme.typography.bodySmall)
        }
        items(data.items.sortedByDescending { it.addedAt }, key = { it.id }) { item ->
            Card(onClick = { onEdit(item) }, shape = RoundedCornerShape(20.dp)) {
                AsyncImage(
                    model = item.heroUri, contentDescription = item.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Column(Modifier.padding(10.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        Pricing.summary(data.card(item.categoryId), item.tier, item.priceOverride, data.settings.currency) ?: "No price yet",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItemDialog(
    item: MenuItem,
    data: MenuData,
    businessName: String,
    onDismiss: () -> Unit,
    onSave: (MenuItem) -> Unit,
    onRemove: () -> Unit,
) {
    var title by remember { mutableStateOf(item.title) }
    var tier by remember { mutableStateOf(item.tier) }
    var custom by remember { mutableStateOf(item.priceOverride?.let { Pricing.trim(it) } ?: "") }
    val card = data.card(item.categoryId)
    val override = custom.trim().toDoubleOrNull()
    val generated = MenuNamer.description(title, businessName, Pricing.details(card, tier, override, data.settings.currency), data.settings.note)
    var description by remember { mutableStateOf(item.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit design") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tier.entries.forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t.label) }) }
                }
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it }, singleLine = true,
                    label = { Text("Fixed price for this design (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    Pricing.details(card, tier, override, data.settings.currency) ?: "No price: set one here or in Prices",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, minLines = 3,
                    label = { Text("Description (blank = automatic)") }, placeholder = { Text(generated) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onRemove) { Text("Remove from menu", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(item.copy(title = title.trim().ifBlank { item.title }, tier = tier, priceOverride = override, description = description.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Prices: set once per category ---

@Composable
private fun PriceEditor(data: MenuData, categories: List<Category>, viewModel: MainViewModel, onSaved: () -> Unit) {
    var currency by remember { mutableStateOf(data.settings.currency) }
    var menuTitle by remember { mutableStateOf(data.settings.title) }
    var note by remember { mutableStateOf(data.settings.note) }
    val cards = remember {
        mutableStateMapOf<String, PriceCard>().apply {
            categories.forEach { c ->
                put(c.id, data.card(c.id) ?: PriceCard(c.id, if (c.name.contains("cupcake", true)) PriceMode.EACH else PriceMode.PER_KG,
                    sizes = if (c.name.contains("cupcake", true)) listOf(1.0, 6.0, 12.0) else listOf(0.5, 1.0, 2.0)))
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Set prices once per category. Every design uses them, with sizes worked out for you. " +
                "Themed or custom designs can add an extra amount.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        categories.forEach { category ->
            val card = cards[category.id] ?: return@forEach
            var priceText by remember(category.id) { mutableStateOf(if (card.price > 0) Pricing.trim(card.price) else "") }
            var sizesText by remember(category.id) { mutableStateOf(card.sizes.joinToString(", ") { Pricing.trim(it) }) }
            var extraText by remember(category.id) { mutableStateOf(if (card.themeExtra > 0) Pricing.trim(card.themeExtra) else "") }
            fun push(mode: PriceMode = cards[category.id]!!.mode) {
                cards[category.id] = card.copy(
                    mode = mode,
                    price = priceText.toDoubleOrNull() ?: 0.0,
                    sizes = Pricing.parseSizes(sizesText).ifEmpty { card.sizes },
                    themeExtra = extraText.toDoubleOrNull() ?: 0.0,
                )
            }
            Section("🎂", category.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriceMode.entries.forEach { m ->
                        FilterChip(selected = cards[category.id]?.mode == m, onClick = {
                            sizesText = if (m == PriceMode.EACH) "1, 6, 12" else "0.5, 1, 2"
                            push(m)
                        }, label = { Text(m.label) })
                    }
                }
                val each = cards[category.id]?.mode == PriceMode.EACH
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText, onValueChange = { priceText = it; push() }, singleLine = true,
                        label = { Text(if (each) "Price per piece" else "Price per kg") },
                        prefix = { Text(currency) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = extraText, onValueChange = { extraText = it; push() }, singleLine = true,
                        label = { Text("Theme extra") }, prefix = { Text("+") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = sizesText, onValueChange = { sizesText = it; push() }, singleLine = true,
                    label = { Text(if (each) "Box sizes, e.g. 1, 6, 12" else "Sizes in kg, e.g. 0.5, 1, 2") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Pricing.details(cards[category.id], Tier.STANDARD, null, currency)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Section("📋", "Menu card text") {
            OutlinedTextField(value = menuTitle, onValueChange = { menuTitle = it }, singleLine = true, label = { Text("Title, e.g. Our Cakes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note, e.g. Order 48 hours ahead") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text("Currency symbol") }, modifier = Modifier.width(160.dp))
        }
        Button(
            onClick = {
                viewModel.saveMenuPrices(
                    com.mobilegamma.cakesync.menu.MenuSettings(currency.trim().ifBlank { "₹" }, menuTitle.trim().ifBlank { "Our Cakes" }, note.trim()),
                    cards.values.toList(),
                )
                onSaved()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save prices") }
        Spacer(Modifier.height(24.dp))
    }
}

// --- Menu card ---

@Composable
private fun MenuCardDialog(data: MenuData, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf(CardSize.POST) }
    var perPage by remember { mutableIntStateOf(4) }
    var backdrop by remember { mutableStateOf<Backdrop>(Backdrop.Solid("Cream", 0xFFFFF4E4.toInt())) }
    var showPrices by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<Pair<List<Uri>, Uri?>?>(null) }
    var working by remember { mutableStateOf(false) }
    val entries by produceState<List<CardEntry>?>(null, data) {
        value = withContext(Dispatchers.IO) { viewModel.menuEntries(data) }
    }
    val options = CardOptions(size, perPage, backdrop, showPrices)
    val kit = remember { viewModel.brandKit() }
    val preview by produceState<Bitmap?>(null, entries, options) {
        val e = entries ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { MenuCard.render(e, 0, options, kit, data.settings, scale = 0.5f) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result == null) "Menu card" else "Menu card saved ✨") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = result
                if (r != null) {
                    Text("${r.first.size} page(s) are in Created" + if (r.second != null) ", and a PDF is in Downloads." else ".")
                    Sharer.Target.entries.forEach { target ->
                        OutlinedButton(onClick = { viewModel.shareUris(r.first, "image/jpeg", target) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Share pictures: ${target.label}")
                        }
                    }
                    r.second?.let { pdf ->
                        OutlinedButton(onClick = { viewModel.shareUris(listOf(pdf), "application/pdf", Sharer.Target.OTHER) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Share PDF…")
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 340.dp), contentAlignment = Alignment.Center) {
                        val p = preview
                        if (p == null) CircularProgressIndicator()
                        else Image(p.asImageBitmap(), "Menu card preview", contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 340.dp).clip(RoundedCornerShape(8.dp)))
                    }
                    entries?.let { e ->
                        Text("${e.size} design(s) · ${MenuCard.pages(e, options)} page(s)", style = MaterialTheme.typography.labelMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardSize.entries.forEach { s -> FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.label) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6).forEach { n -> FilterChip(selected = perPage == n, onClick = { perPage = n }, label = { Text("$n per page") }) }
                    }
                    SwitchRow("Show prices", showPrices) { showPrices = it }
                    Text("Background", style = MaterialTheme.typography.labelMedium)
                    BackdropPicker(selected = backdrop, allowPhoto = false) { backdrop = it }
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(enabled = !working && entries != null, onClick = {
                    working = true
                    viewModel.exportMenuCard(options) { images, pdf -> working = false; result = images to pdf }
                }) { Text(if (working) "Saving…" else "Save & share") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
