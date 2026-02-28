package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaiZProvider())
    }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.lol"
    override var name = "HentaiZ"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    
    private val imageBaseUrl = "https://storage.haiten.org"

    // Bộ Header giả lập trình duyệt thật để vượt tường lửa
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "/browse" to "Mới Cập Nhật",
        "/browse?animationType=THREE_D" to "Hentai 3D",
        "/browse?contentRating=UNCENSORED" to "Không Che",
        "/browse?contentRating=CENSORED" to "Có Che"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) "$mainUrl${request.data}&page=$page" else "$mainUrl${request.data}?page=$page"
        val res = app.get(url, headers = headers)
        val html = res.text
        
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        val items = regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val html = app.get(url, headers = headers).text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        return regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val html = res.text
        
        val title = Regex("""title:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "HentaiZ Video"
        val posterPath = Regex("""posterImage:\{filePath:"([^"]+)"""").find(html)?.groupValues?.get(1)
        val desc = Regex("""description:"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = if (posterPath != null) "$imageBaseUrl$posterPath" else null
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text

        // 1. Tìm URL của Sonar Player
        val sonarRegex = """https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""".toRegex()
        val sonarMatch = sonarRegex.find(html) ?: sonarRegex.find(res.document.html())
        val sonarUrl = sonarMatch?.value
        val videoId = sonarMatch?.groupValues?.get(1)

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // Header đặc biệt để lừa Sonar CDN
            val iframeHeaders = mapOf(
                "User-Agent" to headers["User-Agent"]!!,
                "Referer" to "$mainUrl/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )

            // 2. Truy cập vào iframe
            val sonarRes = app.get(fixedSonarUrl, headers = iframeHeaders)
            val sonarHtml = sonarRes.text

            var linkFound = false

            // Lớp quét 1: Tìm link m3u8 trực tiếp
            val directM3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
            directM3u8Regex.findAll(sonarHtml).forEach { match ->
                val videoUrl = match.value.replace("\\/", "/")
                callback(
                    newExtractorLink("Sonar CDN", "Server VIP 1", videoUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.referer = fixedSonarUrl
                        this.headers = mapOf("Origin" to "https://play.sonar-cdn.com")
                    }
                )
                linkFound = true
            }

            // Lớp quét 2: Tìm trong cấu hình JW Player (file: "...")
            if (!linkFound) {
                val jwFileRegex = """file["']?\s*:\s*["'](https?://[^"']+)["']""".toRegex()
                jwFileRegex.findAll(sonarHtml).forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    val isM3u8 = videoUrl.contains(".m3u8")
                    callback(
                        newExtractorLink("Sonar CDN", "Server VIP 2", videoUrl, type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.referer = fixedSonarUrl
                            this.headers = mapOf("Origin" to "https://play.sonar-cdn.com")
                        }
                    )
                    linkFound = true
                }
            }

            // Lớp quét 3 (Dự phòng cuối cùng): Tự động đoán link dựa trên ID
            if (!linkFound && videoId != null) {
                val guessUrl = "https://play.sonar-cdn.com/hls/$videoId/index.m3u8"
                callback(
                    newExtractorLink("Sonar CDN", "Server Dự Phòng", guessUrl, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = fixedSonarUrl
                        this.headers = mapOf("Origin" to "https://play.sonar-cdn.com")
                    }
                )
            }
        }

        // Quét các iframe khác (nếu có)
        res.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("sonar-cdn")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
