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
    override var lang = "zh" // Trang này gốc tiếng Trung nhưng nội dung trực quan
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "" to "Trang Chủ (Xu Hướng)",
        "search?sort=created_at" to "Mới Cập Nhật",
        "search?sort=views_count" to "Xem Nhiều Nhất",
        "search?type=hentai&genre=無修正" to "Không Che (Uncensored)",
        "search?type=hentai&genre=裏番" to "Series Hentai"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun parseItem(el: Element): SearchResponse? {
        val title = el.selectFirst(".hentai-item-title")?.text() ?: return null
        val href = fixUrl(el.selectFirst("a")?.attr("href") ?: return null)
        val poster = el.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            mainUrl
        } else {
            "$mainUrl/${request.data}${if (page > 1) "&page=$page" else ""}"
        }
        
        val doc = app.get(url, headers = headers).document
        val items = doc.select(".hentai-item, .search-display-item-column").mapNotNull { parseItem(it) }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query"
        val doc = app.get(url, headers = headers).document
        return doc.select(".hentai-item, .search-display-item-column").mapNotNull { parseItem(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1#hentai-video-title")?.text() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: "Untitled"
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst(".hentai-video-description")?.text()
        
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
        val doc = app.get(data, headers = headers).document

        // Hanime1 thường để link trực tiếp trong thẻ <source> của <video>
        doc.select("video source").forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                // Lấy chất lượng từ thuộc tính size (ví dụ: 720, 1080)
                val qualityStr = source.attr("size")
                val quality = when (qualityStr) {
                    "1080" -> Qualities.P1080.value
                    "720" -> Qualities.P720.value
                    "480" -> Qualities.P480.value
                    "360" -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    ExtractorLink(
                        name,
                        "$name $qualityStr" + "p",
                        videoUrl,
                        referer = data,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        // Đôi khi link nằm trong script dưới dạng biến video_sources
        if (doc.select("video source").isEmpty()) {
            val html = doc.html()
            // Tìm các link mp4 trong script
            Regex("""https?://[^\s"'<>]+?\.mp4[^\s"'<>]*""").findAll(html).forEach { match ->
                val link = match.value
                callback(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        return true
    }
}
