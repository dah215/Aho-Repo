package com.nangcuc

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class NangCucPlugin : Plugin() {
    override fun load() {
        registerMainAPI(NangCucProvider())
    }
}

class NangCucProvider : MainAPI() {
    override var mainUrl = "https://nangcuc.cv"
    override var name = "Nắng Cực"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Mới Cập Nhật",
        "the-loai/vietsub/" to "Vietsub",
        "the-loai/jav-hd/" to "JAV HD",
        "the-loai/chau-au/" to "Âu Mỹ",
        "the-loai/han-quoc-18/" to "Hàn Quốc 18+",
        "the-loai/xvideos/" to "Xvideos",
        "the-loai/nhat-ban/" to "Nhật Bản"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val base = mainUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.item-box__image") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("p.item-box__title")?.text() ?: "" }
            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=movie"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.item-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.item-box__image") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("article p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".categories a").map { it.text() }
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

        // 1. Thu thập tất cả các URL tiềm năng từ data-source, iframe và script
        val potentialUrls = mutableSetOf<String>()
        
        // Lấy từ thuộc tính data-source (Server 1, 2...)
        doc.select("[data-source]").forEach { potentialUrls.add(it.attr("data-source")) }
        
        // Lấy từ iframe
        doc.select("iframe").forEach { potentialUrls.add(it.attr("src")) }
        
        // Lấy từ script (Regex tìm các link dfplayer hoặc m3u8)
        Regex("""https?://[^\s"'<>]+dfplayer\.net[^\s"'<>]+""").findAll(html).forEach { potentialUrls.add(it.value) }
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).forEach { potentialUrls.add(it.value) }

        potentialUrls.filter { it.isNotBlank() }.distinct().forEach { rawUrl ->
            val fullUrl = fixUrl(rawUrl)
            
            // Xử lý DFPlayer (Hỗ trợ nhiều subdomain: v, player, ...)
            if (fullUrl.contains("dfplayer.net")) {
                val did = Regex("""did=(\d+)""").find(fullUrl)?.groupValues?.get(1)
                    ?: Regex("""/s/(\d+)""").find(fullUrl)?.groupValues?.get(1)
                
                if (did != null) {
                    val m3u8Link = "https://v.dfplayer.net/v2/s/$did.m3u8"
                    callback(
                        newExtractorLink(
                            "DFPlayer", 
                            "DFPlayer", 
                            m3u8Link, 
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://v.dfplayer.net/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } 
            // Xử lý link m3u8 trực tiếp
            else if (fullUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        name, 
                        "Server VIP", 
                        fullUrl, 
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            // Thử dùng Extractor mặc định cho các server khác (Doodstream, v.v.)
            else {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
