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
        private const val DECODE_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt" 
        private const val AJAX_URL = "/ajax/all"
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

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val output = ByteArray(1024)
        val outputStream = ByteArrayOutputStream()
        while (!inflater.finished()) {
            val count = inflater.inflate(output)
            outputStream.write(output, 0, count)
        }
        inflater.end()
        return outputStream.toByteArray()
    }

    private fun decryptData(aesData: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val key = digest.digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val secretKey = SecretKeySpec(key, "AES")
            val decodedBytes = Base64.decode(aesData, Base64.DEFAULT)
            val iv = decodedBytes.copyOfRange(0, 16)
            val ciphertext = decodedBytes.copyOfRange(16, decodedBytes.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val plaintextPadded = cipher.doFinal(ciphertext)
            val inflated = pakoInflateRaw(plaintextPadded)
            String(inflated, StandardCharsets.UTF_8).replace("\\n", "\n").replace("\"", "")
        } catch (e: Exception) { null }
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
        
        // Fix lỗi Elements mismatch: lấy danh sách thẻ 'a' thay vì Elements đơn lẻ
        var episodesNodes = document.select("li.episode a")
        
        if (episodesNodes.isEmpty()) {
            val watchUrl = if (url.endsWith("/")) "${url}xem-phim.html" else "$url/xem-phim.html"
            document = app.get(watchUrl, headers = defaultHeaders).document
            episodesNodes = document.select("li.episode a")
        }

        val episodesList = episodesNodes.mapNotNull { ep ->
            val epId = ep.attr("data-id")
            val epHash = ep.attr("data-hash")
            val epSource = ep.attr("data-source").ifEmpty { "du" }
            val epPlay = ep.attr("data-play").ifEmpty { "api" }
            val epName = ep.text().trim()
            
            newEpisode("$url|$epHash|$epId|$epSource|$epPlay") {
                this.name = if (epName.contains("Tập")) epName else "Tập $epName"
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = document.selectFirst(".Image img, .InfoImg img")?.attr("src")
            this.plot = document.selectFirst(".Description, .InfoDesc")?.text()?.trim()
            
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
        val hash = parts[1]
        val epId = parts[2]
        val source = parts[3]

        val response = app.post(
            "$mainUrl$AJAX_URL",
            data = mapOf("action" to "get_episodes_player", "episode_id" to epId, "server" to source, "hash" to hash),
            headers = mapOf("Referer" to referer, "X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<AjaxResponse>()

        response?.data?.let { encrypted ->
            decryptData(encrypted)?.let { finalLink ->
                if (finalLink.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(this.name, finalLink, mainUrl).forEach(callback)
                    return true
                }
                loadExtractor(finalLink, mainUrl, subtitleCallback, callback)
                return true
            }
        }
        return false
    }

    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean?,
        @JsonProperty("data") val data: String?
    )
}
