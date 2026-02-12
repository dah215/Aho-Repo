package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

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
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
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

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        
        // Lấy link tập phim: Cần lấy link có chữ "/xem/"
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
        // BƯỚC 1: Truy cập trang xem phim để lấy ID ẩn (Numeric ID)
        val html = app.get(data, headers = defaultHeaders).text
        
        // Trích xuất ID từ biến filmInfo: "episodeID":"12345"
        val episodeId = Regex(""""episodeID":\s*"(\d+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
        
        // Trích xuất Nonce (khóa bảo mật) nếu có
        val nonce = Regex(""""nonce":\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""

        if (episodeId == null) return false

        // BƯỚC 2: Gọi chillsplayer.php với ID số thực
        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf(
                    "qcao" to episodeId,
                    "nonce" to nonce
                ),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            var found = false
            // BƯỚC 3: Quét link m3u8 từ phản hồi của server
            Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(res.replace("\\/", "/")).forEach {
                val link = it.value
                // Loại bỏ các link quảng cáo 1 giây
                if (!link.contains("ads") && !link.contains("skipintro")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                        callback(m3u8)
                        found = true
                    }
                }
            }
            
            // Nếu chillsplayer thất bại, thử quét trực tiếp trong trang (Dự phòng)
            if (!found) {
                Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(html.replace("\\/", "/")).forEach {
                    val link = it.value
                    if (!link.contains("ads")) {
                        M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                            callback(m3u8)
                            found = true
                        }
                    }
                }
            }
            found
        } catch (e: Exception) { false }
    }
}
