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
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to "https://phimmoichill.now"
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

        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = cookies
            ).text

            val cleanRes = res.replace("\\/", "/")
            var found = false
            
            // 1. Quét link từ domain mới bạn vừa tìm thấy (sotrim)
            val sotrimRegex = Regex("""https?://sotrim\.topphimmoi\.org/[^\s"']*""")
            sotrimRegex.findAll(cleanRes).forEach {
                callback(
                    newExtractorLink(
                        source = "Sotrim",
                        name = "ChillPlayer (VIP)",
                        url = it.value,
                        type = if (it.value.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://phimmoichill.now/"
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }

            // 2. Quét link M3U8 thông thường
            val m3u8Regex = Regex("""https?://[\w\.\-/]+\.m3u8[^\s"']*""")
            m3u8Regex.findAll(cleanRes).forEach {
                M3u8Helper.generateM3u8(name, it.value, data).forEach { m3u8 ->
                    callback(m3u8)
                    found = true
                }
            }
            
            // 3. Quét link MP4 trực tiếp
            if (!found) {
                val mp4Regex = Regex("""https?://[\w\.\-/]+\.mp4[^\s"']*""")
                mp4Regex.findAll(cleanRes).forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = it.value,
                            type = null
                        ) {
                            this.referer = data
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                }
            }

            found
        } catch (e: Exception) {
            false
        }
    }
}
