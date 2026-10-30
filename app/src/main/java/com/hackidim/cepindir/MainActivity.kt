package com.hackidim.cepindir

import android.app.DownloadManager
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
        sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent { CepIndirScreen(sharedLink, ::startDownload) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }

    private fun startDownload(raw: String) {
        val link = raw.trim()
        val uri = runCatching { Uri.parse(link) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Geçerli bir http/https bağlantısı gir.", Toast.LENGTH_LONG).show()
            return
        }
        val path = uri.lastPathSegment.orEmpty().lowercase()
        val directMedia = listOf(".mp4", ".m4v", ".mov", ".jpg", ".jpeg", ".png", ".webp", ".mp3", ".m4a").any { path.endsWith(it) }
        if (!directMedia) {
            Toast.makeText(this, "Bu bağlantı doğrudan medya dosyası değil. Sosyal platform çözümleyicisi henüz eklenmedi.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val request = DownloadManager.Request(uri)
                .setTitle("Cepİndir - medya indiriliyor")
                .setDescription(uri.lastPathSegment ?: "Dosya")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uri.lastPathSegment ?: "cepindir_media")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "İndirme başladı. Bildirimlerden takip edebilirsin.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "İndirme başlatılamadı. Bağlantıyı kontrol et.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun CepIndirScreen(initial: String, onDownload: (String) -> Unit) {
    var link by remember { mutableStateOf(initial) }
    var quality by remember { mutableStateOf("En yüksek") }
    LaunchedEffect(initial) { if (initial.isNotBlank()) link = initial }
    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF171127), Ink)))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).align(Alignment.TopCenter).padding(top = 62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Cepİndir", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Videolarını ve görsellerini tek yerde topla", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(34.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = Purple)
                            Spacer(Modifier.width(8.dp))
                            Text("Bağlantı", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Video veya görsel bağlantısını yapıştır") }, minLines = 3, shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Spacer(Modifier.height(16.dp))
                        Text("Kalite tercihi", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("En yüksek", "Orta", "Düşük").forEach { item ->
                                FilterChip(selected = quality == item, onClick = { quality = item }, label = { Text(item) })
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { onDownload(link) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("İndirmeyi başlat", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Nasıl kullanılır?", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Bir uygulamada Paylaş → Cepİndir seçeneğini kullanabilir veya bağlantıyı yukarıya yapıştırabilirsin.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("Not: Şu an doğrudan medya dosyası bağlantıları desteklenir. Instagram, Facebook, YouTube ve Pinterest bağlantıları için platforma özel çözümleyici henüz eklenmedi.", color = Color(0xFFFFD58A), fontSize = 12.sp)
            }
        }
    }
}
