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
import java.io.File
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
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
    private var selectedFps by mutableIntStateOf(0)
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
                selectedFps = selectedFps,
                audioOnly = audioOnly,
                imagePost = imagePost,
                analysisReady = analysisReady,
                onAnalyze = ::analyzeLink,
                onDownload = ::beginDownload,
                onPaste = ::pasteFromClipboard,
                onOpenDownloads = ::openDownloads,
                onHeightChange = { height, fps -> selectedHeight = height; selectedFps = fps },
                onAudioOnlyChange = { audioOnly = it },
                onUpdateEngine = ::updateEngine,
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
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

        // A direct .jpg/.png/.webp URL is already a downloadable image.
        if (DirectImageDownloader.isDirectImageUrl(url)) {
            mediaTitle = "Doğrudan görsel bağlantısı"
            platformName = Uri.parse(url).host ?: "Görsel dosyası"
            resolutions = emptyList()
            selectedHeight = 0
            audioOnly = false
            imagePost = true
            analysisReady = true
            status = "Görsel bağlantısı hazır. İndirebilirsin."
            return
        }

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
                    request.addOption("--extractor-retries", "2")
                    request.addOption("--socket-timeout", "20")
                    YoutubeDL.getInfo(request)
                }
                val availableFormats = info.formats.orEmpty()
                val hasVideoStream = availableFormats.any {
                    !it.vcodec.isNullOrBlank() && it.vcodec != "none"
                }

                // yt-dlp may reject photo-only Instagram posts. In that case, inspect
                // the public post page with the native Instagram photo extractor.
                val instagramPhotos = if (InstagramPhotoDownloader.isPostUrl(url) && !hasVideoStream) {
                    runCatching {
                        withContext(Dispatchers.IO) { InstagramPhotoDownloader.photoCount(url) }
                    }.getOrDefault(0)
                } else 0

                mediaTitle = if (instagramPhotos > 0) {
                    "Instagram gönderisi (" + instagramPhotos + " fotoğraf)"
                } else info.title?.takeIf { it.isNotBlank() } ?: "Başlık alınamadı"
                platformName = if (instagramPhotos > 0) {
                    "Instagram"
                } else info.extractorKey ?: info.extractor ?: "Medya kaynağı"

                val hasImageCandidates = info.thumbnails.orEmpty().isNotEmpty()
                imagePost = instagramPhotos > 0 ||
                    info.ext?.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") ||
                    (!hasVideoStream && hasImageCandidates)
                resolutions = availableFormats
                    .filter { !it.vcodec.isNullOrBlank() && it.vcodec != "none" && it.height > 0 }
                    .map { it.height }.distinct().sortedDescending()
                selectedHeight = 0
                analysisReady = true
                status = if (imagePost) {
                    "Fotoğraf gönderisi hazır. Görsel(ler) orijinal dosya olarak indirilecek."
                } else if (resolutions.isEmpty()) {
                    "Bağlantı çözümlendi. Otomatik kaliteyi deneyebilirsin."
                } else {
                    "Bağlantı hazır. İndirme kalitesini seçebilirsin."
                }
            } catch (e: Exception) {
                val instagramPhotos = if (InstagramPhotoDownloader.isPostUrl(url)) {
                    runCatching {
                        withContext(Dispatchers.IO) { InstagramPhotoDownloader.photoCount(url) }
                    }.getOrDefault(0)
                } else 0

                if (instagramPhotos > 0) {
                    mediaTitle = "Instagram gönderisi (" + instagramPhotos + " fotoğraf)"
                    platformName = "Instagram"
                    resolutions = emptyList()
                    selectedHeight = 0
                    imagePost = true
                    analysisReady = true
                    status = "Fotoğraf gönderisi hazır. Görseller indirilebilir."
                } else {
                    val reason = e.message?.lineSequence()?.firstOrNull()?.take(160)
                        ?: "Kaynak desteklenmiyor veya erişim engellendi."
                    status = "Bağlantı çözümlenemedi: " + reason
                    analysisReady = false
                }
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
            putExtra(MediaDownloadService.EXTRA_FPS, selectedFps)
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
    initial: String, isAnalyzing: Boolean, isDownloading: Boolean, progress: Float, status: String,
    mediaTitle: String, platformName: String, resolutions: List<Int>, selectedHeight: Int, selectedFps: Int,
    audioOnly: Boolean, imagePost: Boolean, analysisReady: Boolean,
    onAnalyze: (String) -> Unit, onDownload: (String) -> Unit, onPaste: () -> String,
    onOpenDownloads: () -> Unit, onHeightChange: (Int, Int) -> Unit,
    onAudioOnlyChange: (Boolean) -> Unit, onUpdateEngine: () -> Unit, onOpenSettings: () -> Unit
) {
    var link by remember { mutableStateOf(initial) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLegalInfo by remember { mutableStateOf(false) }
    LaunchedEffect(initial) { if (initial.isNotBlank()) link = initial }

    val bg = Color(0xFF080B12)
    val panel = Color(0xFF121A27)
    val panelAlt = Color(0xFF192536)
    val accent = Color(0xFF39D6B0)
    val muted = Color(0xFF9BAABD)
    val qualities = listOf(
        Triple("Otomatik • En iyi kalite", 0, 0), Triple("144p", 144, 0),
        Triple("240p", 240, 0), Triple("360p", 360, 0), Triple("480p", 480, 0),
        Triple("720p60fps", 720, 60), Triple("1080p60fps", 1080, 60),
        Triple("1440p60fps", 1440, 60), Triple("2160p • 4K60fps", 2160, 60)
    )
    val selectedQuality = qualities.firstOrNull { it.second == selectedHeight && it.third == selectedFps }
        ?.first ?: if (selectedHeight > 0) selectedHeight.toString() + "p" else "Otomatik • En iyi kalite"

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, secondary = Color(0xFF79A8FF), background = bg, surface = panel,
        onBackground = Color(0xFFF4F7FA), onSurface = Color(0xFFF4F7FA)
    )) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF101722), contentColor = accent) {
                    NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("İndir") })
                    NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Icon(Icons.Default.FolderOpen, null) }, label = { Text("Dosyalar") })
                    NavigationBarItem(selectedTab == 2, { selectedTab = 2; onOpenSettings() }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ayarlar") })
                }
            }
        ) { insets ->
            when (selectedTab) {
                0 -> Column(
                    Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(accent, Color(0xFF2475A8))), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Download, null, tint = bg, modifier = Modifier.size(27.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Cepİndir", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Medya indirmenin kolay yolu", color = muted, fontSize = 12.sp)
                        }
                        Surface(color = Color(0xFF12352F), shape = RoundedCornerShape(50)) {
                            Text("MEDIA", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263648))) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Yeni indirme", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.Link, null, tint = accent)
                            }
                            OutlinedTextField(
                                value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Video veya gönderi bağlantısını yapıştır") }, minLines = 3,
                                shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF344357), focusedContainerColor = panelAlt, unfocusedContainerColor = panelAlt)
                            )
                            OutlinedButton(onClick = { onPaste().let { if (it.isNotBlank()) link = it } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Panodan yapıştır")
                            }
                            Button(onClick = { onAnalyze(link) }, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = link.isNotBlank() && !isAnalyzing && !isDownloading, shape = RoundedCornerShape(15.dp)) {
                                if (isAnalyzing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                                Spacer(Modifier.width(9.dp)); Text(if (isAnalyzing) "Bağlantı analiz ediliyor" else "Bağlantıyı analiz et", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (analysisReady) Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263648))) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = Color(0xFF12352F), shape = RoundedCornerShape(50)) {
                                    Text("MEDYA HAZIR", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.weight(1f)); Text(platformName, color = muted, fontSize = 12.sp)
                            }
                            Text(mediaTitle, style = MaterialTheme.typography.titleMedium)
                            if (imagePost) Text("Fotoğraflar orijinal dosya olarak indirilecek.", color = accent, fontSize = 13.sp)
                            else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !audioOnly, onClick = { onAudioOnlyChange(false) }, label = { Text("Video") })
                                    FilterChip(selected = audioOnly, onClick = { onAudioOnlyChange(true) }, label = { Text("MP3 ses") })
                                }
                                if (!audioOnly) {
                                    Text("Video kalitesi", color = muted, fontSize = 12.sp)
                                    Box {
                                        OutlinedButton(onClick = { qualityMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                                            Text(selectedQuality, modifier = Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                                        }
                                        DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }, modifier = Modifier.background(panelAlt)) {
                                            qualities.forEach { q ->
                                                DropdownMenuItem(
                                                    text = { Text(q.first) },
                                                    onClick = { onHeightChange(q.second, q.third); qualityMenuExpanded = false }
                                                )
                                            }
                                        }
                                    }
                                    Text("Seçilen kalite, kaynak videonun desteklediği uygun akışa göre indirilir.", color = muted, fontSize = 11.sp)
                                }
                            }
                            Button(onClick = { onDownload(link) }, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !isDownloading && !isAnalyzing, shape = RoundedCornerShape(15.dp)) {
                                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (imagePost) "Fotoğrafları indir" else "İndirmeyi başlat", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("İndirme durumu", fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                                if (isDownloading) Text("§{progress.toInt()}%", color = accent, fontWeight = FontWeight.Bold)
                            }
                            Text(status, color = muted, fontSize = 12.sp)
                            if (isDownloading) LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = accent, trackColor = panelAlt)
                        }
                    }
                    Text("YouTube • Instagram • Facebook • TikTok • X • Reddit • Pinterest ve daha fazlası", color = muted, fontSize = 11.sp)
                    TextButton(onClick = { showLegalInfo = true }) { Text("Hakkında ve açık kaynak lisansları", color = muted) }
                }
                1 -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Dosyalarım", style = MaterialTheme.typography.headlineLarge)
                    Text("İndirdiğin içeriklere buradan ulaş.", color = muted)
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.FolderOpen, null, tint = accent, modifier = Modifier.size(36.dp))
                            Text("İndirilenler / CepIndir", style = MaterialTheme.typography.titleLarge)
                            Text("Tamamlanan video, ses ve fotoğraf dosyalarını Android İndirilenler klasöründe bulabilirsin.", color = muted)
                            Button(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("İndirilenleri aç") }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(insets).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Ayarlar", style = MaterialTheme.typography.headlineLarge)
                    Text("İndirme tercihlerini ve uygulama seçeneklerini yönet.", color = muted)
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("İndirme ayarları", style = MaterialTheme.typography.titleLarge)
                            Text("Ses formatı, altyazı, küçük resim, oynatma listesi ve bağlantı ayarları.", color = muted)
                            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Ayrıntılı ayarları aç") }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("İndirme motoru", style = MaterialTheme.typography.titleLarge)
                            Text("yt-dlp motorunu güncel tut. Güncelleme sırasında uygulamayı kapatma.", color = muted)
                            OutlinedButton(onClick = onUpdateEngine, enabled = !isAnalyzing && !isDownloading, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("İndirme motorunu güncelle")
                            }
                        }
                    }
                    Text("Cepİndir • v1.4.0", color = muted, fontSize = 12.sp)
                }
            }
        }
    }
}
