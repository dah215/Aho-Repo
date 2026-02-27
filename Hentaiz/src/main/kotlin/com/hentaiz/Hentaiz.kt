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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // Bạn có thể tuỳ chỉnh lại các đường dẫn thể loại này cho khớp với menu của web
    override val mainPage = mainPageOf(
        "/" to "Mới Cập Nhật",
        "/the-loai/3d/" to "Hentai 3D",
        "/the-loai/khong-che/" to "Không Che",
        "/the-loai/co-che/" to "Có Che",
        "/the-loai/nhat-ban/" to "Nhật Bản",
        "/the-loai/trung-quoc/" to "Trung Quốc"
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
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Sử dụng các class phổ biến để bắt item phim
        val items = doc.select("article, .item, .halim-item, .movie-item, .film-item, .post, .box, .card").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            if (!href.contains(mainUrl)) return@mapNotNull null
            
            val title = linkEl.attr("title").ifBlank { 
                el.selectFirst("h2, h3, .title, .name, .entry-title")?.text() ?: linkEl.text() 
            }.trim()
            
            if (title.isBlank()) return@mapNotNull null
            
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, headers = headers).document
        
        return doc.select("article, .item, .halim-item, .movie-item, .film-item, .post, .box, .card").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            if (!href.contains(mainUrl)) return@mapNotNull null
            
            val title = linkEl.attr("title").ifBlank { 
                el.selectFirst("h2, h3, .title, .name, .entry-title")?.text() ?: linkEl.text() 
            }.trim()
            
            if (title.isBlank()) return@mapNotNull null
            
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1, .title, .name, .entry-title")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst(".poster img, .thumb img")?.attr("src")
        val desc = doc.selectFirst(".description, .summary, .content, article p, .entry-content p")?.text()?.trim()
        
        // Tìm danh sách tập phim (nếu có)
        val episodes = mutableListOf<Episode>()
        doc.select(".episodes a, .list-episode a, .server a, .btn-episode, .halim-list-eps a").forEach { epEl ->
            val epHref = fixUrl(epEl.attr("href"))
            val epName = epEl.text().trim()
            if (epHref.isNotBlank()) {
                episodes.add(Episode(epHref, epName))
            }
        }

        if (episodes.isEmpty()) {
            // Nếu không có danh sách tập, coi như đây là phim lẻ hoặc trang hiện tại chính là trang xem phim
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = doc.select(".categories a, .tags a, .genres a").map { it.text() }
            }
        } else {
            // Nếu có nhiều tập, trả về dạng TvSeries
            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = doc.select(".categories a, .tags a, .genres a").map { it.text() }
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
        val doc = res.document
        val html = res.text

        val potentialUrls = mutableSetOf<String>()
        
        // 1. Quét tìm các thẻ iframe chứa link video (rất phổ biến)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                potentialUrls.add(src)
            }
        }
        
        // 2. Quét toàn bộ thuộc tính của các thẻ HTML để tìm link ẩn
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val value = attr.value
                if (value.contains("dfplayer") || value.contains(".m3u8") || value.contains("bf.html") || value.contains("dood") || value.contains("tape") || value.contains("voe")) {
                    potentialUrls.add(value)
                }
            }
        }
        
        // 3. Dùng Regex để bắt các link nằm trong mã JavaScript
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { potentialUrls.add(it.value.replace("\\/", "/")) }

        potentialUrls.filter { it.isNotBlank() }.distinct().forEach { rawUrl ->
            val fullUrl = fixUrl(rawUrl)
            
            if (fullUrl.contains("dfplayer")) {
                val id = Regex("""(?:did|id|v|s)[=/](\d+)""").find(fullUrl)?.groupValues?.get(1)
                val host = Regex("""https?://([^/]+)""").find(fullUrl)?.groupValues?.get(1)
                
                if (id != null && host != null) {
                    val m3u8Link = "https://$host/v2/s/$id.m3u8"
                    callback(
                        newExtractorLink("DFPlayer", "DFPlayer", m3u8Link, type = ExtractorLinkType.M3U8) {
                            this.referer = "https://$host/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } 
            else if (fullUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(name, "Server VIP", fullUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            // Hỗ trợ thêm các host phổ biến khác thường dùng trên các web phim
            else if (fullUrl.contains("dood") || fullUrl.contains("tape") || fullUrl.contains("voe") || fullUrl.contains("streamwish") || fullUrl.contains("filemoon")) {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
