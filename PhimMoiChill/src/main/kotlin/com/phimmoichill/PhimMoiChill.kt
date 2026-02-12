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
        "Accept" to "application/json, text/javascript, */*; q=0.01",
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
        val items = Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
        val html = app.get(url, headers = defaultHeaders).text
        return Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        
        val episodes = doc.select("ul.list-episode li a, a[href*='/xem/']").map {
            newEpisode(it.attr("href")) {
                this.name = it.text().trim()
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = app.get(data, headers = defaultHeaders)
        val html = pageResponse.text
        val cookies = pageResponse.cookies

        // Lấy episodeID từ filmInfo trong HTML
        val episodeId = Regex(""""episodeID":\s*"?(\d+)"?""").find(html)?.groupValues?.get(1)

        if (episodeId == null) return false

        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = cookies
            ).text

            val cleanRes = res.replace("\\/", "/")
            var found = false
            
            // 1. Xử lý link m3u8 thông qua helper
            Regex("""https?://[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(cleanRes).forEach {
                val link = it.value
                M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                    callback(m3u8)
                    found = true
                }
            }
            
            // 2. Xử lý link mp4 (SỬA LỖI THAM SỐ BUILD TẠI ĐÂY)
            if (!found) {
                Regex("""https?://[^"'<>\s]+?\.mp4[^"'<>\s]*""").findAll(cleanRes).forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = it.value,
                            referer = data, // Đã kiểm tra: trong phiên bản SDK này tham số là 'referer' (viết thường)
                            quality = Qualities.P1080.value
                        )
                    )
                    found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }
}
