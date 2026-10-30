/*
 * Cepİndir background media download service.
 * Modified 30 October 2026.
 * Distributed under GPL-3.0-only; see LICENSE.
 */
package com.hackidim.cepindir

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MediaDownloadService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "com.hackidim.cepindir.DOWNLOAD"
        const val ACTION_STOP = "com.hackidim.cepindir.STOP"
        const val ACTION_EVENT = "com.hackidim.cepindir.DOWNLOAD_EVENT"
        const val EXTRA_URL = "url"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        const val EXTRA_TYPE = "type"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "cepindir_downloads"
        private const val NOTIFICATION_ID = 4103
        private const val PROCESS_ID = "cepindir-active-download"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runCatching { YoutubeDL.destroyProcessById(PROCESS_ID) }
                postFinishedNotification("İndirme durduruldu.")
                sendEvent("stopped", "İndirme durduruldu.", 0f)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                if (!running.compareAndSet(false, true)) {
                    sendEvent("error", "Zaten devam eden bir indirme var.", 0f)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
                startAsForeground("Bağlantı hazırlanıyor…", 0)
                executor.execute { performDownload(url, height, audioOnly, startId) }
            }
        }
        return START_NOT_STICKY
    }

    private fun performDownload(url: String, height: Int, audioOnly: Boolean, startId: Int) {
        var workDir: File? = null
        try {
            YoutubeDL.init(applicationContext)
            workDir = File(cacheDir, "cepindir_${UUID.randomUUID()}").apply { mkdirs() }
            val outputTemplate = File(workDir, "%(title).120B [%(id)s].%(ext)s").absolutePath
            val request = YoutubeDLRequest(url)
                .addOption("--no-mtime")
                .addOption("--no-playlist")
                .addOption("--no-warnings")
                .addOption("--newline")
                .addOption("-o", outputTemplate)

            if (audioOnly) {
                request.addOption("-x")
                    .addOption("--audio-format", "mp3")
                    .addOption("--audio-quality", "0")
            } else {
                val format = if (height > 0) {
                    "bestvideo[height<=${height}]+bestaudio/best[height<=${height}]/best"
                } else {
                    "bestvideo+bestaudio/best"
                }
                request.addOption("-f", format)
            }

            startAsForeground("İndirme başlatılıyor…", 0)
            sendEvent("progress", "İndirme başladı.", 0f)
            YoutubeDL.execute(request, PROCESS_ID, false) { percent, etaSeconds, line ->
                val safePercent = percent.coerceIn(0f, 100f)
                val readable = line.take(180).ifBlank { "İndiriliyor…" }
                updateProgressNotification(readable, safePercent, etaSeconds)
                sendEvent("progress", readable, safePercent)
            }

            val downloadedFile = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.startsWith(".") }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("İndirilen dosya bulunamadı.")

            saveToDownloads(downloadedFile)
            val completedName = downloadedFile.name
            postFinishedNotification("İndirme tamamlandı: $completedName")
            sendEvent("complete", "İndirme tamamlandı. Dosya İndirilenler/CepIndir klasörüne kaydedildi.", 100f)
        } catch (e: Exception) {
            val error = e.message?.lineSequence()?.firstOrNull()?.take(180) ?: "İndirme başarısız oldu."
            postFinishedNotification("İndirme tamamlanamadı: $error")
            sendEvent("error", "İndirme başarısız: $error", 0f)
        } finally {
            workDir?.deleteRecursively()
            running.set(false)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    private fun saveToDownloads(source: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mime = URLConnection.guessContentTypeFromName(source.name) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CepIndir")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Dosya İndirilenler klasörüne kaydedilemedi.")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Dosya yazılamadı.")
                val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, complete, null, null)
                return uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }

        val targetDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("İndirme klasörü oluşturulamadı.")
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, source.name)
        source.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Cepİndir indirmeleri", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Devam eden medya indirmelerinin durumu"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startAsForeground(message: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Cepİndir")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Durdur",
                PendingIntent.getService(
                    this, 1, Intent(this, MediaDownloadService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(message: String, progress: Float, eta: Long) {
        val etaText = if (eta > 0) " • yaklaşık ${eta}s kaldı" else ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Cepİndir • %${progress.toInt()}")
            .setContentText(message + etaText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.toInt(), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Durdur",
                PendingIntent.getService(
                    this, 1, Intent(this, MediaDownloadService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun postFinishedNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Cepİndir")
            .setContentText(message)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendEvent(type: String, message: String, progress: Float) {
        val event = Intent(ACTION_EVENT).setPackage(packageName).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PROGRESS, progress)
        }
        sendBroadcast(event)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
