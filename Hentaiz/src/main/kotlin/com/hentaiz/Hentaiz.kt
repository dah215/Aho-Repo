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
    
    // Domain chứa ảnh của web (lấy từ phân tích HTML của bạn)
    private val imageBaseUrl = "https://storage.haiten.org"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
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
        // Xử lý URL phân trang chuẩn xác
        val url = if (request.data.contains("?")) {
            "$mainUrl${request.data}&page=$page"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        
        val res = app.get(url, headers = headers)
        val doc = res.document
        val html = res.text
        
        // Cách 1: Thử lấy từ HTML (DOM)
        var items = doc.select("a[href*='/watch/']").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")
            
            val epText = el.selectFirst("div.absolute.bottom-2.left-2")?.text()?.trim()
            val fullTitle = if (!epText.isNullOrBlank()) "$title - $epText" else title

            newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        // Cách 2: Nếu HTML trống (do SvelteKit chưa render), dùng Regex quét dữ liệu thô trong Script
        if (items.isEmpty()) {
            // Regex khớp với cấu trúc dữ liệu bạn cung cấp: title:"...",slug:"...",episodeNumber:...,...posterImage:{filePath:"..."
            val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
            
            items = regex.findAll(html).map { match ->
                val (title, slug, ep, posterPath) = match.destructured
                val fullTitle = if (ep != "null" && ep.isNotBlank()) "$title - Tập $ep" else title
                val href = "$mainUrl/watch/$slug"
                // Ghép domain ảnh vào đường dẫn ảnh
                val poster = "$imageBaseUrl$posterPath"
                
                newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }.toList().distinctBy { it.url }
        }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val res = app.get(url, headers = headers)
        val doc = res.document
        val html = res.text
        
        // Tương tự: Thử DOM trước
        var items = doc.select("a[href*='/watch/']").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")
            val epText = el.selectFirst("div.absolute.bottom-2.left-2")?.text()?.trim()
            val fullTitle = if (!epText.isNullOrBlank()) "$title - $epText" else title

            newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        // Fallback sang Regex nếu DOM thất bại
        if (items.isEmpty()) {
            val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
            items = regex.findAll(html).map { match ->
                val (title, slug, ep, posterPath) = match.destructured
                val fullTitle = if (ep != "null" && ep.isNotBlank()) "$title - Tập $ep" else title
                val href = "$mainUrl/watch/$slug"
                val poster = "$imageBaseUrl$posterPath"
                
                newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }.toList().distinctBy { it.url }
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Untitled"
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
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
        val doc = res.document
        val html = res.text

        // 1. Xử lý iframe Sonar CDN (như trong HTML bạn gửi)
        doc.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src.isNotBlank()) {
                if (src.contains("sonar-cdn.com")) {
                    val iframeRes = app.get(src, headers = mapOf("Referer" to "$mainUrl/"))
                    val iframeHtml = iframeRes.text
                    
                    // Tìm link m3u8 trong iframe
                    Regex("""https?[:\\]+[/\\/]+[^"']+\.m3u8[^"']*""").findAll(iframeHtml).forEach {
                        val cleanUrl = it.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(name, "Sonar CDN", cleanUrl, "", ExtractorLinkType.M3U8)
                        )
                    }
                    // Tìm link mp4 trong iframe
                    Regex("""https?[:\\]+[/\\/]+[^"']+\.mp4[^"']*""").findAll(iframeHtml).forEach {
                        val cleanUrl = it.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(name, "Sonar CDN (MP4)", cleanUrl, "", ExtractorLinkType.VIDEO)
                        )
                    }
                } else {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        // 2. Quét dự phòng link m3u8 trong mã nguồn trang chính
        Regex("""https?[:\\]+[/\\/]+[^"']+\.m3u8[^"']*""").findAll(html).forEach {
            val cleanUrl = it.value.replace("\\/", "/")
            callback(
                newExtractorLink(name, "HentaiZ VIP", cleanUrl, "", ExtractorLinkType.M3U8)
            )
        }

        return true
    }
}
