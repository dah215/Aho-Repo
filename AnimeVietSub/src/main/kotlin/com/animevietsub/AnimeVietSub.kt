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
            
            // Thử cả STANDARD và URL_SAFE base64
            val decoded = try {
                Base64.decode(cleaned, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    Base64.decode(cleaned, Base64.URL_SAFE)
                } catch (e2: Exception) {
                    // Thử thay thế ký tự URL-safe thành standard
                    val standardBase64 = cleaned
                        .replace("-", "+")
                        .replace("_", "/")
                    val padding = (4 - standardBase64.length % 4) % 4
                    val padded = standardBase64 + "=".repeat(padding)
                    Base64.decode(padded, Base64.DEFAULT)
                }
            }
            
            if (decoded.size < 16) {
                android.util.Log.e(TAG, "Decoded data too short: ${decoded.size}")
                return null
            }
            
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "decryptLink error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buf = ByteArray(8192)
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

        // filmId từ URL: ".../a5820/..." → "5820"
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
        if (parts.size < 3) {
            android.util.Log.e(TAG, "Invalid data format: $data")
            return false
        }

        val epUrl     = parts[0]  // URL trang tập (Referer)
        val filmId    = parts[1]  // Film ID (5820)
        val episodeId = parts[2]  // Episode ID (111047)

        android.util.Log.d(TAG, "loadLinks: epUrl=$epUrl, filmId=$filmId, episodeId=$episodeId")

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Origin"           to mainUrl
        )

        // Warm CF cookies
        runCatching { 
            app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) 
        }

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 1: POST /ajax/player với episodeId để lấy server selection HTML
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
            // Thử lại với backup=0
            val step1Retry = runCatching {
                app.post(
                    "$mainUrl/ajax/player",
                    data        = mapOf("episodeId" to episodeId, "backup" to "0"),
                    interceptor = cfKiller,
                    referer     = epUrl,
                    headers     = ajaxHeaders
                ).parsedSafe<ServerSelectionResponse>()
            }.getOrNull()
            
            if (step1Retry?.html.isNullOrBlank()) {
                android.util.Log.e(TAG, "Step 1 retry also failed")
                return false
            }
        }

        val serverDoc = Jsoup.parse(htmlContent ?: step1?.html ?: "")

        // Ưu tiên server api (DU), bỏ qua embed (HDX/ADS có quảng cáo)
        var server = serverDoc.selectFirst("a.btn3dsv[data-play=api], a[data-play=api]")
        if (server == null) {
            server = serverDoc.selectFirst("a.btn3dsv, a.server-item, a[data-href]")
        }
        
        if (server == null) {
            android.util.Log.e(TAG, "No server found in HTML: ${serverDoc.html().take(500)}")
            return false
        }

        val serverHash = server.attr("data-href").trim()
        val serverPlay = server.attr("data-play").ifEmpty { "api" }.trim()
        val serverId   = server.attr("data-id").trim()

        android.util.Log.d(TAG, "Server: hash=$serverHash, play=$serverPlay, id=$serverId")

        if (serverHash.isEmpty()) {
            android.util.Log.e(TAG, "Server hash is empty")
            return false
        }

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 2: POST /ajax/player với server hash để lấy encrypted/direct link
        // ═══════════════════════════════════════════════════════════════════════
        val step2Params = when (serverPlay) {
            "api"   -> mapOf("link" to serverHash, "id" to filmId)
            else    -> mapOf("link" to serverHash, "play" to serverPlay, "id" to serverId, "backuplinks" to "1")
        }

        android.util.Log.d(TAG, "Step 2 params: $step2Params")

        val step2Resp = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = step2Params,
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull()

        if (step2Resp == null) {
            android.util.Log.e(TAG, "Step 2: No response")
            return false
        }

        val respText = step2Resp.text.trim()
        android.util.Log.d(TAG, "Step 2 response: ${respText.take(500)}")

        if (respText.isBlank() || respText.startsWith("<")) {
            android.util.Log.e(TAG, "Step 2: Invalid response format")
            return false
        }

        val parsed = step2Resp.parsedSafe<PlayerResponse>()
        if (parsed == null) {
            android.util.Log.e(TAG, "Step 2: Failed to parse JSON")
            return false
        }

        android.util.Log.d(TAG, "Parsed: playTech=${parsed.playTech}, linkString=${parsed.linkString?.take(100)}, linkArray=${parsed.linkArray?.size}")

        return when (parsed.playTech) {
            // ── API server (DU): link là array [{file: "<standard_base64_encrypted>"}]
            "api" -> {
                val encryptedFile = parsed.linkArray
                    ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                
                if (encryptedFile == null) {
                    android.util.Log.e(TAG, "No encrypted file found in linkArray")
                    
                    // Thử lấy từ linkString nếu linkArray null
                    val directLink = parsed.linkString?.takeIf { it.startsWith("http") }
                    if (directLink != null) {
                        android.util.Log.d(TAG, "Found direct link in linkString")
                        return loadExtractor(directLink, epUrl, subtitleCallback, callback)
                    }
                    return false
                }
                
                val decrypted = decryptLink(encryptedFile)
                if (decrypted == null) {
                    android.util.Log.e(TAG, "Decryption failed for: ${encryptedFile.take(50)}")
                    return false
                }
                
                android.util.Log.d(TAG, "Decrypted: ${decrypted.take(200)}")
                handleDecryptedLink(decrypted, epUrl, callback, subtitleCallback)
            }
            // ── Embed server (HDX): link là string URL trực tiếp
            "embed" -> {
                val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                if (directUrl == null) {
                    android.util.Log.e(TAG, "No direct URL in embed mode")
                    return false
                }
                loadExtractor(directUrl, epUrl, subtitleCallback, callback)
                true
            }
            // ── Unknown playTech - thử xử lý linh hoạt
            else -> {
                android.util.Log.d(TAG, "Unknown playTech: ${parsed.playTech}, trying flexible handling")
                
                // Thử linkArray trước
                val encryptedFile = parsed.linkArray
                    ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                
                if (encryptedFile != null) {
                    val decrypted = decryptLink(encryptedFile)
                    if (decrypted != null) {
                        return handleDecryptedLink(decrypted, epUrl, callback, subtitleCallback)
                    }
                }
                
                // Thử linkString
                val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                if (directUrl != null) {
                    loadExtractor(directUrl, epUrl, subtitleCallback, callback)
                    return true
                }
                
                false
            }
        }
    }

    // ── Handle decrypted m3u8 URL hoặc content ────────────────────────────────
    private suspend fun handleDecryptedLink(
        decrypted: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = decrypted.trim()
        android.util.Log.d(TAG, "handleDecryptedLink: ${link.take(200)}")
        
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
                var foundAny = false
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    foundAny = true
                    if (url.contains(".m3u8"))
                        M3u8Helper.generateM3u8(name, url.trim(), referer).forEach(callback)
                    else
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                }
                foundAny
            }
            else -> {
                android.util.Log.e(TAG, "Unknown decrypted format: ${link.take(100)}")
                false
            }
        }
    }

    private suspend fun parseM3u8Content(content: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val lines = content.lines()
        if (content.contains("#EXT-X-STREAM-INF")) {
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val q  = when {
                        bw == null      -> Qualities.Unknown.value
                        bw >= 4_000_000 -> Qualities.P1080.value
                        bw >= 2_000_000 -> Qualities.P720.value
                        bw >= 1_000_000 -> Qualities.P480.value
                        else            -> Qualities.P360.value
                    }
                    val urlLine = lines.getOrNull(i + 1)?.trim() ?: return@forEachIndexed
                    if (urlLine.startsWith("http")) {
                        callback.invoke(newExtractorLink(name, name, urlLine) {
                            this.referer = referer
                            this.quality = q
                            this.type    = ExtractorLinkType.M3U8
                        })
                    }
                }
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class ServerSelectionResponse(
        @JsonProperty("success") val success: Int?  = null,
        @JsonProperty("html")    val html: String?  = null
    )

    // PlayerResponse xử lý cả 2 format:
    //   api server:   "link": [{"file": "base64..."}]
    //   embed server: "link": "https://..."
    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        // Dùng Any? để xử lý cả Array lẫn String
        @JsonProperty("link") private val linkRaw: Any?  = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkItem>?
            get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?.map { LinkItem(it["file"] as? String) }

        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkItem(
        val file: String? = null
    )
}
