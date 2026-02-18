package com.hanime1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class Hanime1Plugin : Plugin() {
    override fun load() {
        registerMainAPI(OneHaniProvider())
    }
}

class OneHaniProvider : MainAPI() {
    override var mainUrl = "https://1hani.me"
    override var name = "1Hani"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Header giả lập trình duyệt tối đa để tránh bị server từ chối
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Cookie" to "h_m_enter=1",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // Sử dụng đúng cấu trúc URL bạn cung cấp. 
    // Mình dùng ký tự gốc để Cloudstream tự mã hóa 1 lần duy nhất cho chuẩn.
    override val mainPage = mainPageOf(
        "search?query=&type=&genre=&sort=created_at&date=&duration=" to "Mới Cập Nhật",
        "search?query=&type=&genre=&sort=views_count&date=&duration=" to "Xem Nhiều Nhất",
        "search?query=&type=&genre=無修正&sort=&date=&duration=" to "Không Che (Uncensored)",
        "search?query=&type=&genre=裏番&sort=&date=&duration=" to "Series Hentai (里番)",
        "search?query=&type=&genre=中文字幕&sort=&date=&duration=" to "Phụ Đề (Chinese Sub)",
        "search?query=&type=&genre=3DCG&sort=&date=&duration=" to "Phim 3D (3DCG)"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun parseItem(el: Element): SearchResponse? {
        // Theo HTML bạn gửi: el chính là thẻ <a> bao quanh phim
        val href = fixUrl(el.attr("href"))
        if (!href.contains("watch?v=")) return null
        
        // Tiêu đề nằm trong class .home-rows-videos-title
        val title = el.selectFirst(".home-rows-videos-title")?.text()?.trim() 
            ?: el.selectFirst(".title")?.text()?.trim()
            ?: return null
            
        // Ảnh nằm trong thẻ img
        val poster = el.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Nối tham số page vào cuối URL đầy đủ
        val url = "$mainUrl/${request.data}${if (page > 1) "&page=$page" else ""}"
        
        val doc = app.get(url, headers = headers).document
        
        // Selector "vét sạch": Tìm tất cả thẻ <a> có link watch?v=
        val items = doc.select("a[href*='watch?v=']").mapNotNull { 
            parseItem(it) 
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search cũng dùng cấu trúc URL đầy đủ tham số
        val url = "$mainUrl/search?query=$query&type=&genre=&sort=&date=&duration="
        val doc = app.get(url, headers = headers).document
        return doc.select("a[href*='watch?v=']").mapNotNull { parseItem(it) }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1#hentai-video-title, .video-title-width, h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content") 
            ?: "Untitled"
        
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val desc = doc.selectFirst(".hentai-video-description, #video-description")?.text()?.trim()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select("a.hentai-video-tag, .video-tags a").map { it.text() }
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

        // 1. Lấy link từ thẻ video source
        res.document.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                val qualityStr = source.attr("size") ?: ""
                callback(
                    newExtractorLink(
                        name,
                        if (qualityStr.isNotBlank()) "$name ${qualityStr}p" else name,
                        videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = qualityStr.toIntOrNull() ?: 0
                    }
                )
            }
        }

        // 2. Quét link MP4 trực tiếp từ script
        val mp4Regex = Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+?\.mp4[^\s"'<>]*""")
        mp4Regex.findAll(html).forEach { match ->
            val link = match.value.replace("\\/", "/")
            if (!link.contains("thumbnail") && !link.contains("preview")) {
                callback(
                    newExtractorLink(
                        "Server VIP",
                        "Direct MP4",
                        link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                    }
                )
            }
        }

        return true
    }
}
