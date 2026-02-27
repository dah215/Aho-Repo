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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    // Cập nhật lại đường dẫn chuẩn theo cấu trúc SvelteKit của web
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
        // Xử lý URL có chứa tham số query (?)
        val url = if (request.data.contains("?")) {
            "$mainUrl${request.data}&page=$page"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Bắt chính xác thẻ <a> chứa link phim dựa trên HTML bạn cung cấp
        val items = doc.select("a[href^=/watch/]").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")
            
            // Lấy số tập (VD: Tập 4) để ghép vào tên phim cho dễ nhìn
            val epText = el.selectFirst("div.absolute.bottom-2.left-2")?.text()?.trim()
            val fullTitle = if (!epText.isNullOrBlank()) "$title - $epText" else title

            newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val doc = app.get(url, headers = headers).document
        
        return doc.select("a[href^=/watch/]").mapNotNull { el ->
            val href = fixUrl(el.attr("href"))
            val title = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")
            
            val epText = el.selectFirst("div.absolute.bottom-2.left-2")?.text()?.trim()
            val fullTitle = if (!epText.isNullOrBlank()) "$title - $epText" else title

            newMovieSearchResponse(fullTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Untitled"
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        // Vì mỗi URL /watch/... tương ứng với 1 tập cụ thể, ta trả về dạng Movie luôn để Cloudstream phát ngay lập tức
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

        // 1. Quét tìm iframe chứa video (Dựa trên dữ liệu bạn cung cấp: play.sonar-cdn.com)
        doc.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") })
            if (src.isNotBlank()) {
                if (src.contains("sonar-cdn.com")) {
                    // Truy cập vào iframe để lấy link m3u8 thực sự
                    val iframeRes = app.get(src, headers = mapOf("Referer" to "$mainUrl/"))
                    val iframeHtml = iframeRes.text
                    
                    // Tìm link m3u8 (Hỗ trợ cả trường hợp link bị escape \/)
                    Regex("""https?[:\\]+[/\\/]+[^"']+\.m3u8[^"']*""").findAll(iframeHtml).forEach {
                        val cleanUrl = it.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(name, "Sonar CDN", cleanUrl, src, Qualities.Unknown.value, true)
                        )
                    }
                    
                    // Tìm link mp4 dự phòng
                    Regex("""https?[:\\]+[/\\/]+[^"']+\.mp4[^"']*""").findAll(iframeHtml).forEach {
                        val cleanUrl = it.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(name, "Sonar CDN (MP4)", cleanUrl, src, Qualities.Unknown.value, false)
                        )
                    }
                } else {
                    // Nếu là các server khác (dood, streamwish...)
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        // 2. Quét dự phòng link m3u8 nằm trực tiếp trong mã nguồn trang
        Regex("""https?[:\\]+[/\\/]+[^"']+\.m3u8[^"']*""").findAll(html).forEach {
            val cleanUrl = it.value.replace("\\/", "/")
            callback(
                newExtractorLink(name, "HentaiZ VIP", cleanUrl, data, Qualities.Unknown.value, true)
            )
        }

        return true
    }
}
