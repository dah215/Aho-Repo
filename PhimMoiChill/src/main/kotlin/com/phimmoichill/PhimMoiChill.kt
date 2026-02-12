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

    // Header copy y hệt từ tab Network bạn chụp
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/javascript, application/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới Cập Nhật",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val timestamp = System.currentTimeMillis() // Giống tham số _= trong sotrim.js bạn tìm thấy
        val url = if (page <= 1) "$mainUrl/${request.data}?_=$timestamp" else "$mainUrl/${request.data}?page=$page&_=$timestamp"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            // Sử dụng selector chuẩn từ file JSON bạn gửi
            val items = doc.select(".list-film .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
                // Ưu tiên data-src để fix lỗi Unknown/Crash
                val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                
                newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
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

        // 1. Tấn công qua chillsplayer (Cổng chính bạn tìm thấy)
        try {
            val playerRes = app.post(
                "$mainUrl/chillsplayer.php?_=$timestamp",
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            // Chỉ lấy m3u8 sạch, bỏ qua link ads gây lỗi 1s
            val m3u8Regex = Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""")
            m3u8Regex.findAll(playerRes.replace("\\/", "/")).forEach { match ->
                val link = match.value
                if (!link.contains("ads") && !link.contains("skipintro")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { 
                        callback(it)
                        hasLinks = true 
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Dự phòng qua api.phimmoi.mx (Phát hiện từ file JSON)
        if (!hasLinks) {
            try {
                val apiRes = app.get("https://api.phimmoi.mx/api/episode/$episodeId", headers = defaultHeaders).text
                Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(apiRes).forEach {
                    M3u8Helper.generateM3u8(name, it.value.replace("\\/", "/"), data).forEach { link ->
                        callback(link)
                        hasLinks = true
                    }
                }
            } catch (e: Exception) {}
        }

        return hasLinks
    }
}
