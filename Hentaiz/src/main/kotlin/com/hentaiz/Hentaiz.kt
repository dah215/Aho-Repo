package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.Headers

@CloudstreamPlugin
class HentaizPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaizProvider())
    }
}

class HentaizProvider : MainAPI() {
    override var mainUrl = "https://hentaivietsub.com"
    override var name = "HentaiVietsub"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/the-loai/vietsub" to "Vietsub",
        "/the-loai/3d" to "Hentai 3D",
        "/the-loai/khong-che" to "Không Che",
        "/the-loai/big-boobs" to "Mông To",
        "/the-loai/school-girl" to "Học Sinh",
        "/the-loai/rape" to "Hiếp Dâm",
        "/the-loai/thu-dam" to "Tự Sướng",
        "/the-loai/threesome" to "Tập Thể",
        "/the-loai/teacher" to "Cô Giáo",
        "/the-loai/succubus" to "Succubus"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl?page=$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), linkEl.attr("href"), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("src")
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?k=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        return doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), linkEl.attr("href"), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val buttons = res.document.select("button.set-player-source")
        
        for (button in buttons) {
            val sourceUrl = button.attr("data-source")
            if (sourceUrl.isBlank()) continue

            val serverRes = app.get(sourceUrl, headers = mapOf("User-Agent" to UA, "Referer" to data))
            val serverHtml = serverRes.text

            // Tìm link master.m3u8
            val masterM3u8Regex = Regex("""https?://[^\s"']+/master\.m3u8\?[^\s"']+""")
            val realLink = masterM3u8Regex.find(serverHtml)?.value ?: continue

            // Sử dụng Proxy để trình phát gọi qua Cloudstream thay vì gọi trực tiếp
            // Cloudstream sẽ tự động xử lý việc thêm Headers/Cookies khi trình phát gọi đến link này
            callback(
                newExtractorLink(
                    name,
                    button.attr("data-cdn-name").ifBlank { "Server HD" },
                    realLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = sourceUrl
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to sourceUrl,
                        "Origin" to "https://p1.spexliu.top"
                    )
                }
            )
            return true
        }
        return false
    }
}
