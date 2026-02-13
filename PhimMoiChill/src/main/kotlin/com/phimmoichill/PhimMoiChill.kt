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

    // Header chuẩn giả lập Chrome
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    // --- HÀM HỖ TRỢ FIX LỖI 1: Xử lý ảnh Poster ---
    private fun getFullPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url" // Ghép domain nếu là link tương đối
            else -> url
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        val items = doc.select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p, .title, h3")?.text()?.trim() ?: a.attr("title")
            
            // FIX LỖI 1: Lấy ảnh từ nhiều nguồn attribute và xử lý link
            val img = el.selectFirst("img")
            val posterRaw = img?.attr("data-src") 
                ?: img?.attr("data-original") 
                ?: img?.attr("src")
            val poster = getFullPosterUrl(posterRaw)

            // FIX LỖI 2: Quét toàn bộ text trong item để tìm Nhãn (Vietsub/Tập)
            val allText = el.text()
            // Tìm chữ "Tập X" hoặc "Vietsub/Thuyết minh"
            val label = when {
                allText.contains("Tập", true) -> "Tập " + Regex("Tập\\s*(\\d+)").find(allText)?.groupValues?.get(1)
                allText.contains("Vietsub", true) -> "Vietsub"
                allText.contains("Thuyết Minh", true) -> "Thuyết Minh"
                else -> el.selectFirst(".label, .status")?.text()?.trim()
            }

            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                if (!label.isNullOrEmpty()) {
                    addQuality(label)
                }
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
            
            val img = el.selectFirst("img")
            val posterRaw = img?.attr("data-src") ?: img?.attr("src")
            val poster = getFullPosterUrl(posterRaw)
            
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        
        val title = doc.selectFirst("h1.entry-title, .caption, .title-film")?.text()?.trim() ?: "Phim"
        
        // FIX LỖI 1: Poster ở trang chi tiết
        val img = doc.selectFirst(".film-poster img, .movie-l-img img")
        val posterRaw = img?.attr("data-src") ?: img?.attr("src")
        val poster = getFullPosterUrl(posterRaw)

        val description = doc.selectFirst("#film-content, .entry-content, .description")?.text()?.trim()
        
        // Tags
        val tags = doc.select(".entry-meta li a, .tags a").map { it.text() }

        // FIX LỖI 3 & 4: Logic lấy tập phim
        // Thử tìm danh sách tập
        var episodes = doc.select("ul.list-episode li a, #list_episodes li a").mapIndexed { index, it ->
            val epName = it.text().trim()
            newEpisode(it.attr("href")) {
                this.name = if (epName.isEmpty()) "Tập ${index + 1}" else epName
                this.episode = index + 1
            }
        }.distinctBy { it.data }

        // Nếu không tìm thấy tập nào (Phim Lẻ), tự tạo 1 tập từ URL hiện tại
        if (episodes.isEmpty()) {
            // Kiểm tra xem có nút "Xem ngay" không để lấy link chính xác
            val watchUrl = doc.selectFirst("a.btn-see, a.btn-watch")?.attr("href") ?: url
            episodes = listOf(
                newEpisode(watchUrl) {
                    this.name = "Phim Lẻ"
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
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
                headers = defaultHeaders.plus(mapOf(
                    "Referer" to data, // Referer phải là trang hiện tại
                    "Origin" to mainUrl
                )),
                cookies = response.cookies
            ).text

            val key = Regex("""iniPlayers\("([^"]+)""").find(res)?.groupValues?.get(1)
                ?: res.substringAfterLast("iniPlayers(\"").substringBefore("\",")
            
            if (key.length < 5) return false

            val serverList = listOf(
                Pair("https://sotrim.topphimmoi.org/manifest/$key/index.m3u8", "VIP HLS"),
                Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "Mega Server")
            )

            serverList.forEach { (link, serverName) ->
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = serverName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // FIX LỖI 3002: Header của luồng phát
                        // Quan trọng: Truyền User-Agent và Origin vào player
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "User-Agent" to defaultHeaders["User-Agent"]!!,
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
