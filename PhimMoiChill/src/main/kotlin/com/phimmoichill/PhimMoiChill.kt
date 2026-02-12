package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now" 
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch     = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới Cập Nhật",
        "list/phim-le"  to "Phim Lẻ",
        "list/phim-bo"  to "Phim Bộ",
    )

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$mainUrl/${url.removePrefix("/")}"
        }
    }

    private fun parseHtmlPage(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        val containers = doc.select(".movies-list .ml-item, .list-film li, .item, .flw-item")
        
        for (el in containers) {
            val a = el.selectFirst("a") ?: continue
            val href = normalizeUrl(a.attr("href")) ?: continue
            if (href.contains("/the-loai/") || !seenUrls.add(href)) continue

            val title = el.selectFirst("h2, .title, .name")?.text()?.trim() 
                ?: a.attr("title").takeIf { it.isNotBlank() } ?: a.text().trim()
            if (title.isBlank()) continue

            val img = el.selectFirst("img")
            val poster = normalizeUrl(img?.attr("data-original") ?: img?.attr("data-src") ?: img?.attr("src"))

            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
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

        val title = doc.selectFirst("h1.title, .movie-info h1, .entry-title")?.text()?.trim() ?: "Unknown"
        val poster = normalizeUrl(doc.selectFirst(".film-poster img, .movie-info img, .poster img")?.attr("src"))
        val plot = doc.selectFirst(".film-content, #film-content")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        doc.select(".list-episode a, #list_episodes a, a[href*='/xem/']").forEach {
            val epHref = normalizeUrl(it.attr("href")) ?: return@forEach
            val epName = it.text().trim()
            if (episodes.none { ep -> ep.data == epHref }) {
                episodes.add(newEpisode(epHref) {
                    this.name = if (epName.contains("Tập")) epName else "Tập $epName"
                })
            }
        }

        if (episodes.isEmpty()) episodes.add(newEpisode(url) { this.name = "Full Movie" })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = defaultHeaders)
        val html = res.text
        val document = org.jsoup.Jsoup.parse(html)
        
        // 1. Giải quyết lỗi "Không tìm thấy link" bằng cách quét sâu vào script
        val scriptData = document.select("script").joinToString("\n") { it.data() }
        
        // Tìm link m3u8 ẩn trong các biến JavaScript
        val m3u8Regex = Regex("""["'](https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*?)["']""")
        m3u8Regex.findAll(scriptData + html).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            M3u8Helper.generateM3u8(name, videoUrl, data).forEach(callback)
        }

        // 2. Quét Iframe (Dành cho các server Player bên thứ 3)
        document.select("iframe[src]").forEach {
            val src = normalizeUrl(it.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // 3. Quét link Video MP4 trực tiếp
        val mp4Regex = Regex("""["'](https?://[^\s"'<>]+?\.mp4[^\s"'<>]*?)["']""")
        mp4Regex.findAll(scriptData + html).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            // ✅ SỬA LỖI BIÊN DỊCH: Chỉ dùng 4 tham số cho hàm newExtractorLink
            callback(
                newExtractorLink(
                    name, 
                    "$name Video", 
                    videoUrl, 
                    ExtractorLinkType.VIDEO
                ).apply {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                }
            )
        }
        
        // 4. Quét các thuộc tính data- ẩn (thường chứa link server dự phòng)
        document.select("[data-src], [data-link], [data-url]").forEach {
            val src = it.attr("data-src").ifBlank { it.attr("data-link") }.ifBlank { it.attr("data-url") }
            val normalized = normalizeUrl(src) ?: return@forEach
            if (normalized.contains("http")) {
                loadExtractor(normalized, data, subtitleCallback, callback)
            }
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
