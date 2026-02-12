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

    // 1. HEADER CHUẨN TỪ SCREENSHOT CỦA BẠN
    // Server kiểm tra rất kỹ User-Agent, copy y hệt từ ảnh bạn gửi
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    // 2. SỬA SELECTOR MÀN HÌNH CHÍNH (Dựa trên file json bạn gửi)
    // Cấu trúc: div.block > ul.list-film > li.item
    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ",
        "list/phim-hot" to "Phim Thịnh Hành"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Thêm timestamp để tránh Cache (như cách sotrim.js làm)
        val timestamp = System.currentTimeMillis()
        val url = if (page <= 1) "$mainUrl/${request.data}?_=$timestamp" else "$mainUrl/${request.data}?page=$page&_=$timestamp"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            // Selector chính xác từ file json: .list-film .item
            val items = doc.select(".list-film .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst("p")?.text()?.trim() ?: el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
                
                // QUAN TRỌNG: Lấy data-src trước (lazyload), nếu không có mới lấy src
                val imgTag = el.selectFirst("img")
                val poster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } 
                             ?: imgTag?.attr("src")
                
                val link = a.attr("href")
                if (link.contains("phimmoichill")) {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    }
                } else null
            }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val html = app.get(url, headers = defaultHeaders).text
        // Logic search y hệt mainpage
        return Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text() ?: el.selectFirst("h3")?.text() ?: ""
            val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, h1.caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        val desc = doc.selectFirst(".film-content")?.text()?.trim()

        // Lấy danh sách tập: Tìm tất cả thẻ <a> có href chứa "/xem/"
        val episodes = doc.select("a[href*='/xem/']").map {
            newEpisode(it.attr("href")) {
                this.name = it.text().trim()
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = desc
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
        val timestamp = System.currentTimeMillis()

        // CHIẾN THUẬT: Kết hợp thông tin từ file txt (API) và Screenshot (Header)
        
        // 1. Thử gọi trực tiếp chillsplayer.php (Cổng chính)
        try {
            val playerRes = app.post(
                "$mainUrl/chillsplayer.php?_=$timestamp", // Thêm timestamp như ảnh sotrim.js
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            // Quét link m3u8 sạch (Bỏ qua ads)
            Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(playerRes.replace("\\/", "/")).forEach { match ->
                val link = match.value
                if (!link.contains("ads") && !link.contains("google")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach {
                        callback(it)
                        hasLinks = true
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Thử gọi API phimmoi.mx (Phát hiện từ file JSON)
        if (!hasLinks) {
            try {
                // API này thường trả về JSON chứa link
                val apiUrl = "https://api.phimmoi.mx/api/episode/$episodeId" 
                val apiRes = app.get(apiUrl, headers = defaultHeaders.plus("Origin" to mainUrl)).text
                
                Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(apiRes).forEach { match ->
                    val link = match.value.replace("\\/", "/")
                    M3u8Helper.generateM3u8(name, link, data).forEach {
                        callback(it)
                        hasLinks = true
                    }
                }
            } catch (e: Exception) {}
        }
        
        return hasLinks
    }
}
