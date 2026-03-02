package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    // Cập nhật danh sách thể loại chính xác từ trang chủ heovl.moe
    override val mainPage = mainPageOf(
        "/the-loai/phim-sex-viet-nam/" to "Việt Nam",
        "/the-loai/phim-sex-vietsub/" to "Vietsub",
        "/the-loai/phim-sex-khong-che/" to "Không Che",
        "/the-loai/phim-sex-jav-hd/" to "JAV HD",
        "/the-loai/phim-sex-trung-quoc/" to "Trung Quốc",
        "/the-loai/phim-sex-au-my/" to "Âu Mỹ",
        "/the-loai/phim-sex-tap-the/" to "Tập Thể",
        "/the-loai/phim-sex-vlxx/" to "VLXX",
        "/the-loai/phim-sex-hiep-dam/" to "Hiếp Dâm",
        "/the-loai/phim-sex-loan-luan/" to "Loạn Luân"
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
        // Xử lý phân trang: heovl.moe thường dùng /page/2/
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}page/$page/"
        }
        
        val doc = app.get(url, headers = headers).document
        
        // Selector chuẩn cho Halim Theme trên HeoVL
        val items = doc.select(".halim-item, article.item, .movie-item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            
            val title = linkEl.attr("title").ifBlank { 
                el.selectFirst(".entry-title, .title, h2")?.text() 
            }?.trim() ?: return@mapNotNull null
            
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        
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
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        
        val title = doc.selectFirst("h1.entry-title, .title, h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "HeoVL Video"
            
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: doc.selectFirst(".poster img, .thumb img")?.attr("src")
            
        val desc = doc.selectFirst(".entry-content, .video-content, #film-content, .description")?.text()?.trim()
        
        val episodes = mutableListOf<Episode>()
        
        // Tìm danh sách tập phim (Halim Theme thường dùng .halim-list-eps)
        // HeoVL có thể có nhiều server, ta quét tất cả
        doc.select(".halim-list-eps li a, .list-episode li a, #server-list li a").forEach { epEl ->
            val epHref = fixUrl(epEl.attr("href"))
            val epName = epEl.text().trim()
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                })
            }
        }

        // Nếu không tìm thấy list tập, có thể là phim lẻ 1 tập, lấy chính URL hiện tại
        if (episodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = desc
                this.tags = doc.select(".category a, .tags a").map { it.text() }
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
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
        val doc = res.document
        val html = res.text

        val potentialUrls = mutableSetOf<String>()
        
        // 1. Quét iframe (Phổ biến nhất trên HeoVL)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) potentialUrls.add(fixUrl(src))
        }
        
        // 2. Quét link ẩn trong script (Dood, StreamWish, v.v.)
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe") || link.contains("tape")) {
                potentialUrls.add(link)
            }
        }

        potentialUrls.distinct().forEach { fullUrl ->
            // Tự động xử lý các host phổ biến
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
