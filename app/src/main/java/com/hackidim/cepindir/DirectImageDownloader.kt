/*
 * Direct image URL downloader for Cepİndir.
 */
package com.hackidim.cepindir

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal object DirectImageDownloader {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

    fun isDirectImageUrl(url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrNull().orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    fun download(url: String, outputDir: File): File {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Görsel sunucusu HTTP " + code + " yanıtı verdi.")
            }
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime != null && !mime.startsWith("image/")) {
                throw IllegalStateException("Bağlantı bir görsel dosyası döndürmedi.")
            }
            val extension = when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                "image/avif" -> "avif"
                "image/heic", "image/heif" -> "heic"
                else -> URL(url).path.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                    .takeIf { it in imageExtensions } ?: "jpg"
            }
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IllegalStateException("Geçici indirme klasörü oluşturulamadı.")
            }
            val target = File(outputDir, "Gorsel_" + System.currentTimeMillis() + "." + extension)
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (!target.exists() || target.length() == 0L) {
                target.delete()
                throw IllegalStateException("Görsel dosyası boş indirildi.")
            }
            return target
        } finally {
            connection.disconnect()
        }
    }
}
