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
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    // Fix URL - chuyển relative URL thành absolute
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("http")) url else {
            val cleanUrl = if (url.startsWith("/")) url else "/$url"
            "$mainUrl$cleanUrl"
        }
    }

    // Lấy URL ảnh từ element, hỗ trợ lazy loading
    private fun getImageUrl(imgElement: org.jsoup.nodes.Element?): String? {
        if (imgElement == null) return null
        
        val lazyAttrs = listOf("data-src", "data-original", "data-lazy-src", "data-lazy")
        for (attr in lazyAttrs) {
            val url = imgElement.attr(attr)
            if (!url.isNullOrEmpty() && url != "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7") {
                return fixUrl(url)
            }
        }
        
        // Fallback to src
        val src = imgElement.attr("src")
        if (!src.isNullOrEmpty()) {
            return fixUrl(src)
        }
        return null
    }

    // Lấy SearchQuality từ string
    private fun getSearchQuality(quality: String?): SearchQuality? {
        return when (quality?.uppercase()?.trim()) {
            "4K", "2160P", "UHD" -> SearchQuality.UHD
            "HD", "720P" -> SearchQuality.HD
            "FULL HD", "FHD", "1080P" -> SearchQuality.HD
            "SD", "480P" -> SearchQuality.SD
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        
        return try {
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            val items = doc.select(".list-film .item, .movie-list .movie-item, .film-list .film-item, .items .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                
                // Lấy title
                val title = el.selectFirst("p.name, .title, .film-name, .movie-name")?.text()?.trim()
                    ?: a.attr("title")
                    ?: a.text()
                    ?: return@mapNotNull null
                
                // Lấy poster URL
                val poster = getImageUrl(el.selectFirst("img"))
                
                // Lấy thông tin bổ sung
                val episodeText = el.selectFirst(".episode, .tap, .current, .ep")?.text()?.trim()
                val qualityText = el.selectFirst(".quality, .hd, .resolution, .tag-quality")?.text()?.trim()
                val yearText = el.selectFirst(".year, .release-year")?.text()?.trim()
                
                // Lấy href
                val href = a.attr("href") ?: return@mapNotNull null
                val fixedHref = fixUrl(href) ?: href
                
                // Xác định loại phim
                val isTvSeries = episodeText != null || href.contains("/phim-bo/")
                
                if (isTvSeries) {
                    newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = yearText?.toIntOrNull()
                        this.quality = getSearchQuality(qualityText)
                    }
                } else {
                    newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = yearText?.toIntOrNull()
                        this.quality = getSearchQuality(qualityText)
                    }
                }
            }.filter { it.name.isNotBlank() }
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            logError("Error loading main page: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
            val html = app.get(url, headers = defaultHeaders).text
            val doc = Jsoup.parse(html)
            
            doc.select(".list-film .item, .movie-list .movie-item, .search-result .item, .items .item").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                
                val title = el.selectFirst("p.name, .title, .film-name")?.text()?.trim()
                    ?: a.attr("title")
                    ?: a.text()
                    ?: return@mapNotNull null
                
                val poster = getImageUrl(el.selectFirst("img"))
                
                // Lấy chất lượng
                val qualityText = el.selectFirst(".quality, .hd, .resolution")?.text()?.trim()
                
                val href = a.attr("href") ?: return@mapNotNull null
                val fixedHref = fixUrl(href) ?: href
                
                val isTvSeries = href.contains("/phim-bo/")
                
                if (isTvSeries) {
                    newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = getSearchQuality(qualityText)
                    }
                } else {
                    newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = getSearchQuality(qualityText)
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
            val title = doc.selectFirst("h1.entry-title, .film-title, .movie-title, .title")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore("-")?.trim()
                ?: "Phim"
            
            // Lấy poster
            val poster = getImageUrl(
                doc.selectFirst(".film-poster img, .poster img, .movie-thumb img, .thumb img, .image img")
            )
            
            // Lấy mô tả
            val description = doc.selectFirst(".film-content, .description, .movie-desc, .content, .plot")?.text()?.trim()
            
            // Lấy năm
            val year = doc.selectFirst(".release-year, .year, [class*='year']")?.text()?.trim()?.toIntOrNull()
            
            // Lấy thể loại
            val genres = doc.select(".genre a, .categories a, .category a").map { it.text() }
            
            // Lấy thông tin chất lượng
            val qualityText = doc.selectFirst(".quality, .hd, .resolution")?.text()?.trim()
            
            // Lấy thông tin phụ đề/thuyết minh từ HTML
            val tags = genres.toMutableList()
            if (html.contains("vietsub", ignoreCase = true) || html.contains("phụ đề", ignoreCase = true)) {
                tags.add("Phụ đề")
            }
            if (html.contains("thuyết minh", ignoreCase = true) || html.contains("lồng tiếng", ignoreCase = true)) {
                tags.add("Thuyết minh")
            }
            
            // Lấy danh sách tập
            val episodes = doc.select("ul.list-episode li a, .episode-list a, .list-ep a, a[href*='/xem/'], a[href*='/watch/']").mapNotNull {
                val epUrl = it.attr("href") ?: return@mapNotNull null
                val epName = it.text().trim().ifEmpty { "Tập ${it.attr("data-episode")}" }
                newEpisode(fixUrl(epUrl) ?: epUrl) {
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
                    this.tags = tags
                }
            } else if (episodes.size == 1) {
                newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
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
        try {
            val pageResponse = app.get(data, headers = defaultHeaders)
            val html = pageResponse.text
            val cookies = pageResponse.cookies

            // Headers cho video request
            val videoHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "$mainUrl/"
            )

            // Lấy episode ID
            val episodeId = extractEpisodeId(html)

            // Lấy key từ chillsplayer
            val key = if (episodeId != null) {
                try {
                    val responseText = app.post(
                        "$mainUrl/chillsplayer.php",
                        data = mapOf("qcao" to episodeId, "sv" to "0"),
                        headers = defaultHeaders + ("Referer" to data),
                        cookies = cookies
                    ).text
                    extractKey(responseText)
                } catch (e: Exception) {
                    null
                }
            } else null

            // Danh sách server - CHỈ add những server có key hợp lệ
            if (!key.isNullOrEmpty() && key.length >= 5) {
                val serverList = listOf(
                    "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" to "Chill-VIP",
                    "https://sotrim.topphimmoi.org/raw/$key/index.m3u8" to "Sotrim-Raw",
                    "https://dash.megacdn.xyz/raw/$key/index.m3u8" to "Mega-HLS",
                    "https://dash.megacdn.xyz/dast/$key/index.m3u8" to "Mega-BK"
                )

                serverList.forEach { (link, serverName) ->
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = serverName,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = videoHeaders
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }

            // Tìm link trực tiếp trong HTML (m3u8, mp4)
            extractDirectLinks(html, data, callback, videoHeaders)

            // Tìm phụ đề
            extractSubtitles(html, subtitleCallback)

            return true

        } catch (e: Exception) {
            logError("loadLinks error: ${e.message}")
            return false
        }
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
        
        // Fallback parse thủ công
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

    private suspend fun extractDirectLinks(
        html: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit,
        videoHeaders: Map<String, String>
    ) {
        // Tìm M3U8 links
        val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        m3u8Pattern.findAll(html).forEach { match ->
            val url = match.value
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
                    this.headers = videoHeaders
                    this.quality = Qualities.P1080.value
                }
            )
        }

        // Tìm MP4 links
        val mp4Pattern = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
        mp4Pattern.findAll(html).forEach { match ->
            val url = match.value
            
            callback(
                newExtractorLink(
                    source = "Direct-MP4",
                    name = "Direct-MP4",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.headers = videoHeaders
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    private fun extractSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // Tìm VTT subtitles
            val vttPattern = Regex("""https?://[^\s"'<>]+\.vtt[^\s"'<>]*""")
            vttPattern.findAll(html).forEach { match ->
                try {
                    subtitleCallback(
                        SubtitleFile(lang = "Vietnamese", url = match.value)
                    )
                } catch (e: Exception) { }
            }
            
            // Tìm SRT subtitles
            val srtPattern = Regex("""https?://[^\s"'<>]+\.srt[^\s"'<>]*""")
            srtPattern.findAll(html).forEach { match ->
                try {
                    subtitleCallback(
                        SubtitleFile(lang = "Vietnamese", url = match.value)
                    )
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    private fun logError(message: String) {
        println("[PhimMoiChill] $message")
    }
}
