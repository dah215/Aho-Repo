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
    override var lang = "vi" // FIX: Đổi từ val sang var
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
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
                if (!epInfo.isNullOrEmpty()) addQuality(epInfo)
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
        
        // FIX: Sử dụng score thay cho rating đã bị xóa
        val scoreValue = document.selectFirst("#score_current")?.attr("value")?.toDoubleOrNull()

        // FIX: Sử dụng newEpisode để tránh lỗi deprecated
        val episodes = document.select(".list-episode li a, #list_episodes li a").map { ep ->
            val epName = ep.text().trim()
            newEpisode(ep.attr("href")) {
                this.name = epName
                this.episode = epName.filter { it.isDigit() }.toIntOrNull()
            }
        }

        // FIX: Truyền episodes vào đúng vị trí của hàm newAnimeLoadResponse
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.score = scoreValue?.let { Score(it, 10.0) } // Hệ thống điểm mới
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = defaultHeaders).text
        val document = Jsoup.parse(html)

        val filmId = document.select("input#film_id").attr("value")
        val episodeId = document.select("input#episode_id").attr("value")

        if (filmId.isNotEmpty() && episodeId.isNotEmpty()) {
            val res = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("episode_id" to episodeId, "film_id" to filmId),
                headers = defaultHeaders.plus("X-Requested-With" to "XMLHttpRequest")
            ).text
            
            // Tìm link m3u8 trong phản hồi
            Regex("""https?://[^\s"']+\.m3u8""").find(res)?.value?.let { link ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "HLS Server",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }
        return true
    }
}
