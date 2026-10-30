package com.hackidim.cepindir

import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Close
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

private val Ink = Color(0xFF090912)
private val Panel = Color(0xFF171525)
private val Purple = Color(0xFF9B72FF)
private val Muted = Color(0xFFAAA6BC)

class MainActivity : ComponentActivity() {
    private var sharedLink by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedLink = readSharedLink(intent)
        setContent {
            CepIndirScreen(
                initial = sharedLink,
                onDownload = ::startDownload,
                onPaste = ::pasteFromClipboard,
                onOpenDownloads = ::openDownloads
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedLink = readSharedLink(intent)
    }

    private fun readSharedLink(source: Intent?): String {
        if (source == null) return ""
        val text = source.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) return text.trim()
        return runCatching { source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty() }
            .getOrDefault("")
    }

    private fun pasteFromClipboard(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (value.isBlank()) Toast.makeText(this, "Panoda bağlantı bulunamadı.", Toast.LENGTH_SHORT).show()
        return value
    }

    private fun openDownloads() {
        runCatching {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        }.onFailure {
            Toast.makeText(this, "İndirmeler ekranı açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDownload(raw: String) {
        val candidate = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.value?.trimEnd('.', ',', ')', ']', '!')
        val uri = runCatching { Uri.parse(candidate ?: raw.trim()) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Geçerli bir http/https bağlantısı gir.", Toast.LENGTH_LONG).show()
            return
        }

        val path = uri.lastPathSegment.orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        val supported = setOf("mp4", "m4v", "mov", "webm", "jpg", "jpeg", "png", "webp", "gif", "mp3", "m4a", "aac", "ogg", "wav")
        if (extension !in supported) {
            Toast.makeText(
                this,
                "Bu bağlantı doğrudan medya dosyası gibi görünmüyor. Sosyal medya gönderilerini indirme desteği sonraki aşamada eklenecek.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val fileName = path.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "cepindir_media.$extension" }
            val request = DownloadManager.Request(uri)
                .setTitle("Cepİndir - medya indiriliyor")
                .setDescription(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "İndirme başladı. Bildirimlerden veya İndirmeler bölümünden takip edebilirsin.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "İndirme başlatılamadı. Bağlantıyı kontrol et.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun CepIndirScreen(
    initial: String,
    onDownload: (String) -> Unit,
    onPaste: () -> String,
    onOpenDownloads: () -> Unit
) {
    var link by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { if (initial.isNotBlank()) link = initial }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF171127), Ink)))) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp).align(Alignment.TopCenter)
                    .padding(top = 52.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Cepİndir", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Videolarını ve görsellerini tek yerde topla", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(30.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = Purple)
                            Spacer(Modifier.width(8.dp))
                            Text("Bağlantı", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { link = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Temizle", tint = Muted)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Video veya görsel bağlantısını yapıştır") },
                            minLines = 3,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { val pasted = onPaste(); if (pasted.isNotBlank()) link = pasted },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Panodan bağlantı yapıştır")
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onDownload(link) },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = link.isNotBlank()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("İndirmeyi başlat", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("İndirmelerimi aç")
                }
                Spacer(Modifier.height(24.dp))
                Text("Nasıl kullanılır?", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bir uygulamada Paylaş → Cepİndir seçeneğini kullanabilir veya bağlantıyı yapıştırabilirsin. İndirmeler arka planda sürer; bildirimden takip edebilirsin.",
                    color = Muted, fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Şu an doğrudan medya dosyası bağlantıları desteklenir. Instagram, Facebook, YouTube ve Pinterest gönderi bağlantıları için platform desteği henüz geliştirme aşamasında.",
                    color = Color(0xFFFFD58A), fontSize = 12.sp
                )
            }
        }
    }
}
