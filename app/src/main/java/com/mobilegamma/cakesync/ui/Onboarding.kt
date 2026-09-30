package com.mobilegamma.cakesync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegamma.cakesync.ui.theme.CakeBrush
import kotlinx.coroutines.launch

/** Three-step first-run introduction. */
@Composable
fun Onboarding(
    hasPhotoPermission: Boolean,
    onGrantPhotos: () -> Unit,
    onFinish: (businessName: String?) -> Unit,
) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onFinish(name) }) { Text("Skip") }
            }
            HorizontalPager(pager, modifier = Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    val (emoji, title, text) = when (page) {
                        0 -> Triple(
                            "🎂", "Welcome to CakeSync",
                            "Finds the cake photos and videos on your phone, keeps the best shots and gets them ready " +
                                "to post, upload and sell.",
                        )
                        1 -> Triple(
                            "🔒", "Private by design",
                            "Photos are checked on your phone, never sent anywhere to be analysed. Only the ones you " +
                                "choose go to your own Google Drive.",
                        )
                        else -> Triple(
                            "🏷", "Make it yours",
                            "Your business name goes on branded photos, collages and captions. You can add a logo later " +
                                "in Settings.",
                        )
                    }
                    Box(
                        Modifier.size(160.dp).clip(CircleShape).background(CakeBrush.hero),
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 72.sp) }
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(
                        text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (page) {
                        1 -> if (hasPhotoPermission) {
                            Text("✓ Photo access allowed", color = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Button(onClick = onGrantPhotos) { Text("Allow photo access") }
                        }
                        2 -> OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text("Business name, e.g. Soni Bakes") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { i ->
                        val active = pager.currentPage == i
                        val width by animateDpAsState(if (active) 24.dp else 8.dp, label = "dot")
                        val color by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            label = "dotColor",
                        )
                        Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
                    }
                }
                val last = pager.currentPage == 2
                Button(
                    onClick = { if (last) onFinish(name) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.height(52.dp),
                ) { Text(if (last) "Get started" else "Next") }
            }
        }
    }
}
