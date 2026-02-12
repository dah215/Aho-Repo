package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        val items = doc.select(".list-film .item, .list-film li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p, .title, .name")?.text()?.trim() ?: a.attr("title")
            val poster = el.selectFirst("img")?.let { 
                if (it.hasAttr("data-src")) it.attr("data-src") else it.attr("src") 
            }
            // Lấy nhãn (Tập phim/Vietsub)
            val label = el.selectFirst(".label, .status")?.text()?.trim()

            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                // FIX LỖI 55:40: Sử dụng hàm addQuality thay vì gán trực tiếp String vào biến quality
                if (label != null) {
                    addQuality(label) 
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        val title = doc.selectFirst("h1.entry-title, .caption, .title-film")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img, .movie-l-img img")?.let {
            if (it.hasAttr("data-src")) it.attr("data-src") else it.attr("src")
        }
        val description = doc.selectFirst("#film-content, .entry-content")?.text()?.trim()
        
        // Fix lỗi không hiện tập: Tìm link xem phim trước để lấy danh sách tập
        val watchUrl = doc.selectFirst("a.btn-see, a[href*='/xem/']")?.attr("href") ?: url
        val watchPageHtml = app.get(watchUrl, headers = defaultHeaders).text
        val watchDoc = Jsoup.parse(watchPageHtml)

        // Selector danh sách tập mới nhất dựa trên HTML thực tế
        val episodes = watchDoc.select("ul.list-episode li a, .list-episodes a, #list_episodes li a").mapIndexed { index, it ->
            val epName = it.text().trim()
            newEpisode(it.attr("href")) {
                this.name = if (epName.isEmpty()) "Tập ${index + 1}" else epName
                this.episode = index + 1
            }
        }.distinctBy { it.data }

        val tags = doc.select(".entry-meta li a, .tags a").map { it.text() }

        return if (episodes.size > 1 || url.contains("phim-bo")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val html = response.text
        
        // Trích xuất ID tập phim từ script
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""episodeID\s*=\s*parseInt\('(\d+)'\)""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = response.cookies
            ).text

            val key = Regex("""iniPlayers\("([^"]+)""").find(res)?.groupValues?.get(1) ?: return false
            
            // Server CDN
            val servers = listOf(
                "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" to "Chill VIP",
                "https://dash.megacdn.xyz/raw/$key/index.m3u8" to "DASH Fast"
            )

            servers.forEach { (link, sName) ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = sName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            true
        } catch (e: Exception) { false }
    }
}
