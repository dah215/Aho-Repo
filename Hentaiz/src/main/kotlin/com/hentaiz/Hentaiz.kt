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

    private val headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")

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
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("src")
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
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val serverButtons = res.document.select("button.set-player-source")
        
        for (button in serverButtons) {
            val sourceUrl = button.attr("data-source")
            if (sourceUrl.isBlank()) continue

            val serverHtml = app.get(sourceUrl, headers = mapOf("User-Agent" to UA, "Referer" to data)).text
            val masterM3u8Regex = Regex("""https?://[^\s"']+/master\.m3u8\?[^\s"']+""")
            val allLinks = masterM3u8Regex.findAll(serverHtml).map { it.value }.toList()

            for (link in allLinks) {
                val m3u8Content = app.get(link, headers = mapOf("Referer" to sourceUrl, "User-Agent" to UA)).text
                
                // Tính tổng thời lượng các đoạn video (#EXTINF:...)
                val durationRegex = Regex("""#EXTINF:([\d\.]+),""")
                val totalDuration = durationRegex.findAll(m3u8Content).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

                // Chỉ chấp nhận playlist dài hơn 60 giây (phim thật)
                if (totalDuration > 60.0) {
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
                    return true
                }
            }
        }
        return false
    }
}
