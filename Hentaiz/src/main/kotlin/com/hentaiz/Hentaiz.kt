package com.hentaivietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class HentaiVietsubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaiVietsubProvider())
    }
}

class HentaiVietsubProvider : MainAPI() {
    override var mainUrl = "https://hentaivietsub.com"
    override var name = "HentaiVietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    // Cập nhật các danh mục theo menu của hentaivietsub.com
    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/the-loai/vietsub" to "Vietsub",
        "/the-loai/3d" to "Hentai 3D",
        "/the-loai/khong-che" to "Không Che",
        "/the-loai/big-boobs" to "Mông To"
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
        val url = if (page == 1) fixUrl(request.data) else "${fixUrl(request.data)}?page=$page"
        val doc = app.get(url, headers = headers).document
        
        // Cập nhật selector từ video-box (cũ) sang item-box (mới)
        val items = doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() ?: "" }
            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?k=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    // Giữ nguyên logic lấy link từ plugin HeoVL cũ
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val res = app.get(data, headers = headers)
    val html = res.text

    // Tìm ID video từ trang web
    // Thường các trang này có dạng: streamqq.com/videos/ID
    val videoId = Regex("""streamqq\.com/videos/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        ?: Regex("""/videos/([a-zA-Z0-9]+)""").find(html)?.groupValues?.get(1)
        ?: return false

    // URL gốc của luồng video (thường không có quảng cáo nếu gọi trực tiếp)
    // Cấu trúc của streamqq thường là: https://e.streamqq.com/videos/ID/master.m3u8
    val m3u8Url = "https://e.streamqq.com/videos/$videoId/master.m3u8"

    // Kiểm tra xem link có hoạt động không
    val checkRes = app.get(m3u8Url, headers = mapOf("Referer" to "https://e.streamqq.com/"))
    
    if (checkRes.code == 200) {
    callback(
        newExtractorLink(
            name, 
            "StreamQQ HD (No Ads)", 
            m3u8Url, 
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://e.streamqq.com/"
            this.headers = mapOf("Referer" to "https://e.streamqq.com/")
        }
    )
    return true
}
    return false
}
