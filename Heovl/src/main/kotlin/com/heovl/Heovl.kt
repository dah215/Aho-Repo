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

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/categories/moi/" to "Mới Cập Nhật",
        "/categories/vietsub/" to "VietSub",
        "/categories/hoat-hinh-hentai/" to "Hentai",
        "/actresses/minami-aizawa/" to "Minami-Aizawa",
        "/actresses/tsumugi-akari/" to "Tsumugi-Akari",
        "actresses/riri-nanatsumori/" to "Riri-Nanatsumori",
        "actresses/miu-shiromine/" to "Miu-Shiromine",
        "actresses/kanako-ioka/" to "Kanako-Ioka"
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
        val html = res.text

        // Tìm tất cả iframe URLs và video IDs
        val videoIds = mutableSetOf<String>()
        
        // Pattern để tìm video ID từ các server
        val patterns = listOf(
            """trivonix\.top/videos/([a-zA-Z0-9]+)""",
            """streamqq\.com/videos/([a-zA-Z0-9]+)""",
            """spexliu\.top/videos/([a-zA-Z0-9]+)""",
            """/videos/([a-zA-Z0-9]+)/play""",
            """/videos/([a-zA-Z0-9]+)/master\.m3u8"""
        )

        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                videoIds.add(match.groupValues[1])
            }
        }

        if (videoIds.isEmpty()) return false

        // Ưu tiên server trivonix.top (không cần token)
        val servers = listOf(
            "trivonix.top" to "Trivonix",
            "p1.spexliu.top" to "Spexliu",
            "e.streamqq.com" to "StreamQQ"
        )

        for ((domain, serverName) in servers) {
            for (videoId in videoIds) {
                val m3u8Url = "https://$domain/videos/$videoId/master.m3u8"
                
                try {
                    val checkHeaders = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://$domain/"
                    )
                    val checkRes = app.get(m3u8Url, headers = checkHeaders)
                    
                    if (checkRes.code == 200 && checkRes.text.contains("#EXTM3U")) {
                        callback(
                            newExtractorLink(name, "Server $serverName", m3u8Url, type = ExtractorLinkType.M3U8) {
                                this.referer = "https://$domain/"
                                this.headers = checkHeaders
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                } catch (e: Exception) {
                    // Try next server
                }
            }
        }

        return false
    }
}
