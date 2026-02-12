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

    // Helper function để fix URL ảnh
    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    // Helper function để lấy URL ảnh từ nhiều attribute
    private fun getImageUrl(imgElement: org.jsoup.nodes.Element?): String? {
        if (imgElement == null) return null
        
        // Thử nhiều attribute theo thứ tự ưu tiên (lazy loading)
        val attributes = listOf("data-src", "data-original", "data-lazy-src", "src")
        for (attr in attributes) {
            val url = imgElement.attr(attr)
            if (!url.isNullOrEmpty() && url != "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7") {
                return fixPosterUrl(url)
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            // Thử nhiều selector khác nhau
            val items = doc.select(".list-film .item, .movie-list .movie-item, .film-list .film-item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                
                // Lấy title từ nhiều nguồn
                val title = el.selectFirst("p.name, .title, .film-name")?.text()?.trim()
                    ?: a.attr("title")
                    ?: a.text()
                    ?: return@mapNotNull null
                
                // Lấy poster với nhiều cách
                val poster = getImageUrl(el.selectFirst("img"))
                
                // Lấy thông tin bổ sung
                val episodeInfo = el.selectFirst(".episode, .tap, .current")?.text()?.trim()
                val year = el.selectFirst(".year, .release-year")?.text()?.trim()
                val quality = el.selectFirst(".quality, .hd, .resolution")?.text()?.trim()
                
                // Kiểm tra xem là phim bộ hay phim lẻ
                val href = a.attr("href") ?: return@mapNotNull null
                val isTvSeries = episodeInfo != null || href.contains("/phim-bo/") || doc.select("ul.list-episode").isNotEmpty()
                
                if (isTvSeries) {
                    newTvSeriesSearchResponse(title, fixPosterUrl(href) ?: href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year?.toIntOrNull()
                        // Hiển thị thông tin tập hiện tại
                        episodeInfo?.let { this.episodes = listOf(it) }
                    }
                } else {
                    newMovieSearchResponse(title, fixPosterUrl(href) ?: href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year?.toIntOrNull()
                        this.quality = getQuality(quality)
                    }
                }
            }.filter { it.name.isNotBlank() }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            // Log error nhưng không crash
            logError("Error loading main page: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun getQuality(quality: String?): Int? {
        return when (quality?.uppercase()) {
            "4K", "2160P" -> Qualities.P2160.value
            "HD", "720P" -> Qualities.P720.value
            "FULL HD", "FHD", "1080P" -> Qualities.P1080.value
            "SD", "480P" -> Qualities.P480.value
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            doc.select(".list-film .item, .movie-list .movie-item, .search-result .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                
                val title = el.selectFirst("p.name, .title, .film-name")?.text()?.trim()
                    ?: a.attr("title")
                    ?: a.text()
                    ?: return@mapNotNull null
                
                val poster = getImageUrl(el.selectFirst("img"))
                val href = a.attr("href") ?: return@mapNotNull null
                
                val episodeInfo = el.selectFirst(".episode, .tap")?.text()?.trim()
                val isTvSeries = episodeInfo != null || href.contains("/phim-bo/")
                
                if (isTvSeries) {
                    newTvSeriesSearchResponse(title, fixPosterUrl(href) ?: href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, fixPosterUrl(href) ?: href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
        } catch (e: Exception) {
            logError("Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            // Lấy title
            val title = doc.selectFirst("h1.entry-title, .film-title, .movie-title")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                ?: "Phim"
            
            // Lấy poster
            val poster = getImageUrl(doc.selectFirst(".film-poster img, .poster img, .movie-thumb img"))
            
            // Lấy mô tả
            val description = doc.selectFirst(".film-content, .description, .movie-desc")?.text()?.trim()
            
            // Lấy năm
            val year = doc.selectFirst(".release-year, .year")?.text()?.trim()?.toIntOrNull()
            
            // Lấy thể loại
            val genres = doc.select(".genre a, .categories a").map { it.text() }
            
            // Lấy thông tin phụ đề/thuyết minh
            val hasSubtitle = html.contains("phụ đề") || html.contains("vietsub") || html.contains("sub")
            val hasDubbing = html.contains("thuyết minh") || html.contains("lồng tiếng") || html.contains("dub")
            
            // Lấy danh sách tập
            val episodes = doc.select("ul.list-episode li a, .episode-list a, a[href*='/xem/']").mapNotNull {
                val epUrl = it.attr("href") ?: return@mapNotNull null
                val epName = it.text().trim().ifEmpty { "Tập ${it.attr("data-episode")}" }
                newEpisode(fixPosterUrl(epUrl) ?: epUrl) {
                    this.name = epName
                }
            }.distinctBy { it.data }
            
            // Xác định loại phim
            val isTvSeries = episodes.size > 1 || html.contains("phim-bo") || url.contains("phim-bo")
            
            if (isTvSeries && episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    // Thêm tags cho phụ đề/thuyết minh
                    if (hasSubtitle) this.tags = (this.tags ?: emptyList()) + "Phụ đề"
                    if (hasDubbing) this.tags = (this.tags ?: emptyList()) + "Thuyết minh"
                }
            } else if (episodes.size == 1) {
                // Phim lẻ có 1 tập
                newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    if (hasSubtitle) this.tags = (this.tags ?: emptyList()) + "Phụ đề"
                    if (hasDubbing) this.tags = (this.tags ?: emptyList()) + "Thuyết minh"
                }
            } else {
                // Không có episode info, coi như phim lẻ với URL hiện tại
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = genres
                    if (hasSubtitle) this.tags = (this.tags ?: emptyList()) + "Phụ đề"
                    if (hasDubbing) this.tags = (this.tags ?: emptyList()) + "Thuyết minh"
                }
            }
        } catch (e: Exception) {
            logError("Load error: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        
        try {
            val pageResponse = app.get(data, headers = defaultHeaders)
            val html = pageResponse.text
            val cookies = pageResponse.cookies

            // Thử nhiều pattern để lấy episode ID
            val episodeId = extractEpisodeId(html)
            
            if (episodeId == null) {
                // Thử tìm link trực tiếp nếu không có episode ID
                foundAny = extractDirectLinks(html, data, callback)
                return foundAny
            }

            // Lấy key từ chillsplayer
            val responseText = try {
                app.post(
                    "$mainUrl/chillsplayer.php",
                    data = mapOf("qcao" to episodeId, "sv" to "0"),
                    headers = defaultHeaders.plus("Referer" to data),
                    cookies = cookies
                ).text
            } catch (e: Exception) {
                logError("Error calling chillsplayer: ${e.message}")
                // Thử tìm link trực tiếp
                return extractDirectLinks(html, data, callback)
            }

            // Giải mã key
            val key = extractKey(responseText)
            
            if (key.isNullOrEmpty() || key.length < 5) {
                // Fallback: tìm link trực tiếp
                return extractDirectLinks(html, data, callback)
            }

            // Danh sách server với nhiều pattern hơn
            val serverList = listOf(
                Triple("https://sotrim.topphimmoi.org/manifest/$key/index.m3u8", "Chill-VIP", true),
                Triple("https://sotrim.topphimmoi.org/raw/$key/index.m3u8", "Sotrim-Raw", true),
                Triple("https://dash.megacdn.xyz/raw/$key/index.m3u8", "Mega-HLS", true),
                Triple("https://dash.megacdn.xyz/dast/$key/index.m3u8", "Mega-BK", true),
                Triple("https://player.phimmoichill.now/manifest/$key/index.m3u8", "Chill-Player", false)
            )

            serverList.forEach { (link, serverName, isPrimary) ->
                try {
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = if (isPrimary) Qualities.P1080.value else Qualities.P720.value
                        }
                    )
                    foundAny = true
                } catch (e: Exception) {
                    // Silent fail cho từng server, không throw error
                    logError("Failed to add server $serverName: ${e.message}")
                }
            }

            // Tìm thêm link trực tiếp trong response
            if (!foundAny) {
                foundAny = extractDirectLinks(responseText, data, callback)
            }

            // Thử tìm link trực tiếp trong HTML gốc nếu vẫn không có
            if (!foundAny) {
                foundAny = extractDirectLinks(html, data, callback)
            }

            // Tìm phụ đề
            extractSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            // Không throw error, chỉ log và trả về false
            logError("loadLinks error: ${e.message}")
        }

        return foundAny
    }

    private fun extractEpisodeId(html: String): String? {
        val patterns = listOf(
            Regex("""episodeID"\s*:\s*"(\d+)""""),
            Regex("""episodeID\s*=\s*["']?(\d+)["']?"""),
            Regex("""data-id\s*=\s*["'](\d+)["']"""),
            Regex("""data-episode-id\s*=\s*["'](\d+)["']"""),
            Regex("""idEpisode\s*=\s*["']?(\d+)["']?"""),
            Regex(""""id"\s*:\s*(\d+)"""),
            Regex("""episode_id\s*=\s*["']?(\d+)["']?""")
        )
        
        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun extractKey(responseText: String): String? {
        val patterns = listOf(
            Regex("""iniPlayers\("([^"]+)""""),
            Regex("""key\s*=\s*["']([^"']+)["']"""),
            Regex("""playerKey\s*=\s*["']([^"']+)["']"""),
            Regex(""""key"\s*:\s*"([^"]+)""""),
            Regex("""data-key\s*=\s*["']([^"']+)["']""")
        )
        
        for (pattern in patterns) {
            pattern.find(responseText)?.groupValues?.get(1)?.let { 
                if (it.length >= 5) return it 
            }
        }
        
        // Fallback: parse thủ công
        val startMarkers = listOf("iniPlayers(\"", "key\":\"", "key='")
        for (marker in startMarkers) {
            val start = responseText.indexOf(marker)
            if (start != -1) {
                val keyStart = start + marker.length
                val keyEnd = responseText.indexOfAny(charArrayOf('"', '\'', ','), keyStart)
                if (keyEnd != -1) {
                    val key = responseText.substring(keyStart, keyEnd)
                    if (key.length >= 5) return key
                }
            }
        }
        
        return null
    }

    private fun extractDirectLinks(html: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        
        // Tìm M3U8 links
        val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        m3u8Pattern.findAll(html).forEach { match ->
            val url = match.value
            try {
                val name = when {
                    url.contains("manifest") -> "Chill-Manifest"
                    url.contains("raw") -> "Chill-Raw"
                    url.contains("megacdn") -> "MegaCDN"
                    else -> "Direct-M3U8"
                }
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            } catch (e: Exception) {
                // Silent fail
            }
        }

        // Tìm MP4 links
        val mp4Pattern = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
        mp4Pattern.findAll(html).forEach { match ->
            val url = match.value
            try {
                callback(
                    newExtractorLink(
                        source = "Direct-MP4",
                        name = "Direct-MP4",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            } catch (e: Exception) {
                // Silent fail
            }
        }

        return found
    }

    private fun extractSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // Tìm subtitle trong HTML
            val subtitlePatterns = listOf(
                Regex("""subtitle"\s*:\s*\[([^\]]+)\]"""),
                Regex("""sub"\s*:\s*\[([^\]]+)\]"""),
                Regex("""tracks\s*=\s*\[([^\]]+)\]""")
            )

            for (pattern in subtitlePatterns) {
                pattern.find(html)?.groupValues?.get(1)?.let { subArray ->
                    // Parse subtitle URLs
                    val urlPattern = Regex("""https?://[^\s"'<>]+\.vtt[^\s"'<>]*""")
                    urlPattern.findAll(subArray).forEach { match ->
                        try {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = "Vietnamese",
                                    url = match.value
                                )
                            )
                        } catch (e: Exception) {
                            // Silent fail
                        }
                    }
                }
            }

            // Tìm direct subtitle links
            val vttPattern = Regex("""https?://[^\s"'<>]+\.vtt[^\s"'<>]*""")
            vttPattern.findAll(html).forEach { match ->
                try {
                    subtitleCallback(
                        SubtitleFile(
                            lang = "Vietnamese",
                            url = match.value
                        )
                    )
                } catch (e: Exception) {
                    // Silent fail
                }
            }
        } catch (e: Exception) {
            // Silent fail - không quan trọng nếu không tìm thấy subtitle
        }
    }

    private fun logError(message: String) {
        // Có thể thay bằng logging framework nếu cần
        println("[PhimMoiChill] $message")
    }
}
