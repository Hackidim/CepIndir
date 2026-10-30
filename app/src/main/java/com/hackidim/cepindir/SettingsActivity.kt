package com.hackidim.cepindir

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SettingsBg = androidx.compose.ui.graphics.Color(0xFF090912)
private val SettingsPanel = androidx.compose.ui.graphics.Color(0xFF171525)
private val SettingsPurple = androidx.compose.ui.graphics.Color(0xFF9B72FF)

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cepindir_settings", MODE_PRIVATE)
        setContent {
            var audioFormat by remember { mutableStateOf(prefs.getString("audio_format", "mp3") ?: "mp3") }
            var subtitles by remember { mutableStateOf(prefs.getBoolean("subtitles", false)) }
            var thumbnail by remember { mutableStateOf(prefs.getBoolean("thumbnail", false)) }
            var playlist by remember { mutableStateOf(prefs.getBoolean("playlist", false)) }
            var metadata by remember { mutableStateOf(prefs.getBoolean("embed_metadata", true)) }
            var retries by remember { mutableIntStateOf(prefs.getInt("retries", 2)) }
            var timeout by remember { mutableIntStateOf(prefs.getInt("timeout", 20)) }
            var formatMenu by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(primary = SettingsPurple, background = SettingsBg, surface = SettingsPanel)) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Gelişmiş ayarlar") },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.ArrowBack, "Geri") } },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsBg)
                        )
                    },
                    containerColor = SettingsBg
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("İNDİRME TERCİHLERİ", color = SettingsPurple, style = MaterialTheme.typography.labelLarge)
                        Card(colors = CardDefaults.cardColors(containerColor = SettingsPanel), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Ses formatı", style = MaterialTheme.typography.titleMedium)
                                Box {
                                    OutlinedButton(onClick = { formatMenu = true }) { Text(audioFormat.uppercase()) }
                                    DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                                        listOf("mp3", "m4a", "opus").forEach { item ->
                                            DropdownMenuItem(text = { Text(item.uppercase()) }, onClick = {
                                                audioFormat = item; prefs.edit().putString("audio_format", item).apply(); formatMenu = false
                                            })
                                        }
                                    }
                                }
                                SettingSwitch("Altyazıları varsayılan indir", subtitles) { subtitles = it; prefs.edit().putBoolean("subtitles", it).apply() }
                                SettingSwitch("Küçük resmi kaydet", thumbnail) { thumbnail = it; prefs.edit().putBoolean("thumbnail", it).apply() }
                                SettingSwitch("Oynatma listelerini toplu indir", playlist) { playlist = it; prefs.edit().putBoolean("playlist", it).apply() }
                                SettingSwitch("Ses dosyasına metadata ekle", metadata) { metadata = it; prefs.edit().putBoolean("embed_metadata", it).apply() }
                            }
                        }
                        Text("BAĞLANTI VE KARARLILIK", color = SettingsPurple, style = MaterialTheme.typography.labelLarge)
                        Card(colors = CardDefaults.cardColors(containerColor = SettingsPanel), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Yeniden deneme sayısı: $retries")
                                Slider(value = retries.toFloat(), onValueChange = { retries = it.toInt() }, valueRange = 0f..10f, steps = 9,
                                    onValueChangeFinished = { prefs.edit().putInt("retries", retries).apply() })
                                Text("Ağ zaman aşımı: $timeout saniye")
                                Slider(value = timeout.toFloat(), onValueChange = { timeout = (it.toInt() / 5) * 5 }, valueRange = 5f..120f, steps = 22,
                                    onValueChangeFinished = { prefs.edit().putInt("timeout", timeout.coerceIn(5, 120)).apply() })
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = SettingsPanel), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Bilgi", style = MaterialTheme.typography.titleMedium)
                                Text("Bu tercihler arka plandaki indirme servisine uygulanır. Ağ zaman aşımı ve yeniden deneme değerleri bağlantı sorunlarında kullanılır.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Tamam") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
