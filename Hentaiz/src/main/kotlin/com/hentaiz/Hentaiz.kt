package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

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
    private val mapper = jacksonObjectMapper()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
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

    private fun decodeUnicode(input: String): String {
        var res = input
        val regex = "\\\\u([0-9a-fA-F]{4})".toRegex()
        regex.findAll(input).forEach { match ->
            val charCode = match.groupValues[1].toInt(16).toChar()
            res = res.replace(match.value, charCode.toString())
        }
        return res
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
        val rawDesc = Regex("""description:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val desc = decodeUnicode(rawDesc).replace(Regex("<[^>]*>"), "").trim()

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
            val foundLinks = mutableSetOf<String>()

            // --- CHIẾN THUẬT 1: Gọi API (Giả lập trình duyệt) ---
            if (videoId != null) {
                try {
                    val apiUrl = "https://play.sonar-cdn.com/api/source/$videoId"
                    val apiHeaders = mapOf(
                        "User-Agent" to headers["User-Agent"]!!,
                        "Referer" to fixedSonarUrl,
                        "Origin" to "https://play.sonar-cdn.com",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    )
                    val apiRes = app.post(apiUrl, headers = apiHeaders, data = mapOf("r" to "$mainUrl/", "d" to "hentaiz.lol"))
                    if (apiRes.code == 200) {
                        val json = mapper.readTree(apiRes.text)
                        json.get("data")?.forEach { item ->
                            val file = item.get("file")?.asText()
                            if (!file.isNullOrBlank()) foundLinks.add(file)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // --- CHIẾN THUẬT 2: Quét HTML của trang Player ---
            val sonarRes = app.get(fixedSonarUrl, headers = mapOf("Referer" to "$mainUrl/"))
            val sonarHtml = sonarRes.text

            // Regex 1: Tìm file: "..." (JW Player chuẩn)
            Regex("""file\s*:\s*["']([^"']+)["']""").findAll(sonarHtml).forEach { 
                foundLinks.add(it.groupValues[1].replace("\\/", "/")) 
            }

            // Regex 2: Tìm bất kỳ link m3u8/mp4 nào (Thô bạo)
            Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""").findAll(sonarHtml).forEach {
                foundLinks.add(it.value.replace("\\/", "/"))
            }

            // --- XỬ LÝ LINK TÌM ĐƯỢC ---
            foundLinks.forEach { link ->
                val isM3u8 = link.contains(".m3u8")
                val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                // Tạo 2 phiên bản cho mỗi link để dự phòng lỗi 3002
                
                // Option A: Referer là trang Player (Thường dùng cho HLS)
                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (Player)",
                        url = link,
                        type = type
                    ) {
                        this.referer = fixedSonarUrl
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Origin" to "https://play.sonar-cdn.com")
                    }
                )

                // Option B: Referer là trang Web chính (Dự phòng)
                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (Site)",
                        url = link,
                        type = type
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value
                    }
                )
            }
        }

        // Quét các iframe khác (Dood, Streamwish...)
        res.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("sonar-cdn")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
