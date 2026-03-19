package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Lấy trang chi tiết
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val doc = res.document
        
        // 2. Tìm tất cả các script trong trang, vì link video thường nằm trong các biến JS
        val scripts = doc.select("script").map { it.html() }
        
        // 3. Tìm link master.m3u8 trong toàn bộ nội dung trang (bao gồm cả script)
        // Chúng ta tìm link có chứa 'master.m3u8' và tham số 'e=' (phim thật)
        val masterM3u8Regex = Regex("""https?://[^\s"']+/master\.m3u8\?[^\s"']+""")
        
        // Gom tất cả nội dung trang lại để quét
        val fullContent = res.text + scripts.joinToString("\n")
        val allLinks = masterM3u8Regex.findAll(fullContent).map { it.value }.toList()

        // 4. Lọc link thật (có chứa 'e=')
        val realLink = allLinks.find { it.contains("e=") } ?: allLinks.firstOrNull()

        if (realLink != null) {
            callback(
                newExtractorLink(
                    name,
                    "Server HD (Direct)",
                    realLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to data,
                        "Origin" to "https://hentaivietsub.com"
                    )
                }
            )
            return true
        }
        
        return false
    }

    // ... (Giữ nguyên các hàm getMainPage, search, load như cũ)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) fixUrl(request.data) else "${fixUrl(request.data)}?page=$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
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
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        return doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
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

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        return "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
    }
}
