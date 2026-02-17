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
        "search?sort=created_at" to "Mới Cập Nhật",
        "search?sort=views_count" to "Xem Nhiều Nhất",
        "search?type=hentai&genre=無修正" to "Không Che (Uncensored)",
        "search?type=hentai&genre=裏番" to "Series Hentai",
        "search?type=hentai&genre=日本語字幕" to "Vietsub/Phụ đề"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun parseItem(el: Element): SearchResponse? {
        // Selector tiêu đề linh hoạt cho cả trang chủ và trang tìm kiếm
        val title = el.selectFirst(".hentai-item-title, .search-display-item-title, h5, .video-title")?.text() ?: return null
        val linkEl = el.selectFirst("a") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        
        // Lấy ảnh từ img hoặc từ style background-image
        var poster = el.selectFirst("img")?.attr("src")
        if (poster.isNullOrBlank()) {
            val style = el.selectFirst("[style*='background-image']")?.attr("style") ?: ""
            poster = Regex("""url\(['"]?([^'"]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        
        return newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (page > 1) "&page=$page" else ""}"
        val doc = app.get(url, headers = headers).document
        
        // Quét tất cả các khối có khả năng chứa phim
        val items = doc.select(".hentai-item, .search-display-item-column, [class*='col-xs-'], .video-item").mapNotNull { 
            parseItem(it) 
        }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query"
        val doc = app.get(url, headers = headers).document
        return doc.select(".hentai-item, .search-display-item-column, [class*='col-xs-']").mapNotNull { parseItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1#hentai-video-title, .video-title-width, h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content") 
            ?: "Untitled"
        
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val desc = doc.selectFirst(".hentai-video-description, #video-description, .video-details p")?.text()?.trim()
        
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
        val doc = res.document
        val html = res.text

        // 1. Lấy link từ thẻ <video> <source> (Cách chuẩn nhất)
        doc.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                val qualityStr = source.attr("size") ?: ""
                val quality = when {
                    qualityStr.contains("1080") -> Qualities.P1080.value
                    qualityStr.contains("720") -> Qualities.P720.value
                    qualityStr.contains("480") -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    newExtractorLink(
                        name,
                        if (qualityStr.isNotBlank()) "$name ${qualityStr}p" else name,
                        videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = quality
                    }
                )
            }
        }

        // 2. Quét link MP4 trực tiếp từ script (Dành cho server hembed)
        if (html.contains(".mp4")) {
            // Regex tìm link mp4 có kèm token bảo mật
            val mp4Regex = Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""")
            mp4Regex.findAll(html).forEach { match ->
                val link = match.value.replace("\\/", "/")
                if (!link.contains("thumbnail")) { // Loại bỏ link ảnh thumbnail
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
        }

        return true
    }
}
