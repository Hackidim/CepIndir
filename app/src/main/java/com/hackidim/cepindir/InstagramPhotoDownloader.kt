/*
 * Instagram public-post image downloader for Cepİndir.
 * Adapted for this project from the public-post extraction approach used by
 * Orang-Studio/InstaDownload (GPL-3.0). Instagram's page structure is undocumented
 * and may change; private/restricted posts may require authentication.
 */
package com.hackidim.cepindir

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal object InstagramPhotoDownloader {
    private val postPattern = Regex(
        """(?:instagram\.com|instagr\.am)/(p|reel|reels|tv)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val scriptPattern = Regex(
        """<script\b[^>]*\bdata-sjs[^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private const val PAGE_USER_AGENT = "Googlebot/2.1 (+http://www.google.com/bot.html)"
    private const val MEDIA_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private data class PostRef(val route: String, val shortcode: String)

    fun isPostUrl(url: String): Boolean = postPattern.containsMatchIn(url)

    fun photoCount(url: String): Int = extractPhotoUrls(url).size

    fun download(url: String, outputDir: File, onProgress: (Int, Int) -> Unit): List<File> {
        val ref = extractPostRef(url)
        val photos = extractPhotoUrls(url)
        if (photos.isEmpty()) {
            throw IllegalStateException(
                "Instagram gönderisinde indirilebilir herkese açık fotoğraf bulunamadı. " +
                    "Gönderi gizli olabilir veya Instagram giriş isteyebilir."
            )
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IllegalStateException("Geçici indirme klasörü oluşturulamadı.")
        }
        val saved = mutableListOf<File>()
        photos.forEachIndexed { index, photoUrl ->
            saved += downloadImage(photoUrl, outputDir, ref.shortcode, index + 1)
            onProgress(index + 1, photos.size)
        }
        return saved
    }

    private fun extractPostRef(url: String): PostRef {
        val match = postPattern.find(url)
            ?: throw IllegalArgumentException("Geçerli bir Instagram gönderi bağlantısı değil.")
        return PostRef(match.groupValues[1], match.groupValues[2].take(11))
    }

    private fun extractPhotoUrls(url: String): List<String> {
        val ref = extractPostRef(url)
        val expectedId = shortcodeToMediaId(ref.shortcode)
        val page = fetchText(
            "https://www.instagram.com/" + ref.route + "/" + ref.shortcode + "/",
            PAGE_USER_AGENT,
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        for (script in scriptPattern.findAll(page)) {
            val jsonText = script.groupValues.getOrNull(1).orEmpty()
            if (!jsonText.contains(expectedId)) continue
            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val product = findTargetProduct(root, expectedId, 0) ?: continue
            val urls = mutableListOf<String>()
            val carousel = product.optJSONArray("carousel_media")
            if (carousel != null) {
                for (index in 0 until carousel.length()) {
                    val item = carousel.optJSONObject(index) ?: continue
                    imageUrlFromItem(item)?.let(urls::add)
                }
            } else {
                imageUrlFromItem(product)?.let(urls::add)
            }
            val unique = urls.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
            if (unique.isNotEmpty()) return unique
        }
        throw IllegalStateException(
            "Instagram gönderisinin fotoğraf bilgileri alınamadı. " +
                "Gönderi gizli olabilir, oturum isteyebilir veya Instagram sayfa yapısını değiştirmiş olabilir."
        )
    }

    private fun findTargetProduct(value: Any?, expectedId: String, depth: Int): JSONObject? {
        if (depth > 40) return null
        when (value) {
            is JSONObject -> {
                val loggedOut = value.optJSONObject("if_not_gated_logged_out")
                if (loggedOut != null && matchesPost(loggedOut, expectedId)) return loggedOut
                if (matchesPost(value, expectedId) &&
                    (value.has("image_versions2") || value.has("carousel_media") || value.has("display_url"))
                ) return value
                val keys = value.keys()
                while (keys.hasNext()) {
                    findTargetProduct(value.opt(keys.next()), expectedId, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findTargetProduct(value.opt(index), expectedId, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun matchesPost(value: JSONObject, expectedId: String): Boolean =
        value.optString("pk") == expectedId || value.optString("id") == expectedId

    private fun imageUrlFromItem(item: JSONObject): String? {
        // A video may carry a preview image. Do not mistake that preview for a photo.
        if (item.optInt("media_type") == 2 ||
            (item.optJSONArray("video_versions")?.length() ?: 0) > 0
        ) return null

        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        val bestCandidate = candidates?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter {
                    it.optString("url").isNotBlank() &&
                        !it.optString("url").contains(Regex("""stp=c\d"""))
                }
                .maxByOrNull {
                    val width = it.optInt("width")
                    val height = it.optInt("height")
                    if (width > 0 && height > 0) width.toLong() * height else width.toLong()
                }
        }
        bestCandidate?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }

        val resources = item.optJSONArray("display_resources")
        if (resources != null) {
            val resource = (0 until resources.length()).mapNotNull { resources.optJSONObject(it) }
                .maxByOrNull { it.optInt("config_width") * it.optInt("config_height") }
            resource?.optString("src")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return item.optString("display_url").takeIf { it.startsWith("http") }
    }

    private fun downloadImage(url: String, outputDir: File, shortcode: String, index: Int): File {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 25_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", MEDIA_USER_AGENT)
            setRequestProperty("Referer", "https://www.instagram.com/")
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Instagram görsel sunucusu HTTP " + code + " yanıtı verdi.")
            }
            val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime != null && !mime.startsWith("image/")) {
                throw IllegalStateException("Instagram görsel yerine farklı bir dosya döndürdü.")
            }
            val extension = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/avif" -> "avif"
                "image/heic", "image/heif" -> "heic"
                else -> "jpg"
            }
            val target = File(
                outputDir,
                "Instagram_" + shortcode + "_" + index.toString().padStart(2, '0') + "." + extension
            )
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (!target.exists() || target.length() == 0L) {
                target.delete()
                throw IllegalStateException("Instagram fotoğrafı boş indirildi.")
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String, userAgent: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    when (code) {
                        401, 403 -> "Instagram bu gönderiye erişimi engelledi (HTTP " + code + "). Gönderi giriş isteyebilir."
                        429 -> "Instagram çok fazla istek algıladı (HTTP 429). Bir süre sonra tekrar dene."
                        else -> "Instagram sayfası HTTP " + code + " yanıtı verdi."
                    }
                )
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (character in shortcode) {
            val digit = alphabet.indexOf(character)
            require(digit >= 0) { "Instagram gönderi kodu geçersiz." }
            id = Math.addExact(Math.multiplyExact(id, 64L), digit.toLong())
        }
        return id.toString()
    }
}
