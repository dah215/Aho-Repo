package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

@CloudstreamPlugin
class HeoVLPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HeoVLProvider())
    }
}

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.moe"
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Cookie bạn cung cấp, được định dạng lại để gửi kèm request
    private val COOKIES = "__cf_bm=igDu3NVyWHJxG_DYAkYAQvx0F8gInb3ErDiI7hQK9Ps-1772718773-1.0.1.1-XCwFoJsn1rDY2702X9pAzccgo8ElE_ZBMFGtWsIfbU.6sSNIDC3rQnM_bschhLaee95RhN_0EQNf0SuxHQ3YxSb6OSSLDcGPCbLq0PNd9Fo; _ga=GA1.1.1737587921.1772718764; _ga_GR0GKQ8JBK=GS2.1.s1772718763$o1$g1$t1772718829$j60$l0$h0"

    private val baseHeaders = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Cookie" to COOKIES
    )

    override val mainPage = mainPageOf(
        "/" to "Mới Cập Nhật",
        "/categories/viet-nam" to "Việt Nam",
        "/categories/vietsub" to "Vietsub",
        "/categories/trung-quoc" to "Trung Quốc",
        "/categories/au-my" to "Âu - Mỹ",
        "/categories/khong-che" to "Không Che",
        "/categories/jav-hd" to "JAV HD"
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
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = baseHeaders).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = baseHeaders).text
        val doc = Jsoup.parse(html)

        // Lấy danh sách các nút Server
        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }

        sources.forEach { (sourceUrl, serverName) ->
            val fixedUrl = fixUrl(sourceUrl)
            
            // Truy cập vào trang Iframe để lấy link thật
            val iframeRes = app.get(fixedUrl, headers = baseHeaders)
            val iframeHtml = iframeRes.text
            
            // Quét link video (elifros hoặc m3u8)
            val regex = """(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*|https?://elifros\.top/s/[^"'\s]+)""".toRegex()
            regex.findAll(iframeHtml).forEach { match ->
                val link = match.value.replace("\\/", "/")
                
                // Loại bỏ link quảng cáo
                if (!link.contains("ads") && !link.contains("vast")) {
                    callback(newExtractorLink("HeoVL VIP", serverName, link, ExtractorLinkType.M3U8) {
                        // GẮN COOKIE VÀO ĐÂY ĐỂ VƯỢT LỖI 2004/403
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to fixedUrl,
                            "Origin" to "https://${java.net.URI(fixedUrl).host}",
                            "Cookie" to COOKIES
                        )
                        this.quality = Qualities.P1080.value
                    })
                }
            }
        }
        return true
    }
}
