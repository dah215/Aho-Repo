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

    // Header mô phỏng trình duyệt Chrome thật sự trên Windows
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val res = app.get(url, headers = defaultHeaders)
        val html = res.text
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
        // BƯỚC 1: Lấy Cookie và ID từ trang xem phim
        val pageResponse = app.get(data, headers = defaultHeaders)
        val html = pageResponse.text
        val cookies = pageResponse.cookies // Lưu lại cookie để gửi kèm request sau

        // Trích xuất ID thật (ví dụ: 204125)
        val episodeId = Regex(""""episodeID":\s*"(\d+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)

        if (episodeId == null) return false

        // BƯỚC 2: Gọi API chillsplayer.php với đầy đủ "vũ khí"
        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus(Pair("Referer", data)),
                cookies = cookies // Gửi kèm cookie để server tin tưởng
            ).text

            // BƯỚC 3: Xử lý link m3u8 (bao gồm cả link bị escape \/)
            val cleanRes = res.replace("\\/", "/")
            var found = false
            
            Regex("""https?://[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(cleanRes).forEach {
                val link = it.value
                if (!link.contains("ads")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                        callback(m3u8)
                        found = true
                    }
                }
            }
            
            // Dự phòng: Tìm link mp4 nếu có
            if (!found) {
                Regex("""https?://[^"'<>\s]+?\.mp4[^"'<>\s]*""").findAll(cleanRes).forEach {
                    callback(ExtractorLink(name, name, it.value, data, Qualities.P1080.value, false))
                    found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }
}
