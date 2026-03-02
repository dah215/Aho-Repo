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
    override var mainUrl = "https://heovl.lol" // Bạn có thể đổi thành .moe nếu cần
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/" to "Phim Mới Cập Nhật",
        "/the-loai/phim-bo/" to "Phim Bộ (Nhiều Tập)",
        "/the-loai/phim-le/" to "Phim Lẻ",
        "/the-loai/phim-sex-han-quoc/" to "Hàn Quốc 18+",
        "/the-loai/phim-sex-nhat-ban/" to "Nhật Bản (JAV)",
        "/the-loai/phim-sex-au-my/" to "Âu Mỹ",
        "/the-loai/phim-sex-trung-quoc/" to "Trung Quốc / Em Gái Tàu",
        "/the-loai/phim-sex-loan-luan/" to "Loạn Luân",
        "/the-loai/phim-sex-hiep-dam/" to "Cưỡng Chế / Hiếp Dâm"
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
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        val doc = app.get(url, headers = headers).document
        
        val items = doc.select(".halim-item, .item, article").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst(".entry-title, h2, h3")?.text() } ?: ""
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { img.attr("data-original") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, headers = headers).document
        
        return doc.select(".halim-item, .item, article").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst(".entry-title, h2, h3")?.text() } ?: ""
            val poster = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1.entry-title, .title, h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst(".poster img, .thumb img")?.attr("src")
        val desc = doc.selectFirst(".entry-content, .video-content, #film-content")?.text()?.trim()
        
        val episodes = mutableListOf<Episode>()
        // Tìm danh sách tập phim (thường nằm trong các thẻ li của server)
        doc.select(".halim-list-eps li a, .list-episode li a, #list-server li a").forEach { epEl ->
            val epHref = fixUrl(epEl.attr("href"))
            val epName = epEl.text().trim()
            if (epHref.isNotBlank()) {
                episodes.add(newEpisode(epHref) {
                    this.name = if (epName.contains("Tập")) epName else "Tập $epName"
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
        val doc = res.document
        val html = res.text

        val potentialUrls = mutableSetOf<String>()
        
        // 1. Lấy link từ các thẻ iframe (Rất phổ biến trên HeoVL)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) potentialUrls.add(fixUrl(src))
        }
        
        // 2. Quét link video ẩn trong mã nguồn JavaScript (Regex)
        val regex = """https?[:\\]+[/\\/]+[^\s"'<>]+""".toRegex()
        regex.findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains(".m3u8") || link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                potentialUrls.add(link)
            }
        }

        potentialUrls.distinct().forEach { fullUrl ->
            if (fullUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server VIP",
                        url = fullUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                    }
                )
            } else {
                // Tự động gọi các bộ giải mã (Extractors) có sẵn của Cloudstream
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
