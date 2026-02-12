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

    // Header chuẩn từ file JS và Screenshot của bạn
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới Cập Nhật",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            // Selector chính xác từ phimmoichill_main.json: .list-film .item
            val items = doc.select(".list-film .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
                
                // Lấy poster từ data-src (Lazyload) để tránh lỗi Unknown/Crash
                val img = el.selectFirst("img")
                val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                
                newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        // Lấy thông tin từ phimmoichill_movie.json
        val title = doc.selectFirst("h1.entry-title, h1.caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        val desc = doc.selectFirst(".film-content")?.text()?.trim()

        // Danh sách tập phim từ watch_page.html
        val episodes = doc.select("ul.list-episode li a, a[href*='/xem/']").map {
            newEpisode(it.attr("href")) {
                this.name = it.text().trim().replace("Tập ", "")
            }
        }.distinctBy { it.data }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = desc
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = desc
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hasLinks = false
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1) ?: return false

        try {
            // Tấn công vào chillsplayer.php (Logic từ phimchill.public.js)
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            // Lọc link m3u8 sạch (Bỏ qua ads/skipintro từ skipintro.js)
            val m3u8Regex = Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""")
            m3u8Regex.findAll(res.replace("\\/", "/")).forEach { match ->
                val link = match.value
                if (!link.contains("ads") && !link.contains("skipintro")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { 
                        callback(it)
                        hasLinks = true 
                    }
                }
            }
        } catch (e: Exception) {}

        return hasLinks
    }
}
