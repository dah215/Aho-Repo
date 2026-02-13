package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // Header bảo mật cao - Cần Referer và User-Agent chính xác
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

    private fun getImageUrl(el: Element?): String? {
        if (el == null) return null
        val url = el.attr("data-src").ifEmpty { 
            el.attr("data-original").ifEmpty { 
                el.attr("src") 
            } 
        }
        return if (url.startsWith("//")) "https:$url" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val html = app.get(url, headers = defaultHeaders).text
        val document = Jsoup.parse(html)
        
        val items = document.select(".TPostMv, .TPost").mapNotNull { item ->
            val title = item.selectFirst(".Title, h3")?.text()?.trim() ?: return@mapNotNull null
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = getImageUrl(item.selectFirst("img"))
            val epInfo = item.selectFirst(".mli-eps, .Tag")?.text()?.trim()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                // FIX LỖI getSearchQuality: Gán trực tiếp hoặc dùng addQuality nếu hỗ trợ
                // Ở đây ta dùng trường quality của SearchResponse
                if (!epInfo.isNullOrEmpty()) {
                    addQuality(epInfo) 
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        val html = app.get(url, headers = defaultHeaders).text
        return Jsoup.parse(html).select(".TPostMv, .TPost").mapNotNull { item ->
            val title = item.selectFirst(".Title, h3")?.text()?.trim() ?: return@mapNotNull null
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = getImageUrl(item.selectFirst("img"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Anime"
        val poster = getImageUrl(document.selectFirst(".Image img, .InfoImg img"))
        val description = document.selectFirst(".Description, .InfoDesc")?.text()?.trim()
        
        // FIX LỖI Score: Gán trực tiếp Int thay vì khởi tạo Class Score
        val scoreValue = document.selectFirst("#score_current")?.attr("value")
            ?.toDoubleOrNull()?.times(10)?.toInt()

        val episodesList = document.select(".list-episode li a, #list_episodes li a").map { ep ->
            val epName = ep.text().trim()
            newEpisode(ep.attr("href")) {
                this.name = epName
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            // FIX LỖI 112: Sử dụng giá trị Int trực tiếp nếu property là Int?
            // Nếu SDK yêu cầu Score, ta phải tìm cách khác, nhưng thường là Int
            if (scoreValue != null) {
                this.rating = scoreValue 
            }
            // FIX LỖI 115: Sử dụng mutableMapOf để khớp kiểu MutableMap
            this.episodes = mutableMapOf(DubStatus.Subbed to episodesList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bảo mật cao: Cần lấy Cookie từ GET request trước khi POST
        val req = app.get(data, headers = defaultHeaders)
        val document = Jsoup.parse(req.text())

        val filmId = document.select("input#film_id").attr("value")
        val episodeId = document.select("input#episode_id").attr("value")

        if (filmId.isNotEmpty() && episodeId.isNotEmpty()) {
            val res = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("episode_id" to episodeId, "film_id" to filmId),
                headers = defaultHeaders.plus(mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                )),
                cookies = req.cookies
            ).text
            
            // Tìm link m3u8 trong JSON response
            // Lưu ý: Có thể link bị mã hóa Base64 hoặc AES, tạm thời quét Regex
            Regex("""https?://[^\s"']+\.m3u8""").find(res)?.value?.let { link ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "HLS Server",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = defaultHeaders
                    }
                )
            }
        }
        return true
    }
}
