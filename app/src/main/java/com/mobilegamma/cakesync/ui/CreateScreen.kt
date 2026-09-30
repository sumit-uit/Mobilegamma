package com.mobilegamma.cakesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegamma.cakesync.ui.theme.CakeBrush

/** Big, colourful entry points for everything the app can make. */
@Composable
fun CreateScreen(createdCount: Int, onPick: (EditAction) -> Unit, onSeeCreated: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenTitle("Create", "Turn your cakes into posts, reels and collages")
        }
        items(EditAction.creative) { action ->
            Box(
                Modifier
                    .height(150.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(action.brush ?: CakeBrush.hero)
                    .clickable { onPick(action) }
                    .padding(16.dp)
            ) {
                Text(action.emoji, fontSize = 34.sp)
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(action.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(action.blurb, style = MaterialTheme.typography.bodySmall, color = Color(0xE6FFFFFF), maxLines = 2)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedCard(
                onClick = onSeeCreated,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✨", fontSize = 26.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Your creations", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (createdCount == 0) "Nothing yet: pick a tool above" else "$createdCount made so far",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", fontSize = 28.sp)
                }
            }
        }
    }
}
