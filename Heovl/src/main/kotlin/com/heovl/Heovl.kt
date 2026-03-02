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

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
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

    // --- PHẦN GIAO DIỆN ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        }
        
        val doc = app.get(url, headers = headers).document
        
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
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

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
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

    // --- PHẦN XỬ LÝ VIDEO: STREAMQQ HUNTER ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text
        val doc = res.document

        // 1. Tìm Iframe chứa video (Ưu tiên streamqq và spexliu)
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu)[^"']*)["']""").find(html)?.groupValues?.get(1)
        
        // Nếu không tìm thấy bằng Regex, thử tìm bằng Selector
        if (iframeUrl == null) {
             iframeUrl = doc.select("iframe[src*='streamqq'], iframe[src*='spexliu']").attr("src")
        }
        
        // Fallback: Lấy iframe đầu tiên nếu không tìm thấy tên miền cụ thể
        if (iframeUrl.isNullOrBlank()) {
            iframeUrl = doc.select("iframe").attr("src")
        }

        // 2. Nếu tìm thấy Iframe, truy cập vào nó để lấy link gốc
        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            try {
                // Gọi request vào trang Iframe (Giả lập Referer từ HeoVL)
                val iframeRes = app.get(fixedIframeUrl, headers = mapOf("Referer" to "$mainUrl/"))
                val iframeHtml = iframeRes.text
                
                // Quét link m3u8 trong mã nguồn Iframe
                // Regex bắt: "https://...m3u8..." hoặc 'https://...m3u8...'
                val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
                m3u8Regex.findAll(iframeHtml).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/")
                    
                    // Trả về link với Referer là link của Iframe (Quan trọng!)
                    callback(
                        newExtractorLink(
                            source = "HeoVL VIP",
                            name = "Server VIP (StreamQQ)",
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = fixedIframeUrl 
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Origin" to "https://e.streamqq.com", // Thêm Origin cho chắc ăn
                                "User-Agent" to UA
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // 3. Fallback: Các host khác (Dood, StreamWish...)
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
