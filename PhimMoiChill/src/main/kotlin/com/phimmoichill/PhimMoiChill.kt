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
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            val items = doc.select(".list-film .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
                val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
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
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        
        // Tìm danh sách tập phim và lấy Numeric ID (data-id)
        val episodes = doc.select("ul.list-episode li a, .list-episodes a").map {
            val href = it.attr("href")
            val id = it.attr("data-id") // Đây là Numeric ID quan trọng
            newEpisode(href) {
                this.name = it.text().trim()
                this.description = id // Lưu ID vào description để dùng ở loadLinks
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
        // Lấy ID từ trang xem phim nếu description rỗng
        val html = app.get(data, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        // Tìm ID tập phim từ Script (Biến filmInfo trong watch_page.html)
        val episodeId = Regex(""""episodeID":\s*"(\d+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
        
        // Tìm Nonce (khóa bảo mật) nếu có
        val nonce = Regex(""""nonce":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

        if (episodeId == null) return false

        return try {
            // Gọi API chillsplayer.php với ID số thực tế
            val timestamp = System.currentTimeMillis()
            val res = app.post(
                "$mainUrl/chillsplayer.php?_=$timestamp",
                data = mapOf(
                    "qcao" to episodeId,
                    "nonce" to (nonce ?: "")
                ),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            var found = false
            // Quét link m3u8 và loại bỏ rác
            Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(res.replace("\\/", "/")).forEach {
                val link = it.value
                if (!link.contains("ads") && !link.contains("skipintro")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                        callback(m3u8)
                        found = true
                    }
                }
            }
            found
        } catch (e: Exception) { false }
    }
}
