package com.hackidim.cepindir

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SharePanel = Color(0xFF191624)
private val SharePurple = Color(0xFF9B72FF)
private val ShareMuted = Color(0xFFAAA6BC)

class ShareDownloadActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf("")
    private var isAnalyzing by mutableStateOf(true)
    private var isStarting by mutableStateOf(false)
    private var analysisReady by mutableStateOf(false)
    private var status by mutableStateOf("Bağlantı kontrol ediliyor…")
    private var mediaTitle by mutableStateOf("")
    private var platformName by mutableStateOf("")
    private var heights by mutableStateOf(emptyList<Int>())
    private var selectedHeight by mutableIntStateOf(0)
    private var audioOnly by mutableStateOf(false)
    private var includeSubtitles by mutableStateOf(false)
    private var includeThumbnail by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.62f)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        sharedUrl = readUrl(intent)
        setContent {
            ShareSheet(
                isAnalyzing, isStarting, analysisReady, status, mediaTitle, platformName,
                heights, selectedHeight, audioOnly, includeSubtitles, includeThumbnail, showSettings,
                onSettings = { showSettings = true },
                onQuickDownload = { startDownload(false, 0, false, false) },
                onCancel = { finish() },
                onRetry = ::analyze,
                onHeightChange = { selectedHeight = it },
                onAudioChange = { audioOnly = it },
                onSubtitlesChange = { includeSubtitles = it },
                onThumbnailChange = { includeThumbnail = it },
                onDownload = { startDownload(audioOnly, selectedHeight, includeSubtitles, includeThumbnail) }
            )
        }
        analyze()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = readUrl(intent)
        showSettings = false
        analyze()
    }

    private fun readUrl(source: Intent?): String {
        if (source == null) return ""
        val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        extractUrl(text)?.let { return it }
        val stream = runCatching { source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty() }.getOrDefault("")
        return extractUrl(stream) ?: extractUrl(source.dataString.orEmpty()).orEmpty()
    }

    private fun extractUrl(raw: String): String? {
        val candidate = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.value?.trimEnd('.', ',', ')', ']', '!') ?: raw.trim()
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun analyze() {
        if (sharedUrl.isBlank()) {
            isAnalyzing = false
            status = "Paylaşılan bağlantı bulunamadı."
            analysisReady = false
            return
        }
        if (isStarting) return
        isAnalyzing = true
        analysisReady = false
        status = "Video bilgileri alınıyor…"
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)
                    FFmpeg.init(applicationContext)
                    val request = YoutubeDLRequest(sharedUrl)
                    request.addOption("--no-playlist")
                    request.addOption("--no-warnings")
                    YoutubeDL.getInfo(request)
                }
                mediaTitle = info.title?.takeIf { it.isNotBlank() } ?: "Paylaşılan medya"
                platformName = info.extractorKey ?: info.extractor ?: "Medya kaynağı"
                heights = info.formats.orEmpty()
                    .filter { !it.vcodec.isNullOrBlank() && it.vcodec != "none" && it.height > 0 }
                    .map { it.height }.distinct().sortedDescending()
                selectedHeight = 0
                analysisReady = true
                status = "İndirmeye hazır"
            } catch (e: Exception) {
                val detail = e.message?.lineSequence()?.firstOrNull()?.take(160)
                    ?: "Bu bağlantı şu anda çözümlenemedi."
                status = "Bağlantı alınamadı: $detail"
                analysisReady = false
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun startDownload(audio: Boolean, height: Int, subtitles: Boolean, thumbnail: Boolean) {
        if (!analysisReady || isStarting) return
        isStarting = true
        val downloadIntent = Intent(this, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, sharedUrl)
            putExtra(MediaDownloadService.EXTRA_HEIGHT, height)
            putExtra(MediaDownloadService.EXTRA_AUDIO_ONLY, audio)
            putExtra(MediaDownloadService.EXTRA_SUBTITLES, subtitles)
            putExtra(MediaDownloadService.EXTRA_THUMBNAIL, thumbnail)
        }
        try {
            ContextCompat.startForegroundService(this, downloadIntent)
            Toast.makeText(this, "İndirme arka planda başladı.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            isStarting = false
            status = "İndirme başlatılamadı: ${e.message ?: "Bilinmeyen hata"}"
        }
    }
}

@Composable
private fun ShareSheet(
    isAnalyzing: Boolean,
    isStarting: Boolean,
    analysisReady: Boolean,
    status: String,
    mediaTitle: String,
    platformName: String,
    heights: List<Int>,
    selectedHeight: Int,
    audioOnly: Boolean,
    includeSubtitles: Boolean,
    includeThumbnail: Boolean,
    showSettings: Boolean,
    onSettings: () -> Unit,
    onQuickDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onHeightChange: (Int) -> Unit,
    onAudioChange: (Boolean) -> Unit,
    onSubtitlesChange: (Boolean) -> Unit,
    onThumbnailChange: (Boolean) -> Unit,
    onDownload: () -> Unit
) {
    var qualityMenu by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = SharePurple, background = Color(0xFF090912), surface = SharePanel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 740.dp)
                .background(SharePanel, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(46.dp).height(5.dp).background(Color(0xFF55515E), RoundedCornerShape(8.dp)))
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cepİndir", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (showSettings) "İndirmeden önce yapılandırın" else "Paylaşılan bağlantı hazır",
                        fontSize = 13.sp, color = ShareMuted
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = ShareMuted)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(mediaTitle.ifBlank { "Medya bağlantısı" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (platformName.isNotBlank()) Text(platformName, color = ShareMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Text(status, color = if (analysisReady) Color(0xFFBDA4FF) else ShareMuted, fontSize = 12.sp)
            if (isAnalyzing) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = SharePurple)
            } else if (!analysisReady) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Tekrar dene") }
            } else if (!showSettings) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onQuickDownload,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp), enabled = !isStarting
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Hızlı indir · En iyi kalite", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSettings,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("İndirmeyi ayarla")
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Text("İndirme türü", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !audioOnly, onClick = { onAudioChange(false) }, label = { Text("Video") })
                    FilterChip(selected = audioOnly, onClick = { onAudioChange(true) }, label = { Text("Ses · MP3") })
                }
                if (!audioOnly) {
                    Spacer(Modifier.height(13.dp))
                    Text("Video kalitesi", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Box {
                        OutlinedButton(onClick = { qualityMenu = true }) {
                            Text(if (selectedHeight == 0) "En iyi kalite" else "${selectedHeight}p")
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            DropdownMenuItem(text = { Text("En iyi kalite") }, onClick = {
                                onHeightChange(0); qualityMenu = false
                            })
                            heights.forEach { h ->
                                DropdownMenuItem(text = { Text("${h}p") }, onClick = {
                                    onHeightChange(h); qualityMenu = false
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text("Ek ayarlar", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = includeSubtitles, onClick = { onSubtitlesChange(!includeSubtitles) },
                        label = { Text("Altyazı indir") })
                    FilterChip(selected = includeThumbnail, onClick = { onThumbnailChange(!includeThumbnail) },
                        label = { Text("Küçük resmi kaydet") })
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)) { Text("İptal et") }
                    Button(onClick = onDownload, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp), enabled = !isStarting) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isStarting) "Başlatılıyor" else "İndir")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
