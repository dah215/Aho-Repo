package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
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

    // ── Giải mã AES-256-CBC + pako.inflateRaw ─────────────────────────────────
    private fun decryptData(aesData: String): String? {
        return try {
            // Xóa khoảng trắng, newline thừa trước khi decode
            val cleaned = aesData.replace(Regex("[\\n\\r\\t ]"), "")

            val digest    = MessageDigest.getInstance("SHA-256")
            val key       = digest.digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val secretKey = SecretKeySpec(key, "AES")

            // FIX: Thử URL_SAFE base64 trước (hash trên site dùng - và _)
            // Nếu fail thì fallback sang DEFAULT
            val decoded = tryBase64Decode(cleaned) ?: return null

            if (decoded.size <= 16) return null
            val iv        = decoded.copyOfRange(0, 16)
            val ct        = decoded.copyOfRange(16, decoded.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)

            // FIX: Nếu inflate thất bại (data không bị nén), trả raw string
            val result = try {
                String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                String(plain, StandardCharsets.UTF_8)
            }

            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun tryBase64Decode(s: String): ByteArray? {
        // Thử URL_SAFE trước
        runCatching { Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Fallback DEFAULT
        runCatching { Base64.decode(s, Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buffer = ByteArray(8192)
        val out    = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) break
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    // ── Search / MainPage ──────────────────────────────────────────────────────

    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = this.selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = this.selectFirst("a")?.attr("href")            ?: return null
        val poster = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        val epText = this.select(".mli-eps, .Tag, .label, .Status, .Quality").text().trim()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            if (epText.isNotEmpty()) {
                Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { n -> this.addSub(n) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url   = if (page == 1) request.data else "${request.data}trang-$page.html"
        val doc   = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        val items = doc.select(".TPostMv, .TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url.trimEnd('/') }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select(".TPostMv, .TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url.trimEnd('/') }
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-'], a.btn-episode, a.episode-link")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                val doc2 = runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()
                if (doc2 != null) {
                    episodesNodes = doc2.select("ul.list-episode li a[data-id]")
                    if (episodesNodes.isNotEmpty()) document = doc2
                }
            }
        }

        val episodesList = episodesNodes.mapNotNull { ep ->
            val epId   = ep.attr("data-id").trim()
            val epHash = ep.attr("data-hash").trim()
            val epPlay = ep.attr("data-play").ifEmpty { "api" }
            val epSrc  = ep.attr("data-source").ifEmpty { "du" }
            val epName = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl  = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (epId.isEmpty() || epHash.isEmpty()) return@mapNotNull null

            // Lưu đủ: epUrl@@epHash@@epId@@epSrc@@epPlay
            // Dùng @@ tránh xung đột với URL có dấu |
            newEpisode("$epUrl@@$epHash@@$epId@@$epSrc@@$epPlay") {
                this.name    = epName
                this.episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = document.selectFirst(".Image img, .InfoImg img, .TPost .Image img")
                ?.let { img -> img.attr("data-src").ifEmpty { img.attr("src") } }
            this.plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")
                ?.text()?.trim()
            val epMap = mutableMapOf<DubStatus, List<Episode>>()
            if (episodesList.isNotEmpty()) epMap[DubStatus.Subbed] = episodesList
            this.episodes = epMap
        }
    }

    // ── loadLinks ──────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Format: epUrl@@hash@@epId@@source@@play
        val parts = data.split("@@")
        if (parts.size < 5) return false

        val epUrl  = parts[0]  // URL trang tập — làm Referer
        val hash   = parts[1]  // data-hash
        val epId   = parts[2]  // data-id (episodeID)
        val source = parts[3]  // data-source ("du")
        val play   = parts[4]  // data-play ("api")

        // Warm up CF cookies bằng cách GET trang tập trước
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // ══ ENDPOINT 1: /ajax/all ══════════════════════════════════════════════
        // Xác nhận từ HTML: var AjaxURL = 'https://animevietsub.ee/ajax/all'
        // Đây là endpoint thực tế JS của site đang dùng
        //
        // FIX QUAN TRỌNG: Dùng parsedSafe<T>() của CloudStream thay vì
        // mapper.readValue<T>() vì server trả "status": 1 (Int) không phải Boolean
        // → Jackson strict sẽ throw exception → runCatching trả null → fail im lặng
        val resp1 = runCatching {
            app.post(
                "$mainUrl/ajax/all",
                data        = mapOf(
                    "action"     to "get_episodes_player",
                    "episode_id" to epId,
                    "server"     to source,
                    "hash"       to hash
                ),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull()

        // Parse với parsedSafe — CloudStream's mapper xử lý Int/Bool linh hoạt hơn
        resp1?.parsedSafe<AjaxResponse>()?.data?.let { enc ->
            if (enc.isNotBlank()) {
                val dec = decryptData(enc)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }
        }

        // Fallback: thử parse thẳng field "link" nếu response format khác
        resp1?.parsedSafe<PlayerResponse>()?.link
            ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            ?.let { enc ->
                val dec = decryptData(enc)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }

        // ══ ENDPOINT 2: /ajax/player?v=2019a ══════════════════════════════════
        // Endpoint cũ từ reference code — thử như fallback
        val resp2 = runCatching {
            app.post(
                "$mainUrl/ajax/player?v=2019a",
                data        = mapOf("id" to epId, "play" to play, "link" to hash),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull()

        resp2?.parsedSafe<PlayerResponse>()?.link
            ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            ?.let { enc ->
                val dec = decryptData(enc)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }

        resp2?.parsedSafe<AjaxResponse>()?.data?.let { enc ->
            if (enc.isNotBlank()) {
                val dec = decryptData(enc)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }
        }

        return false
    }

    private suspend fun handleLink(
        raw: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = raw.trim()
        return when {
            link.contains(".m3u8") -> {
                M3u8Helper.generateM3u8(name, link, mainUrl).forEach(callback)
                true
            }
            link.startsWith("http") -> {
                loadExtractor(link, mainUrl, subtitleCallback, callback)
                true
            }
            else -> false
        }
    }

    // ── Data classes ───────────────────────────────────────────────────────────

    // FIX: status là Int? thay vì Boolean?
    // Server trả {"status": 1, "data": "..."} → Boolean? sẽ throw MismatchedInputException
    data class AjaxResponse(
        @JsonProperty("status") val status: Int?    = null,
        @JsonProperty("data")   val data: String?   = null
    )

    data class PlayerResponse(
        @JsonProperty("success") val success: Int?        = null,
        @JsonProperty("link")    val link: List<LinkItem> = emptyList()
    )

    data class LinkItem(
        @JsonProperty("file") val file: String? = null
    )
}
