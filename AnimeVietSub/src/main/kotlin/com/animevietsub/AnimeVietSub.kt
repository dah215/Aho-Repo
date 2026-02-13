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
        private const val AES_KEY = "anhemlun@animevs" // 
        private const val AES_IV = "@animevsub@anime"  // 
        private const val AJAX_URL = "/ajax/all"       // 
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("data-original").ifEmpty { it.attr("src") } }
        }
        val epInfo = this.selectFirst(".mli-eps, .Tag")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            if (!epInfo.isNullOrEmpty()) addQuality(epInfo)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val document = app.get(url, headers = defaultHeaders).document
        val items = document.select(".TPostMv, .TPost").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, headers = defaultHeaders).document
            .select(".TPostMv, .TPost").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Anime"
        
        val episodesList = document.select(".list-episode li a, #list_episodes li a, .episode-link").map { ep ->
            val epName = ep.text().trim()
            val epId = ep.attr("data-id")     // [cite: 124]
            val epHash = ep.attr("data-hash") // [cite: 124]
            val epSource = ep.attr("data-source").ifEmpty { "du" }
            val epPlay = ep.attr("data-play").ifEmpty { "api" }
            
            // Định dạng dữ liệu: url|hash|id|source|playType 
            val data = "$url|$epHash|$epId|$epSource|$epPlay"
            
            newEpisode(data) {
                this.name = epName
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = document.selectFirst(".Image img")?.attr("src")
            this.plot = document.selectFirst(".Description")?.text()?.trim()
            
            val map = mutableMapOf<DubStatus, List<Episode>>()
            map[DubStatus.Subbed] = episodesList
            this.episodes = map
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|") // [cite: 128]
        val hash = parts.getOrNull(1) ?: ""
        val episodeId = parts.getOrNull(2) ?: ""
        val source = parts.getOrNull(3) ?: "du"
        val playType = parts.getOrNull(4) ?: "api"

        if (playType == "api" && hash.isNotEmpty()) {
            val decryptedUrl = decryptHash(hash) // [cite: 130]
            if (!decryptedUrl.isNullOrEmpty()) {
                if (decryptedUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(this.name, decryptedUrl, mainUrl).forEach(callback)
                    return true
                }
                loadExtractor(decryptedUrl, mainUrl, subtitleCallback, callback)
                return true
            }
        }

        // Gọi AJAX dự phòng nếu không có hash hoặc giải mã lỗi [cite: 135, 136]
        return loadAjaxLinks(episodeId, source, callback)
    }

    private suspend fun loadAjaxLinks(episodeId: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (episodeId.isEmpty()) return false
        val response = app.post(
            "$mainUrl$AJAX_URL",
            data = mapOf("action" to "get_episodes_player", "episode_id" to episodeId, "server" to source),
            headers = defaultHeaders
        ).parsedSafe<AjaxResponse>() // 

        response?.data?.let { link ->
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8(this.name, link, mainUrl).forEach(callback)
            } else {
                loadExtractor(link, mainUrl, subtitleCallback = {}, callback)
            }
            return true
        }
        return false
    }

    private fun decryptHash(hash: String): String? {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // 
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decoded = Base64.decode(hash, Base64.DEFAULT) // 
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // Model để xử lý JSON trả về từ Server 
    data class AjaxResponse(
        @JsonProperty("status") val status: Boolean?,
        @JsonProperty("data") val data: String?,
        @JsonProperty("message") val message: String?
    )
}
