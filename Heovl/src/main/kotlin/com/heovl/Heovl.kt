package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class HeoVLPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HeoVLProvider())
    }
}

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.moe"
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Nhúng Cookie bạn cung cấp vào đây
    private val userCookie = "_ga=GA1.1.56505491.1772459545; _ga_GR0GKQ8JBK=GS2.1.s1772459544\$o1\$g1\$t1772461145\$j53\$l0\$h0"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Cookie" to userCookie // Gửi kèm Cookie để tăng độ uy tín
    )

    override val mainPage = mainPageOf(
        "/" to "Mới Cập Nhật",
        "/categories/viet-nam" to "Việt Nam",
        "/categories/vietsub" to "Vietsub",
        "/categories/trung-quoc" to "Trung Quốc",
        "/categories/au-my" to "Âu - Mỹ",
        "/categories/khong-che" to "Không Che",
        "/categories/jav-hd" to "JAV HD",
        "/categories/gai-xinh" to "Gái Xinh",
        "/categories/nghiep-du" to "Nghiệp Dư",
        "/categories/xnxx" to "Xnxx",
        "/categories/vlxx" to "Vlxx",
        "/categories/tap-the" to "Tập Thể",
        "/categories/nhat-ban" to "Nhật Bản",
        "/categories/han-quoc" to "Hàn Quốc",
        "/categories/vung-trom" to "Vụng Trộm",
        "/categories/vu-to" to "Vú To",
        "/categories/tu-the-69" to "Tư Thế 69",
        "/categories/hoc-sinh" to "Học Sinh",
        "/categories/quay-len" to "Quay Lén",
        "/categories/tu-suong" to "Tự Sướng"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    // --- PHẦN GIAO DIỆN (ĐÃ CHUẨN) ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: ""
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst("div.video-player-container img")?.attr("src")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".video-info__tags a").map { it.text() }
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: DIRECT REQUEST + COOKIE ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text
        val doc = res.document

        // 1. Tìm Iframe chứa video (Ưu tiên streamqq/spexliu)
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu)[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (iframeUrl == null) iframeUrl = doc.select("iframe").attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            
            try {
                // 2. Gửi request trực tiếp vào Iframe
                // Quan trọng: Gửi kèm Referer là trang HeoVL để server không chặn
                val iframeHeaders = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "$mainUrl/",
                    "Cookie" to userCookie // Thử dùng cookie
                )
                
                val iframeRes = app.get(fixedIframeUrl, headers = iframeHeaders)
                val iframeHtml = iframeRes.text
                
                // 3. Quét tất cả các link .m3u8 trong mã nguồn Iframe
                // Regex này bắt cả link nằm trong biến JS (var source = '...')
                val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
                
                m3u8Regex.findAll(iframeHtml).forEach { match ->
                    val link = match.value.replace("\\/", "/")
                    
                    // Trả về link với Referer là link của Iframe
                    callback(
                        newExtractorLink(
                            source = "HeoVL VIP",
                            name = "Server VIP (Direct)",
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = fixedIframeUrl
                            this.quality = Qualities.P1080.value
                            // Thêm Origin của server video (thường là domain của iframe)
                            val origin = "https://${java.net.URI(fixedIframeUrl).host}"
                            this.headers = mapOf("Origin" to origin)
                        }
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Fallback: Quét link m3u8 ngay trên trang chính (nếu có)
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            callback(
                newExtractorLink(
                    source = "HeoVL VIP",
                    name = "Server VIP (Backup)",
                    url = link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.P720.value
                }
            )
        }

        // Fallback: Các host khác
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
