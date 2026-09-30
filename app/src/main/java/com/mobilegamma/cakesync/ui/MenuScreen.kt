package com.mobilegamma.cakesync.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.mobilegamma.cakesync.menu.Pricing
import com.mobilegamma.cakesync.menu.PriceTable
import com.mobilegamma.cakesync.menu.PriceListParser
import com.mobilegamma.cakesync.menu.TableTemplate
import com.mobilegamma.cakesync.menu.DesignLevel
import com.mobilegamma.cakesync.menu.DesignLevels
import com.mobilegamma.cakesync.menu.Extra
import com.mobilegamma.cakesync.menu.FlavourRow
import com.mobilegamma.cakesync.menu.SizeCol
import com.mobilegamma.cakesync.menu.MenuSettings
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
    var tab by remember { mutableStateOf(if (menu.data.tables.none { it.isSet }) MenuTab.PRICES else MenuTab.NEW) }
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    var makingCard by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("✕", fontSize = 20.sp) }
                    Text("Menu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Button(onClick = { makingCard = true }, enabled = menu.data.items.isNotEmpty() || menu.data.tables.any { it.isSet }) { Text("📋 Menu card") }
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
    val settings = menu.data.settings
    val table = menu.data.table(current.categoryId)
    var title by remember(current.photos, current.title) { mutableStateOf(current.title) }
    var levelId by remember(current.photos) { mutableStateOf(com.mobilegamma.cakesync.menu.DesignLevels.SIMPLE) }
    val offset = remember(current.photos) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun decide(add: Boolean) {
        scope.launch {
            offset.animateTo(if (add) 1400f else -1400f)
            if (add) viewModel.addDesign(current, title, levelId) else viewModel.skipDesign(current)
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
                .pointerInput(current.photos) {
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
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(
                            "Tap a photo to make it the cover",
                            color = Color.White, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(current.photos, key = { it.mediaId }) { p ->
                                val chosen = p.mediaId == current.hero.mediaId
                                AsyncImage(
                                    model = p.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                                        .then(if (chosen) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                                        .clickable { viewModel.setCover(current, p) },
                                )
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (current.photos.size > 1) {
                        Text(
                            "Split into ${current.photos.size}",
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(CircleShape).background(Color(0xCC000000))
                                .clickable { viewModel.splitSuggestion(current) }.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        "${current.photos.size} photo(s)",
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it }, singleLine = true,
            label = { Text("Name on the menu") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            settings.levels.forEach { l -> FilterChip(selected = levelId == l.id, onClick = { levelId = l.id }, label = { Text(l.name) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                categories.firstOrNull { it.id == current.categoryId }?.name?.let { "Category: $it" } ?: "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            val price = Pricing.summary(levelId, null, table, settings)
            if (price != null) Text(price, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = onGoToPrices) { Text("Set prices") }
        }
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
            Text("Tap a design to rename it, change its design level or price, or remove it.", style = MaterialTheme.typography.bodySmall)
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
                        data.settings.level(item.levelId).name + " · " + (data.price(item) ?: "no price yet"),
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
    var levelId by remember { mutableStateOf(item.levelId) }
    var custom by remember { mutableStateOf(item.priceOverride?.let { Pricing.trim(it) } ?: "") }
    val table = data.table(item.categoryId)
    val override = Pricing.parsePrice(custom)
    val summary = Pricing.summary(levelId, override, table, data.settings)
    val generated = MenuNamer.description(title, businessName, summary, data.settings.note)
    var description by remember { mutableStateOf(item.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit design") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text("Design", style = MaterialTheme.typography.labelMedium)
                FlowRowCompat {
                    data.settings.levels.forEach { l -> FilterChip(selected = levelId == l.id, onClick = { levelId = l.id }, label = { Text(l.name) }) }
                }
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it }, singleLine = true,
                    label = { Text("Fixed price for this design (optional)") },
                    prefix = { Text(data.settings.currency) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(summary ?: "No price: set one here or in Prices", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                onSave(item.copy(title = title.trim().ifBlank { item.title }, levelId = levelId, priceOverride = override, description = description.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Prices: a flavour × size table per category, design levels and extras ---

@Composable
private fun PriceEditor(data: MenuData, categories: List<Category>, viewModel: MainViewModel, onSaved: () -> Unit) {
    var currency by remember { mutableStateOf(data.settings.currency) }
    var menuTitle by remember { mutableStateOf(data.settings.title) }
    var note by remember { mutableStateOf(data.settings.note) }
    val tables = remember {
        mutableStateMapOf<String, PriceTable>().apply { data.tables.forEach { put(it.categoryId, it) } }
    }
    val levels = remember { mutableStateListOf<DesignLevel>().apply { addAll(data.settings.levels) } }
    val extras = remember { mutableStateListOf<Extra>().apply { addAll(data.settings.extras) } }
    var pasteFor by remember { mutableStateOf<Category?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Enter your price list once: flavours down the side, sizes across. Designs then show “from” prices, " +
                "and the menu card gets a price-list page. Tip: paste your price list and it fills in for you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        categories.forEach { category ->
            val table = tables[category.id]
            Section("🎂", category.name) {
                if (table == null) {
                    Text("Start from:", style = MaterialTheme.typography.labelMedium)
                    FlowRowCompat {
                        TableTemplate.entries.forEach { t ->
                            AssistChip(onClick = { tables[category.id] = t.table(category.id) }, label = { Text(t.label) })
                        }
                    }
                    FilledTonalButton(onClick = { pasteFor = category }) { Text("📋 Paste my price list") }
                } else {
                    PriceTableEditor(table, currency) { tables[category.id] = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { pasteFor = category }) { Text("📋 Paste list") }
                        TextButton(onClick = { tables.remove(category.id) }) { Text("Clear table", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Section("🎨", "Design levels") {
            Text(
                "Leave “from” empty to use the price table (e.g. Simple). Fill it for designs that start higher.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            levels.forEachIndexed { i, level ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = level.name, onValueChange = { levels[i] = level.copy(name = it) }, singleLine = true,
                        label = { Text("Design") }, modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        value = level.fromPrice?.let { Pricing.trim(it) } ?: "",
                        onValueChange = { levels[i] = level.copy(fromPrice = Pricing.parsePrice(it)) }, singleLine = true,
                        label = { Text("from") }, prefix = { Text(currency) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    if (levels.size > 1) TextButton(onClick = { levels.removeAt(i) }) { Text("✕") }
                }
            }
            TextButton(onClick = { levels += DesignLevel("l${System.currentTimeMillis()}", "New design level") }) { Text("+ Add design level") }
        }
        Section("✨", "Options & extras") {
            Text(
                "Shown on the price list, e.g. Eggless · +20%, Edible image · $15 per page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            extras.forEachIndexed { i, extra ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = extra.name, onValueChange = { extras[i] = extra.copy(name = it) }, singleLine = true, label = { Text("Option") }, modifier = Modifier.weight(1.2f))
                    OutlinedTextField(value = extra.price, onValueChange = { extras[i] = extra.copy(price = it) }, singleLine = true, label = { Text("Price") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { extras.removeAt(i) }) { Text("✕") }
                }
            }
            TextButton(onClick = { extras += Extra("", "") }) { Text("+ Add option") }
        }
        Section("📋", "Menu card text") {
            OutlinedTextField(value = menuTitle, onValueChange = { menuTitle = it }, singleLine = true, label = { Text("Title, e.g. Our Cakes") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Terms, e.g. 50% advance · cancel 4 days before") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text("Currency symbol") }, modifier = Modifier.width(160.dp))
        }
        Button(
            onClick = {
                viewModel.saveMenuPrices(
                    MenuSettings(
                        currency = currency.trim().ifBlank { "$" },
                        title = menuTitle.trim().ifBlank { "Our Cakes" },
                        note = note.trim(),
                        levels = levels.filter { it.name.isNotBlank() }.ifEmpty { DesignLevels.defaults },
                        extras = extras.filter { it.name.isNotBlank() },
                    ),
                    tables.values.map { t -> t.copy(flavours = t.flavours.filter { it.name.isNotBlank() }) },
                )
                onSaved()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save prices") }
        Spacer(Modifier.height(24.dp))
    }

    pasteFor?.let { category ->
        var text by remember { mutableStateOf("") }
        val parsed = remember(text) { PriceListParser.parse(text) }
        AlertDialog(
            onDismissRequest = { pasteFor = null },
            title = { Text("Paste price list: ${category.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Copy your price list (e.g. from your website) and paste it here. A size line like " +
                            "Flavour 6\" 8\" 10\" then lines like Vanilla \$60 \$75 \$95. For packs: Vanilla - 12 \$40.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 6, maxLines = 10, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Found ${parsed.second.size} flavour(s) × ${parsed.first.size} size(s)" +
                            parsed.first.joinToString(", ", prefix = if (parsed.first.isEmpty()) "" else ": ") { it.label },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = parsed.second.isNotEmpty() && parsed.first.isNotEmpty(), onClick = {
                    tables[category.id] = PriceTable(category.id, parsed.first, parsed.second)
                    pasteFor = null
                }) { Text("Use this") }
            },
            dismissButton = { TextButton(onClick = { pasteFor = null }) { Text("Cancel") } },
        )
    }
}

/** Editable grid: size headers (with servings) and one row per flavour. */
@Composable
private fun PriceTableEditor(table: PriceTable, currency: String, onChange: (PriceTable) -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Flavour", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.6f))
            table.sizes.forEachIndexed { i, size ->
                Column(Modifier.weight(1f)) {
                    CompactField(size.label, "Size", small) { v -> onChange(table.copy(sizes = table.sizes.toMutableList().also { it[i] = size.copy(label = v) })) }
                    CompactField(size.serves, "serves", small) { v -> onChange(table.copy(sizes = table.sizes.toMutableList().also { it[i] = size.copy(serves = v) })) }
                }
            }
        }
        table.flavours.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactField(row.name, "Flavour", small, Modifier.weight(1.6f)) { v ->
                    onChange(table.copy(flavours = table.flavours.toMutableList().also { it[r] = row.copy(name = v) }))
                }
                table.sizes.indices.forEach { c ->
                    CompactField(row.prices.getOrNull(c)?.let { Pricing.trim(it) } ?: "", currency, small, Modifier.weight(1f), numeric = true) { v ->
                        val prices = table.sizes.indices.map { i -> if (i == c) Pricing.parsePrice(v) else row.prices.getOrNull(i) }
                        onChange(table.copy(flavours = table.flavours.toMutableList().also { it[r] = row.copy(prices = prices) }))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onChange(table.copy(flavours = table.flavours + FlavourRow("", table.sizes.map { null }))) }) { Text("+ Flavour") }
            TextButton(onClick = {
                onChange(table.copy(sizes = table.sizes + SizeCol("Size"), flavours = table.flavours.map { it.copy(prices = it.prices + null) }))
            }) { Text("+ Size") }
            if (table.flavours.isNotEmpty()) {
                TextButton(onClick = { onChange(table.copy(flavours = table.flavours.dropLast(1))) }) { Text("− Last flavour") }
            }
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    hint: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        singleLine = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(hint, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                inner()
            }
        },
    )
}

// --- Menu card ---

@Composable
private fun MenuCardDialog(data: MenuData, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf(CardSize.POST) }
    var perPage by remember { mutableIntStateOf(4) }
    var backdrop by remember { mutableStateOf<Backdrop>(Backdrop.Solid("Cream", 0xFFFFF4E4.toInt())) }
    var showPrices by remember { mutableStateOf(true) }
    var priceList by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<Pair<List<Uri>, Uri?>?>(null) }
    var working by remember { mutableStateOf(false) }
    val entries by produceState<List<CardEntry>?>(null, data) {
        value = withContext(Dispatchers.IO) { viewModel.menuEntries(data) }
    }
    val options = CardOptions(size, perPage, backdrop, showPrices, priceList)
    val kit = remember { viewModel.brandKit() }
    val tables = remember(data) { viewModel.namedTables(data) }
    var previewPage by remember { mutableIntStateOf(0) }
    val designPages = entries?.let { if (it.isEmpty()) 0 else MenuCard.pages(it, options) } ?: 0
    val pricePages = MenuCard.pricePageCount(tables, data.settings, options)
    val totalPages = designPages + pricePages
    val preview by produceState<Bitmap?>(null, entries, options, previewPage) {
        val e = entries ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val page = previewPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                if (page < designPages) MenuCard.render(e, page, options, kit, data.settings, scale = 0.5f)
                else MenuCard.renderPriceList(tables, page - designPages, options, kit, data.settings, scale = 0.5f)
            }.getOrNull()
        }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Page ${previewPage.coerceAtMost((totalPages - 1).coerceAtLeast(0)) + 1} of $totalPages · ${entries?.size ?: 0} design(s)",
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = previewPage > 0, onClick = { previewPage-- }) { Text("‹") }
                        TextButton(enabled = previewPage < totalPages - 1, onClick = { previewPage++ }) { Text("›") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CardSize.entries.forEach { s -> FilterChip(selected = size == s, onClick = { size = s }, label = { Text(s.label) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6).forEach { n -> FilterChip(selected = perPage == n, onClick = { perPage = n }, label = { Text("$n per page") }) }
                    }
                    SwitchRow("Show “from” prices on designs", showPrices) { showPrices = it }
                    SwitchRow("Add price list page (flavours × sizes)", priceList) { priceList = it }
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
                }) { Text(if (working) "Saving…" else "Save and share") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
