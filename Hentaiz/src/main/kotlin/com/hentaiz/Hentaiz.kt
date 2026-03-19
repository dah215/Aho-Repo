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
        "/" to "Mới Cập Nhật",
        "the-loai/han-quoc-18/" to "Hàn Quốc 18+",
        "the-loai/nhat-ban/" to "Nhật Bản",
        "the-loai/loan-luan/" to "Loạn Luân",
        "the-loai/trung-quoc/" to "Trung Quốc"
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
        val desc = doc.selectFirst("article p, .entry-content p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".categories a, .tags a").map { it.text() }
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
        
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val value = attr.value
                if (value.contains("dfplayer") || value.contains(".m3u8") || value.contains("bf.html")) {
                    potentialUrls.add(value)
                }
            }
        }
        
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { potentialUrls.add(it.value) }

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

            else if (fullUrl.contains("dood") || fullUrl.contains("tape") || fullUrl.contains("voe")) {
                loadExtractor(fullUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
