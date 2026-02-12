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
        
        // Lấy danh sách tập phim từ trang xem
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
        // 1. Lấy trang và Cookie
        val pageResponse = app.get(data, headers = defaultHeaders)
        val html = pageResponse.text
        val cookies = pageResponse.cookies

        // 2. Trích xuất episodeID từ filmInfo trong HTML
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            // 3. Gọi API chillsplayer.php để lấy nguồn video
            val res = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId),
                headers = mapOf(
                    "Referer" to data,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                cookies = cookies
            ).text

            val cleanRes = res.replace("\\/", "/")
            var found = false
            
            // 4. Tìm link M3U8
            val m3u8Regex = Regex("""https?://[\w\.\-/]+\.m3u8[^\s"']*""")
            m3u8Regex.findAll(cleanRes).forEach {
                M3u8Helper.generateM3u8(name, it.value, data).forEach { m3u8 ->
                    callback(m3u8)
                    found = true
                }
            }
            
            // 5. Tìm link MP4 (Sử dụng cú pháp newExtractorLink chuẩn SDK mới)
            if (!found) {
                val mp4Regex = Regex("""https?://[\w\.\-/]+\.mp4[^\s"']*""")
                mp4Regex.findAll(cleanRes).forEach {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = it.value,
                            type = null // Tự động nhận diện kiểu link
                        ) {
                            this.referer = data
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                }
            }
            
            // 6. Nếu là link nhúng (Embed) thì dùng Extractor mặc định
            if (!found && cleanRes.contains("iframe")) {
                val embedUrl = Regex("""src="([^"]+)"""").find(cleanRes)?.groupValues?.get(1)
                if (embedUrl != null) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                    found = true
                }
            }

            found
        } catch (e: Exception) {
            false
        }
    }
}
