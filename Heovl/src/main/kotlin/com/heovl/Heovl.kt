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
    // Thay thế dòng cũ bằng dòng này (đã thêm \ trước mỗi dấu $)
private val COOKIES = "__cf_bm=igDu3NVyWHJxG_DYAkYAQvx0F8gInb3ErDiI7hQK9Ps-1772718773-1.0.1.1-XCwFoJsn1rDY2702X9pAzccgo8ElE_ZBMFGtWsIfbU.6sSNIDC3rQnM_bschhLaee95RhN_0EQNf0SuxHQ3YxSb6OSSLDcGPCbLq0PNd9Fo; _ga=GA1.1.1737587921.1772718764; _ga_GR0GKQ8JBK=GS2.1.s1772718763\$o1\$g1\$t1772718829\$j60\$l0\$h0"
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

    // ... (Giữ nguyên các phần đầu như V32)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        // Lấy danh sách Server
        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }
        
        val targets = if (sources.isNotEmpty()) sources else {
            val iframe = doc.select("iframe").attr("src")
            if (iframe.isNotBlank()) listOf(iframe to "Default Server") else emptyList()
        }

        targets.forEach { (sourceUrl, serverName) ->
            val fixedUrl = fixUrl(sourceUrl)
            
            // Dùng WebView để bắt link thật (như bản V32)
            val captured = sniffLinkWithCookie(fixedUrl)

            if (captured != null) {
                // THAY ĐỔI QUAN TRỌNG: Dùng ExtractorLinkType.VIDEO thay vì M3U8
                // Điều này ép Cloudstream không cố gắng parse manifest mà phát thẳng link
                callback(
                    newExtractorLink(
                        source = "HeoVL VIP",
                        name = "$serverName (Direct Play)",
                        url = captured.url,
                        type = ExtractorLinkType.VIDEO 
                    ) {
                        this.headers = captured.headers
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
        
        return true
    }
}
