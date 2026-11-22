package com.leemon41k.glyphlab

import android.content.ComponentName
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import kotlin.math.floor

private const val MATRIX_SIZE = 25
private const val MATRIX_CELLS = MATRIX_SIZE * MATRIX_SIZE
private const val GRID_SCALE = 0.86f

class MainActivity : ComponentActivity() {
    private var glyphManager: GlyphMatrixManager? = null
    private val glyphStatus = mutableStateOf("ПОДКЛЮЧЕНИЕ К GLYPH MATRIX…")
    private var glyphReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectGlyphMatrix()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF111111),
                primary = Color.White,
                onPrimary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                GlyphLabEditor(
                    glyphStatus = glyphStatus.value,
                    onShowOnGlyph = { pixels -> showOnGlyph(pixels) },
                    onUpdateGlyph = { pixels -> updateGlyphFrame(pixels) },
                    onTurnOffGlyph = { turnOffGlyph() }
                )
            }
        }
    }

    private fun connectGlyphMatrix() {
        try {
            val manager = GlyphMatrixManager.getInstance(applicationContext)
            glyphManager = manager
            val callback = object : GlyphMatrixManager.Callback {
                override fun onServiceConnected(componentName: ComponentName) {
                    try {
                        manager.register(Glyph.DEVICE_23112)
                        glyphReady = true
                        runOnUiThread { glyphStatus.value = "GLYPH MATRIX ПОДКЛЮЧЕНА" }
                    } catch (error: Exception) {
                        glyphReady = false
                        runOnUiThread { glyphStatus.value = "ОШИБКА РЕГИСТРАЦИИ GLYPH MATRIX" }
                    }
                }

                override fun onServiceDisconnected(componentName: ComponentName) {
                    glyphReady = false
                    runOnUiThread { glyphStatus.value = "GLYPH MATRIX ОТКЛЮЧЕНА" }
                }
            }
            manager.init(callback)
        } catch (error: Exception) {
            glyphReady = false
            glyphStatus.value = "GLYPH MATRIX НЕДОСТУПНА"
        }
    }

    private fun makeFrame(pixels: List<Boolean>) = IntArray(MATRIX_CELLS) { index ->
        if (pixels.getOrNull(index) == true) 2047 else 0
    }

    private fun showOnGlyph(pixels: List<Boolean>) {
        if (!glyphReady || glyphManager == null) {
            glyphStatus.value = "СНАЧАЛА ДОЖДИТЕСЬ ПОДКЛЮЧЕНИЯ GLYPH"
            return
        }
        updateGlyphFrame(pixels)
        glyphStatus.value = "ОТОБРАЖЕНИЕ GLYPH ВКЛЮЧЕНО"
    }

    private fun updateGlyphFrame(pixels: List<Boolean>) {
        val manager = glyphManager
        if (!glyphReady || manager == null) return
        try {
            manager.setAppMatrixFrame(makeFrame(pixels))
        } catch (error: Exception) {
            glyphStatus.value = "НЕ УДАЛОСЬ ОБНОВИТЬ GLYPH MATRIX"
        }
    }

    private fun turnOffGlyph() {
        try {
            glyphManager?.closeAppMatrix()
            glyphStatus.value = "ОТОБРАЖЕНИЕ GLYPH ВЫКЛЮЧЕНО"
        } catch (error: Exception) {
            glyphStatus.value = "НЕ УДАЛОСЬ ВЫКЛЮЧИТЬ GLYPH"
        }
    }

    override fun onDestroy() {
        try {
            glyphManager?.closeAppMatrix()
            glyphManager?.unInit()
        } catch (_: Exception) {
        }
        glyphReady = false
        glyphManager = null
        super.onDestroy()
    }
}

@Composable
private fun GlyphLabEditor(
    glyphStatus: String,
    onShowOnGlyph: (List<Boolean>) -> Unit,
    onUpdateGlyph: (List<Boolean>) -> Unit,
    onTurnOffGlyph: () -> Unit
) {
    val pixels = remember {
        mutableStateListOf<Boolean>().apply {
            repeat(MATRIX_CELLS) { add(false) }
        }
    }
    val glyphVisible = remember { mutableStateOf(false) }

    // While the eye is enabled, every edit is immediately mirrored to the physical matrix.
    LaunchedEffect(glyphVisible.value) {
        if (glyphVisible.value) {
            snapshotFlow { pixels.toList() }.drop(1).collect { editedPixels ->
                onUpdateGlyph(editedPixels)
            }
        }
    }

    val visiblePixelIndices = remember {
        buildList {
            val center = MATRIX_SIZE / 2f
            val pixelHalf = 0.72f / 2f
            val maxRadius = center - pixelHalf
            for (row in 0 until MATRIX_SIZE) {
                for (column in 0 until MATRIX_SIZE) {
                    val dx = column + 0.5f - center
                    val dy = row + 0.5f - center
                    if (dx * dx + dy * dy <= maxRadius * maxRadius) {
                        add(row * MATRIX_SIZE + column)
                    }
                }
            }
        }
    }
    val visibleMask = remember {
        BooleanArray(MATRIX_CELLS).also { mask ->
            visiblePixelIndices.forEach { mask[it] = true }
        }
    }
    val litCount = visiblePixelIndices.count { pixels[it] }

    fun pixelIndex(position: Offset, width: Float): Int? {
        if (width <= 0f) return null
        val gridSide = width * GRID_SCALE
        val gridStart = (width - gridSide) / 2f
        val localX = position.x - gridStart
        val localY = position.y - gridStart
        if (localX < 0f || localY < 0f || localX >= gridSide || localY >= gridSide) return null
        val cell = gridSide / MATRIX_SIZE
        val column = floor(localX / cell).toInt()
        val row = floor(localY / cell).toInt()
        if (column !in 0 until MATRIX_SIZE || row !in 0 until MATRIX_SIZE) return null
        val dx = localX - gridSide / 2f
        val dy = localY - gridSide / 2f
        if (dx * dx + dy * dy > (gridSide / 2f) * (gridSide / 2f)) return null
        val index = row * MATRIX_SIZE + column
        return index.takeIf { visibleMask[it] }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 20.dp)
                .padding(top = 68.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("GLYPHLAB", fontSize = 25.sp, color = Color.White, letterSpacing = 4.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "MATRIX PAINTER  ·  25 × 25",
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
                        var eraseMode = false
                        detectDragGestures(
                            onDragStart = { offset ->
                                val index = pixelIndex(offset, size.width.toFloat())
                                if (index != null) {
                                    eraseMode = pixels[index]
                                    pixels[index] = !eraseMode
                                }
                            },
                            onDrag = { change, _ ->
                                pixelIndex(change.position, size.width.toFloat())?.let { index ->
                                    pixels[index] = !eraseMode
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                val panelCenter = size.width / 2f
                val panelRadius = panelCenter - 1.5.dp.toPx()
                val gridSide = size.width * GRID_SCALE
                val gridStart = (size.width - gridSide) / 2f
                val cell = gridSide / MATRIX_SIZE
                val pixelSize = cell * 0.72f

                // Larger circular rim around the inset 25x25 square LED layout.
                drawCircle(
                    color = Color(0xFF171719),
                    radius = panelRadius,
                    center = Offset(panelCenter, panelCenter)
                )
                drawCircle(
                    color = Color(0xFF45454A),
                    radius = panelRadius,
                    center = Offset(panelCenter, panelCenter),
                    style = Stroke(width = 2.dp.toPx())
                )

                for (index in visiblePixelIndices) {
                    val row = index / MATRIX_SIZE
                    val column = index % MATRIX_SIZE
                    val x = gridStart + (column + 0.5f) * cell
                    val y = gridStart + (row + 0.5f) * cell
                    drawRect(
                        color = if (pixels[index]) Color.White else Color(0xFF050506),
                        topLeft = Offset(x - pixelSize / 2f, y - pixelSize / 2f),
                        size = Size(pixelSize, pixelSize)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$litCount / ${visiblePixelIndices.size} PIXELS",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    letterSpacing = 1.sp
                )
                Button(onClick = {
                    for (i in pixels.indices) pixels[i] = false
                }) {
                    Text("CLEAR")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                glyphStatus,
                color = Color(0xFF888888),
                fontSize = 10.sp,
                letterSpacing = 0.7.sp
            )
            Spacer(Modifier.height(8.dp))
            IconButton(
                onClick = {
                    val nextVisible = !glyphVisible.value
                    glyphVisible.value = nextVisible
                    if (nextVisible) onShowOnGlyph(pixels.toList()) else onTurnOffGlyph()
                }
            ) {
                Icon(
                    imageVector = if (glyphVisible.value) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (glyphVisible.value) "Скрыть Glyph Matrix" else "Показать на Glyph Matrix",
                    tint = if (glyphVisible.value) Color.White else Color(0xFF888888)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "TAP TO TOGGLE  ·  DRAG TO DRAW / ERASE",
                color = Color(0xFF666666),
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
