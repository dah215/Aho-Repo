package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
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

    // ── AES-256-CBC + pako.inflateRaw ─────────────────────────────────────────
    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned   = aes.replace(Regex("\\s"), "")
            val key       = MessageDigest.getInstance("SHA-256")
                                .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded   = Base64.decode(cleaned, Base64.URL_SAFE)
            val iv        = decoded.copyOfRange(0, 16)
            val ct        = decoded.copyOfRange(16, decoded.size)
            val cipher    = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            val inflated  = pakoInflateRaw(plain)
            String(inflated, StandardCharsets.UTF_8)
                .replace("\\n", "\n")
                .replace("\"", "")
                .trim()
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
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    // ── Search / MainPage ──────────────────────────────────────────────────────

    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = selectFirst("a")?.attr("href")            ?: return null
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url   = if (page == 1) request.data else "${request.data}trang-$page.html"
        val items = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select(".TPostMv, .TPost").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select(".TPostMv, .TPost").mapNotNull { it.toSearchResponse() }
    }

    // ── Load ───────────────────────────────────────────────────────────────────
    // Lưu format: epPageUrl@@epId
    // epPageUrl = URL trang XEM tập (dùng làm Referer và để lấy data-id)
    // epId = data-id của episode (dùng cho bước 1 AJAX)

    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        // Nếu film page không có episodes → thử load trang tập đầu tiên
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

        val episodesList = episodesNodes.mapNotNull { ep ->
            val epId   = ep.attr("data-id").trim()
            val epName = ep.attr("title").ifEmpty { ep.text().trim() }
            // URL trang tập = Referer cho AJAX call
            val epUrl  = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (epId.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            // Chỉ lưu: epUrl@@epId
            newEpisode("$epUrl@@$epId") {
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
                mutableMapOf(DubStatus.Subbed to episodesList) else mutableMapOf()
        }
    }

    // ── loadLinks ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 2) return false

        val epUrl = parts[0]  // URL trang tập (Referer)
        val epId  = parts[1]  // data-id của tập

        // ════════════════════════════════════════════════════════════════════════
        // BƯỚC 1: GET trang tập để CF cookies được set
        // ════════════════════════════════════════════════════════════════════════
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // ════════════════════════════════════════════════════════════════════════
        // BƯỚC 2: POST /ajax/player với episodeId để lấy server selection HTML
        // Xác nhận từ network: POST /ajax/player, body: episodeId=111603&backup=1
        // Response: {"success":1,"html":"<a class=\"btn3dsv\" data-href=\"HASH\">..."}
        // ════════════════════════════════════════════════════════════════════════
        var freshHash: String? = null
        var freshPlay: String  = "api"

        for (backup in listOf("1", "0")) {
            val resp1 = runCatching {
                app.post(
                    "$mainUrl/ajax/player",
                    data        = mapOf("episodeId" to epId, "backup" to backup),
                    interceptor = cfKiller,
                    referer     = epUrl,
                    headers     = ajaxHeaders
                ).parsedSafe<ServerSelectionResponse>()
            }.getOrNull()

            val htmlContent = resp1?.html ?: continue

            // Parse HTML bên trong JSON để lấy data-href và data-play
            val serverDoc  = Jsoup.parse(htmlContent)
            // Ưu tiên: server đang active, loại "api" (không phải embed/ads)
            val apiServer  = serverDoc.selectFirst("a.btn3dsv[data-play=api]")
                ?: serverDoc.selectFirst("a.btn3dsv")

            freshHash = apiServer?.attr("data-href")?.takeIf { it.isNotBlank() }
            freshPlay = apiServer?.attr("data-play")?.takeIf { it.isNotBlank() } ?: "api"

            if (!freshHash.isNullOrBlank()) break
        }

        if (freshHash.isNullOrBlank()) return false

        // ════════════════════════════════════════════════════════════════════════
        // BƯỚC 3: POST /ajax/player?v=2019a để lấy encrypted link
        // Params: id=episodeId, play=data-play, link=data-href từ bước 2
        // ════════════════════════════════════════════════════════════════════════
        val resp2 = runCatching {
            app.post(
                "$mainUrl/ajax/player?v=2019a",
                data        = mapOf("id" to epId, "play" to freshPlay, "link" to freshHash),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            ).parsedSafe<PlayerResponse>()
        }.getOrNull()

        val encryptedFile = resp2?.link?.firstNotNullOfOrNull {
            it.file?.takeIf(String::isNotBlank)
        } ?: return false

        // ════════════════════════════════════════════════════════════════════════
        // BƯỚC 4: Decrypt AES-256-CBC + pako.inflateRaw
        // ════════════════════════════════════════════════════════════════════════
        val decrypted = decryptLink(encryptedFile) ?: return false

        return handleDecryptedContent(decrypted, epUrl, callback, subtitleCallback)
    }

    // ── Handle decrypted content ───────────────────────────────────────────────

    private suspend fun handleDecryptedContent(
        content: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val lines = content.trim().lines().map { it.trim() }.filter { it.isNotBlank() }

        return when {
            // Đây là URL đơn
            content.startsWith("http") && !content.contains("\n") -> {
                val url = content.trim()
                when {
                    url.contains(".m3u8") ->
                        M3u8Helper.generateM3u8(name, url, referer).forEach(callback)
                    else ->
                        loadExtractor(url, referer, subtitleCallback, callback)
                }
                true
            }

            // Đây là nội dung m3u8 thực sự (bắt đầu bằng #EXTM3U)
            content.trimStart().startsWith("#EXTM3U") -> {
                parseAndCallbackM3u8(content, referer, callback)
                true
            }

            // Thử parse từng dòng
            lines.any { it.startsWith("http") } -> {
                lines.filter { it.startsWith("http") }.forEach { url ->
                    when {
                        url.contains(".m3u8") ->
                            M3u8Helper.generateM3u8(name, url, referer).forEach(callback)
                        else ->
                            loadExtractor(url, referer, subtitleCallback, callback)
                    }
                }
                true
            }

            else -> false
        }
    }

    // Parse m3u8 text content → ExtractorLinks
    private fun parseAndCallbackM3u8(m3u8Content: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val lines = m3u8Content.lines()
        var quality = Qualities.Unknown.value

        // Master playlist: tìm EXT-X-STREAM-INF
        if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    quality = when {
                        bw == null          -> Qualities.Unknown.value
                        bw >= 4_000_000     -> Qualities.P1080.value
                        bw >= 2_000_000     -> Qualities.P720.value
                        bw >= 1_000_000     -> Qualities.P480.value
                        else                -> Qualities.P360.value
                    }
                    val urlLine = lines.getOrNull(i + 1)?.trim() ?: return@forEachIndexed
                    if (urlLine.startsWith("http")) {
                        callback.invoke(
                            ExtractorLink(name, name, urlLine, referer,
                                quality, isM3u8 = true)
                        )
                    }
                }
            }
        } else {
            // Đây là media playlist (segment list) - không có URL dùng được trực tiếp
            // Thử lấy segment đầu làm fallback
            val firstSegment = lines.firstOrNull { it.startsWith("http") }
            if (firstSegment != null) {
                callback.invoke(
                    ExtractorLink(name, name, firstSegment, referer,
                        Qualities.Unknown.value, isM3u8 = false)
                )
            }
        }
    }

    // ── Data classes ───────────────────────────────────────────────────────────

    // Bước 2: /ajax/player response
    data class ServerSelectionResponse(
        @JsonProperty("success") val success: Int?    = null,
        @JsonProperty("html")    val html: String?    = null
    )

    // Bước 3: /ajax/player?v=2019a response
    data class PlayerResponse(
        @JsonProperty("success") val success: Int?        = null,
        @JsonProperty("link")    val link: List<LinkItem> = emptyList()
    )

    data class LinkItem(
        @JsonProperty("file") val file: String? = null
    )
}
