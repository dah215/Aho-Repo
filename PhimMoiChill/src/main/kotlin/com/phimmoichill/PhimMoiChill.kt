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
        // Đăng ký Provider vào hệ thống
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name    = "PhimMoiChill"
    override val hasMainPage = true
    override var lang    = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // Header mô phỏng trình duyệt đang gọi AJAX sau khi bỏ quảng cáo
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("http")) url else if (url.startsWith("//")) "https:$url" else "$mainUrl/${url.removePrefix("/")}"
    }

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới Cập Nhật",
        "list/phim-le"  to "Phim Lẻ",
        "list/phim-bo"  to "Phim Bộ",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val items = Jsoup.parse(html).select(".movies-list .ml-item, .list-film li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("h2, .title, .name")?.text()?.trim() ?: a.text().trim()
            val poster = normalizeUrl(el.selectFirst("img")?.let { it.attr("data-original").ifBlank { it.attr("src") } })
            newMovieSearchResponse(title, normalizeUrl(a.attr("href"))!!, TvType.Movie) { this.posterUrl = poster }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}", headers = defaultHeaders).text
        return Jsoup.parse(html).select(".movies-list .ml-item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("h2, .title")?.text() ?: ""
            val poster = normalizeUrl(el.selectFirst("img")?.attr("data-original"))
            newMovieSearchResponse(title, normalizeUrl(a.attr("href"))!!, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.title, .movie-info h1")?.text()?.trim() ?: "Unknown"
        val episodes = doc.select(".list-episode a, #list_episodes a, a[href*='/xem/']").map {
            newEpisode(normalizeUrl(it.attr("href"))!!) { this.name = it.text().trim() }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = normalizeUrl(doc.selectFirst(".film-poster img, .movie-info img")?.attr("src"))
            this.plot = doc.selectFirst(".film-content, #film-content")?.text()?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hasLinks = false
        // Tách ID phim từ link (pm126172 -> 126172)
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1)

        // GỌI AJAX - Bước quan trọng nhất bạn đã tìm ra
        if (episodeId != null) {
            listOf("1", "2").forEach { serverId -> 
                try {
                    val ajaxRes = app.post(
                        "$mainUrl/ajax/get_episode_links",
                        data = mapOf("episode_id" to episodeId, "server_id" to serverId),
                        headers = defaultHeaders.plus("Referer" to data)
                    ).text

                    // Regex bóc tách link từ JSON (xử lý cả dấu gạch chéo ngược)
                    val linkRegex = Regex("""https?[:\\]+[^"'<>]+?\.(?:m3u8|mp4|html)[^"'<>]*""")
                    linkRegex.findAll(ajaxRes.replace("\\/", "/")).forEach { match ->
                        val link = match.value
                        if (link.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, link, data).forEach { callback(it); hasLinks = true }
                        } else {
                            loadExtractor(link, data, subtitleCallback) { callback(it); hasLinks = true }
                        }
                    }
                } catch (e: Exception) { }
                if (hasLinks) return true // Dừng lại ngay khi có link để không nhảy tập
            }
        }

        // Nếu AJAX không chạy (hiếm), quét toàn bộ trang làm phương án dự phòng
        if (!hasLinks) {
            val html = app.get(data, headers = defaultHeaders).text
            Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(html).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { callback(it); hasLinks = true }
                } else {
                    callback(newExtractorLink(name, "$name Video", link, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                        this.referer = data
                    })
                    hasLinks = true
                }
            }
        }

        return hasLinks
    }
}
