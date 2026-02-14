package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import java.net.HttpURLConnection
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name   = "AnimeVietSub"
    override var lang   = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val DECODE_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"
        private const val TAG = "AnimeVietSub"
    }

    private val cfKiller = CloudflareKiller()

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer"    to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DECRYPT: AES-256-CBC + pako.inflateRaw
    // ─────────────────────────────────────────────────────────────────────────
    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            
            val decoded = try {
                Base64.decode(cleaned, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    val urlSafe = cleaned.replace("-", "+").replace("_", "/")
                    val padding = (4 - urlSafe.length % 4) % 4
                    Base64.decode(urlSafe + "=".repeat(padding), Base64.DEFAULT)
                } catch (e2: Exception) { null }
            } ?: return null
            
            if (decoded.size < 16) return null
            
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "decryptLink error: ${e.message}")
            null
        }
    }

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buf = ByteArray(16384)
        val out = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        } finally { inflater.end() }
        return out.toByteArray()
    }

    // ── Follow redirect để lấy final URL từ short link ───────────────────────
    private fun followRedirects(startUrl: String, maxRedirects: Int = 10): String? {
        var currentUrl = startUrl
        var redirectCount = 0
        
        try {
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", 
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                
                val responseCode = connection.responseCode
                
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    
                    if (newUrl.isNullOrEmpty()) return currentUrl
                    
                    currentUrl = if (newUrl.startsWith("http")) {
                        newUrl
                    } else {
                        val baseUrl = URL(currentUrl)
                        URL(baseUrl.protocol, baseUrl.host, newUrl).toString()
                    }
                    
                    android.util.Log.d(TAG, "Redirect $redirectCount -> $currentUrl")
                    redirectCount++
                } else {
                    connection.disconnect()
                    return currentUrl
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "followRedirects error: ${e.message}")
        }
        
        return currentUrl
    }

    // ── Extract video từ Abyss ───────────────────────────────────────────────
    private suspend fun extractAbyssVideo(abyssUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            android.util.Log.d(TAG, "Extracting Abyss URL: $abyssUrl")
            
            // Thử loadExtractor trước (CloudStream có thể đã có extractor)
            val extracted = loadExtractor(abyssUrl, referer, { }, callback)
            if (extracted) {
                android.util.Log.d(TAG, "loadExtractor succeeded for $abyssUrl")
                return true
            }
            
            // Nếu không, tự parse trang
            val doc = app.get(abyssUrl, 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to referer
                )
            ).document
            
            // Tìm video URL trong trang
            val scripts = doc.select("script")
            for (script in scripts) {
                val content = script.html()
                
                // Tìm URL dạng storage.googleapis.com hoặc .mp4
                val videoPatterns = listOf(
                    Regex("""["'](https?://storage\.googleapis\.com[^"']+)["']"""),
                    Regex("""["'](https?://[^\s"']+\.mp4[^\s"']*)["']"""),
                    Regex("""["'](https?://[^\s"']+\.m3u8[^\s"']*)["']"""),
                    Regex("""file\s*:\s*["']([^"']+)["']"""),
                    Regex("""sources?\s*:\s*\[?\s*["']([^"']+)["']""")
                )
                
                for (pattern in videoPatterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        val videoUrl = match.groupValues[1]
                        android.util.Log.d(TAG, "Found video URL: $videoUrl")
                        
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, videoUrl, "https://abysscdn.com/").forEach(callback)
                        } else {
                            callback.invoke(newExtractorLink(name, name, videoUrl) {
                                this.referer = "https://abysscdn.com/"
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.VIDEO
                            })
                        }
                        return true
                    }
                }
            }
            
            // Tìm iframe hoặc embed
            val iframe = doc.selectFirst("iframe")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotEmpty()) {
                    android.util.Log.d(TAG, "Found iframe: $iframeSrc")
                    return loadExtractor(iframeSrc, abyssUrl, { }, callback)
                }
            }
            
            false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractAbyssVideo error: ${e.message}")
            false
        }
    }

    // ── Search response ───────────────────────────────────────────────────────
    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3, h2.Title")?.text()?.trim() ?: return null
        val href   = selectFirst("a")?.attr("href")            ?: return null
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        val epText = selectFirst(".mli-eps i, .mli-eps, .Epnum, .ep-count")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            Regex("\\d+").find(epText)?.value?.toIntOrNull()?.let { addSub(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val items = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost, .TPostMv, .TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost, .TPostMv, .TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id], .list-episode a[data-id], ul.list-ep a[data-id]")

        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-'], a[href*='/xem-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()?.also { doc2 ->
                    episodesNodes = doc2.select("ul.list-episode li a[data-id], .list-episode a[data-id], ul.list-ep a[data-id]")
                    if (episodesNodes.isNotEmpty()) document = doc2
                }
            }
        }

        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val episodesList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (episodeId.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            newEpisode("$epUrl@@$filmId@@$episodeId") {
                name    = epName
                episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1, h1.entry-title")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst(".Image img, .InfoImg img, .poster img")
                ?.let { img -> img.attr("data-src").ifEmpty { img.attr("src") } }
            plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description, .entry-content")
                ?.text()?.trim()
            episodes = if (episodesList.isNotEmpty())
                mutableMapOf(DubStatus.Subbed to episodesList)
            else mutableMapOf()
        }
    }

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false

        val epUrl     = parts[0]
        val filmId    = parts[1]
        val episodeId = parts[2]

        android.util.Log.d(TAG, "loadLinks: epUrl=$epUrl, filmId=$filmId, episodeId=$episodeId")

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Origin"           to mainUrl
        )

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 1: Lấy server list
        // ═══════════════════════════════════════════════════════════════════════
        val step1 = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = mapOf("episodeId" to episodeId, "backup" to "1"),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            ).parsedSafe<ServerSelectionResponse>()
        }.getOrNull()

        val htmlContent = step1?.html
        if (htmlContent.isNullOrBlank()) {
            android.util.Log.e(TAG, "Step 1 failed: no HTML content")
            return false
        }

        val serverDoc = Jsoup.parse(htmlContent)
        val servers = serverDoc.select("a.btn3dsv")
        
        if (servers.isEmpty()) {
            android.util.Log.e(TAG, "No servers found")
            return false
        }

        android.util.Log.d(TAG, "Found ${servers.size} servers: ${servers.map { it.text() }}")

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 2: Thử từng server
        // ═══════════════════════════════════════════════════════════════════════
        for (server in servers) {
            val serverHash = server.attr("data-href").trim()
            val serverPlay = server.attr("data-play").ifEmpty { "api" }.trim()
            val serverId   = server.attr("data-id").trim()
            val serverName = server.text().trim()

            android.util.Log.d(TAG, "Trying server: $serverName, play=$serverPlay")
            if (serverHash.isEmpty()) continue

            val result = tryLoadServer(
                serverHash, serverPlay, serverId, filmId, epUrl, ajaxHeaders,
                callback, subtitleCallback
            )

            if (result) {
                android.util.Log.d(TAG, "Successfully loaded from: $serverName")
                return true
            }
        }

        return false
    }

    // ── Load server ───────────────────────────────────────────────────────────
    private suspend fun tryLoadServer(
        serverHash: String,
        serverPlay: String,
        serverId: String,
        filmId: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return try {
            val step2Params = when (serverPlay) {
                "api" -> mapOf("link" to serverHash, "id" to filmId)
                else -> mapOf("link" to serverHash, "play" to serverPlay, "id" to serverId, "backuplinks" to "1")
            }

            val step2Resp = app.post(
                "$mainUrl/ajax/player",
                data        = step2Params,
                interceptor = cfKiller,
                referer     = referer,
                headers     = headers
            )

            val respText = step2Resp.text.trim()
            android.util.Log.d(TAG, "Response ($serverPlay): ${respText.take(300)}")

            if (respText.isBlank() || respText.startsWith("<")) return false

            val parsed = step2Resp.parsedSafe<PlayerResponse>() ?: return false

            when (parsed.playTech ?: serverPlay) {
                "api" -> {
                    val encryptedFile = parsed.linkArray
                        ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                        ?: return false

                    val decrypted = decryptLink(encryptedFile)
                    if (decrypted.isNullOrEmpty()) return false

                    handleDecryptedLink(decrypted, referer, callback, subtitleCallback)
                }
                "embed" -> {
                    val shortUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                        ?: return false

                    android.util.Log.d(TAG, "Embed URL: $shortUrl")

                    // Follow redirects từ short link
                    val finalUrl = followRedirects(shortUrl)
                    android.util.Log.d(TAG, "Final URL after redirects: $finalUrl")

                    if (finalUrl.isNullOrEmpty()) return false

                    // Xử lý dựa trên domain cuối
                    when {
                        finalUrl.contains("abyss.to") || finalUrl.contains("abysscdn.com") -> {
                            extractAbyssVideo(finalUrl, referer, callback)
                        }
                        finalUrl.contains(".m3u8") -> {
                            M3u8Helper.generateM3u8(name, finalUrl, referer).forEach(callback)
                            true
                        }
                        finalUrl.contains(".mp4") -> {
                            callback.invoke(newExtractorLink(name, name, finalUrl) {
                                this.referer = "https://abysscdn.com/"
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.VIDEO
                            })
                            true
                        }
                        else -> {
                            // Thử loadExtractor
                            loadExtractor(finalUrl, referer, subtitleCallback, callback)
                            true
                        }
                    }
                }
                else -> {
                    // Fallback
                    val encryptedFile = parsed.linkArray
                        ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                    
                    if (encryptedFile != null) {
                        val decrypted = decryptLink(encryptedFile)
                        if (!decrypted.isNullOrEmpty()) {
                            return handleDecryptedLink(decrypted, referer, callback, subtitleCallback)
                        }
                    }

                    val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                    if (directUrl != null) {
                        val finalUrl = followRedirects(directUrl) ?: directUrl
                        loadExtractor(finalUrl, referer, subtitleCallback, callback)
                        return true
                    }
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "tryLoadServer error: ${e.message}")
            false
        }
    }

    // ── Handle decrypted link ─────────────────────────────────────────────────
    private suspend fun handleDecryptedLink(
        decrypted: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = decrypted.trim()
        
        return when {
            link.contains(".m3u8") && link.startsWith("http") -> {
                M3u8Helper.generateM3u8(name, link, referer).forEach(callback)
                true
            }
            link.startsWith("#EXTM3U") -> {
                parseM3u8Content(link, referer, callback)
                true
            }
            link.startsWith("http") -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                true
            }
            link.contains("\n") -> {
                var found = false
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    found = true
                    if (url.contains(".m3u8"))
                        M3u8Helper.generateM3u8(name, url.trim(), referer).forEach(callback)
                    else
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                }
                found
            }
            else -> false
        }
    }

    private suspend fun parseM3u8Content(content: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val lines = content.lines()
        if (content.contains("#EXT-X-STREAM-INF")) {
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val q = when {
                        bw == null -> Qualities.Unknown.value
                        bw >= 4_000_000 -> Qualities.P1080.value
                        bw >= 2_000_000 -> Qualities.P720.value
                        bw >= 1_000_000 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                    val urlLine = lines.getOrNull(i + 1)?.trim() ?: return@forEachIndexed
                    if (urlLine.startsWith("http")) {
                        callback.invoke(newExtractorLink(name, name, urlLine) {
                            this.referer = referer
                            this.quality = q
                            this.type = ExtractorLinkType.M3U8
                        })
                    }
                }
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class ServerSelectionResponse(
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("html")    val html: String? = null
    )

    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int? = null,
        @JsonProperty("success")   val success: Int? = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("link") private val linkRaw: Any? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkItem>?
            get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?.map { LinkItem(it["file"] as? String) }

        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkItem(val file: String? = null)
}
