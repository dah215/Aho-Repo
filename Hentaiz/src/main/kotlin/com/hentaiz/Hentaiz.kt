package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

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

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/the-loai/vietsub" to "Vietsub",
        "/the-loai/3d" to "Hentai 3D",
        "/the-loai/khong-che" to "Không Che"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        return "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) fixUrl(request.data) else "${fixUrl(request.data)}?page=$page"
        val doc = app.get(url, headers = headers).document
        
        val items = doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title")
            val poster = el.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
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
                this.posterUrl = el.selectFirst("img")?.attr("src")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val doc = res.document
        val serverButtons = doc.select("button.set-player-source")
        
        for (button in serverButtons) {
            val sourceUrl = button.attr("data-source")
            if (sourceUrl.isBlank()) continue

            // Lấy nội dung trang trung gian (iframe)
            val serverRes = app.get(sourceUrl, headers = mapOf("User-Agent" to UA, "Referer" to data))
            val serverHtml = serverRes.text

            // Tìm tất cả các link master.m3u8
            val masterM3u8Regex = Regex("""https?://[^\s"']+/master\.m3u8\?[^\s"']+""")
            val allLinks = masterM3u8Regex.findAll(serverHtml).map { it.value }.toList()

            for (link in allLinks) {
                // Kiểm tra nội dung file m3u8 trước khi gửi cho trình phát
                val m3u8Content = app.get(link, headers = mapOf("Referer" to sourceUrl, "User-Agent" to UA)).text
                
                // Điều kiện: Phải là playlist hợp lệ và chứa các đoạn video (.ts)
                // Quảng cáo thường không có các đoạn .ts hoặc rất ít
                if (m3u8Content.contains("#EXTM3U") && m3u8Content.contains(".ts")) {
                    callback(
                        newExtractorLink(
                            name,
                            button.attr("data-cdn-name").ifBlank { "Server HD" },
                            link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = sourceUrl
                            this.headers = mapOf("User-Agent" to UA, "Referer" to sourceUrl)
                        }
                    )
                    // Nếu tìm thấy link hợp lệ, trả về true ngay
                    return true
                }
            }
        }
        return false
    }
}
