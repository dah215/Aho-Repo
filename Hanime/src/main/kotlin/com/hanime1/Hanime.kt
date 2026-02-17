package com.hanime1

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class Hanime1Plugin : Plugin() {
    override fun load() {
        registerMainAPI(Hanime1Provider())
    }
}

class Hanime1Provider : MainAPI() {
    override var mainUrl = "https://hanime1.me"
    override var name = "Hanime1"
    override var lang = "zh"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // Sử dụng mã Unicode để tránh lỗi font/encoding khi build
    override val mainPage = mainPageOf(
        "search?sort=created_at" to "Mới Cập Nhật",
        "search?sort=views_count" to "Xem Nhiều Nhất",
        "search?type=hentai&genre=\u7121\u4fee\u6b63" to "Không Che (Uncensored)", // 無修正
        "search?type=hentai&genre=\u88cf\u756a" to "Series Hentai (\u88cf\u756a)", // 裏番
        "search?type=hentai&genre=\u4e2d\u6587\u5b57\u5e55" to "Phụ Đề (Chinese Sub)", // 中文字幕
        "search?type=hentai&genre=3DCG" to "Phim 3D (3DCG)"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun parseItem(el: Element): SearchResponse? {
        // Selector chính xác dựa trên HTML bạn gửi: thẻ a có class video-link
        val linkEl = el.selectFirst("a.video-link, a[href*='watch?v=']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        
        // Chỉ lấy link xem phim thực sự
        if (!href.contains("watch?v=")) return null
        
        // Lấy tiêu đề từ class .title (như trong HTML bạn gửi)
        val title = el.selectFirst(".title, .hentai-item-title, .search-display-item-title")?.text()?.trim() 
            ?: el.attr("title") 
            ?: return null
            
        // Lấy ảnh bìa từ img.main-thumb
        val poster = el.selectFirst("img.main-thumb, img")?.let { 
            val src = it.attr("src")
            if (src.isBlank() || src.contains("data:image")) it.attr("data-src") else src
        }
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            mainUrl
        } else {
            val baseUrl = "$mainUrl/${request.data}"
            if (page > 1) "$baseUrl&page=$page" else baseUrl
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Quét tất cả các khối chứa phim theo cấu trúc HTML bạn gửi
        val items = doc.select(".video-item-container, .hentai-item, .search-display-item-column").mapNotNull { 
            parseItem(it) 
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query"
        val doc = app.get(url, headers = headers).document
        return doc.select(".video-item-container, .hentai-item").mapNotNull { parseItem(it) }.distinctBy { it.url }
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
            this.tags = doc.select("a.hentai-video-tag").map { it.text() }
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

        // 2. Quét link MP4 trực tiếp từ script (Dành cho server hembed)
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
