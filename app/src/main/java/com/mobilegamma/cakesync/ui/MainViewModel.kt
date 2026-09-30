package com.mobilegamma.cakesync.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mobilegamma.cakesync.data.Categories
import com.mobilegamma.cakesync.data.Category
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.data.PhotoStore
import com.mobilegamma.cakesync.data.Settings
import com.mobilegamma.cakesync.drive.DriveAuth
import com.mobilegamma.cakesync.edit.BrandKit
import com.mobilegamma.cakesync.edit.Backdrop
import com.mobilegamma.cakesync.edit.CollageTemplate
import com.mobilegamma.cakesync.edit.ColorFilterPreset
import com.mobilegamma.cakesync.edit.Music
import com.mobilegamma.cakesync.edit.ReelOptions
import com.mobilegamma.cakesync.menu.CardEntry
import com.mobilegamma.cakesync.menu.CardOptions
import com.mobilegamma.cakesync.menu.ColourNames
import com.mobilegamma.cakesync.menu.DesignGrouper
import com.mobilegamma.cakesync.menu.DesignInput
import com.mobilegamma.cakesync.menu.MenuCard
import com.mobilegamma.cakesync.menu.MenuData
import com.mobilegamma.cakesync.menu.MenuItem
import com.mobilegamma.cakesync.menu.MenuNamer
import com.mobilegamma.cakesync.menu.MenuSettings
import com.mobilegamma.cakesync.menu.MenuStore
import com.mobilegamma.cakesync.menu.PriceCard
import com.mobilegamma.cakesync.menu.Pricing
import com.mobilegamma.cakesync.menu.Tier
import com.mobilegamma.cakesync.edit.Creations
import com.mobilegamma.cakesync.edit.PhotoEditor
import com.mobilegamma.cakesync.edit.ReelMaker
import com.mobilegamma.cakesync.drive.DriveClient
import com.mobilegamma.cakesync.share.Captions
import com.mobilegamma.cakesync.share.Catalog
import com.mobilegamma.cakesync.share.Sharer
import com.mobilegamma.cakesync.scan.PhotoScanner
import com.mobilegamma.cakesync.work.SyncScheduler
import com.mobilegamma.cakesync.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val categories: List<Category>,
    val uploadHour: Int,
    val wifiOnly: Boolean,
    val dailySyncEnabled: Boolean,
    val requireApproval: Boolean,
    val excludePeople: Boolean,
    val includeVideos: Boolean,
    val skipDuplicates: Boolean,
    val scanDays: Int,
    val scanFolders: Set<String>,
)

/** Which photos the review grid shows. */
enum class GridTab { MATCHES, VIDEOS, ALL, CREATED }

data class UiState(
    val hasPhotoPermission: Boolean = false,
    val hasVideoPermission: Boolean = false,
    val driveConnected: Boolean = false,
    /** The Google account used for Drive, when one was chosen. */
    val driveAccount: String? = null,
    val tab: GridTab = GridTab.MATCHES,
    val photos: List<Photo> = emptyList(),
    val pendingCount: Int = 0,
    val scanProgress: Pair<Int, Int>? = null,
    val syncRunning: Boolean = false,
    val message: String? = null,
    val consentIntent: PendingIntent? = null,
    val settings: SettingsState? = null,
    /** Photo folders on the device, loaded when the folder picker opens. */
    val folders: List<PhotoScanner.Folder>? = null,
    /** Grid filter: a category id, or null for all categories. */
    val categoryFilter: String? = null,
    /** Labels seen in scanned photos, offered as suggestions when editing a category. */
    val seenLabels: List<String> = emptyList(),
    /** Photos picked with a long-press, for bulk actions. */
    val selected: Set<Long> = emptySet(),
    /** Order tags already used, offered as suggestions. */
    val orderTags: List<String> = emptyList(),
    /** Bumped when the brand kit changes so the preview redraws. */
    val brandVersion: Int = 0,
    /** An edit or reel just finished: offer a shortcut to the Created tab. */
    val resultsReady: Boolean = false,
    /** Newest matched photos and videos, for the home screen. */
    val recent: List<Photo> = emptyList(),
    val matchCount: Int = 0,
    val createdCount: Int = 0,
    /** Designs found in new photos that aren't on the menu yet. */
    val menuNewCount: Int = 0,
    /** The menu screen's data while it is open. */
    val menu: MenuUi? = null,
    /** Whether the first-run introduction still needs showing (null = not loaded yet). */
    val showIntro: Boolean? = null,
)

/** A suggested new design: photos of one cake, with a generated name. */
data class Suggestion(val photos: List<Photo>, val hero: Photo, val title: String, val categoryId: String?)

data class MenuUi(val data: MenuData, val suggestions: List<Suggestion>, val loading: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val categories = Categories(app)
    private val store = PhotoStore.get(app)
    private val auth = DriveAuth(app)
    private val menuStore = MenuStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                val finished = _state.value.syncRunning && !running
                _state.update {
                    it.copy(syncRunning = running, message = if (finished) settings.lastSyncMessage else it.message)
                }
                if (!running) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val tab = _state.value.tab
            val filter = _state.value.categoryFilter
            val loaded = withContext(Dispatchers.IO) {
                val matches = store.matches()
                val created = runCatching { Creations.list(getApplication()) }.getOrDefault(emptyList())
                val photos = when (tab) {
                    GridTab.MATCHES -> matches
                    GridTab.VIDEOS -> matches.filter { it.isVideo }
                    GridTab.ALL -> store.all()
                    GridTab.CREATED -> created
                }.filter { tab == GridTab.CREATED || filter == null || it.category == filter }
                val pending = store.pendingUploads(settings.requireApproval, settings.excludePeople, settings.skipDuplicates).size
                val newDesigns = runCatching { designGroups(matches, menuStore.load()).size }.getOrDefault(0)
                Loaded(photos, pending, matches.sortedByDescending { it.takenAtMillis }.take(12), matches.size, created.size, newDesigns)
            }
            val (photos, pending) = loaded.photos to loaded.pending
            _state.update {
                it.copy(
                    hasPhotoPermission = SyncWorker.hasPhotoPermission(getApplication()),
                    hasVideoPermission = SyncWorker.hasVideoPermission(getApplication()),
                    driveConnected = settings.driveConnected,
                    driveAccount = settings.driveAccount,
                    photos = photos,
                    pendingCount = pending,
                    recent = loaded.recent,
                    matchCount = loaded.matchCount,
                    createdCount = loaded.createdCount,
                    menuNewCount = loaded.newDesigns,
                    showIntro = it.showIntro ?: !settings.onboarded,
                    message = it.message ?: settings.lastSyncMessage,
                    settings = readSettings(),
                )
            }
        }
    }

    private data class Loaded(
        val photos: List<Photo>,
        val pending: Int,
        val recent: List<Photo>,
        val matchCount: Int,
        val createdCount: Int,
        val newDesigns: Int,
    )

    // --- Menu ---

    /** Groups of photos of the same cake that aren't on the menu or skipped yet. */
    private fun designGroups(matches: List<Photo>, data: MenuData): List<List<Photo>> {
        val handled = data.handled
        val candidates = matches.filter {
            !it.isVideo && it.mediaId !in handled && it.included(settings.excludePeople, skipDuplicates = false)
        }
        val byId = candidates.associateBy { it.mediaId }
        val hashes = store.hashes()
        val inputs = candidates.map { DesignInput(it.mediaId, it.takenAtMillis, hashes[it.mediaId], it.sharpness, it.orderTag) }
        return DesignGrouper.group(inputs).map { group -> group.mapNotNull { byId[it.id] } }.filter { it.isNotEmpty() }
    }

    fun openMenu() {
        _state.update { it.copy(menu = MenuUi(menuStore.load(), emptyList(), loading = true)) }
        viewModelScope.launch {
            val ui = withContext(Dispatchers.IO) {
                val data = menuStore.load()
                val groups = designGroups(store.matches(), data).take(40)
                val taken = data.items.map { it.title }.toMutableSet()
                val suggestions = groups.map { photos ->
                    val heroInput = DesignGrouper.hero(photos.map { DesignInput(it.mediaId, it.takenAtMillis, null, it.sharpness, it.orderTag) })
                    val hero = photos.first { it.mediaId == heroInput.id }
                    val labels = photos.flatMap { PhotoStore.parseLabels(it.labels) }
                        .groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.max() }
                    val colour = runCatching {
                        val bmp = PhotoEditor.loadScaled(getApplication(), hero.uri, 64)
                        val x0 = bmp.width / 5
                        val y0 = bmp.height / 5
                        val w = bmp.width - 2 * x0
                        val h = bmp.height - 2 * y0
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, x0, y0, w, h)
                        ColourNames.dominant(px)
                    }.getOrNull()
                    val categoryName = categories.byId(hero.category)?.name ?: "Cake"
                    val title = MenuNamer.unique(MenuNamer.title(labels, colour, singular(categoryName)), taken)
                    taken += title
                    Suggestion(photos, hero, title, hero.category)
                }
                MenuUi(data, suggestions)
            }
            _state.update { it.copy(menu = ui) }
        }
    }

    private fun singular(name: String) = if (name.endsWith("s") && !name.endsWith("ss")) name.dropLast(1) else name

    fun closeMenu() {
        _state.update { it.copy(menu = null) }
        refresh()
    }

    private fun updateMenu(dropSuggestion: Suggestion? = null, change: (MenuData) -> MenuData) {
        val data = menuStore.update(change)
        _state.update { s ->
            val menu = s.menu ?: return@update s
            s.copy(menu = menu.copy(data = data, suggestions = menu.suggestions.filter { it != dropSuggestion }))
        }
    }

    fun addDesign(suggestion: Suggestion, title: String, tier: Tier) = updateMenu(suggestion) { d ->
        d.copy(items = d.items + MenuItem(
            id = "d${suggestion.hero.mediaId}",
            title = title.trim().ifBlank { suggestion.title },
            categoryId = suggestion.categoryId,
            photoIds = suggestion.photos.map { it.mediaId },
            heroId = suggestion.hero.mediaId,
            heroUri = suggestion.hero.uri.toString(),
            tier = tier,
            addedAt = System.currentTimeMillis(),
        ))
    }

    fun skipDesign(suggestion: Suggestion) =
        updateMenu(suggestion) { d -> d.copy(skipped = d.skipped + suggestion.photos.map { it.mediaId }) }

    fun updateMenuItem(item: MenuItem) = updateMenu { d -> d.copy(items = d.items.map { if (it.id == item.id) item else it }) }

    fun removeMenuItem(item: MenuItem) = updateMenu { d -> d.copy(items = d.items.filterNot { it.id == item.id }) }

    fun saveMenuPrices(settings: MenuSettings, cards: List<PriceCard>) = updateMenu { d -> d.copy(settings = settings, cards = cards) }

    /** Renders and saves the menu card pages and PDF, then calls [onDone] with the results. */
    fun exportMenuCard(options: CardOptions, onDone: (List<Uri>, Uri?) -> Unit) {
        val data = menuStore.load()
        if (data.items.isEmpty()) {
            _state.update { it.copy(message = "Add some designs to your menu first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(message = "Making your menu card…") }
            val result = runCatching {
                val entries = withContext(Dispatchers.IO) { menuEntries(data) }
                MenuCard.export(getApplication(), entries, options, brandKit(), data.settings)
            }
            result.onSuccess { (images, pdf) ->
                _state.update {
                    it.copy(
                        resultsReady = true,
                        message = "Menu card saved: ${images.size} page(s) in Created" + if (pdf != null) " and a PDF in Downloads" else "",
                    )
                }
                onDone(images, pdf)
            }.onFailure { e -> _state.update { it.copy(message = "Could not make the menu card: ${e.message}") } }
        }
    }

    /** Menu designs with their photo and price text, for the menu card. */
    fun menuEntries(data: MenuData): List<CardEntry> = data.items.mapNotNull { item ->
        val photo = MenuCard.loadPhoto(getApplication(), item.heroUri) ?: return@mapNotNull null
        CardEntry(item.title, Pricing.summary(data.card(item.categoryId), item.tier, item.priceOverride, data.settings.currency), photo)
    }

    fun shareUris(uris: List<Uri>, mime: String, target: Sharer.Target) {
        val result = runCatching { Sharer.share(getApplication(), uris.map { it to mime }, "", target) }
        _state.update { it.copy(message = result.getOrElse { e -> "Could not share: ${e.message}" }) }
    }


    /** Ends the first-run introduction, optionally saving the business name. */
    fun finishIntro(businessName: String?) {
        settings.onboarded = true
        businessName?.trim()?.takeIf { it.isNotEmpty() }?.let { saveBrandKit(brandKit().copy(businessName = it)) }
        _state.update { it.copy(showIntro = false) }
    }

    fun showIntroAgain() = _state.update { it.copy(showIntro = true) }

    /** Starts a selection with [photo] (from the photo viewer). */
    fun selectOnly(photo: Photo) = _state.update { it.copy(selected = setOf(photo.mediaId)) }

    fun setTab(tab: GridTab) {
        _state.update { it.copy(tab = tab, selected = emptySet(), resultsReady = if (tab == GridTab.CREATED) false else it.resultsReady) }
        refresh()
    }

    fun setCategoryFilter(id: String?) {
        _state.update { it.copy(categoryFilter = id) }
        refresh()
    }

    fun loadSeenLabels() {
        viewModelScope.launch {
            val labels = withContext(Dispatchers.IO) { store.seenLabels() }
            _state.update { it.copy(seenLabels = labels) }
        }
    }

    /** Adds or edits a category, then re-sorts already-scanned photos with the new setup. */
    fun saveCategory(category: Category) = changeCategories { categories.upsert(category) }

    fun deleteCategory(id: String) = changeCategories {
        categories.delete(id)
        if (_state.value.categoryFilter == id) _state.update { it.copy(categoryFilter = null) }
    }

    private fun changeCategories(change: () -> Unit) {
        change()
        _state.update { it.copy(settings = readSettings()) }
        viewModelScope.launch(Dispatchers.IO) {
            val current = categories.all()
            store.reclassify { labels -> categories.classify(labels, current) }
            refresh()
        }
    }

    fun scanNow() {
        if (_state.value.scanProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(scanProgress = 0 to 0, message = "Scanning photos…") }
            val result = runCatching {
                PhotoScanner(getApplication()).scanNew { done, total ->
                    _state.update { it.copy(scanProgress = done to total) }
                }
            }
            _state.update {
                it.copy(
                    scanProgress = null,
                    message = result.fold(
                        { r -> r.summary() },
                        { e -> "Scan failed: ${e.message}" },
                    ),
                )
            }
            refresh()
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            val folders = runCatching { PhotoScanner(getApplication()).listFolders() }.getOrDefault(emptyList())
            _state.update { it.copy(folders = folders) }
        }
    }

    fun syncNow() {
        _state.update { it.copy(message = "Uploading in the background…") }
        SyncScheduler.syncNow(getApplication())
    }

    /** Opens a creation in the phone's photo/video viewer. */
    fun openCreated(photo: Photo) {
        val app = getApplication<Application>()
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, photo.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { e -> _state.update { it.copy(message = "Could not open: ${e.message}") } }
    }

    fun shareCreated(photo: Photo, target: Sharer.Target) {
        val result = runCatching {
            Sharer.share(getApplication(), listOf(photo.uri to photo.mimeType), captionFor(listOf(photo)), target)
        }
        _state.update { it.copy(message = result.getOrElse { e -> "Could not share: ${e.message}" }) }
    }

    fun deleteCreated(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { Creations.delete(getApplication(), photo.uri) }.getOrDefault(false)
            _state.update { it.copy(message = if (ok) "Deleted ${photo.displayName}" else "Could not delete ${photo.displayName}") }
            refresh()
        }
    }

    /** Long-press: start or extend a selection. */
    fun toggleSelected(photo: Photo) {
        _state.update {
            it.copy(selected = if (photo.mediaId in it.selected) it.selected - photo.mediaId else it.selected + photo.mediaId)
        }
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun selectAllShown() = _state.update { s -> s.copy(selected = s.photos.map { it.mediaId }.toSet()) }

    fun includeSelected(include: Boolean) = onSelected { store.setOverride(it, include) }

    /** Tags the selected photos with an order/customer name (null removes the tag). */
    fun tagSelected(tag: String?) = onSelected { store.setOrder(it, tag?.trim()?.ifBlank { null }) }

    fun moveSelectedTo(categoryId: String) = onSelected { store.setCategoryManually(it, categoryId) }

    /** Background removal: saves a white-background copy of each selected photo. */
    fun whiteBackgroundForSelected() = editSelected("White background") { editor, photo ->
        editor.removeBackground(photo.uri, photo.displayName)
    }

    /** Saves a copy of each selected photo cropped to [shape]. */
    fun cropSelected(shape: PhotoEditor.Shape) = editSelected("Crop ${shape.label}") { editor, photo ->
        editor.crop(photo.uri, photo.displayName, shape)
    }

    /** Saves branded copies (logo, name, filter) with an optional price label. */
    fun brandSelected(price: String?) = editSelected("Brand") { editor, photo ->
        editor.brand(photo.uri, photo.displayName, price)
    }

    fun brandKit(): BrandKit = BrandKit.load(getApplication())

    fun saveBrandKit(kit: BrandKit) {
        BrandKit.save(getApplication(), kit)
        _state.update { it.copy(brandVersion = it.brandVersion + 1) }
    }

    fun setBrandLogo(uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (uri == null) BrandKit.removeLogo(getApplication()) else BrandKit.setLogo(getApplication(), uri)
            }
            _state.update {
                it.copy(
                    brandVersion = it.brandVersion + 1,
                    message = result.exceptionOrNull()?.let { e -> "Logo: ${e.message}" } ?: it.message,
                )
            }
        }
    }

    /** Caption for the current selection from its category's template and hashtags. */
    fun captionForSelection(): String = captionFor(_state.value.photos.filter { it.mediaId in _state.value.selected })

    private fun captionFor(photos: List<Photo>): String {
        val categoryId = photos.mapNotNull { it.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Captions.build(photos, categories.byId(categoryId) ?: categories.all().first(), brandKit().businessName)
    }

    /**
     * Shares the selection to [target] with [caption]. With [brand], photos are shared as
     * branded copies (created first); videos are shared as they are.
     */
    fun shareSelected(target: Sharer.Target, caption: String, brand: Boolean) {
        val photos = _state.value.photos.filter { it.mediaId in _state.value.selected }
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val items = mutableListOf<Pair<Uri, String>>()
            val editor = PhotoEditor(getApplication())
            for ((i, photo) in photos.withIndex()) {
                if (brand && !photo.isVideo) {
                    _state.update { it.copy(message = "Branding ${i + 1} of ${photos.size}…") }
                    val branded = runCatching { editor.brand(photo.uri, photo.displayName, null) }.getOrNull()
                    items += (branded ?: photo.uri) to "image/jpeg"
                } else {
                    items += photo.uri to photo.mimeType
                }
            }
            val result = runCatching { Sharer.share(getApplication(), items, caption, target) }
            _state.update {
                it.copy(
                    selected = emptySet(),
                    message = result.getOrElse { e -> "Could not share: ${e.message}" },
                )
            }
        }
    }

    /**
     * Makes a 9:16 reel from the selected photos/videos (in date order) with [options] and
     * either the user's own [music] file or a built-in [track].
     */
    fun makeReel(music: Uri?, track: Music.Track?, options: ReelOptions) {
        val items = _state.value.photos.filter { it.mediaId in _state.value.selected }.sortedBy { it.takenAtMillis }
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(message = "Making a reel from ${items.size} item(s)…", selected = emptySet()) }
            val result = runCatching {
                val song = music ?: track?.let { t ->
                    withContext(Dispatchers.Default) { Uri.fromFile(Music.file(getApplication<Application>().filesDir, t)) }
                }
                ReelMaker(getApplication()).make(items, song, options)
            }
            _state.update {
                it.copy(
                    resultsReady = result.isSuccess,
                    message = result.fold(
                        { "Reel saved (${minOf(items.size, ReelMaker.MAX_ITEMS)} item(s)). Find it under Created." },
                        { e -> "Could not make the reel: ${e.message}" },
                    ),
                )
            }
        }
    }

    fun say(message: String) = _state.update { it.copy(message = message) }

    /** A studio edit was saved: show it under Created. */
    fun studioSaved() {
        _state.update { it.copy(message = "Studio edit saved. Find it under Created.", resultsReady = true) }
        refresh()
    }

    fun shareUri(uri: Uri, target: Sharer.Target) {
        val result = runCatching { Sharer.share(getApplication(), listOf(uri to "image/jpeg"), "", target) }
        _state.update { it.copy(message = result.getOrElse { e -> "Could not share: ${e.message}" }) }
    }

    /** Saves a copy of each selected photo with a colour filter. */
    fun filterSelected(filter: ColorFilterPreset) = editSelected("${filter.label} filter") { editor, photo ->
        editor.filter(photo.uri, photo.displayName, filter)
    }

    /** The selected photos (not videos) in date order, for collages and previews. */
    fun selectedPhotos(): List<Photo> =
        _state.value.photos.filter { it.mediaId in _state.value.selected && !it.isVideo }.sortedBy { it.takenAtMillis }

    fun makeCollage(
        template: CollageTemplate,
        shape: PhotoEditor.Shape,
        backdrop: Backdrop,
        spacing: Float,
        rounded: Boolean,
        brand: Boolean,
        price: String?,
    ) {
        val photos = selectedPhotos()
        if (photos.size < template.size) {
            _state.update { it.copy(message = "Collage: “${template.label}” needs ${template.size} photos") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(message = "Making a collage…") }
            val result = runCatching {
                PhotoEditor(getApplication()).collage(photos.map { it.uri }, template, shape, backdrop, spacing, rounded, brand, price)
            }
            _state.update {
                it.copy(
                    selected = emptySet(),
                    resultsReady = result.isSuccess,
                    message = result.fold({ "Collage saved. Find it under Created." }, { e -> "Could not make the collage: ${e.message}" }),
                )
            }
        }
    }

    /**
     * Writes a Meta catalog CSV (Commerce Manager bulk upload) for the selected photos that
     * are already in Drive, sharing each image by link so Meta can fetch it.
     */
    fun exportCatalog(price: String) {
        val selected = _state.value.photos.filter { it.mediaId in _state.value.selected && !it.isVideo }
        val uploaded = selected.filter { it.driveFileId != null }
        if (uploaded.isEmpty()) {
            _state.update { it.copy(message = "Catalog: upload these photos to Drive first (catalog images must be online)") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(message = "Catalog: sharing ${uploaded.size} image(s)…", selected = emptySet()) }
            val result = runCatching {
                val drive = DriveClient(driveToken())
                val business = brandKit().businessName
                val items = uploaded.map { photo ->
                    drive.makePublic(photo.driveFileId!!)
                    val category = categories.byId(photo.category)?.name ?: "Cake"
                    val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(photo.takenAtMillis))
                    Catalog.Item(
                        id = "cakesync-${photo.mediaId}",
                        title = listOfNotNull(category, photo.orderTag).joinToString(" – "),
                        description = listOfNotNull("$category made $date", business.ifBlank { null }).joinToString(" · "),
                        imageLink = Catalog.driveImageLink(photo.driveFileId),
                        price = price.trim(),
                        brand = business,
                    )
                }
                saveDownload("CakeSync_catalog_${System.currentTimeMillis() / 1000}.csv", "text/csv", Catalog.toCsv(items))
                items.size
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        { n ->
                            "Catalog: $n item(s) saved to Downloads. Upload it in Meta Commerce Manager → Catalog → Data sources" +
                                (if (selected.size > n) " (${selected.size - n} not yet in Drive were skipped)" else "")
                        },
                        { e -> "Catalog failed: ${e.message}" },
                    ),
                )
            }
        }
    }

    /** Shares a category's Drive folder with "anyone with the link" and returns the link. */
    fun portfolioLink(category: Category, onLink: (String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val drive = DriveClient(driveToken())
                val id = settings.rootFolderId(category)?.takeIf { drive.folderExists(it) }
                    ?: drive.findOrCreateFolder(category.driveFolder, null).also { settings.setRootFolderId(category, it) }
                drive.makePublic(id)
                Catalog.driveFolderLink(id)
            }
            result.onSuccess(onLink)
            _state.update {
                it.copy(message = result.fold({ "Portfolio link for ${category.name}: $it" }, { e -> "Portfolio link failed: ${e.message}" }))
            }
        }
    }

    private suspend fun driveToken(): String =
        when (val auth = auth.authorize()) {
            is DriveAuth.Outcome.Token -> auth.accessToken
            is DriveAuth.Outcome.NeedsConsent -> {
                _state.update { it.copy(consentIntent = auth.pendingIntent) }
                throw IllegalStateException("connect Google Drive first")
            }
        }

    private suspend fun saveDownload(name: String, mime: String, text: String) = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/CakeSync")
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("Could not create $name")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    private fun editSelected(what: String, edit: suspend (PhotoEditor, Photo) -> Unit) {
        val photos = _state.value.photos.filter { it.mediaId in _state.value.selected && !it.isVideo }
        if (photos.isEmpty()) {
            _state.update { it.copy(message = "$what: select photos (videos can't be edited)") }
            return
        }
        viewModelScope.launch {
            val editor = PhotoEditor(getApplication())
            var done = 0
            var error: String? = null
            for ((i, photo) in photos.withIndex()) {
                _state.update { it.copy(message = "$what: ${i + 1} of ${photos.size}…") }
                try {
                    edit(editor, photo)
                    done++
                } catch (e: Exception) {
                    error = e.message
                    if (e is PhotoEditor.ModelDownloading) break // same for every photo; try later
                }
            }
            finishEdit(what, done, error)
        }
    }

    private fun finishEdit(what: String, done: Int, error: String?) {
        val text = buildString {
            append("$what: saved $done new photo(s), see Created (originals unchanged)")
            if (error != null) append(" · problem: $error")
        }
        _state.update { it.copy(message = text, selected = emptySet(), resultsReady = done > 0) }
    }

    fun loadOrderTags() {
        viewModelScope.launch {
            val tags = withContext(Dispatchers.IO) { store.orderTags() }
            _state.update { it.copy(orderTags = tags) }
        }
    }

    private fun onSelected(action: (Set<Long>) -> Unit) {
        val ids = _state.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            action(ids)
            _state.update { it.copy(selected = emptySet()) }
            refresh()
        }
    }

    /** Cycles a photo between included and excluded (an explicit user decision). */
    fun toggle(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setOverride(photo.mediaId, !photo.included(settings.excludePeople, settings.skipDuplicates))
            refresh()
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            val outcome = runCatching { auth.authorize() }
            handleAuth(outcome)
        }
    }

    /** Switches Drive to another Google account; folders are found or created again there. */
    fun switchDriveAccount(accountName: String) {
        settings.driveAccount = accountName
        settings.clearDriveFolders()
        settings.driveConnected = false
        _state.update { it.copy(driveConnected = false, driveAccount = accountName) }
        connectDrive()
    }

    fun disconnectDrive() {
        settings.driveConnected = false
        settings.driveAccount = null
        settings.clearDriveFolders()
        _state.update { it.copy(driveConnected = false, driveAccount = null, message = "Google Drive disconnected") }
    }

    /** Uploads for [category] go to a Drive folder called [folder] from now on. */
    fun setDriveFolder(category: Category, folder: String) {
        val name = folder.trim().ifBlank { return }
        saveCategory(category.copy(driveFolder = name))
        _state.update { it.copy(message = "${category.name} will upload to the Drive folder “$name”") }
    }

    fun onConsentLaunched() = _state.update { it.copy(consentIntent = null) }

    fun onConsentResult(data: Intent?) = handleAuth(runCatching { auth.resultFromConsent(data) })

    private fun handleAuth(outcome: Result<DriveAuth.Outcome>) {
        outcome.onSuccess { result ->
            when (result) {
                is DriveAuth.Outcome.NeedsConsent -> _state.update { it.copy(consentIntent = result.pendingIntent) }
                is DriveAuth.Outcome.Token -> {
                    settings.driveConnected = true
                    _state.update { it.copy(driveConnected = true, message = "Google Drive connected") }
                }
            }
        }.onFailure { e ->
            _state.update { it.copy(message = "Could not connect Drive: ${e.message}") }
        }
    }

    fun updateSettings(transform: Settings.() -> Unit) {
        val before = readSettings()
        settings.transform()
        val after = readSettings()
        if (before.uploadHour != after.uploadHour || before.wifiOnly != after.wifiOnly ||
            before.dailySyncEnabled != after.dailySyncEnabled
        ) {
            SyncScheduler.apply(getApplication())
        }
        _state.update { it.copy(settings = after) }
        if (before.requireApproval != after.requireApproval || before.excludePeople != after.excludePeople ||
            before.skipDuplicates != after.skipDuplicates
        ) refresh()
    }

    private fun readSettings() = SettingsState(
        categories = categories.all(),
        uploadHour = settings.uploadHour,
        wifiOnly = settings.wifiOnly,
        dailySyncEnabled = settings.dailySyncEnabled,
        requireApproval = settings.requireApproval,
        excludePeople = settings.excludePeople,
        includeVideos = settings.includeVideos,
        skipDuplicates = settings.skipDuplicates,
        scanDays = settings.scanDays,
        scanFolders = settings.scanFolders,
    )
}
