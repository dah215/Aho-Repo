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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "*/*"
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

        // 1. Tìm URL của Sonar Player (play.sonar-cdn.com)
        val sonarRegex = """https?://play\.sonar-cdn\.com/watch\?v=[a-zA-Z0-9-]+""".toRegex()
        val sonarUrl = sonarRegex.find(html)?.value ?: res.document.select("iframe[src*='sonar-cdn']").attr("src")

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            // 2. Lấy nội dung player với Referer từ HentaiZ
            val sonarRes = app.get(fixedSonarUrl, headers = mapOf("Referer" to "$mainUrl/"))
            val sonarHtml = sonarRes.text

            // 3. Bóc tách link video từ cấu trúc JW Player setup
            // Tìm các chuỗi có đuôi .m3u8 hoặc .mp4
            val videoRegex = """["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""".toRegex()
            videoRegex.findAll(sonarHtml).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                
                val isM3u8 = videoUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = if (isM3u8) "Server VIP (HLS)" else "Server VIP (MP4)",
                        url = videoUrl,
                        referer = fixedSonarUrl,
                        quality = Qualities.P1080.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        // Quét các iframe khác nếu có (dự phòng)
        res.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("sonar-cdn")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
