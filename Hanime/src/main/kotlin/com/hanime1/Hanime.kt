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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "" to "Trang Chủ (Xu Hướng)",
        "search?query=&type=&genre=%E8%A3%8F%E7%95%AA&sort=&date=&duration=" to "裏番",
        "search?query=&type=&genre=%E6%B3%A1%E9%BA%B5%E7%95%AA&sort=&date=&duration=" to "泡麵番t",
        "search?genre=3DCG" to "Phim 3D"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun parseItem(el: Element): SearchResponse? {
        // Tìm link có chứa watch?v= (Dựa trên HTML bạn gửi)
        val linkEl = el.selectFirst("a[href*='watch?v=']") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        
        // Lấy tiêu đề từ class .title hoặc .hentai-item-title
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
        // Logic nối URL chuẩn: Nếu có data thì nối vào mainUrl, nếu không thì dùng mainUrl gốc
        val url = if (request.data.isEmpty()) {
            mainUrl
        } else {
            val baseUrl = "$mainUrl/${request.data}"
            if (page > 1) "$baseUrl&page=$page" else baseUrl
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Quét cả cấu trúc trang chủ (.hentai-item) và trang tìm kiếm (.video-item-container)
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
