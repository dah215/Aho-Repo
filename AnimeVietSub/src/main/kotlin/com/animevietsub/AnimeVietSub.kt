package com.animevietsub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override val lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val AJAX_URL = "/ajax/player" // Cập nhật endpoint AJAX mới
        private const val AES_KEY = "anhemlun@animevs" // Key cũ, cần kiểm tra lại nếu web đổi
        private const val AES_IV = "@animevsub@anime"
    }

    // Header giả lập trình duyệt để tránh bị chặn
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/anime-sap-chieu/" to "Sắp Chiếu"
    )

    // HÀM FIX LỖI ẢNH (Học từ PhimMoiChill)
    private fun getImageUrl(el: Element?): String? {
        if (el == null) return null
        val url = el.attr("data-src").ifEmpty { 
            el.attr("data-original").ifEmpty { 
                el.attr("src") 
            } 
        }
        return fixUrl(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val document = app.get(url, headers = defaultHeaders).document
        
        val items = document.select(".TPostMv, .TPost").mapNotNull { item ->
            item.toSearchResponse()
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = getImageUrl(this.selectFirst("img"))
        
        // Lấy thông tin tập/chất lượng
        val epInfo = this.selectFirst(".mli-eps, .Tag")?.text()?.trim()
        val type = if (href.contains("/anime-le/")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
            if (!epInfo.isNullOrEmpty()) {
                addQuality(epInfo) // Hiển thị số tập/trạng thái lên poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/$query/"
        val document = app.get(url, headers = defaultHeaders).document
        return document.select(".TPostMv, .TPost").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Anime"
        val altTitle = document.selectFirst("h2.SubTitle")?.text()?.trim()
        val poster = getImageUrl(document.selectFirst(".Image img, .InfoImg img"))
        val banner = getImageUrl(document.selectFirst(".TPostBg img"))
        val description = document.selectFirst(".Description, .InfoDesc")?.text()?.trim()
        
        // Lấy năm, điểm số
        val year = document.selectFirst(".Date a, .InfoList li:contains(Năm)")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val rating = document.selectFirst("#score_current")?.attr("value")?.toRatingInt()

        val tags = document.select(".InfoList li:contains(Thể loại) a").map { it.text() }
        val recommendations = document.select(".Related .TPostMv").mapNotNull { it.toSearchResponse() }

        // Lấy danh sách tập phim
        val episodes = document.select(".list-episode li a, #list_episodes li a").mapNotNull { ep ->
            val link = ep.attr("href")
            val name = ep.text().trim()
            if (link.isNotEmpty()) {
                newEpisode(link) {
                    this.name = name
                    // Cố gắng tách số tập từ tên (vd: "Tập 1")
                    this.episode = name.filter { it.isDigit() }.toIntOrNull()
                }
            } else null
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.japName = altTitle
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.recommendations = recommendations
            addEpisodes(if (episodes.isEmpty()) listOf(Episode(url)) else episodes) // Fallback nếu không thấy tập
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = defaultHeaders).document

        // Cách 1: Quét ID để gọi AJAX (Cách hiện đại)
        val filmId = document.select("input#film_id").attr("value")
        val episodeId = document.select("input#episode_id").attr("value")

        if (filmId.isNotEmpty() && episodeId.isNotEmpty()) {
            try {
                // Gọi API lấy link player
                val ajaxData = mapOf(
                    "episode_id" to episodeId,
                    "film_id" to filmId
                )
                val json = app.post(
                    "$mainUrl$AJAX_URL",
                    data = ajaxData,
                    headers = defaultHeaders.plus("X-Requested-With" to "XMLHttpRequest")
                ).text
                
                // Web trả về JSON có chứa HTML hoặc Link
                // Vì AnimeVietSub hay thay đổi cấu trúc trả về, ta quét regex tìm link trong response
                val linkRegex = Regex("""https?://[^\s"']+\.(m3u8|mp4)""")
                linkRegex.findAll(json).forEach { match ->
                    val link = match.value.replace("\\/", "/")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            } catch (e: Exception) {
                // Fallback nếu API lỗi
            }
        }

        // Cách 2: Quét link trực tiếp trong mã nguồn (Fallback)
        document.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("sources:")) {
                Regex("""file:\s*["']([^"']+)["']""").findAll(content).forEach { match ->
                    val link = match.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = "Backup",
                            name = "Server Backup",
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }
        
        return true
    }
}
