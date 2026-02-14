package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(cleaned, Base64.URL_SAFE)
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val inflated = pakoInflateRaw(plain)
            String(inflated, StandardCharsets.UTF_8)
                .replace("\\n", "\n").replace("\"", "").trim()
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

    // ── SearchResponse helper ──────────────────────────────────────────────────
    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = selectFirst("a")?.attr("href")            ?: return null
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        val epText   = selectFirst(".mli-eps i, .mli-eps, .Epnum, .ep-count")?.text()?.trim() ?: ""
        val qualText = selectFirst(".mli-quality span, .Quality span, .Quality")?.text()?.trim()?.lowercase() ?: ""

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            Regex("\\d+").find(epText)?.value?.toIntOrNull()?.let { addSub(it) }
            quality = when {
                qualText.contains("hd")       -> SearchQuality.HD
                qualText.contains("vietsub")  -> SearchQuality.HD
                qualText.contains("cam")      -> SearchQuality.Cam
                else                          -> null
            }
        }
    }

    // ── MainPage & Search ──────────────────────────────────────────────────────
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

    // ── Load ───────────────────────────────────────────────────────────────────
    // Data format lưu vào episode: "epUrl@@filmId@@epHash"
    // ├─ epUrl:  URL trang TẬP (dùng làm Referer)
    // ├─ filmId: ID phim từ URL (VD: a5820 → 5820) — dùng trong POST /ajax/player
    // └─ epHash: data-hash của tập — dùng làm param "link"
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

        // Trích filmId từ URL: "...a5820..." → "5820"
        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val episodesList = episodesNodes.mapNotNull { ep ->
            val epHash = ep.attr("data-hash").trim()
            val epName = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl  = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (epHash.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            newEpisode("$epUrl@@$filmId@@$epHash") {
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

    // ── loadLinks ──────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Format: epUrl@@filmId@@epHash
        val parts = data.split("@@")
        if (parts.size < 3) return false

        val epUrl  = parts[0]  // URL trang tập (Referer)
        val filmId = parts[1]  // Film ID (VD: 5820) — từ URL "a5820"
        val epHash = parts[2]  // data-hash của tập

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        // ════════════════════════════════════════════════════════════════════════
        // POST /ajax/player với link=data-hash & id=filmId
        //
        // ✅ XÁC NHẬN TỪ NETWORK THỰC TẾ:
        //    Payload: link=7tEBSpMg...&id=5820
        //    id = FILM ID (5820), KHÔNG phải episode ID (111047)!
        //    Size response: 39.3kB → chứa encrypted m3u8
        // ════════════════════════════════════════════════════════════════════════
        val resp = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = mapOf("link" to epHash, "id" to filmId),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull() ?: return false

        val respText = resp.text.trim()
        if (respText.isBlank() || respText.startsWith("<")) return false

        // Parse encrypted file từ response
        // Format: {"success":1,"link":[{"file":"<AES_ENCRYPTED>"}]}
        val encryptedFile =
            resp.parsedSafe<PlayerResponse>()?.link
                ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            ?: resp.parsedSafe<AjaxResponse>()?.data?.takeIf(String::isNotBlank)
            ?: return false

        // Decrypt AES-256-CBC + pako.inflateRaw → URL thực
        val decrypted = decryptLink(encryptedFile) ?: return false

        return handleDecryptedContent(decrypted, epUrl, callback, subtitleCallback)
    }

    // ── Handle kết quả sau decrypt ────────────────────────────────────────────
    private suspend fun handleDecryptedContent(
        content: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = content.trim()
        return when {
            link.startsWith("#EXTM3U") -> {
                parseAndCallbackM3u8(link, referer, callback)
                true
            }
            link.contains(".m3u8") && link.startsWith("http") -> {
                M3u8Helper.generateM3u8(name, link, referer).forEach(callback)
                true
            }
            link.startsWith("http") -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                true
            }
            // Nếu có nhiều dòng, lọc lấy dòng URL
            link.contains("\n") -> {
                link.lines().map { it.trim() }.filter { it.startsWith("http") }
                    .forEach { url ->
                        if (url.contains(".m3u8"))
                            M3u8Helper.generateM3u8(name, url, referer).forEach(callback)
                        else
                            loadExtractor(url, referer, subtitleCallback, callback)
                    }
                true
            }
            else -> false
        }
    }

    private suspend fun parseAndCallbackM3u8(
        m3u8Content: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val lines = m3u8Content.lines()
        var quality = Qualities.Unknown.value
        if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    quality = when {
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
                            this.quality = quality
                            this.type    = ExtractorLinkType.M3U8
                        })
                    }
                }
            }
        } else {
            lines.firstOrNull { it.startsWith("http") }?.let { seg ->
                callback.invoke(newExtractorLink(name, name, seg) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.type    = ExtractorLinkType.VIDEO
                })
            }
        }
    }

    // ── Data classes ───────────────────────────────────────────────────────────
    data class PlayerResponse(
        @JsonProperty("success") val success: Int?        = null,
        @JsonProperty("link")    val link: List<LinkItem> = emptyList()
    )
    data class LinkItem(
        @JsonProperty("file") val file: String? = null
    )
    data class AjaxResponse(
        @JsonProperty("status") val status: Int?  = null,
        @JsonProperty("data")   val data: String? = null
    )
}
