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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val items = org.jsoup.Jsoup.parse(html).select(".movies-list .ml-item, .list-film li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst("h2, .title, .name")?.text()?.trim() ?: a.text().trim()
            val poster = normalizeUrl(el.selectFirst("img")?.let { it.attr("data-original").ifBlank { it.attr("src") } })

            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}"
        val html = app.get(searchUrl, headers = defaultHeaders).text
        return org.jsoup.Jsoup.parse(html).select(".movies-list .ml-item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst("h2, .title")?.text() ?: ""
            val poster = normalizeUrl(el.selectFirst("img")?.attr("data-original"))
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("h1.title, .movie-info h1")?.text()?.trim() ?: "Unknown"
        val poster = normalizeUrl(doc.selectFirst(".film-poster img, .movie-info img")?.attr("src"))
        val plot = doc.selectFirst(".film-content, #film-content")?.text()?.trim()

        val episodes = doc.select(".list-episode a, #list_episodes a, a[href*='/xem/']").map {
            val epHref = normalizeUrl(it.attr("href")) ?: ""
            val epName = it.text().trim()
            newEpisode(epHref) {
                this.name = if (epName.contains("Tập")) epName else "Tập $epName"
            }
        }.distinctBy { it.data }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
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
        
        // 1. Quét link m3u8 ẩn (Sửa lỗi tải phim)
        val m3u8Regex = Regex("""["'](https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*?)["']""")
        m3u8Regex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            M3u8Helper.generateM3u8(name, videoUrl, data).forEach(callback)
        }

        // 2. Quét Iframe server Player
        org.jsoup.Jsoup.parse(html).select("iframe[src]").forEach {
            val src = normalizeUrl(it.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // 3. Quét link MP4 trực tiếp (Sửa lỗi Build bằng cú pháp newExtractorLink chuẩn nhất)
        val mp4Regex = Regex("""["'](https?://[^\s"'<>]+?\.mp4[^\s"'<>]*?)["']""")
        mp4Regex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\/", "/")
            
            // ✅ SỬA LỖI BIÊN DỊCH: Sử dụng newExtractorLink với ExtractorLinkType ở vị trí thứ 4
            callback(
                newExtractorLink(
                    name,
                    "$name Video",
                    videoUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                }
            )
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
