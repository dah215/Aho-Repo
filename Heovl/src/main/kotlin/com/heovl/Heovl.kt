package com.heovl

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/categories/moi/" to "Mới Cập Nhật",
        "/categories/viet-nam/" to "Việt Nam",
        "/categories/han-quoc/" to "Hàn Quốc",
        "/categories/nhat-ban/" to "Nhật Bản",
        "/categories/trung-quoc/" to "Trung Quốc"
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
        val url = "$mainUrl${request.data}${if (page > 1) "?page=$page" else ""}"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() ?: "" }
            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
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

        // Find all iframe sources
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) potentialUrls.add(src)
        }

        // Find URLs in attributes
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val value = attr.value
                if (value.contains("spexliu") || value.contains(".m3u8") || value.contains("iframe")) {
                    potentialUrls.add(value)
                }
            }
        }

        // Find URLs in HTML via regex
        Regex("""https?://[^\s"'<>]+""").findAll(html).forEach { 
            potentialUrls.add(it.value) 
        }

        potentialUrls.filter { it.isNotBlank() }.distinct().forEach { rawUrl ->
            val fullUrl = fixUrl(rawUrl)

            // Direct m3u8 link
            if (fullUrl.contains("master.m3u8")) {
                callback(
                    newExtractorLink(name, "Server VIP", fullUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "https://p1.spexliu.top/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            // Iframe - fetch and extract m3u8
            else if (fullUrl.contains("spexliu") || fullUrl.contains("streamqq") || fullUrl.contains("trivonix")) {
                try {
                    val iframeRes = app.get(fullUrl, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36",
                        "Referer" to data
                    ))
                    val iframeHtml = iframeRes.text

                    // Find m3u8 in iframe HTML
                    val m3u8Patterns = listOf(
                        """(https://p1\.spexliu\.top/videos/[a-zA-Z0-9]+/master\.m3u8[^"'\s]*)""",
                        """(/videos/[a-zA-Z0-9]+/master\.m3u8[^"'\s]*)""",
                        """(https://[^\s"']+master\.m3u8[^\s"']*)"""
                    )

                    for (pattern in m3u8Patterns) {
                        val match = Regex(pattern).find(iframeHtml)
                        if (match != null) {
                            var m3u8Url = match.groupValues[1].replace("\\/", "/")
                            if (m3u8Url.startsWith("/videos/")) {
                                m3u8Url = "https://p1.spexliu.top$m3u8Url"
                            }
                            callback(
                                newExtractorLink(name, "Server VIP", m3u8Url, type = ExtractorLinkType.M3U8) {
                                    this.referer = "https://p1.spexliu.top/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Skip failed iframe
                }
            }
        }

        return true
    }
}
