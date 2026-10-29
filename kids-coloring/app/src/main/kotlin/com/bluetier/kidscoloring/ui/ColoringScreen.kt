package com.bluetier.kidscoloring.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import com.bluetier.kidscoloring.art.Level
import com.bluetier.kidscoloring.art.Picture
import kotlin.math.min

/** The whole app: controls on top, colouring canvas in the middle, crayons below. */
@Composable
fun ColoringScreen(viewModel: ColoringViewModel) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- Difficulty selector ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Level.values().forEach { level ->
                val selected = level == viewModel.level
                Button(
                    onClick = { viewModel.selectLevel(level) },
                    modifier = Modifier.weight(1f),
                    colors = if (selected) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    },
                ) { Text(level.label) }
            }
        }

        // --- Title + page navigation ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = viewModel::previous) { Text("◀") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    viewModel.picture.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                viewModel.picture.subtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Button(onClick = viewModel::next) { Text("▶") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::clearCurrent, modifier = Modifier.weight(1f)) { Text("Clear") }
            Button(
                onClick = {
                    val bitmap = renderToBitmap(viewModel.picture, viewModel::fillOf, sizePx = 1080)
                    val ok = saveToGallery(context, bitmap, "${viewModel.picture.name}-${System.currentTimeMillis()}")
                    Toast.makeText(context, if (ok) "Saved to gallery!" else "Could not save", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        }

        // --- Canvas ---
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(viewModel.level, viewModel.index) {
                    detectTapGestures { tap ->
                        val side = min(size.width, size.height).toFloat()
                        if (side <= 0f) return@detectTapGestures
                        val scale = side / Picture.SIZE
                        val dx = (size.width - side) / 2f
                        val dy = (size.height - side) / 2f
                        viewModel.tapAt((tap.x - dx) / scale, (tap.y - dy) / scale)
                    }
                },
        ) {
            val side = min(size.width, size.height)
            val scale = side / Picture.SIZE
            val dx = (size.width - side) / 2f
            val dy = (size.height - side) / 2f
            fun mapped(region: com.bluetier.kidscoloring.art.Region): Path = Path().apply {
                region.points.forEachIndexed { i, p ->
                    val x = dx + p.x * scale
                    val y = dy + p.y * scale
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            val paths = viewModel.picture.regions.map { it to mapped(it) }
            paths.forEach { (region, path) ->
                drawPath(path, color = Color(viewModel.fillOf(region.id) ?: Palette.WHITE))
            }
            paths.forEach { (_, path) ->
                drawPath(path, color = Color.Black, style = Stroke(width = side * 0.012f, join = StrokeJoin.Round))
            }
        }

        // --- Crayon palette ---
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Palette.colors.forEach { argb ->
                val selected = argb == viewModel.selectedColor
                Box(
                    modifier = Modifier
                        .size(if (selected) 56.dp else 46.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(width = if (selected) 4.dp else 2.dp, color = Color.Black, shape = CircleShape)
                        .clickable { viewModel.selectColor(argb) },
                ) {}
            }
        }

        // --- Build marker: lets you confirm at a glance which version is installed ---
        Text(
            "Coloring Fun v0.3 · detailed art",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
