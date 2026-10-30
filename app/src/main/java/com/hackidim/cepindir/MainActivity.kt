/*
 * Cepİndir Android media downloader.
 * Modified 30 October 2026.
 * Distributed under GPL-3.0-only; see LICENSE.
 */
package com.hackidim.cepindir

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

private val Ink = Color(0xFF090912)
private val Panel = Color(0xFF171525)
private val Purple = Color(0xFF9B72FF)
private val Muted = Color(0xFFAAA6BC)
private val Gold = Color(0xFFFFD58A)

class MainActivity : ComponentActivity() {
    private var sharedLink by mutableStateOf("")
    private var isAnalyzing by mutableStateOf(false)
    private var isDownloading by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var status by mutableStateOf("Bağlantıyı yapıştır veya başka bir uygulamadan Cepİndir'e paylaş.")
    private var mediaTitle by mutableStateOf("")
    private var platformName by mutableStateOf("")
    private var resolutions by mutableStateOf(emptyList<Int>())
    private var selectedHeight by mutableIntStateOf(0)
    private var analysisReady by mutableStateOf(false)
    private var audioOnly by mutableStateOf(false)
    private var imagePost by mutableStateOf(false)
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MediaDownloadService.ACTION_EVENT) return
            val type = intent.getStringExtra(MediaDownloadService.EXTRA_TYPE).orEmpty()
            status = intent.getStringExtra(MediaDownloadService.EXTRA_MESSAGE).orEmpty()
            progress = intent.getFloatExtra(MediaDownloadService.EXTRA_PROGRESS, progress)
            when (type) {
                "complete", "error", "stopped" -> isDownloading = false
                "progress" -> isDownloading = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedLink = readSharedLink(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        }
        setContent {
            CepIndirScreen(
                initial = sharedLink,
                isAnalyzing = isAnalyzing,
                isDownloading = isDownloading,
                progress = progress,
                status = status,
                mediaTitle = mediaTitle,
                platformName = platformName,
                resolutions = resolutions,
                selectedHeight = selectedHeight,
                audioOnly = audioOnly,
                imagePost = imagePost,
                analysisReady = analysisReady,
                onAnalyze = ::analyzeLink,
                onDownload = ::beginDownload,
                onPaste = ::pasteFromClipboard,
                onOpenDownloads = ::openDownloads,
                onHeightChange = { selectedHeight = it },
                onAudioOnlyChange = { audioOnly = it },
                onUpdateEngine = ::updateEngine
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, downloadReceiver, IntentFilter(MediaDownloadService.ACTION_EVENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(downloadReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedLink = readSharedLink(intent)
        analysisReady = false
        mediaTitle = ""
        resolutions = emptyList()
    }

    private fun readSharedLink(source: Intent?): String {
        if (source == null) return ""
        val text = source.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) return text.trim()
        return runCatching {
            source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun extractUrl(raw: String): String? {
        val candidate = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.value?.trimEnd('.', ',', ')', ']', '!', '?') ?: raw.trim()
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun analyzeLink(raw: String) {
        val url = extractUrl(raw)
        if (url == null) {
            status = "Geçerli bir http/https bağlantısı gir."
            analysisReady = false
            return
        }
        if (isAnalyzing || isDownloading) return
        isAnalyzing = true
        analysisReady = false
        mediaTitle = ""
        platformName = ""
        resolutions = emptyList()
        imagePost = false
        audioOnly = false
        status = "Bağlantı analiz ediliyor…"
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)
                    FFmpeg.init(applicationContext)
                    val request = YoutubeDLRequest(url)
                    request.addOption("--no-playlist")
                    request.addOption("--no-warnings")
                    request.addOption("--cache-dir", File(cacheDir, "yt-dlp-cache").absolutePath)
                    request.addOption("--extractor-retries", "1")
                    request.addOption("--socket-timeout", "12")
                    YoutubeDL.getInfo(request)
                }
                mediaTitle = info.title?.takeIf { it.isNotBlank() } ?: "Başlık alınamadı"
                platformName = info.extractorKey ?: info.extractor ?: "Medya kaynağı"
                val availableFormats = info.formats.orEmpty()
                val hasVideoStream = availableFormats.any {
                    !it.vcodec.isNullOrBlank() && it.vcodec != "none"
                }
                val hasImageCandidates = info.thumbnails.orEmpty().isNotEmpty()
                imagePost = info.ext?.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") ||
                    (!hasVideoStream && hasImageCandidates)
                resolutions = info.formats.orEmpty()
                    .filter { !it.vcodec.isNullOrBlank() && it.vcodec != "none" && it.height > 0 }
                    .map { it.height }.distinct().sortedDescending()
                selectedHeight = 0
                analysisReady = true
                status = if (resolutions.isEmpty()) {
                    "Bağlantı çözümlendi. Otomatik kaliteyi deneyebilirsin."
                } else {
                    "Bağlantı hazır. İndirme kalitesini seçebilirsin."
                }
            } catch (e: Exception) {
                val reason = e.message?.lineSequence()?.firstOrNull()?.take(160)
                    ?: "Kaynak desteklenmiyor veya erişim engellendi."
                status = "Bağlantı çözümlenemedi: $reason"
                analysisReady = false
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun beginDownload(raw: String) {
        val url = extractUrl(raw)
        if (url == null) {
            status = "Önce geçerli bir bağlantı gir."
            return
        }
        if (!analysisReady) {
            status = "Önce bağlantıyı analiz et."
            return
        }
        if (isDownloading) return
        val serviceIntent = Intent(this, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, url)
            putExtra(MediaDownloadService.EXTRA_HEIGHT, selectedHeight)
            putExtra(MediaDownloadService.EXTRA_AUDIO_ONLY, audioOnly)
            putExtra(MediaDownloadService.EXTRA_IMAGE, imagePost)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            isDownloading = true
            progress = 0f
            status = "İndirme başlatılıyor…"
        } catch (e: Exception) {
            status = "İndirme servisi başlatılamadı: ${e.message ?: "Bilinmeyen hata"}"
            isDownloading = false
        }
    }

    private fun pasteFromClipboard(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (value.isBlank()) Toast.makeText(this, "Panoda bağlantı bulunamadı.", Toast.LENGTH_SHORT).show()
        return value
    }

    private fun openDownloads() {
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure { Toast.makeText(this, "İndirmeler ekranı açılamadı.", Toast.LENGTH_SHORT).show() }
    }

    private fun updateEngine() {
        if (isAnalyzing || isDownloading) return
        isAnalyzing = true
        status = "İndirme motoru güncelleniyor…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    YoutubeDL.init(applicationContext)
                    YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                }
                status = if (result == YoutubeDL.UpdateStatus.DONE) {
                    "İndirme motoru güncellendi."
                } else {
                    "İndirme motoru zaten güncel."
                }
            } catch (e: Exception) {
                status = "Motor güncellenemedi: ${e.message ?: "Bağlantıyı kontrol et."}"
            } finally {
                isAnalyzing = false
            }
        }
    }
}

@Composable
private fun CepIndirScreen(
    initial: String,
    isAnalyzing: Boolean,
    isDownloading: Boolean,
    progress: Float,
    status: String,
    mediaTitle: String,
    platformName: String,
    resolutions: List<Int>,
    selectedHeight: Int,
    audioOnly: Boolean,
    imagePost: Boolean,
    analysisReady: Boolean,
    onAnalyze: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPaste: () -> String,
    onOpenDownloads: () -> Unit,
    onHeightChange: (Int) -> Unit,
    onAudioOnlyChange: (Boolean) -> Unit,
    onUpdateEngine: () -> Unit
) {
    var link by remember { mutableStateOf(initial) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var showLegalInfo by remember { mutableStateOf(false) }
    LaunchedEffect(initial) { if (initial.isNotBlank()) link = initial }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Purple, background = Ink, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211638), Ink)))) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cepİndir", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Sosyal medya bağlantılarından video ve ses indir", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "YouTube • Instagram • Facebook • TikTok • X • Reddit • Pinterest ve daha fazlası",
                    color = Gold, fontSize = 11.sp
                )
                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = Purple)
                            Spacer(Modifier.width(8.dp))
                            Text("Bağlantı", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { link = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Temizle", tint = Muted)
                            }
                        }
                        OutlinedTextField(
                            value = link, onValueChange = { link = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Sosyal medya gönderi bağlantısını yapıştır") },
                            minLines = 3, shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { onPaste().let { if (it.isNotBlank()) link = it } },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Panodan bağlantı yapıştır")
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onAnalyze(link) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(15.dp),
                            enabled = link.isNotBlank() && !isAnalyzing && !isDownloading
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(10.dp))
                            } else {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Bağlantıyı analiz et", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (analysisReady) {
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Medya bulundu", color = Purple, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text(mediaTitle, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(platformName, color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("İndirme türü", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            if (imagePost) {
                                Text("Fotoğraf gönderisi algılandı. Görsel(ler) orijinal dosya olarak indirilecek.", color = Gold, fontSize = 12.sp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !audioOnly, onClick = { onAudioOnlyChange(false) }, label = { Text("Video") })
                                    FilterChip(selected = audioOnly, onClick = { onAudioOnlyChange(true) }, label = { Text("MP3 ses") })
                                }
                            }
                            if (!audioOnly && !imagePost) {
                                Spacer(Modifier.height(12.dp))
                                Text("Video kalitesi", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(7.dp))
                                Box {
                                    OutlinedButton(onClick = { qualityMenuExpanded = true }) {
                                        Text(if (selectedHeight == 0) "En iyi kalite (otomatik)" else "${selectedHeight}p")
                                    }
                                    DropdownMenu(expanded = qualityMenuExpanded,
                                        onDismissRequest = { qualityMenuExpanded = false }) {
                                        DropdownMenuItem(text = { Text("En iyi kalite (otomatik)") }, onClick = {
                                            onHeightChange(0); qualityMenuExpanded = false
                                        })
                                        resolutions.forEach { height ->
                                            DropdownMenuItem(text = { Text("${height}p") }, onClick = {
                                                onHeightChange(height); qualityMenuExpanded = false
                                            })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { onDownload(link) },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp), enabled = !isDownloading && !isAnalyzing) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (imagePost) "Fotoğraf(ları) indir" else "İndirmeyi başlat", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF11101B)),
                    shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("İndirme durumu", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(status, color = Muted, fontSize = 12.sp)
                        if (isDownloading) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { (progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(), color = Purple
                            )
                            Text("${progress.toInt()}%", color = Purple, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("İndirmelerimi aç")
                }
                TextButton(onClick = onUpdateEngine, enabled = !isAnalyzing && !isDownloading) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Muted)
                    Spacer(Modifier.width(6.dp))
                    Text("İndirme motorunu güncelle", color = Muted)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Yalnızca erişebildiğin ve indirme hakkına sahip olduğun içerikleri indir. Özel hesaplar, giriş gerektiren içerikler ve DRM koruması desteklenmez. Platform değişiklikleri nedeniyle bazı bağlantılar geçici olarak çalışmayabilir.",
                    color = Muted, fontSize = 11.sp
                )
                TextButton(onClick = { showLegalInfo = true }) {
                    Text("Hakkında ve açık kaynak lisansları", color = Muted)
                }
                if (showLegalInfo) {
                    AlertDialog(
                        onDismissRequest = { showLegalInfo = false },
                        title = { Text("Cepİndir • Açık kaynak") },
                        text = {
                            Text(
                                "Cepİndir'in kaynak kodu GPL-3.0 lisansı koşullarıyla yayımlanır. " +
                                "Kaynak kod: github.com/Hackidim/CepIndir. " +
                                "Üçüncü taraf bileşenler ve lisansları THIRD_PARTY_NOTICES.md dosyasında açıklanır. " +
                                "Bu yazılım, yasaların izin verdiği ölçüde, hiçbir açık veya zımni garanti olmaksızın olduğu gibi sunulur."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showLegalInfo = false }) { Text("Kapat") }
                        }
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
