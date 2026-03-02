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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Danh sách thể loại chính xác lấy từ HTML bạn gửi
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Xử lý phân trang (thường là ?page=2 hoặc /page/2)
        // Dựa trên URL mẫu, ta thử dùng ?page=
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Selector chuẩn dựa trên HTML: div.video-box
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            
            val title = linkEl.attr("title").ifBlank { 
                el.selectFirst("h3.video-box__heading")?.text() 
            }?.trim() ?: return@mapNotNull null
            
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query" // Dự đoán URL search dựa trên form search
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
        
        // Lấy thông tin chi tiết (Cần điều chỉnh nếu trang chi tiết khác trang chủ)
        // Dựa trên cấu trúc chung, thường tiêu đề là h1
        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "HeoVL Video"
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: doc.selectFirst("div.video-player-container img")?.attr("src")
            
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        
        // HeoVL thường là phim lẻ (video clip), nhưng nếu có list tập thì quét thêm
        val episodes = mutableListOf<Episode>()
        
        // Nếu không tìm thấy list tập, trả về phim lẻ
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".video-info__tags a").map { it.text() }
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
        val doc = res.document

        val potentialUrls = mutableSetOf<String>()
        
        // 1. Quét Iframe (Ưu tiên)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) potentialUrls.add(fixUrl(src))
        }
        
        // 2. Quét Regex tìm link m3u8 (Bao gồm cả link spexliu.top bạn gửi)
        // Regex này bắt mọi link bắt đầu bằng http và kết thúc bằng .m3u8 (có thể có query string)
        val m3u8Regex = """https?://[^"'\s]+\.m3u8[^"'\s]*""".toRegex()
        m3u8Regex.findAll(html).forEach { 
            potentialUrls.add(it.value.replace("\\/", "/"))
        }

        potentialUrls.distinct().forEach { fullUrl ->
            if (fullUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server VIP (HLS)",
                        url = fullUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data // Thử referer là trang web
                        this.quality = Qualities.P1080.value
                    }
                )
                
                // Thêm một link dự phòng không có Referer (đôi khi server chặn referer lạ)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server VIP (No Ref)",
                        url = fullUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                    }
                )
            } else {
                // Xử lý các host khác (Dood, StreamWish...)
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
