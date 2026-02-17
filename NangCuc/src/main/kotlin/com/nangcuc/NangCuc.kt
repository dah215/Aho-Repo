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
    override val supportedTypes = setOf(TvType.Movie)

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
        "the-loai/xvideos/" to "Xvideos"
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
            newMovieSearchResponse(title.trim(), href, TvType.Movie) {
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
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.Movie) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("article p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val doc = app.get(data, headers = headers).document

        // 1. Tìm tất cả các nguồn video từ thuộc tính data-source và iframe
        val sources = doc.select("[data-source]").map { it.attr("data-source") }.toMutableList()
        doc.select("iframe").forEach { sources.add(it.attr("src")) }

        sources.distinct().forEach { sourceUrl ->
            val fullSourceUrl = fixUrl(sourceUrl)
            
            if (fullSourceUrl.contains("dfplayer.net")) {
                val did = Regex("""did=(\d+)""").find(fullSourceUrl)?.groupValues?.get(1)
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
            } else if (fullSourceUrl.isNotEmpty()) {
                loadExtractor(fullSourceUrl, data, subtitleCallback, callback)
            }
        }

        // 2. Quét thêm link m3u8 trực tiếp trong script
        doc.select("script").forEach { script ->
            val content = script.html()
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(content).forEach { match ->
                val link = match.groupValues[1]
                callback(
                    newExtractorLink(
                        name, 
                        name, 
                        link, 
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
