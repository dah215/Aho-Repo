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

        if (!sonarUrl.isNullOrBlank() && videoId != null) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // 2. Gọi API trực tiếp để lấy link
            val apiUrl = "https://play.sonar-cdn.com/api/source/$videoId"
            
            try {
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
                    val dataArray = json.get("data")
                    
                    if (dataArray != null && dataArray.isArray) {
                        dataArray.forEach { item ->
                            val file = item.get("file")?.asText() ?: return@forEach
                            val label = item.get("label")?.asText() ?: "Auto"
                            val type = item.get("type")?.asText() ?: ""
                            
                            val isM3u8 = file.contains(".m3u8") || type.contains("hls")
                            
                            // SỬ DỤNG THAM SỐ VỊ TRÍ (POSITIONAL ARGUMENTS) ĐỂ TRÁNH LỖI BIÊN DỊCH
                            // Thứ tự: source, name, url, referer, quality, isM3u8, headers
                            callback(
                                newExtractorLink(
                                    "Sonar CDN",
                                    "Server VIP ($label)",
                                    file,
                                    fixedSonarUrl,
                                    Qualities.P1080.value,
                                    isM3u8,
                                    mapOf("Origin" to "https://play.sonar-cdn.com")
                                )
                            )
                        }
                    }
                } else {
                    // Fallback: Quét Regex
                    val sonarPageRes = app.get(fixedSonarUrl, headers = mapOf("Referer" to "$mainUrl/"))
                    val sonarHtml = sonarPageRes.text
                    
                    val bruteForceRegex = """(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""".toRegex()
                    bruteForceRegex.findAll(sonarHtml).forEach { match ->
                        val videoUrl = match.value.replace("\\/", "/")
                        val isM3u8 = videoUrl.contains(".m3u8")
                        
                        // SỬ DỤNG THAM SỐ VỊ TRÍ
                        callback(
                            newExtractorLink(
                                "Sonar CDN",
                                "Server VIP (Backup)",
                                videoUrl,
                                fixedSonarUrl,
                                Qualities.Unknown.value,
                                isM3u8,
                                mapOf("Origin" to "https://play.sonar-cdn.com")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Quét các iframe khác
        res.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("sonar-cdn")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
