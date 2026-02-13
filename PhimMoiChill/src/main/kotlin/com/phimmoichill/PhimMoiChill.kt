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

    // === SỬA LỖI 1: Fix URL poster ===
    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // Lấy URL ảnh với lazy loading support
    private fun getImageUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-original", "src")
        for (attr in attrs) {
            val url = el.attr(attr)
            if (!url.isNullOrEmpty() && !url.startsWith("data:image")) {
                return fixPosterUrl(url)
            }
        }
        return null
    }

    // === SỬA LỖI 2: Lấy quality ===
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
        val html = app.get(url, headers = defaultHeaders).text
        val items = Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = el.selectFirst("p")?.text()?.trim() ?: a.attr("title")
            
            // === SỬA LỖI 1: Dùng getImageUrl ===
            val poster = getImageUrl(el.selectFirst("img"))
            
            // === SỬA LỖI 2: Lấy thêm quality ===
            val qualityText = el.selectFirst(".quality, .hd, .resolution")?.text()?.trim()
            
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                this.quality = getSearchQuality(qualityText)
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
            val poster = getImageUrl(el.selectFirst("img"))
            val qualityText = el.selectFirst(".quality, .hd, .resolution")?.text()?.trim()
            
            newMovieSearchResponse(title, a.attr("href"), TvType.Movie) {
                this.posterUrl = poster
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1.entry-title, .caption")?.text()?.trim() ?: "Phim"
        
        // === SỬA LỖI 1: Dùng getImageUrl ===
        val poster = getImageUrl(doc.selectFirst(".film-poster img"))
        
        // === SỬA LỖI 2: Lấy thêm thông tin ===
        val description = doc.selectFirst(".film-content, .description")?.text()?.trim()
        val year = doc.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        val genres = doc.select(".genre a, .categories a").map { it.text() }
        
        // Tags với phụ đề/thuyết minh
        val tags = genres.toMutableList()
        if (html.contains("vietsub", ignoreCase = true) || html.contains("phụ đề", ignoreCase = true)) {
            tags.add("Phụ đề")
        }
        if (html.contains("thuyết minh", ignoreCase = true) || html.contains("lồng tiếng", ignoreCase = true)) {
            tags.add("Thuyết minh")
        }
        
        val episodes = doc.select("ul.list-episode li a, a[href*='/xem/']").map {
            newEpisode(it.attr("href")) {
                this.name = it.text().trim()
            }
        }.distinctBy { it.data }

        // GIỮ NGUYÊN như code gốc - luôn dùng TvSeries
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    // === GIỮ NGUYÊN loadLinks như code gốc ===
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = app.get(data, headers = defaultHeaders)
        val html = pageResponse.text
        val cookies = pageResponse.cookies

        // Lấy ID tập phim - GIỐNG CODE GỐC
        val episodeId = Regex("""episodeID"\s*:\s*"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: return false

        return try {
            // Bước 1: Lấy Key từ chillsplayer - GIỐNG CODE GỐC
            val responseText = app.post(
                "$mainUrl/chillsplayer.php",
                data = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = cookies
            ).text

            // Bước 2: Giải mã Key - GIỐNG CODE GỐC
            val key = Regex("""iniPlayers\("([^"]+)"""").find(responseText)?.groupValues?.get(1)
                ?: responseText.substringAfterLast("iniPlayers(\"").substringBefore("\",")
                ?: responseText.filter { it.isLetterOrDigit() }

            if (key.length < 5) return false

            // Bước 3: Ghép Key vào Server - GIỐNG CODE GỐC
            val serverList = listOf(
                Pair("https://sotrim.topphimmoi.org/manifest/$key/index.m3u8", "Chill-VIP"),
                Pair("https://sotrim.topphimmoi.org/raw/$key/index.m3u8", "Sotrim-Raw"),
                Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "Mega-HLS"),
                Pair("https://dash.megacdn.xyz/dast/$key/index.m3u8", "Mega-BK")
            )

            var found = false
            serverList.forEach { (link, serverName) ->
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }

            // Bước 4: Fallback - GIỐNG CODE GỐC
            if (!found) {
                Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(responseText).forEach {
                    M3u8Helper.generateM3u8(name, it.value, data).forEach { m3u8 ->
                        callback(m3u8)
                        found = true
                    }
                }
            }

            found
        } catch (e: Exception) {
            false
        }
    }
}
