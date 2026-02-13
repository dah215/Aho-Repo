package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
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

    private val mapper = jacksonObjectMapper()

    private val defaultHeaders = mapOf(
        "User-Agent"        to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer"           to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

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

    private fun decryptData(aesData: String): String? {
        return try {
            val digest    = MessageDigest.getInstance("SHA-256")
            val key       = digest.digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val secretKey = SecretKeySpec(key, "AES")
            val decoded   = Base64.decode(aesData.trim(), Base64.DEFAULT)
            val iv        = decoded.copyOfRange(0, 16)
            val ct        = decoded.copyOfRange(16, decoded.size)
            val cipher    = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
                .replace("\\n", "\n")
                .replace("\"", "")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
                if (epText.contains(Regex("Full|HD|Movie", RegexOption.IGNORE_CASE)))
                    this.quality = SearchQuality.HD
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url   = if (page == 1) request.data else "${request.data}trang-$page.html"
        val doc   = app.get(url, headers = defaultHeaders).document
        val items = doc.select(".TPostMv, .TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url.trimEnd('/') }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, headers = defaultHeaders).document
            .select(".TPostMv, .TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url.trimEnd('/') }
    }

    override suspend fun load(url: String): LoadResponse {
        // Trang phim (film page) → cần lấy danh sách tập từ trang xem
        // Thử load trực tiếp url, nếu không có tập thì thử tap-01
        var document      = app.get(url, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        // Nếu film page không có list-episode → thử load trang tập đầu tiên
        if (episodesNodes.isEmpty()) {
            // Lấy href của tập đầu tiên từ bất kỳ link nào trên film page
            val firstEpHref = document.selectFirst(
                "a[href*='/tap-'], a.btn-episode, a.episode-link"
            )?.attr("href")?.let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            if (firstEpHref != null) {
                val doc2 = runCatching {
                    app.get(firstEpHref, headers = defaultHeaders).document
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

            // href của tập này, VD: https://animevietsub.ee/phim/.../tap-01-111047.html
            // *** ĐÂY LÀ FIX QUAN TRỌNG NHẤT ***
            // Server kiểm tra Referer — phải là URL trang TẬP, không phải trang PHIM
            val epUrl = ep.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }

            if (epId.isEmpty() || epHash.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            // Format: epUrl|hash|id|source|play
            // parts[0] = URL trang tập (dùng làm Referer cho AJAX call)
            newEpisode("$epUrl|$epHash|$epId|$epSrc|$epPlay") {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Format: epUrl|hash|epId|source|play
        val parts = data.split("|")
        if (parts.size < 5) return false

        // *** FIX: parts[0] là URL trang TẬP — đây là Referer server yêu cầu ***
        // VD: https://animevietsub.ee/phim/jujutsu-kaisen-3rd-season-a5820/tap-07-111770.html
        val epUrl   = parts[0]
        val hash    = parts[1]
        val epId    = parts[2]
        val source  = parts[3]
        val play    = parts[4]

        val reqHeaders = mapOf(
            "Referer"           to epUrl,   // ← Referer là URL tập cụ thể
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent"        to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // Endpoint chính: /ajax/player?v=2019a
        // Params: id=episodeID, play=data-play, link=data-hash
        val body1 = runCatching {
            app.post(
                "$mainUrl/ajax/player?v=2019a",
                data    = mapOf("id" to epId, "play" to play, "link" to hash),
                headers = reqHeaders
            ).text
        }.getOrNull().orEmpty()

        if (body1.isNotEmpty()) {
            // Parse dạng {"success":1, "link":[{"file":"<aes_encrypted>"}]}
            val enc1 = runCatching {
                mapper.readValue<PlayerResponse>(body1)
                    .link.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            }.getOrNull()
            if (!enc1.isNullOrBlank()) {
                val dec = decryptData(enc1)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }
        }

        // Fallback: /ajax/all
        val body2 = runCatching {
            app.post(
                "$mainUrl/ajax/all",
                data    = mapOf(
                    "action"     to "get_episodes_player",
                    "episode_id" to epId,
                    "server"     to source,
                    "hash"       to hash
                ),
                headers = reqHeaders
            ).text
        }.getOrNull().orEmpty()

        if (body2.isNotEmpty()) {
            val enc2 = runCatching {
                mapper.readValue<AjaxResponse>(body2).data
            }.getOrNull()
            if (!enc2.isNullOrBlank()) {
                val dec = decryptData(enc2)
                if (!dec.isNullOrBlank()) return handleLink(dec, callback, subtitleCallback)
            }

            val enc3 = runCatching {
                mapper.readValue<PlayerResponse>(body2)
                    .link.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
            }.getOrNull()
            if (!enc3.isNullOrBlank()) {
                val dec = decryptData(enc3)
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

    // ── Data classes ──────────────────────────────────────────────────────────

    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int?       = null,
        @JsonProperty("success")   val success: Int?        = null,
        @JsonProperty("link")      val link: List<LinkItem> = emptyList()
    )

    data class LinkItem(
        @JsonProperty("file") val file: String? = null
    )

    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("data")   val data: String?    = null
    )
}
