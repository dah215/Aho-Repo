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

/**
 * AnimeVietSub CloudStream3 Plugin - v12
 * 
 * Changelog v12:
 * - FIX QUAN TRỌNG: Lấy hash trực tiếp từ data-hash attribute trong HTML
 * - Không cần gọi Step 1 (/ajax/player với episodeId) vì hash đã có sẵn
 * - Gọi trực tiếp /ajax/player với link=HASH&id=FILM_ID
 * - Hỗ trợ cả api server (encrypted) và embed server (short link)
 * - Base64 dùng STANDARD (có + và /), không phải URL_SAFE
 */

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
    // 
    // Format: IV (16 bytes) + Ciphertext
    // Base64: STANDARD (có + và /), KHÔNG phải URL_SAFE
    // ─────────────────────────────────────────────────────────────────────────
    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            // STANDARD BASE64 - có + và / trong response
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            
            if (decoded.size < 16) return null
            
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            println("[AnimeVietSub] Decrypt failed: ${e.message}")
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

    // ── Load ──────────────────────────────────────────────────────────────────
    // Data format: "epUrl@@filmId@@hash@@playType"
    // epUrl     = URL trang xem tập (Referer)
    // filmId    = ID phim từ URL (a5820 → 5820)
    // hash      = data-hash từ episode link
    // playType  = data-play từ episode link (api/embed)
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

        // filmId từ URL: ".../a5820/..." → "5820"
        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val episodesList = episodesNodes.mapNotNull { ep ->
            // ═══════════════════════════════════════════════════════════════════
            // FIX v12: Lấy hash trực tiếp từ data-hash attribute
            // Đây là cách website hoạt động: AnimeVsub(hash, filmID)
            // ═══════════════════════════════════════════════════════════════════
            val hash     = ep.attr("data-hash").trim()
            val playType = ep.attr("data-play").ifEmpty { "api" }.trim()
            val epName   = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl    = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (hash.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            newEpisode("$epUrl@@$filmId@@$hash@@$playType") {
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

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 4) return false

        val epUrl    = parts[0]  // URL trang tập (Referer)
        val filmId   = parts[1]  // Film ID (5820)
        val hash     = parts[2]  // data-hash từ episode link
        val playType = parts[3]  // data-play (api/embed)

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        // ═══════════════════════════════════════════════════════════════════════
        // FIX v12: Gọi trực tiếp /ajax/player với hash đã lấy từ HTML
        // 
        // API server (playType = "api"):
        //   Payload: link=HASH&id=FILM_ID
        //   Response: {"link":[{"file":"base64_encrypted..."}], "playTech":"api"}
        //
        // Embed server (playType = "embed"):
        //   Payload: link=HASH&play=embed&id=SERVER_ID&backuplinks=1
        //   Response: {"link":"https://short.icu/...","playTech":"embed"}
        // ═══════════════════════════════════════════════════════════════════════
        
        println("[AnimeVietSub] Loading links - filmId: $filmId, playType: $playType, hash length: ${hash.length}")

        // Thử nhiều server types
        val serversToTry = mutableListOf<Pair<String, Map<String, String>>>()
        
        // Ưu tiên api server trước (DU)
        if (playType == "api") {
            serversToTry.add("api" to mapOf("link" to hash, "id" to filmId))
        }
        
        // Thử embed server
        serversToTry.add("embed" to mapOf(
            "link" to hash, 
            "play" to "embed", 
            "id" to "3",  // Server ID mặc định
            "backuplinks" to "1"
        ))
        
        // Nếu không phải api, thử playType gốc
        if (playType != "api" && playType.isNotEmpty()) {
            serversToTry.add(playType to mapOf(
                "link" to hash, 
                "play" to playType, 
                "id" to filmId,
                "backuplinks" to "1"
            ))
        }

        for ((serverName, params) in serversToTry) {
            println("[AnimeVietSub] Trying server: $serverName with params: ${params.keys}")
            
            val response = runCatching {
                app.post(
                    "$mainUrl/ajax/player",
                    data        = params,
                    interceptor = cfKiller,
                    referer     = epUrl,
                    headers     = ajaxHeaders
                )
            }.getOrNull()
            
            if (response == null) {
                println("[AnimeVietSub] Response null for server: $serverName")
                continue
            }
            
            val respText = response.text.trim()
            println("[AnimeVietSub] Response: ${respText.take(200)}...")
            
            if (respText.isBlank() || respText.startsWith("<") || respText.contains("Không tải được")) {
                println("[AnimeVietSub] Invalid response for server: $serverName")
                continue
            }
            
            val parsed = response.parsedSafe<PlayerResponse>()
            if (parsed == null || parsed.fxStatus != 1) {
                println("[AnimeVietSub] Parse failed or fxStatus != 1 for server: $serverName")
                continue
            }
            
            println("[AnimeVietSub] playTech: ${parsed.playTech}")
            
            when (parsed.playTech) {
                "api" -> {
                    val encryptedFile = parsed.linkArray
                        ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                    
                    if (encryptedFile != null) {
                        println("[AnimeVietSub] Decrypting file, length: ${encryptedFile.length}")
                        val decrypted = decryptLink(encryptedFile)
                        if (decrypted != null) {
                            println("[AnimeVietSub] Decrypted successfully: ${decrypted.take(100)}...")
                            if (handleDecryptedLink(decrypted, epUrl, callback, subtitleCallback)) {
                                return true
                            }
                        }
                    }
                }
                "embed" -> {
                    val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                    if (directUrl != null) {
                        println("[AnimeVietSub] Embed URL: $directUrl")
                        // Follow short link redirect
                        if (followShortLink(directUrl, epUrl, callback, subtitleCallback)) {
                            return true
                        }
                    }
                }
            }
        }
        
        return false
    }

    // ── Follow short link redirect (short.icu → abyss.to → video) ─────────────
    private suspend fun followShortLink(
        shortUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("[AnimeVietSub] Following short link: $shortUrl")
        
        return try {
            // Follow redirect chain
            val response = app.get(
                shortUrl, 
                interceptor = cfKiller,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to referer
                ),
                allowRedirects = false
            )
            
            // Check for redirect
            val location = response.headers["location"]
            if (!location.isNullOrEmpty()) {
                println("[AnimeVietSub] Redirect to: $location")
                
                // Check if it's an Abyss.to link
                if (location.contains("abyss")) {
                    return extractAbyssVideo(location, callback, subtitleCallback)
                }
                
                // Try to load as extractor
                loadExtractor(location, referer, subtitleCallback, callback)
                return true
            }
            
            // Try to extract from response body
            val body = response.text
            println("[AnimeVietSub] Response body preview: ${body.take(500)}")
            
            // Look for video URL in body
            val videoPatterns = listOf(
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://storage\.googleapis\.com[^'"]+)['"]"""),
                Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]"""),
                Regex("""src=['"](https?://[^'"]+)['"]""")
            )
            
            for (pattern in videoPatterns) {
                val match = pattern.find(body)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    println("[AnimeVietSub] Found video URL: $videoUrl")
                    callback.invoke(newExtractorLink(name, name, videoUrl) {
                        this.referer = "https://abysscdn.com/"
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    return true
                }
            }
            
            // Try loading with extractor as last resort
            loadExtractor(shortUrl, referer, subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("[AnimeVietSub] Error following short link: ${e.message}")
            e.printStackTrace()
            // Try direct extraction
            runCatching { loadExtractor(shortUrl, referer, subtitleCallback, callback) }.getOrDefault(false)
        }
    }

    // ── Extract video from Abyss.to ───────────────────────────────────────────
    private suspend fun extractAbyssVideo(
        abyssUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        println("[AnimeVietSub] Extracting Abyss URL: $abyssUrl")
        
        return try {
            val response = app.get(
                abyssUrl,
                interceptor = cfKiller,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            val body = response.text
            
            // Look for video source
            val patterns = listOf(
                Regex("""file\s*:\s*['"](https?://[^'"]+)['"]"""),
                Regex("""source\s*:\s*['"](https?://[^'"]+)['"]"""),
                Regex("""src\s*=\s*['"](https?://[^'"]+)['"]"""),
                Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://storage\.googleapis\.com[^'"]+)['"]""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(body)
                if (match != null) {
                    val videoUrl = match.groupValues[1]
                    println("[AnimeVietSub] Found Abyss video: $videoUrl")
                    callback.invoke(newExtractorLink(name, name, videoUrl) {
                        this.referer = "https://abysscdn.com/"
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    })
                    return true
                }
            }
            
            // Try loadExtractor
            loadExtractor(abyssUrl, "https://abysscdn.com/", subtitleCallback, callback)
            
        } catch (e: Exception) {
            println("[AnimeVietSub] Error extracting Abyss: ${e.message}")
            runCatching { loadExtractor(abyssUrl, "https://abysscdn.com/", subtitleCallback, callback) }.getOrDefault(false)
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
        println("[AnimeVietSub] Handling decrypted link type: ${when {
            link.contains(".m3u8") -> "m3u8"
            link.startsWith("#EXTM3U") -> "m3u8_content"
            link.startsWith("http") -> "url"
            else -> "unknown"
        }}")
        
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

    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
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
