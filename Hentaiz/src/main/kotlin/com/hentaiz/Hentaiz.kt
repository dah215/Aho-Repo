package com.hentaiz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaiZProvider())
    }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.lol"
    override var name = "HentaiZ"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    // Cập nhật lại đường dẫn chuẩn của HentaiZ
    override val mainPage = mainPageOf(
        "danh-sach/phim-moi" to "Mới Cập Nhật",
        "the-loai/hentai-3d" to "Hentai 3D",
        "the-loai/khong-che" to "Không Che",
        "the-loai/co-che" to "Có Che",
        "the-loai/hentai-vietsub" to "Hentai Vietsub",
        "the-loai/hentai-trung-quoc" to "Trung Quốc"
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
        // Xử lý URL để tránh bị double slash //
        val path = request.data.removePrefix("/").removeSuffix("/")
        val url = if (page == 1) {
            "$mainUrl/$path/"
        } else {
            "$mainUrl/$path/page/$page/"
        }
        
        val res = app.get(url, headers = headers)
        val doc = res.document
        
        // Selector đặc trưng của theme Halim trên HentaiZ
        val items = doc.select(".halim-item, article.item, .movie-item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            
            val title = linkEl.attr("title").ifBlank { 
                el.selectFirst(".entry-title, .title")?.text() 
            }?.trim() ?: return@mapNotNull null
            
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, headers = headers).document
        
        return doc.select(".halim-item, article.item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst(".entry-title")?.text() }?.trim() ?: ""
            
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.entry-title, .title")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.split("-")?.get(0)?.trim()
            ?: "Untitled"
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: doc.selectFirst(".poster img, .thumb img")?.attr("src")
            
        val desc = doc.selectFirst(".entry-content, .video-content, #film-content")?.text()?.trim()
        
        val episodes = mutableListOf<Episode>()
        // Tìm tập phim trong các server
        doc.select(".halim-list-eps li a, .list-episode li a").forEach { epEl ->
            val epHref = fixUrl(epEl.attr("href"))
            val epName = epEl.text().trim()
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                })
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = doc.select(".category a, .tags a").map { it.text() }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = doc.select(".category a, .tags a").map { it.text() }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text
        val doc = res.document

        val potentialUrls = mutableSetOf<String>()
        
        // Lấy link từ iframe (phổ biến nhất)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) potentialUrls.add(fixUrl(src))
        }
        
        // Quét link trong script
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains(".m3u8") || link.contains("google") || link.contains("player")) {
                potentialUrls.add(link)
            }
        }

        potentialUrls.distinct().forEach { fullUrl ->
            if (fullUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(name, "Server VIP", fullUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = data
                    }
                )
            } else if (fullUrl.contains("dood") || fullUrl.contains("streamwish") || fullUrl.contains("filemoon") || fullUrl.contains("voe")) {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
