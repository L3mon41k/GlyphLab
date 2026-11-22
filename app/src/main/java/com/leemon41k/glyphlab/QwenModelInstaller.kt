package com.leemon41k.glyphlab

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal const val QWEN_MODEL_FILE = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
private const val MODEL_URL =
    "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/$QWEN_MODEL_FILE"
private const val MODEL_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
private const val MIN_FREE_BYTES = 450L * 1024L * 1024L

internal data class ModelDownloadProgress(
    val receivedBytes: Long = 0,
    val totalBytes: Long = -1,
    val bytesPerSecond: Long = 0,
    val remainingSeconds: Long = -1,
    val stage: String = "ПРОВЕРКА МОДЕЛИ"
) {
    val fraction: Float
        get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

internal object QwenModelInstaller {
    suspend fun ensureInstalled(
        context: Context,
        onProgress: suspend (ModelDownloadProgress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destination = File(context.filesDir, QWEN_MODEL_FILE)
        onProgress(ModelDownloadProgress(stage = "ПРОВЕРКА УСТАНОВЛЕННОЙ МОДЕЛИ"))
        if (destination.isFile && sha256(destination, onProgress) == MODEL_SHA256) {
            onProgress(ModelDownloadProgress(destination.length(), destination.length(), stage = "МОДЕЛЬ УЖЕ УСТАНОВЛЕНА"))
            return@withContext destination
        }
        if (destination.exists()) destination.delete()

        val usable = context.filesDir.usableSpace
        if (usable < MIN_FREE_BYTES) {
            throw IllegalStateException("Недостаточно свободного места. Освободите не менее 450 МБ и повторите попытку.")
        }

        val partial = File(context.filesDir, "$QWEN_MODEL_FILE.part")
        var attempt = 0
        while (attempt < 3) {
            attempt++
            val offset = partial.length()
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GlyphLab/0.3.0")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }

            try {
                val response = connection.responseCode
                if (response == 416) {
                    partial.delete()
                    continue
                }
                if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("Сервер загрузки ответил: HTTP $response")
                }

                val append = response == HttpURLConnection.HTTP_PARTIAL && offset > 0
                if (!append) partial.delete()
                val startBytes = if (append) offset else 0L
                val contentRange = connection.getHeaderField("Content-Range")
                val total = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(startBytes)
                    ?: -1L

                var received = startBytes
                var lastUpdate = System.nanoTime()
                var lastBytes = received
                var speed = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            val now = System.nanoTime()
                            if (now - lastUpdate >= 500_000_000L) {
                                val elapsed = (now - lastUpdate) / 1_000_000_000.0
                                speed = ((received - lastBytes) / elapsed).toLong().coerceAtLeast(0)
                                val remaining = if (total > received && speed > 0) (total - received) / speed else -1L
                                onProgress(ModelDownloadProgress(received, total, speed, remaining, "ЗАГРУЗКА QWEN3-0.6B"))
                                lastUpdate = now
                                lastBytes = received
                            }
                        }
                        output.fd.sync()
                    }
                }
                onProgress(ModelDownloadProgress(received, total, speed, 0, "ПРОВЕРКА ЦЕЛОСТНОСТИ"))
                val actualHash = sha256(partial, onProgress)
                if (actualHash != MODEL_SHA256) {
                    partial.delete()
                    throw IllegalStateException("Контрольная сумма модели не совпала. Файл удалён; попробуйте загрузить ещё раз.")
                }
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                onProgress(ModelDownloadProgress(destination.length(), destination.length(), stage = "МОДЕЛЬ ПРОВЕРЕНА"))
                return@withContext destination
            } catch (error: Exception) {
                if (attempt >= 3) throw error
                if (error is IllegalStateException && error.message?.startsWith("Сервер загрузки") == true) throw error
                // Keep the partial file so the next attempt can resume with HTTP Range.
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Не удалось загрузить модель. Проверьте интернет и повторите попытку.")
    }

    private suspend fun sha256(file: File, onProgress: suspend (ModelDownloadProgress) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var read = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
                onProgress(ModelDownloadProgress(read, total, stage = "ПРОВЕРКА ЦЕЛОСТНОСТИ"))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal fun formatModelBytes(value: Long): String {
    if (value < 0) return "—"
    val mb = value / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f МБ", mb)
}

internal fun formatModelTime(seconds: Long): String {
    if (seconds < 0) return "Рассчитываем…"
    if (seconds < 60) return "около $seconds сек."
    return "около ${seconds / 60} мин. ${seconds % 60} сек."
}

@Composable
internal fun ModelSetupScreen(
    progress: ModelDownloadProgress,
    error: String?,
    onRetry: () -> Unit
) {
    val accent = Color(0xFFFF3038)
    val muted = Color(0xFF929298)
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050506)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("GLYPHLAB", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("FIRST START", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(104.dp).background(Color(0xFF171719), RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("G", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(accent, RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(28.dp))
            Text("GLYPH INTELLIGENCE", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            Text("ПОДГОТОВКА ЛОКАЛЬНОЙ НЕЙРОСЕТИ", color = muted, fontSize = 10.sp, letterSpacing = 1.2.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(42.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(progress.stage, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Spacer(Modifier.weight(1f))
                if (progress.totalBytes > 0 && progress.stage.startsWith("ЗАГРУЗКА")) {
                    Text("${(progress.fraction * 100).toInt()}%", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (progress.stage.startsWith("ЗАГРУЗКА") && progress.totalBytes <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = accent, trackColor = Color(0xFF303034))
            } else {
                LinearProgressIndicator(
                    progress = { if (progress.totalBytes > 0) progress.fraction else 0f },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = accent,
                    trackColor = Color(0xFF303034)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatModelBytes(progress.receivedBytes)} / ${formatModelBytes(progress.totalBytes)}", color = muted, fontSize = 10.sp)
                Text(if (progress.bytesPerSecond > 0) "${formatModelBytes(progress.bytesPerSecond)}/с" else "", color = muted, fontSize = 10.sp)
            }
            if (progress.stage.startsWith("ЗАГРУЗКА")) {
                Spacer(Modifier.height(6.dp))
                Text("Осталось: ${formatModelTime(progress.remainingSeconds)}", color = muted, fontSize = 11.sp)
            }
            if (error != null) {
                Spacer(Modifier.height(22.dp))
                Text(error, color = Color(0xFFFF777D), fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text("ПОВТОРИТЬ ЗАГРУЗКУ")
                }
            } else {
                Spacer(Modifier.height(28.dp))
                Text("Редактор откроется после загрузки и проверки модели.", color = muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Text("QWEN3 · 0.6B · INT4 · OFFLINE", color = Color(0xFF66666C), fontSize = 9.sp, letterSpacing = 1.1.sp)
        }
    }
}
