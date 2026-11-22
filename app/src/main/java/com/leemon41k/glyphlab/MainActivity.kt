package com.leemon41k.glyphlab

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlin.math.floor

private const val MATRIX_SIZE = 25
private const val MATRIX_CELLS = MATRIX_SIZE * MATRIX_SIZE
private const val GRID_SCALE = 0.86f
private val Accent = Color(0xFFFF3038)
private val Panel = Color(0xFF151517)
private val Muted = Color(0xFF85858B)

class MainActivity : ComponentActivity() {
    private var glyphManager: GlyphMatrixManager? = null
    private val glyphStatus = mutableStateOf("ПОДКЛЮЧЕНИЕ К GLYPH MATRIX…")
    private val modelProgress = mutableStateOf(ModelDownloadProgress())
    private val modelError = mutableStateOf<String?>(null)
    private val modelReady = mutableStateOf(false)
    private var qwenEngine: Engine? = null
    private var qwenConversation: Conversation? = null
    private var glyphReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectGlyphMatrix()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black, surface = Panel, primary = Accent,
                    onPrimary = Color.White, onBackground = Color.White,
                    onSurface = Color.White, secondary = Accent
                )
            ) {
                if (modelReady.value) {
                    GlyphLabEditor(
                        glyphStatus = glyphStatus.value,
                        onShowOnGlyph = { showOnGlyph(it) },
                        onUpdateGlyph = { updateGlyphFrame(it) },
                        onTurnOffGlyph = { turnOffGlyph() },
                        onGenerate = { prompt, onText, onComplete ->
                            generateWithQwen(prompt, onText, onComplete)
                        }
                    )
                } else {
                    ModelSetupScreen(
                        progress = modelProgress.value,
                        error = modelError.value,
                        onRetry = { startModelSetup() }
                    )
                }
            }
        }
        startModelSetup()
    }

    private fun startModelSetup() {
        modelError.value = null
        lifecycleScope.launch {
            try {
                val modelFile = QwenModelInstaller.ensureInstalled(this@MainActivity) { progress ->
                    withContext(Dispatchers.Main) { modelProgress.value = progress }
                }
                modelProgress.value = ModelDownloadProgress(
                    modelFile.length(), modelFile.length(), stage = "ЗАПУСК НЕЙРОСЕТИ"
                )
                val engine = withContext(Dispatchers.IO) {
                    Engine(
                        EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.CPU(),
                            cacheDir = cacheDir.absolutePath
                        )
                    ).apply { initialize() }
                }
                val conversation = withContext(Dispatchers.IO) {
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(GlyphKnowledgeBase.systemInstruction),
                            samplerConfig = SamplerConfig(temperature = 0.25, topK = 24, topP = 0.9)
                        )
                    )
                }
                qwenEngine = engine
                qwenConversation = conversation
                modelProgress.value = ModelDownloadProgress(
                    modelFile.length(), modelFile.length(), stage = "GLYPH INTELLIGENCE ГОТОВ"
                )
                modelReady.value = true
            } catch (error: Exception) {
                modelError.value = error.localizedMessage ?: "Не удалось подготовить локальную нейросеть."
            }
        }
    }

    private fun generateWithQwen(
        prompt: String,
        onText: (String) -> Unit,
        onComplete: (String?) -> Unit
    ) {
        val conversation = qwenConversation
        if (conversation == null) {
            onComplete("Нейросеть ещё не готова. Повторите подготовку модели.")
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    conversation.sendMessageAsync(prompt).collect { message ->
                        val response = message.text
                        withContext(Dispatchers.Main) { onText(response) }
                    }
                }
                onComplete(null)
            } catch (error: Exception) {
                onComplete(error.localizedMessage ?: "Ошибка генерации.")
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
                    } catch (_: Exception) {
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
        } catch (_: Exception) {
            glyphReady = false
            glyphStatus.value = "GLYPH MATRIX НЕДОСТУПНА"
        }
    }

    private fun makeFrame(pixels: List<Boolean>) = IntArray(MATRIX_CELLS) { index ->
        if (pixels.getOrNull(index) == true) 2047 else 0
    }
    private fun showOnGlyph(pixels: List<Boolean>) {
        if (!glyphReady || glyphManager == null) {
            glyphStatus.value = "GLYPH MATRIX ПОКА НЕДОСТУПНА"
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
        } catch (_: Exception) {
            glyphStatus.value = "НЕ УДАЛОСЬ ОБНОВИТЬ GLYPH MATRIX"
        }
    }
    private fun turnOffGlyph() {
        try {
            glyphManager?.closeAppMatrix()
            glyphStatus.value = "ОТОБРАЖЕНИЕ GLYPH ВЫКЛЮЧЕНО"
        } catch (_: Exception) {
            glyphStatus.value = "НЕ УДАЛОСЬ ВЫКЛЮЧИТЬ GLYPH"
        }
    }
    override fun onDestroy() {
        try {
            glyphManager?.closeAppMatrix()
            glyphManager?.unInit()
        } catch (_: Exception) {}
        glyphReady = false
        glyphManager = null
        try { qwenConversation?.close() } catch (_: Exception) {}
        try { qwenEngine?.close() } catch (_: Exception) {}
        qwenConversation = null
        qwenEngine = null
        super.onDestroy()
    }
}

private data class HistoryEntry(val layerKey: Int, val pixels: List<Boolean>, val groups: List<List<Int>>)

private enum class EditorMode(val title: String) {
    DRAW("Рисунки"), ANIMATION("Анимации"), TOYS("Glyph Toys")
}

@Composable
private fun GlyphLabEditor(
    glyphStatus: String,
    onShowOnGlyph: (List<Boolean>) -> Unit,
    onUpdateGlyph: (List<Boolean>) -> Unit,
    onTurnOffGlyph: () -> Unit,
    onGenerate: (String, (String) -> Unit, (String?) -> Unit) -> Unit
) {
    val pixels = remember { mutableStateListOf<Boolean>().apply { repeat(MATRIX_CELLS) { add(false) } } }
    val toyPixels = remember { mutableStateListOf<Boolean>().apply { repeat(MATRIX_CELLS) { add(false) } } }
    val glyphVisible = remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(EditorMode.DRAW) }
    var prompt by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var aiResponse by remember { mutableStateOf("") }
    var aiError by remember { mutableStateOf<String?>(null) }
    var frameSpeed by remember { mutableFloatStateOf(1f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    val history = remember { mutableStateListOf<HistoryEntry>() }
    val redoHistory = remember { mutableStateListOf<HistoryEntry>() }
    val animationFrames = remember {
        mutableStateListOf<SnapshotStateList<Boolean>>(
            mutableStateListOf<Boolean>().apply { repeat(MATRIX_CELLS) { add(false) } }
        )
    }
    var selectedFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    val activePixels = when (mode) {
        EditorMode.DRAW -> pixels
        EditorMode.ANIMATION -> animationFrames[selectedFrame]
        EditorMode.TOYS -> toyPixels
    }
    val strokeGroups = remember { mutableStateMapOf<Int, List<List<Int>>>() }
    val layerKey = when (mode) {
        EditorMode.DRAW -> -1
        EditorMode.ANIMATION -> selectedFrame
        EditorMode.TOYS -> -2
    }
    var activeEraseGroup by remember { mutableStateOf<List<Int>?>(null) }

    LaunchedEffect(glyphVisible.value, mode, selectedFrame) {
        if (glyphVisible.value) {
            onUpdateGlyph(activePixels.toList())
            snapshotFlow { activePixels.toList() }.collect { onUpdateGlyph(it) }
        }
    }
    LaunchedEffect(mode) {
        if (mode != EditorMode.ANIMATION) isPlaying = false
    }
    LaunchedEffect(isPlaying, mode, frameSpeed, animationFrames.size) {
        if (isPlaying && mode == EditorMode.ANIMATION && animationFrames.size > 1) {
            while (true) {
                delay((500f / frameSpeed.coerceAtLeast(0.2f)).toLong())
                selectedFrame = (selectedFrame + 1) % animationFrames.size
            }
        }
    }
    fun layerPixels(key: Int): SnapshotStateList<Boolean> = when {
        key == -1 -> pixels
        key == -2 -> toyPixels
        else -> animationFrames[key.coerceIn(0, animationFrames.lastIndex)]
    }
    fun saveHistory(key: Int = layerKey) {
        history.add(HistoryEntry(key, layerPixels(key).toList(), strokeGroups[key].orEmpty().map { it.toList() }))
        if (history.size > 60) history.removeAt(0)
        redoHistory.clear()
    }
    fun restoreHistory(entry: HistoryEntry) {
        val target = layerPixels(entry.layerKey)
        entry.pixels.forEachIndexed { index, value -> target[index] = value }
        strokeGroups[entry.layerKey] = entry.groups.map { it.toList() }
    }
    fun beginStroke(index: Int): Boolean {
        saveHistory(layerKey)
        val erase = activePixels[index]
        activeEraseGroup = if (erase) strokeGroups[layerKey].orEmpty().lastOrNull { index in it } else null
        return erase
    }
    fun updateStroke(indices: List<Int>, erase: Boolean) {
        if (erase) {
            val target = activeEraseGroup ?: indices
            target.forEach { if (it in activePixels.indices) activePixels[it] = false }
        } else {
            indices.forEach { if (it in activePixels.indices) activePixels[it] = true }
        }
    }
    fun finishStroke(indices: List<Int>, erase: Boolean) {
        val groups = strokeGroups[layerKey].orEmpty().toMutableList()
        if (erase) {
            activeEraseGroup?.let { removed -> groups.removeAll { it == removed } }
        } else if (indices.isNotEmpty()) {
            groups.add(indices.distinct())
        }
        strokeGroups[layerKey] = groups
        activeEraseGroup = null
    }
    fun applyTap(index: Int) {
        val erase = beginStroke(index)
        updateStroke(listOf(index), erase)
        finishStroke(listOf(index), erase)
    }
    fun pixelIndex(position: Offset, width: Float): Int? {
        if (width <= 0f) return null
        val gridSide = width * GRID_SCALE
        val start = (width - gridSide) / 2f
        val x = position.x - start
        val y = position.y - start
        if (x < 0f || y < 0f || x >= gridSide || y >= gridSide) return null
        val cell = gridSide / MATRIX_SIZE
        val column = floor(x / cell).toInt()
        val row = floor(y / cell).toInt()
        if (column !in 0 until MATRIX_SIZE || row !in 0 until MATRIX_SIZE) return null
        val dx = x - gridSide / 2f
        val dy = y - gridSide / 2f
        if (dx * dx + dy * dy > (gridSide / 2f) * (gridSide / 2f)) return null
        return row * MATRIX_SIZE + column
    }
    val litCount = activePixels.count { it }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(42.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GLYPHLAB", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("MATRIX CREATOR", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                }
                Box(Modifier.border(1.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text("BETA 0.3.0", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    glyphVisible.value = !glyphVisible.value
                    if (glyphVisible.value) onShowOnGlyph(activePixels.toList()) else onTurnOffGlyph()
                }) {
                    Icon(if (glyphVisible.value) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Переключить вывод на Glyph Matrix", tint = if (glyphVisible.value) Accent else Muted)
                }
                Text("GLYPH MATRIX  ·  25 × 25", color = Muted, fontSize = 10.sp, letterSpacing = 1.3.sp)
                Row {
                    IconButton(enabled = history.isNotEmpty(), onClick = {
                        val entry = history.removeAt(history.lastIndex)
                        val target = layerPixels(entry.layerKey)
                        redoHistory.add(HistoryEntry(entry.layerKey, target.toList(), strokeGroups[entry.layerKey].orEmpty().map { it.toList() }))
                        restoreHistory(entry)
                    }) { Icon(Icons.Default.Undo, "Undo", tint = if (history.isNotEmpty()) Color.White else Muted) }
                    IconButton(enabled = redoHistory.isNotEmpty(), onClick = {
                        val entry = redoHistory.removeAt(redoHistory.lastIndex)
                        val target = layerPixels(entry.layerKey)
                        history.add(HistoryEntry(entry.layerKey, target.toList(), strokeGroups[entry.layerKey].orEmpty().map { it.toList() }))
                        restoreHistory(entry)
                    }) { Icon(Icons.Default.Redo, "Redo", tint = if (redoHistory.isNotEmpty()) Color.White else Muted) }
                }
            }
            MatrixPreview(
                activePixels,
                onTap = ::applyTap,
                onStrokeStart = ::beginStroke,
                onStrokeUpdate = ::updateStroke,
                onStrokeEnd = ::finishStroke,
                pixelIndex = ::pixelIndex
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$litCount / 489 PIXELS", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(glyphStatus, color = Muted, fontSize = 9.sp, textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(20.dp))
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EditorMode.values().toList()) { item ->
                    val selected = mode == item
                    Box(Modifier.background(if (selected) Accent else Panel, RoundedCornerShape(50)).clickable { mode = item }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text(item.title, color = if (selected) Color.White else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (mode == EditorMode.ANIMATION) {
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("КАДРЫ АНИМАЦИИ", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${animationFrames.size} КАДР.", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { isPlaying = !isPlaying },
                                enabled = animationFrames.size > 1,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                                    tint = if (animationFrames.size > 1) Accent else Muted
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(animationFrames.size) { index ->
                                val selected = selectedFrame == index
                                Column(
                                    Modifier.width(76.dp)
                                        .border(1.dp, if (selected) Accent else Color(0xFF3A3A3F), RoundedCornerShape(12.dp))
                                        .background(if (selected) Color(0xFF261416) else Color(0xFF101012), RoundedCornerShape(12.dp))
                                        .clickable { selectedFrame = index }
                                        .padding(9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(Modifier.size(42.dp).background(Color(0xFF080809), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                        Text(if (animationFrames[index].any { it }) "●" else "＋", color = if (animationFrames[index].any { it }) Accent else Muted, fontSize = 19.sp)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text("КАДР ${index + 1}", color = if (selected) Accent else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            item {
                                Column(
                                    Modifier.width(76.dp).height(83.dp)
                                        .border(1.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1D1113), RoundedCornerShape(12.dp))
                                        .clickable {
                                            animationFrames.add(mutableStateListOf<Boolean>().apply { repeat(MATRIX_CELLS) { add(false) } })
                                            selectedFrame = animationFrames.lastIndex
                                        }.padding(9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text("+", color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Light)
                                    Text("НОВЫЙ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Выбран кадр ${selectedFrame + 1}. Рисуй его в матрице выше. Новый кадр всегда пустой.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(when (mode) {
                        EditorMode.DRAW -> "ИНСТРУМЕНТЫ РИСУНКА"
                        EditorMode.ANIMATION -> "ПАРАМЕТРЫ АНИМАЦИИ"
                        EditorMode.TOYS -> "ПАРАМЕТРЫ GLYPH TOY"
                    }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(12.dp))
                    when (mode) {
                        EditorMode.DRAW -> {
                            Text("Прозрачность выбранных пикселей", color = Muted, fontSize = 11.sp)
                            Slider(opacity, { opacity = it }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                        }
                        EditorMode.ANIMATION, EditorMode.TOYS -> {
                            Text("Скорость: ${frameSpeed}×", color = Muted, fontSize = 11.sp)
                            Slider(frameSpeed, { frameSpeed = it }, valueRange = 0.2f..3f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                            Text("Прозрачность", color = Muted, fontSize = 11.sp)
                            Slider(opacity, { opacity = it }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                            if (mode == EditorMode.TOYS) {
                                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent), border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.65f))) {
                                    Text("Создать анимацию для долгого нажатия")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveHistory(layerKey); activePixels.indices.forEach { activePixels[it] = false }; strokeGroups[layerKey] = emptyList() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Очистить")
                        }
                        Button(onClick = {}, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text(if (mode == EditorMode.TOYS) "Добавить Toy" else "Экспорт")
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101012)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).background(Accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("GLYPH INTELLIGENCE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text("AI ASSISTANT", fontSize = 9.sp, color = Muted, letterSpacing = 1.5.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("PREVIEW", color = Accent, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (generating) {
                        Column(Modifier.fillMaxWidth().background(Color(0xFF171719), RoundedCornerShape(14.dp)).padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(9.dp))
                                Text("QWEN3 ОБРАБАТЫВАЕТ ЗАПРОС", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if (aiResponse.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(aiResponse, color = Color(0xFFEDEDEF), fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    } else if (aiError != null) {
                        Text(aiError ?: "", Modifier.fillMaxWidth().background(Color(0xFF1D1718), RoundedCornerShape(14.dp)).padding(13.dp), color = Color(0xFFFF858A), fontSize = 12.sp, lineHeight = 18.sp)
                    } else if (aiResponse.isNotBlank()) {
                        Text(aiResponse, Modifier.fillMaxWidth().background(Color(0xFF171719), RoundedCornerShape(14.dp)).padding(13.dp), color = Color(0xFFEDEDEF), fontSize = 12.sp, lineHeight = 18.sp)
                    } else {
                        Text("Опиши, что хочешь создать для Glyph Matrix. Glyph Intelligence сначала подготовит план и дождётся подтверждения.", color = Muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Например: нарисуй маленькое сердце…", fontSize = 12.sp, color = Muted) },
                        enabled = !generating, minLines = 2, maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF38383D), cursorColor = Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        if (!generating && prompt.isNotBlank()) {
                            aiResponse = ""
                            aiError = null
                            generating = true
                            onGenerate(
                                prompt.trim(),
                                { response -> aiResponse = response },
                                { error -> generating = false; aiError = error }
                            )
                        }
                    }, enabled = !generating && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Accent.copy(alpha = 0.45f))) {
                        if (generating) {
                            CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(9.dp)); Text("ГЕНЕРАЦИЯ…", letterSpacing = 1.sp, fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp)); Text("СОЗДАТЬ", letterSpacing = 1.5.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun MatrixPreview(
    pixels: SnapshotStateList<Boolean>,
    onTap: (Int) -> Unit,
    onStrokeStart: (Int) -> Boolean,
    onStrokeUpdate: (List<Int>, Boolean) -> Unit,
    onStrokeEnd: (List<Int>, Boolean) -> Unit,
    pixelIndex: (Offset, Float) -> Int?
) {
    val currentPixels by rememberUpdatedState(pixels)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnStrokeStart by rememberUpdatedState(onStrokeStart)
    val currentOnStrokeUpdate by rememberUpdatedState(onStrokeUpdate)
    val currentOnStrokeEnd by rememberUpdatedState(onStrokeEnd)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
        .pointerInput(Unit) {
            detectTapGestures { pos ->
                pixelIndex(pos, size.width.toFloat())?.let(currentOnTap)
            }
        }
        .pointerInput(Unit) {
            var eraseMode = false
            var lastIndex = -1
            val visited = linkedSetOf<Int>()
            fun addLine(from: Int, to: Int) {
                val x0 = from % MATRIX_SIZE
                val y0 = from / MATRIX_SIZE
                val x1 = to % MATRIX_SIZE
                val y1 = to / MATRIX_SIZE
                val dx = kotlin.math.abs(x1 - x0)
                val dy = kotlin.math.abs(y1 - y0)
                val steps = maxOf(dx, dy, 1)
                for (step in 0..steps) {
                    val x = (x0 + (x1 - x0) * (step.toFloat() / steps)).toInt()
                    val y = (y0 + (y1 - y0) * (step.toFloat() / steps)).toInt()
                    visited.add(y * MATRIX_SIZE + x)
                }
            }
            detectDragGestures(onDragStart = { pos ->
                visited.clear()
                lastIndex = pixelIndex(pos, size.width.toFloat()) ?: -1
                if (lastIndex >= 0) {
                    eraseMode = currentOnStrokeStart(lastIndex)
                    visited.add(lastIndex)
                    currentOnStrokeUpdate(visited.toList(), eraseMode)
                }
            }, onDrag = { change, _ ->
                val index = pixelIndex(change.position, size.width.toFloat())
                if (index != null && lastIndex >= 0 && index != lastIndex) {
                    addLine(lastIndex, index)
                    lastIndex = index
                    onStrokeUpdate(visited.toList(), eraseMode)
                }
                change.consume()
            }, onDragEnd = {
                if (visited.isNotEmpty()) currentOnStrokeEnd(visited.toList(), eraseMode)
            }, onDragCancel = {
                if (visited.isNotEmpty()) onStrokeEnd(visited.toList(), eraseMode)
            })
        }
    ) {
        val center = size.width / 2f
        val radius = center - 1.5.dp.toPx()
        val gridSide = size.width * GRID_SCALE
        val start = (size.width - gridSide) / 2f
        val cell = gridSide / MATRIX_SIZE
        val pixelSize = cell * 0.72f
        drawCircle(Color(0xFF18181B), radius, Offset(center, center))
        drawCircle(Color(0xFF44444A), radius, Offset(center, center), style = Stroke(2.dp.toPx()))
        for (row in 0 until MATRIX_SIZE) for (column in 0 until MATRIX_SIZE) {
            val dx = column + 0.5f - MATRIX_SIZE / 2f
            val dy = row + 0.5f - MATRIX_SIZE / 2f
            if (dx * dx + dy * dy <= 12.15f * 12.15f) {
                val index = row * MATRIX_SIZE + column
                val x = start + (column + 0.5f) * cell
                val y = start + (row + 0.5f) * cell
                drawRect(if (pixels[index]) Color(0xFFFFF4F4) else Color(0xFF080809), Offset(x - pixelSize / 2f, y - pixelSize / 2f), Size(pixelSize, pixelSize))
            }
        }
    }
}

@Composable
private fun GenerationAnimation(letter: Char, progress: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.Start) {
        Text("Г Е Н Е Р А Ц И Я", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        PixelLetter(letter)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent, trackColor = Color(0xFF303034))
        Spacer(Modifier.height(7.dp))
        Text("ОБРАБОТКА ЗАПРОСА  $progress%", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun PixelLetter(character: Char) {
    val glyph = when (character) {
        'A', 'А' -> listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001")
        'B', 'В' -> listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110")
        'C', 'С' -> listOf("01111", "10000", "10000", "10000", "10000", "10000", "01111")
        'E', 'Е', 'Ё' -> listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111")
        'F' -> listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000")
        'H', 'Н' -> listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001")
        'I', 'І' -> listOf("11111", "00100", "00100", "00100", "00100", "00100", "11111")
        'K', 'К' -> listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001")
        'L', 'Л' -> listOf("00111", "00100", "00100", "00100", "10100", "10100", "11000")
        'M', 'М' -> listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001")
        'N', 'И' -> listOf("10001", "11001", "10101", "10011", "10001", "10001", "10001")
        'O', 'О' -> listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110")
        'P', 'Р' -> listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000")
        'S' -> listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110")
        'T', 'Т' -> listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100")
        'X', 'Х', 'Ж' -> listOf("10001", "10001", "01010", "00100", "01010", "10001", "10001")
        'Y', 'У' -> listOf("10001", "10001", "01010", "00100", "00100", "00100", "00100")
        'З' -> listOf("01110", "00001", "00001", "00110", "00001", "00001", "01110")
        'Ч' -> listOf("10001", "10001", "10001", "01111", "00001", "00001", "00001")
        else -> listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100")
    }
    Canvas(Modifier.padding(start = 12.dp).size(width = 58.dp, height = 72.dp)) {
            val cell = size.minDimension / 8f
            val pixel = cell * 0.82f
            glyph.forEachIndexed { row, bits ->
                bits.forEachIndexed { column, bit ->
                    if (bit == '1') {
                        val x = (column + 1.5f) * cell - pixel / 2f
                        val y = (row + 0.5f) * cell - pixel / 2f
                        drawRect(
                            color = Color.White.copy(alpha = 0.035f),
                            topLeft = Offset(x - pixel * 1.35f, y - pixel * 1.35f),
                            size = Size(pixel * 3.7f, pixel * 3.7f)
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.09f),
                            topLeft = Offset(x - pixel * 0.85f, y - pixel * 0.85f),
                            size = Size(pixel * 2.7f, pixel * 2.7f)
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(x, y),
                            size = Size(pixel, pixel)
                        )
                    }
                }
            }
    }
}
