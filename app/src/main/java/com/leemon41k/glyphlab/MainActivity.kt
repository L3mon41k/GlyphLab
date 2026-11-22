package com.leemon41k.glyphlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

private const val MATRIX_SIZE = 25

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF111111),
                primary = Color.White,
                onPrimary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                GlyphLabEditor()
            }
        }
    }
}

@Composable
private fun GlyphLabEditor() {
    val pixels = remember { mutableStateListOf<Boolean>().apply {
        repeat(MATRIX_SIZE * MATRIX_SIZE) { add(false) }
    } }
    val litCount = pixels.count { it }

    fun isInsideMatrix(position: Offset, width: Float): Boolean {
        val center = width / 2f
        val dx = position.x - center
        val dy = position.y - center
        return dx * dx + dy * dy <= center * center
    }

    fun pixelIndex(position: Offset, width: Float): Int? {
        if (width <= 0f || position.x < 0f || position.y < 0f ||
            position.x >= width || position.y >= width ||
            !isInsideMatrix(position, width)
        ) return null
        val cell = width / MATRIX_SIZE
        val column = floor(position.x / cell).toInt().coerceIn(0, MATRIX_SIZE - 1)
        val row = floor(position.y / cell).toInt().coerceIn(0, MATRIX_SIZE - 1)
        return row * MATRIX_SIZE + column
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 20.dp)
                .padding(top = 68.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "GLYPHLAB",
                fontSize = 25.sp,
                color = Color.White,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "MATRIX PAINTER  ·  25 × 25",
                fontSize = 11.sp,
                color = Color(0xFF888888),
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(28.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            pixelIndex(offset, size.width.toFloat())?.let { index ->
                                pixels[index] = !pixels[index]
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                pixelIndex(offset, size.width.toFloat())?.let { index ->
                                    pixels[index] = true
                                }
                            },
                            onDrag = { change, _ ->
                                pixelIndex(change.position, size.width.toFloat())?.let { index ->
                                    pixels[index] = true
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                val cell = size.width / MATRIX_SIZE
                val pixelSize = cell * 0.72f
                val center = size.width / 2f
                val radius = center

                // The physical Glyph Matrix is circular, not a square panel.
                drawCircle(
                    color = Color(0xFF080808),
                    radius = radius,
                    center = Offset(center, center)
                )

                for (row in 0 until MATRIX_SIZE) {
                    for (column in 0 until MATRIX_SIZE) {
                        val x = (column + 0.5f) * cell
                        val y = (row + 0.5f) * cell
                        val dx = x - center
                        val dy = y - center

                        // Keep each square LED fully inside the circular boundary.
                        if (dx * dx + dy * dy <= (radius - pixelSize / 2f) * (radius - pixelSize / 2f)) {
                            val index = row * MATRIX_SIZE + column
                            drawRect(
                                color = if (pixels[index]) Color.White else Color(0xFF303030),
                                topLeft = Offset(x - pixelSize / 2f, y - pixelSize / 2f),
                                size = androidx.compose.ui.geometry.Size(pixelSize, pixelSize)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$litCount / 625 PIXELS",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Button(
                    onClick = { for (i in pixels.indices) pixels[i] = false }
                ) {
                    Text("CLEAR")
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "TAP TO TOGGLE  ·  DRAG TO DRAW",
                color = Color(0xFF666666),
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
