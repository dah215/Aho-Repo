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
        private const val TAG = "[AVS_v14]"
    }

    private val cfKiller = CloudflareKiller()

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer"    to "$mainUrl/",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,vi;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    private fun log(msg: String) {
        println("$TAG $msg")
    }

    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            
            if (decoded.size < 16) {
                log("Decoded data too short: ${decoded.size}")
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
            log("Decrypt failed: ${e.message}")
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
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
            .select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
    }

    // Data format: "epUrl@@filmId@@episodeId"
    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()?.also { doc2 ->
                    episodesNodes = doc2.select("ul.list-episode li a[data-id]")
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

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst(".Image img, .InfoImg img")
                ?.let { img -> img.attr("data-src").ifEmpty { img.attr("src") } }
            plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")
                ?.text()?.trim()
            episodes = if (episodesList.isNotEmpty())
                mutableMapOf(DubStatus.Subbed to episodesList)
            else mutableMapOf()
        }
    }

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

        log("═══════════════════════════════════════════════════")
        log("Loading episode: $episodeId, filmId: $filmId")
        log("URL: $epUrl")

        // Lấy trang episode
        val epDoc = runCatching {
            app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders).document
        }.getOrNull()
        
        if (epDoc == null) {
            log("Failed to load episode page!")
            return false
        }

        // ═══════════════════════════════════════════════════════════════
        // BƯỚC 1: Tìm hash
        // ═══════════════════════════════════════════════════════════════
        
        var hash: String? = null
        var playType = "api"

        // Cách 1: Từ data-hash attribute (ưu tiên episode đang active)
        val selectors = listOf(
            "a.btn-episode[data-id=$episodeId][data-hash]",
            "a.btn3d[data-id=$episodeId][data-hash]",
            "a.episode-link[data-id=$episodeId][data-hash]",
            "a[data-id=$episodeId][data-hash]"
        )
        
        for (selector in selectors) {
            val el = epDoc.selectFirst(selector)
            if (el != null) {
                hash = el.attr("data-hash").trim()
                playType = el.attr("data-play").ifEmpty { "api" }.trim()
                log("Found hash from selector '$selector'")
                break
            }
        }

        // Cách 2: Từ JavaScript AnimeVsub() call
        if (hash.isNullOrBlank()) {
            log("Trying to find hash from AnimeVsub() JavaScript...")
            val scripts = epDoc.select("script")
            for (script in scripts) {
                val content = script.html()
                if (content.contains("AnimeVsub(")) {
                    // Pattern: AnimeVsub('hash', filmID)
                    val match = Regex("""AnimeVsub\s*\(\s*['"]([^'"]+)['"]\s*,""").find(content)
                    if (match != null) {
                        hash = match.groupValues[1]
                        log("Found hash from AnimeVsub() call")
                        break
                    }
                }
            }
        }

        // Cách 3: Episode đang active (class contains "active" or "playing")
        if (hash.isNullOrBlank()) {
            log("Looking for active episode...")
            hash = epDoc.selectFirst("a.episode-link.active[data-hash]")?.attr("data-hash")?.trim()
                ?: epDoc.selectFirst("a.btn-episode.active[data-hash]")?.attr("data-hash")?.trim()
                ?: epDoc.selectFirst("a.playing[data-hash]")?.attr("data-hash")?.trim()
            
            if (!hash.isNullOrBlank()) {
                playType = epDoc.selectFirst("a.active[data-hash]")?.attr("data-play")?.trim() ?: "api"
                log("Found hash from active episode")
            }
        }

        // Cách 4: Fallback - lấy hash đầu tiên
        if (hash.isNullOrBlank()) {
            log("Fallback to first hash found...")
            hash = epDoc.selectFirst("a[data-hash]")?.attr("data-hash")?.trim()
            playType = epDoc.selectFirst("a[data-hash]")?.attr("data-play")?.trim() ?: "api"
        }

        if (hash.isNullOrBlank()) {
            log("ERROR: No hash found!")
            return false
        }

        log("Hash: ${hash.take(50)}...")
        log("PlayType: $playType")
        log("FilmID: $filmId")

        // ═══════════════════════════════════════════════════════════════
        // BƯỚC 2: Gọi API
        // ═══════════════════════════════════════════════════════════════
        
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer"          to epUrl,
            "Origin"           to mainUrl,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // Thử API server
        log("─────────────────────────────────────────")
        log("Trying API server...")
        val apiParams = mapOf("link" to hash, "id" to filmId)
        log("Params: link=${hash.take(30)}..., id=$filmId")
        
        val apiResult = tryApiCall(apiParams, epUrl, ajaxHeaders, callback, subtitleCallback)
        if (apiResult) {
            log("SUCCESS with API server!")
            return true
        }

        // Thử Embed server
        log("─────────────────────────────────────────")
        log("Trying Embed server...")
        val embedParams = mapOf(
            "link" to hash,
            "play" to "embed",
            "id" to "3",
            "backuplinks" to "1"
        )
        log("Params: link=${hash.take(30)}..., play=embed, id=3")
        
        val embedResult = tryEmbedCall(embedParams, epUrl, ajaxHeaders, callback, subtitleCallback)
        if (embedResult) {
            log("SUCCESS with Embed server!")
            return true
        }

        // Thử tất cả servers từ HTML
        log("─────────────────────────────────────────")
        log("Trying all servers from HTML...")
        val servers = epDoc.select("a[data-hash][data-source]")
        log("Found ${servers.size} servers")
        
        for ((index, server) in servers.withIndex()) {
            val serverHash = server.attr("data-hash").trim()
            val serverPlay = server.attr("data-play").ifEmpty { "api" }.trim()
            val serverName = server.text().trim()
            
            if (serverHash == hash) continue // Đã thử rồi
            
            log("Server $index: $serverName (play=$serverPlay)")
            
            val params = when (serverPlay) {
                "api" -> mapOf("link" to serverHash, "id" to filmId)
                else -> mapOf("link" to serverHash, "play" to serverPlay, "id" to "3", "backuplinks" to "1")
            }
            
            val result = when (serverPlay) {
                "api" -> tryApiCall(params, epUrl, ajaxHeaders, callback, subtitleCallback)
                else -> tryEmbedCall(params, epUrl, ajaxHeaders, callback, subtitleCallback)
            }
            
            if (result) {
                log("SUCCESS with server: $serverName")
                return true
            }
        }

        log("All methods failed!")
        return false
    }

    private suspend fun tryApiCall(
        params: Map<String, String>,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return try {
            val response = app.post(
                "$mainUrl/ajax/player",
                data        = params,
                interceptor = cfKiller,
                referer     = referer,
                headers     = headers
            )
            
            val respText = response.text.trim()
            log("Response: ${respText.take(200)}...")
            
            if (respText.isBlank() || respText.contains("Không tải được") || respText.startsWith("<")) {
                log("Invalid response")
                return false
            }
            
            val parsed = response.parsedSafe<PlayerResponse>()
            log("Parsed: fxStatus=${parsed?.fxStatus}, playTech=${parsed?.playTech}")
            
            if (parsed?.fxStatus != 1 || parsed.playTech != "api") {
                return false
            }
            
            val encryptedFile = parsed.linkArray
                ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            
            if (encryptedFile == null) {
                log("No encrypted file found")
                return false
            }
            
            log("Decrypting... length: ${encryptedFile.length}")
            val decrypted = decryptLink(encryptedFile)
            
            if (decrypted == null) {
                log("Decrypt failed")
                return false
            }
            
            log("Decrypted: ${decrypted.take(100)}...")
            handleDecryptedLink(decrypted, referer, callback, subtitleCallback)
            
        } catch (e: Exception) {
            log("API call error: ${e.message}")
            false
        }
    }

    private suspend fun tryEmbedCall(
        params: Map<String, String>,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return try {
            val response = app.post(
                "$mainUrl/ajax/player",
                data        = params,
                interceptor = cfKiller,
                referer     = referer,
                headers     = headers
            )
            
            val respText = response.text.trim()
            log("Response: ${respText.take(200)}...")
            
            if (respText.isBlank() || respText.contains("Không tải được")) {
                return false
            }
            
            val parsed = response.parsedSafe<PlayerResponse>()
            log("Parsed: fxStatus=${parsed?.fxStatus}, playTech=${parsed?.playTech}")
            
            if (parsed?.fxStatus != 1 || parsed.playTech != "embed") {
                return false
            }
            
            val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
            
            if (directUrl == null) {
                log("No embed URL found")
                return false
            }
            
            log("Embed URL: $directUrl")
            followShortLink(directUrl, referer, callback, subtitleCallback)
            
        } catch (e: Exception) {
            log("Embed call error: ${e.message}")
            false
        }
    }

    private suspend fun followShortLink(
        shortUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        log("Following: $shortUrl")
        
        return try {
            val response = app.get(
                shortUrl,
                interceptor = cfKiller,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml"
                )
            )
            
            val finalUrl = response.url
            log("Final URL: $finalUrl")
            
            if (finalUrl.contains("abyss") || response.text.contains("abyss")) {
                extractAbyss(response.text, finalUrl, callback, subtitleCallback)
            } else {
                loadExtractor(shortUrl, referer, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            log("Short link error: ${e.message}")
            runCatching { loadExtractor(shortUrl, referer, subtitleCallback, callback) }.getOrDefault(false)
        }
    }

    private suspend fun extractAbyss(
        body: String,
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        log("Extracting Abyss from: $url")
        
        val patterns = listOf(
            Regex("""file\s*:\s*['"](https?://[^'"]+)['"]"""),
            Regex("""source\s*:\s*['"](https?://[^'"]+)['"]"""),
            Regex("""['"](https?://storage\.googleapis\.com[^'"]+)['"]"""),
            Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""")
        )
        
        for (pattern in patterns) {
            pattern.find(body)?.let { match ->
                val videoUrl = match.groupValues[1]
                log("Found video: $videoUrl")
                
                callback.invoke(newExtractorLink(name, name, videoUrl) {
                    this.referer = "https://abysscdn.com/"
                    this.quality = Qualities.Unknown.value
                    this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                })
                return true
            }
        }
        
        runCatching { loadExtractor(url, "https://abysscdn.com/", subtitleCallback, callback) }
        return true
    }

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
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    if (url.contains(".m3u8"))
                        M3u8Helper.generateM3u8(name, url.trim(), referer).forEach(callback)
                    else
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                }
                true
            }
            else -> false
        }
    }

    private fun parseM3u8Content(content: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val lines = content.lines()
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
                lines.getOrNull(i + 1)?.trim()?.takeIf { it.startsWith("http") }?.let { urlLine ->
                    callback.invoke(newExtractorLink(name, name, urlLine) {
                        this.referer = referer
                        this.quality = q
                        this.type = ExtractorLinkType.M3U8
                    })
                }
            }
        }
    }

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
