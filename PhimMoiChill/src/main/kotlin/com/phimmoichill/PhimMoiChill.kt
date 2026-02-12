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

    // Sử dụng Headers từ sotrim.js mà bạn đã tìm thấy
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/javascript, application/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val timestamp = System.currentTimeMillis().toString() // Tạo mã tương tự _=1770898565454
        val url = if (page <= 1) "$mainUrl/${request.data}?_=$timestamp" else "$mainUrl/${request.data}?page=$page&_=$timestamp"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val items = Jsoup.parse(html).select(".movies-list .ml-item, .halim-item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst(".title, .entry-title")?.text()?.trim() ?: a.text().trim()
                val poster = el.selectFirst("img")?.attr("data-src") ?: el.selectFirst("img")?.attr("src")
                newMovieSearchResponse(title, if (a.attr("href").startsWith("http")) a.attr("href") else "$mainUrl${a.attr("href")}", TvType.Movie) {
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
        val timestamp = System.currentTimeMillis().toString()

        try {
            // Bước 1: Gọi chillsplayer kèm timestamp để bypass hệ thống kiểm tra của sotrim.js
            val playerUrl = "$mainUrl/chillsplayer.php?_=$timestamp"
            val response = app.post(
                playerUrl,
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            // Bước 2: Truy quét link m3u8 sạch
            val m3u8Regex = Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""")
            m3u8Regex.findAll(response.replace("\\/", "/")).forEach { match ->
                val link = match.value
                if (!link.contains("ads") && !link.contains("skipintro")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { 
                        callback(it)
                        hasLinks = true 
                    }
                }
            }
        } catch (e: Exception) {}

        return hasLinks
    }
}
