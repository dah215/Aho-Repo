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

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img")?.attr("src")
        val description = doc.selectFirst("#film-content, .entry-content")?.text()?.trim()
        
        val isSub = title.contains("Vietsub", true) || html.contains("Vietsub", true)
        val isDub = title.contains("Thuyết Minh", true) || html.contains("Thuyết Minh", true)

        val episodes = doc.select("ul.list-episode li a, a[href*='/xem/']").mapIndexed { index, it ->
            val epName = it.text().trim()
            newEpisode(it.attr("href")) {
                this.name = if (epName.contains("Tập")) epName else "Tập ${index + 1}: $epName"
                this.episode = index + 1
            }
        }.distinctBy { it.data }

        return if (episodes.size > 1 || url.contains("/phim-bo/")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = listOfNotNull(if (isSub) "Vietsub" else null, if (isDub) "Thuyết Minh" else null)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = listOfNotNull(if (isSub) "Vietsub" else null, if (isDub) "Thuyết Minh" else null)
            }
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
        
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            val responseText = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = pageResponse.cookies
            ).text

            val key = Regex("""iniPlayers\("([^"]+)""").find(responseText)?.groupValues?.get(1)
                ?: responseText.substringAfter("iniPlayers(\"").substringBefore("\"")
            
            if (key.length < 5) return false

            val serverList = listOf(
                "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" to "Chill VIP",
                "https://dash.megacdn.xyz/raw/$key/index.m3u8" to "DASH Fast"
            )

            serverList.forEach { (link, serverName) ->
                // SỬA LỖI: Đưa referer và quality vào trong khối lambda { }
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            true
        } catch (e: Exception) { false }
    }
}
