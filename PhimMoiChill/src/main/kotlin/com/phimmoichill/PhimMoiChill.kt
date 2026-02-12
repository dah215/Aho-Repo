package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now" 
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
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
        val episodes = doc.select(".list-episode a, #list_episodes a, a[href*='/xem/']").map {
            newEpisode(normalizeUrl(it.attr("href"))!!) { this.name = it.text().trim() }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hasLinks = false
        
        // 1. Tách Episode ID (pmXXXXX) từ URL bạn cung cấp
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1)

        if (episodeId != null) {
            // 2. Gọi thẳng vào Endpoint AJAX mà bạn thám thính được
            try {
                val ajaxUrl = "$mainUrl/ajax/get_episode_links"
                val ajaxRes = app.post(
                    ajaxUrl,
                    data = mapOf("episode_id" to episodeId),
                    headers = defaultHeaders.plus("Referer" to data)
                ).text

                // 3. Phân tích link từ JSON trả về (thường chứa link m3u8 hoặc iframe)
                val linkRegex = Regex("""https?[:\\]+[^"'<>]+?\.(?:m3u8|mp4|html)[^"'<>]*""")
                linkRegex.findAll(ajaxRes.replace("\\/", "/")).forEach { match ->
                    val link = match.value
                    if (link.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, link, data).forEach {
                            hasLinks = true
                            callback(it)
                        }
                    } else {
                        loadExtractor(link, data, subtitleCallback) {
                            hasLinks = true
                            callback(it)
                        }
                    }
                }
            } catch (e: Exception) {
                // Nếu bị Cloudflare chặn AJAX, sẽ nhảy xuống bước quét HTML
            }
        }

        // 4. Quét dự phòng trong mã nguồn HTML (Nếu AJAX bị lỗi)
        if (!hasLinks) {
            val html = app.get(data, headers = defaultHeaders).text
            val videoRegex = Regex("""https?[:\\]+[^"'<>]+?\.(?:m3u8|mp4)[^"'<>]*""")
            videoRegex.findAll(html.replace("\\/", "/")).forEach { match ->
                val rawUrl = match.value
                if (rawUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, rawUrl, data).forEach {
                        hasLinks = true
                        callback(it)
                    }
                } else {
                    callback(
                        newExtractorLink(name, "$name Player", rawUrl, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                    hasLinks = true
                }
            }
        }

        return hasLinks
    }
}
