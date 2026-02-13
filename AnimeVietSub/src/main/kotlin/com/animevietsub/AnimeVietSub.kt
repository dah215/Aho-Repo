package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
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
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        // FIX: Giữ nguyên password giải mã
        private const val DECODE_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    // FIX: Tăng buffer inflate từ 1024 → 8192 tránh lỗi với file lớn
    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buffer = ByteArray(8192)
        val outputStream = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                outputStream.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }
        return outputStream.toByteArray()
    }

    private fun decryptData(aesData: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val key = digest.digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val secretKey = SecretKeySpec(key, "AES")
            // FIX: trim() trước khi decode tránh lỗi whitespace
            val decodedBytes = Base64.decode(aesData.trim(), Base64.DEFAULT)
            val iv = decodedBytes.copyOfRange(0, 16)
            val ciphertext = decodedBytes.copyOfRange(16, decodedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val plaintextPadded = cipher.doFinal(ciphertext)
            val inflated = pakoInflateRaw(plaintextPadded)
            String(inflated, StandardCharsets.UTF_8)
                .replace("\\n", "\n")
                .replace("\"", "")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("data-original").ifEmpty { it.attr("src") } }
        }
        val epText = this.select(".mli-eps, .Tag, .label, .Status, .Quality").text().trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            if (epText.isNotEmpty()) {
                val epMatch = Regex("""(\d+)""").find(epText)
                epMatch?.groupValues?.get(1)?.toIntOrNull()?.let { this.addSub(it) }
                if (epText.contains(Regex("Full|HD|Movie", RegexOption.IGNORE_CASE))) {
                    this.quality = SearchQuality.HD
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select(".TPostMv, .TPost")
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
        var document = app.get(url, headers = defaultHeaders).document

        // FIX: Mở rộng selector để bắt nhiều cấu trúc HTML hơn
        var episodesNodes = document.select(
            "li.episode a, .list-server .server a, .listEp li a, ul.episodios li a"
        )

        if (episodesNodes.isEmpty()) {
            val watchUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
            document = app.get(watchUrl, headers = defaultHeaders).document
            episodesNodes = document.select(
                "li.episode a, .list-server .server a, .listEp li a, ul.episodios li a"
            )
        }

        val episodesList = episodesNodes.mapNotNull { ep ->
            val epId   = ep.attr("data-id")
            val epHash = ep.attr("data-hash")
            // FIX: data-source và data-play lấy đúng thứ tự, có fallback
            val epSource = ep.attr("data-source").ifEmpty { "du" }
            val epPlay   = ep.attr("data-play").ifEmpty { "api" }
            val epName   = ep.text().trim()

            // Bỏ qua nếu không có id lẫn hash
            if (epId.isEmpty() && epHash.isEmpty()) return@mapNotNull null

            newEpisode("$url|$epHash|$epId|$epSource|$epPlay") {
                this.name    = if (epName.contains("Tập") || epName.contains("Tap")) epName
                               else "Tập $epName"
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = document.selectFirst(".Image img, .InfoImg img, .TPost .Image img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            this.plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")?.text()?.trim()

            val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
            if (episodesList.isNotEmpty()) {
                episodesMap[DubStatus.Subbed] = episodesList
            }
            this.episodes = episodesMap
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 5) return false

        val referer = parts[0]
        val hash    = parts[1]
        val epId    = parts[2]
        val source  = parts[3]
        val play    = parts[4]  // FIX: Biến play trước đây bị bỏ quên, nay đưa vào POST

        val requestHeaders = mapOf(
            "Referer"           to referer,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )

        // ── BƯỚC 1: Thử endpoint chính /ajax/player?v=2019a ──────────────────
        // FIX: Đây là endpoint thực sự hoạt động; code cũ dùng /ajax/all sai
        val resp1 = try {
            app.post(
                "$mainUrl/ajax/player?v=2019a",
                data = mapOf("id" to epId, "play" to play, "link" to hash),
                headers = requestHeaders
            ).text
        } catch (e: Exception) { "" }

        if (resp1.isNotEmpty()) {
            // FIX: Thử parse dạng Json2Anime {link:[{file:...}]} (từ reference)
            try {
                val json = parseJson<Json2AnimeResponse>(resp1)
                val fileEncrypted = json.link.mapNotNull { it.file }.firstOrNull() ?: ""
                if (fileEncrypted.isNotEmpty()) {
                    val decrypted = decryptData(fileEncrypted)
                    if (!decrypted.isNullOrBlank()) {
                        return handleDecryptedLink(decrypted, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) { }

            // Thử parse dạng AjaxResponse {status, data}
            try {
                val json = parseJson<AjaxResponse>(resp1)
                json.data?.let { encrypted ->
                    val decrypted = decryptData(encrypted)
                    if (!decrypted.isNullOrBlank()) {
                        return handleDecryptedLink(decrypted, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) { }
        }

        // ── BƯỚC 2: Fallback → /ajax/all (endpoint cũ) ───────────────────────
        val resp2 = try {
            app.post(
                "$mainUrl/ajax/all",
                data = mapOf(
                    "action"     to "get_episodes_player",
                    "episode_id" to epId,
                    "server"     to source,
                    "hash"       to hash
                ),
                headers = requestHeaders
            ).text
        } catch (e: Exception) { "" }

        if (resp2.isNotEmpty()) {
            try {
                val json = parseJson<AjaxResponse>(resp2)
                json.data?.let { encrypted ->
                    val decrypted = decryptData(encrypted)
                    if (!decrypted.isNullOrBlank()) {
                        return handleDecryptedLink(decrypted, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) { }

            try {
                val json = parseJson<Json2AnimeResponse>(resp2)
                val fileEncrypted = json.link.mapNotNull { it.file }.firstOrNull() ?: ""
                if (fileEncrypted.isNotEmpty()) {
                    val decrypted = decryptData(fileEncrypted)
                    if (!decrypted.isNullOrBlank()) {
                        return handleDecryptedLink(decrypted, mainUrl, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) { }
        }

        return false
    }

    // ── Helper xử lý link sau khi giải mã ────────────────────────────────────
    private suspend fun handleDecryptedLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanLink = link.trim()
        return when {
            cleanLink.contains(".m3u8") -> {
                M3u8Helper.generateM3u8(this.name, cleanLink, referer).forEach(callback)
                true
            }
            cleanLink.startsWith("http") -> {
                loadExtractor(cleanLink, referer, subtitleCallback, callback)
                true
            }
            else -> false
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    // FIX: Thêm class này để parse đúng response dạng {link:[{file:...}]}
    data class Json2AnimeResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int? = null,
        @JsonProperty("success")   val success: Int? = null,
        @JsonProperty("link")      val link: List<LinkItem> = emptyList()
    )

    data class LinkItem(
        @JsonProperty("file") val file: String? = null
    )

    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("data")   val data: String? = null
    )
}
