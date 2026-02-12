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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    // Sửa lại cấu trúc MainPage để không bị lỗi "Operation not implemented"
    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        // Thêm try-catch để tránh văng app từ màn hình chính
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val items = Jsoup.parse(html).select(".movies-list .ml-item, .halim-item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val title = el.selectFirst(".title, .entry-title")?.text()?.trim() ?: a.text().trim()
                val poster = el.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() } 
                             ?: el.selectFirst("img")?.attr("src")
                newMovieSearchResponse(title, if (a.attr("href").startsWith("http")) a.attr("href") else "$mainUrl${a.attr("href")}", TvType.Movie) {
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
        // Fix lỗi "Unknown" trên tiêu đề
        val title = doc.selectFirst("h1.entry-title, .halim-movie-title")?.text()?.trim() ?: "PhimMoiChill"
        val poster = doc.selectFirst(".halim-movie-poster img")?.attr("src")
        
        val episodes = doc.select("a[href*='/xem/']").map {
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
        var hasLinks = false
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1)

        // Tích hợp da88.xml và chillsplayer.php dựa trên Initiator của bạn
        if (episodeId != null) {
            val requests = listOf(
                "$mainUrl/da88.xml",
                "$mainUrl/chillsplayer.php?qcao=$episodeId"
            )

            for (reqUrl in requests) {
                try {
                    val res = if (reqUrl.contains("chillsplayer")) {
                        app.post(reqUrl.split("?")[0], data = mapOf("qcao" to episodeId), headers = defaultHeaders).text
                    } else {
                        app.get(reqUrl, headers = defaultHeaders).text
                    }

                    // Tìm link m3u8 và loại bỏ ads
                    Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""").findAll(res.replace("\\/", "/")).forEach {
                        val link = it.value
                        if (!link.contains("ads") && !link.contains("skipintro")) {
                            M3u8Helper.generateM3u8(name, link, data).forEach { m3u8 ->
                                callback(m3u8)
                                hasLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {}
                if (hasLinks) break
            }
        }
        return hasLinks
    }
}
