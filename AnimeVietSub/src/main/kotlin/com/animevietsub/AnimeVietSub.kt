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
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
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
    // Data format: "epUrl@@filmId@@episodeId@@hash@@dataPlay"
    // 
    // QUAN TRỌNG: Extract data-hash từ episode links trực tiếp thay vì gọi API
    // Flow đúng: 
    //   1. Trang HTML có sẵn data-hash trong link tập phim
    //   2. JavaScript gọi AnimeVsub(hash, filmID)
    //   3. POST /ajax/player với link=<hash>&id=<filmId>
    // ──────────────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id], .list-episode a[data-id]")

        // Nếu không có episode list, thử vào trang tập đầu tiên
        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()?.also { doc2 ->
                    episodesNodes = doc2.select("ul.list-episode li a[data-id], .list-episode a[data-id]")
                    if (episodesNodes.isNotEmpty()) document = doc2
                }
            }
        }

        // filmId từ URL: ".../a5591/..." → "5591"
        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val episodesList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            
            // *** FIX QUAN TRỌNG: Extract data-hash trực tiếp từ episode link ***
            val hash      = ep.attr("data-hash").trim()
            val dataPlay  = ep.attr("data-play").ifEmpty { "api" }.trim()

            if (episodeId.isEmpty() || epUrl.isEmpty() || hash.isEmpty()) return@mapNotNull null

            // Format: epUrl@@filmId@@episodeId@@hash@@dataPlay
            newEpisode("$epUrl@@$filmId@@$episodeId@@$hash@@$dataPlay") {
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
    // 
    // API flow đã xác nhận từ debug files:
    // 
    // 1. Server DU (api): 
    //    POST /ajax/player với payload: link=<hash>&id=<filmId>
    //    Response: {"link":[{"file":"<base64_encrypted>"}],"playTech":"api"}
    //    → Decrypt base64 để lấy m3u8 URL
    // 
    // 2. Server HDX (embed):
    //    POST /ajax/player với payload: link=<hash>&play=embed&id=<serverId>&backuplinks=1
    //    Response: {"link":"https://short.icu/...","playTech":"embed"}
    //    → Follow redirect chain: short.icu → abyss.to → video URL
    // 
    // ──────────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 4) return false

        val epUrl     = parts[0]  // URL trang tập (Referer)
        val filmId    = parts[1]  // Film ID (5591)
        val episodeId = parts[2]  // Episode ID (111225)
        val hash      = parts[3]  // data-hash từ episode link
        val dataPlay  = if (parts.size > 4) parts[4] else "api"  // "api" hoặc "embed"

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        // ═══════════════════════════════════════════════════════════════════════
        // GỌI API TRỰC TIẾP VỚI HASH - không cần bước lấy server selection
        // ═══════════════════════════════════════════════════════════════════════
        
        // Thử cả server DU (api) và HDX (embed) để có fallback
        val servers = listOf(
            // Server DU (api) - không quảng cáo, xem ngay
            Pair("api", mapOf("link" to hash, "id" to filmId)),
            // Server HDX (embed) - có quảng cáo nhưng fallback
            Pair("embed", mapOf("link" to hash, "play" to "embed", "id" to "3", "backuplinks" to "1"))
        )

        for ((serverType, params) in servers) {
            val response = runCatching {
                app.post(
                    "$mainUrl/ajax/player",
                    data        = params,
                    interceptor = cfKiller,
                    referer     = epUrl,
                    headers     = ajaxHeaders
                )
            }.getOrNull() ?: continue

            val respText = response.text.trim()
            if (respText.isBlank() || respText.startsWith("<") || respText.contains("Không tải được")) {
                continue
            }

            val parsed = response.parsedSafe<PlayerResponse>() ?: continue

            when (parsed.playTech ?: serverType) {
                // ── API server (DU): link là array [{file: "<base64_encrypted>"}]
                "api" -> {
                    val encryptedFile = parsed.linkArray
                        ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                        ?: continue
                    
                    val decrypted = decryptLink(encryptedFile) ?: continue
                    if (handleDecryptedLink(decrypted, epUrl, callback, subtitleCallback, "DU")) {
                        return true  // Thành công với server DU
                    }
                }
                // ── Embed server (HDX): link là string URL
                "embed" -> {
                    val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                        ?: continue
                    
                    // Follow short link redirects
                    if (handleEmbedLink(directUrl, epUrl, callback, subtitleCallback, "HDX")) {
                        // Tiếp tục thử server DU nếu HDX thành công
                        // (không return ngay để có cả 2 server)
                    }
                }
            }
        }

        return false
    }

    // ── Handle decrypted m3u8 URL hoặc content ────────────────────────────────
    private suspend fun handleDecryptedLink(
        decrypted: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        serverName: String = "DU"
    ): Boolean {
        val link = decrypted.trim()
        return when {
            link.contains(".m3u8") && link.startsWith("http") -> {
                M3u8Helper.generateM3u8("$name - $serverName", link, referer).forEach(callback)
                true
            }
            link.startsWith("#EXTM3U") -> {
                parseM3u8Content(link, referer, callback, serverName)
                true
            }
            link.startsWith("http") -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                true
            }
            link.contains("\n") -> {
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    if (url.contains(".m3u8"))
                        M3u8Helper.generateM3u8("$name - $serverName", url.trim(), referer).forEach(callback)
                    else
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                }
                true
            }
            else -> false
        }
    }

    private suspend fun parseM3u8Content(
        content: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit,
        serverName: String = "DU"
    ) {
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
                        callback.invoke(newExtractorLink(name, "$name - $serverName", urlLine) {
                            this.referer = referer
                            this.quality = q
                            this.type    = ExtractorLinkType.M3U8
                        })
                    }
                }
            }
        }
    }

    // ── Handle embed links (short.icu → abyss.to → video) ─────────────────────
    private suspend fun handleEmbedLink(
        shortUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        serverName: String = "HDX"
    ): Boolean {
        return try {
            // Follow redirects từ short link
            val response = app.get(
                shortUrl,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to referer
                )
            )
            
            // Check redirect location
            val location = response.headers["location"] ?: response.headers["Location"]
            if (!location.isNullOrEmpty()) {
                // short.icu → abyss.to hoặc abysscdn.com
                if (location.contains("abyss")) {
                    loadExtractor(location, referer, subtitleCallback, callback)
                    return true
                }
                // Recursive follow nếu vẫn là short link
                if (location.contains("short.icu") || location.contains("short.")) {
                    return handleEmbedLink(location, referer, callback, subtitleCallback, serverName)
                }
            }
            
            // Parse HTML response để tìm video URL
            val doc = response.document
            val videoUrl = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("iframe")?.attr("src")
                ?: doc.selectFirst("[src*='.m3u8']")?.attr("src")
                ?: doc.selectFirst("[src*='.mp4']")?.attr("src")
            
            if (!videoUrl.isNullOrEmpty()) {
                val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                if (finalUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8("$name - $serverName", finalUrl, "https://abysscdn.com/").forEach(callback)
                } else {
                    loadExtractor(finalUrl, referer, subtitleCallback, callback)
                }
                return true
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
