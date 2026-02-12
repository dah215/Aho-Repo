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
        val doc = Jsoup.parse(html)
        
        val items = doc.select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            val poster = el.selectFirst("img")?.let { 
                if (it.hasAttr("data-src")) it.attr("data-src") else it.attr("src") 
            }
            // Lấy thông tin Vietsub/Tập hiện trên bìa phim
            val label = el.selectFirst(".label, .status")?.text()?.trim()

            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                if (label != null) {
                    addQuality(label) // Hiển thị nhãn trên poster
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        val poster = doc.selectFirst(".film-poster img, .movie-l-img img")?.attr("src")
        val description = doc.selectFirst("#film-content, .entry-content")?.text()?.trim()
        
        // Tìm danh sách tập phim
        val episodes = doc.select("ul.list-episode li a, #list_episodes li a").mapIndexed { index, it ->
            val epName = it.text().trim()
            newEpisode(it.attr("href")) {
                this.name = if (epName.isEmpty()) "Tập ${index + 1}" else epName
                this.episode = index + 1
            }
        }.distinctBy { it.data }.ifEmpty {
            listOf(newEpisode(url) { this.name = "Full"; this.episode = 1 })
        }

        return if (episodes.size > 1 || url.contains("phim-bo")) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = doc.select(".entry-meta li a").map { it.text() }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val html = response.text
        
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus(mapOf("Referer" to data, "Origin" to mainUrl)),
                cookies = response.cookies
            ).text

            val key = Regex("""iniPlayers\("([^"]+)""").find(res)?.groupValues?.get(1)
                ?: res.substringAfterLast("iniPlayers(\"").substringBefore("\",")
            
            if (key.length < 5) return false

            val serverList = listOf(
                Pair("https://sotrim.topphimmoi.org/manifest/$key/index.m3u8", "Chill-VIP"),
                Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "Mega-HLS")
            )

            serverList.forEach { (link, serverName) ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        // Thêm Header để fix lỗi 3002 Malformed Manifest
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
