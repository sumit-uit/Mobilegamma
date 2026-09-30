package com.mobilegamma.cakesync.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.video.VideoFrameDecoder
import com.mobilegamma.cakesync.data.Photo
import com.mobilegamma.cakesync.ui.theme.CakeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
            }
            CakeTheme { CakeSyncApp(viewModel) }
        }
    }
}

/** The four main pages, in bottom-bar order. */
enum class Screen(val label: String) { HOME("Home"), GALLERY("Gallery"), CREATE("Create"), SETTINGS("Settings") }

@Composable
private fun CakeSyncApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-check permissions etc. whenever the user returns to the app.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onConsentResult(result.data) }

    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            viewModel.onConsentLaunched()
        }
    }

    // Ask for video access once per app start if videos are on and it's missing. Updating
    // from a photo-only version keeps photo access but never grants videos by itself.
    val wantVideos = state.settings?.includeVideos == true
    var askedForVideos by rememberSaveable { mutableStateOf(false) }
    val requestVideos = {
        askedForVideos = true
        permissionLauncher.launch(videoPermissions().toTypedArray())
    }
    LaunchedEffect(state.hasPhotoPermission, state.hasVideoPermission, wantVideos) {
        if (state.hasPhotoPermission && wantVideos && !state.hasVideoPermission && !askedForVideos) requestVideos()
    }
    val grantPhotos = {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val context = LocalContext.current
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }

    when (state.showIntro) {
        null -> Surface(Modifier.fillMaxSize()) {}
        true -> Onboarding(
            hasPhotoPermission = state.hasPhotoPermission,
            onGrantPhotos = grantPhotos,
            onFinish = viewModel::finishIntro,
        )
        false -> MainScaffold(state, viewModel, grantPhotos, requestVideos, openAppSettings)
    }
}

@Composable
private fun MainScaffold(
    state: UiState,
    viewModel: MainViewModel,
    grantPhotos: () -> Unit,
    grantVideos: () -> Unit,
    openAppSettings: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var pending by rememberSaveable { mutableStateOf<EditAction?>(null) }
    var dialog by remember { mutableStateOf<EditAction?>(null) }
    var openCreation by remember { mutableStateOf<Photo?>(null) }
    val categories = state.settings?.categories.orEmpty()
    val businessName = remember(state.brandVersion) { viewModel.brandKit().businessName }

    fun go(to: Screen, tab: GridTab? = null) {
        tab?.let { viewModel.setTab(it) }
        screen = to
    }

    fun startCreate(action: EditAction) {
        pending = action
        viewModel.clearSelection()
        if (state.tab == GridTab.CREATED) viewModel.setTab(GridTab.MATCHES)
        screen = Screen.GALLERY
    }

    BackHandler(enabled = screen != Screen.HOME || state.selected.isNotEmpty() || pending != null) {
        when {
            pending != null -> { pending = null; viewModel.clearSelection() }
            state.selected.isNotEmpty() -> viewModel.clearSelection()
            else -> screen = Screen.HOME
        }
    }

    Scaffold(
        bottomBar = {
            when {
                screen == Screen.GALLERY && pending != null -> ContinueBar(
                    action = pending!!,
                    count = state.selected.size,
                    onContinue = {
                        val action = pending!!
                        pending = null
                        runAction(action, viewModel) { dialog = it }
                    },
                )
                screen == Screen.GALLERY && state.selected.isNotEmpty() -> SelectionPanel(
                    count = state.selected.size,
                    showCategory = categories.size > 1,
                    onAction = { runAction(it, viewModel) { a -> dialog = a } },
                    onSelectAll = viewModel::selectAllShown,
                    onDone = viewModel::clearSelection,
                )
                else -> NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Screen.entries.forEach { item ->
                        NavigationBarItem(
                            selected = screen == item,
                            onClick = { screen = item },
                            icon = { Icon(item.icon(), contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }) togetherWith fadeOut(tween(120))
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "screen",
        ) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(
                    state = state,
                    businessName = businessName,
                    onGrantPhotos = grantPhotos,
                    onGrantVideos = grantVideos,
                    onOpenSettings = openAppSettings,
                    onConnectDrive = viewModel::connectDrive,
                    onScan = viewModel::scanNow,
                    onSync = viewModel::syncNow,
                    onViewResults = { go(Screen.GALLERY, GridTab.CREATED) },
                    onSeeAll = { go(Screen.GALLERY, GridTab.MATCHES) },
                    onOpenPhoto = { go(Screen.GALLERY, GridTab.MATCHES) },
                    onCreate = ::startCreate,
                )
                Screen.GALLERY -> GalleryScreen(
                    state = state,
                    viewModel = viewModel,
                    pendingAction = pending,
                    onCancelPending = { pending = null; viewModel.clearSelection() },
                    onOpenCreation = { openCreation = it },
                )
                Screen.CREATE -> CreateScreen(
                    createdCount = state.createdCount,
                    onPick = ::startCreate,
                    onSeeCreated = { go(Screen.GALLERY, GridTab.CREATED) },
                )
                Screen.SETTINGS -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onConnectDrive = viewModel::connectDrive,
                    onShowIntro = viewModel::showIntroAgain,
                )
            }
        }
    }

    dialog?.let { action -> ActionDialog(action, state, categories, viewModel) { dialog = null } }
    openCreation?.let { item ->
        CreationDialog(
            item = item,
            onDismiss = { openCreation = null },
            onOpen = { viewModel.openCreated(item); openCreation = null },
            onShare = { target -> viewModel.shareCreated(item, target); openCreation = null },
            onDelete = { viewModel.deleteCreated(item); openCreation = null },
        )
    }
}

private fun Screen.icon(): ImageVector = when (this) {
    Screen.HOME -> Icons.Filled.Home
    Screen.GALLERY -> AppIcons.Gallery
    Screen.CREATE -> AppIcons.Sparkle
    Screen.SETTINGS -> Icons.Filled.Settings
}

/** Bottom bar while picking photos for a tool chosen on the Create screen. */
@Composable
private fun ContinueBar(action: EditAction, count: Int, onContinue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp, shadowElevation = 12.dp) {
        Button(
            onClick = onContinue,
            enabled = count >= action.minPhotos,
            modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Text(
                when {
                    count == 0 -> "Select photos to continue"
                    count < action.minPhotos -> "Select ${action.minPhotos - count} more"
                    else -> "Continue: ${action.title} with $count"
                }
            )
        }
    }
}

private fun videoPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
    else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}
