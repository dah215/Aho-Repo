package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PhimMoiChillProvider : MainAPI() {
    // ✅ Cập nhật domain sang .net (hoặc bạn có thể thử .tv nếu .net bị chặn)
    override var mainUrl = "https://phimmoichill.net" 
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch     = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    // Headers giả lập trình duyệt di động để tránh bị chặn
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới Cập Nhật",
        "list/phim-le"  to "Phim Lẻ",
        "list/phim-bo"  to "Phim Bộ",
        "the-loai/phim-chieu-rap" to "Phim Chiếu Rạp"
    )

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$mainUrl/${url.removePrefix("/")}"
        }
    }

    // ✅ HÀM QUAN TRỌNG: Quét dữ liệu cực mạnh (Fix lỗi màn hình trống)
    private fun parseHtmlPage(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        // Danh sách các vùng chứa phim tiềm năng trên web mới
        val containers = doc.select(".block-body li, .list-film li, .movies-list .ml-item, .item")
        
        for (el in containers) {
            val a = el.selectFirst("a") ?: continue
            val href = normalizeUrl(a.attr("href")) ?: continue
            
            // Lọc bỏ các link rác (genre, country, dmca...)
            if (href.contains("/the-loai/") || href.contains("/quoc-gia/") || href == "$mainUrl/") continue
            if (!seenUrls.add(href)) continue

            val title = el.selectFirst("h2, .title, .name, .movie-title")?.text()?.trim() 
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().trim()

            if (title.isBlank()) continue

            val img = el.selectFirst("img")
            val poster = normalizeUrl(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
            )

            // Kiểm tra xem là phim bộ hay phim lẻ dựa vào label
            val isSeries = el.select(".label, .ep, .status").text().lowercase().let {
                it.contains("tập") || it.contains("/") || it.contains("full")
            }

            if (isSeries) {
                items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
            } else {
                items.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
            }
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val html = app.get(url, headers = defaultHeaders).text
        val items = parseHtmlPage(html)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}"
        val html = app.get(searchUrl, headers = defaultHeaders).text
        return parseHtmlPage(html)
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("h1.title, .movie-info h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        
        val poster = doc.selectFirst(".film-poster img, .movie-info img")?.attr("src")
        val plot = doc.selectFirst(".film-content, #film-content")?.text()?.trim()
        val year = doc.selectFirst(".year, .release-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        // Tìm link xem phim hoặc danh sách tập
        doc.select(".list-episode a, #list_episodes a, a[href*='-tap-']").forEach {
            val epHref = normalizeUrl(it.attr("href")) ?: return@forEach
            val epName = it.text().trim()
            episodes.add(newEpisode(epHref) {
                this.name = if (epName.contains("Tập")) epName else "Tập $epName"
                this.episode = epName.filter { c -> c.isDigit() }.toIntOrNull()
            })
        }

        // Nếu là phim lẻ không thấy danh sách tập, lấy nút "Xem phim"
        if (episodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-watch, .btn-see")?.attr("href")?.let { normalizeUrl(it) } ?: url
            episodes.add(newEpisode(watchUrl) { this.name = "Full Movie" })
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = defaultHeaders).text
        
        // Cách 1: Tìm trong iframe
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("iframe[src]").forEach {
            val src = normalizeUrl(it.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // Cách 2: Quét link m3u8 ẩn trong Script (PhimMoi hay dùng cách này)
        val regex = Regex("""(https?://[^\s"'<>]+(\.m3u8|\.mp4)[^\s"'<>]*)""")
        regex.findAll(html).forEach { match ->
            val videoUrl = match.value.replace("\\/", "/")
            M3u8Helper.generateM3u8(name, videoUrl, data).forEach(callback)
        }

        return true
    }
}

@CloudstreamPlugin
class PhimMoiChill : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}
