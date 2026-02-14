package com.boctem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BocTemProvider : MainAPI() {
    override var mainUrl = "https://boctem.com"
    override var name = "BocTem"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun requestHeaders(referer: String? = null, extra: Map<String, String>? = null): Map<String, String> {
        return defaultHeaders.toMutableMap().apply {
            referer?.let { put("Referer", it) }
            extra?.let { putAll(it) }
        }
    }

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return url
    }

    private fun cleanStreamUrl(url: String?): String? {
        return url?.replace("\\/", "/")?.replace("\"", "")?.replace("'", "")?.trim()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/phim-moi/page/" to "Phim Mới Cập Nhật",
        "$mainUrl/danh-sach/anime-bo/page/" to "Anime Bộ",
        "$mainUrl/danh-sach/anime-le/page/" to "Anime Lẻ",
        "$mainUrl/the-loai/hoc-duong/page/" to "Học Đường",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/", headers = requestHeaders(mainUrl)).document
        val home = document.select(".halim-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text()?.trim() ?: return null
        val href = normalizeUrl(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = normalizeUrl(
            selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("src")
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "utf-8")}"
        val document = app.get(searchUrl, headers = requestHeaders(mainUrl)).document
        return document.select(".halim-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val document = app.get(fixedUrl, headers = requestHeaders(fixedUrl)).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst(".halim-movie-title")?.text()?.trim()
            ?: return null

        val poster = normalizeUrl(
            document.selectFirst(".halim-movie-poster img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".halim-movie-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val seen = HashSet<String>()
        
        // SỬA LỖI TẬP PHIM: Chỉ quét link trong class list-episode để tránh lấy nhầm điểm đánh giá ở sidebar
        val episodes = document
            .select(".list-episode a[href*=/xem-phim/], .halim-list-eps a[href*=/xem-phim/]")
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!epUrl.contains("-tap-")) return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null

                val epName = link.text().trim().ifBlank { null }
                val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val tags = document.select(".halim-movie-genres a, .post-category a").map { it.text() }
        val year = Regex("""/release/(\d+)/""").find(fixedUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".halim-movie-year")?.text()?.toIntOrNull()

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val dataUrl = normalizeUrl(data) ?: return false
            val document = app.get(dataUrl, headers = requestHeaders(dataUrl)).document

            var hasLinks = false
            val linkCallback: (ExtractorLink) -> Unit = {
                hasLinks = true
                callback(it)
            }

            fun extractIframeUrlsFromText(text: String?): List<String> {
                if (text.isNullOrBlank()) return emptyList()
                val cleanText = text.replace("\\/", "/")
                val keyBased = Regex("""(?:src|file|link|embed_url|iframe|player|url)["']?\s*[:=]\s*["']((?:https?:)?//[^"'<>\s]+)["']""")
                    .findAll(cleanText)
                    .map { it.groupValues[1] }
                val rawBased = Regex("""(?:https?:)?//[^"'<>\s]+(?:embed|player|stream|video|watch)[^"'<>\s]*""")
                    .findAll(cleanText)
                    .map { it.value }

                return (keyBased + rawBased)
                    .map { cleanStreamUrl(it) }
                    .mapNotNull { normalizeUrl(it) }
                    .filter { !it.endsWith(".jpg") && !it.endsWith(".png") && !it.endsWith(".webp") }
                    .distinct()
                    .toList()
            }

            suspend fun tryM3u8FromText(text: String?): Boolean {
                if (text.isNullOrBlank()) return false
                val cleanText = text.replace("\\/", "/")
                val m3u8Patterns = listOf(
                    Regex("""(?:file|src|link|playlist)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                    Regex("""(?:https?:)?//[^"'<>\s]+\.m3u8[^"'<>\s]*"""),
                    Regex("""(?:master|hls|stream)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                )

                val m3u8 = m3u8Patterns.firstNotNullOfOrNull { pattern ->
                    val match = pattern.find(cleanText) ?: return@firstNotNullOfOrNull null
                    if (match.groupValues.size > 1) match.groupValues[1] else match.value
                }

                val cleanM3u8 = m3u8?.let(::cleanStreamUrl)?.let { normalizeUrl(it) ?: it }

                if (!cleanM3u8.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = cleanM3u8,
                        referer = dataUrl,
                        headers = requestHeaders(dataUrl)
                    ).forEach(linkCallback)
                    return true
                }
                return false
            }

            // 1. Quét HTML tĩnh trước
            tryM3u8FromText(document.html())

            // 2. Tìm thông tin AJAX
            val bodyClass = document.selectFirst("body")?.attr("class").orEmpty()
            val postId = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(bodyClass)?.groupValues?.get(1)

            val episode = Regex("""tap-(\d+)""").find(dataUrl)?.groupValues?.get(1) ?: "1"

            var nonce: String? = null
            for (script in document.select("script")) {
                val content = script.data()
                if (content.contains("nonce")) {
                    nonce = Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']""").find(content)?.groupValues?.get(1)
                    if (!nonce.isNullOrBlank()) break
                }
            }

            // SỬA LỖI 3001: Lặp qua tất cả server, không return ngay lập tức
            for (server in listOf("1", "2", "3", "4", "5")) {
                if (postId.isNullOrBlank() || nonce.isNullOrBlank()) continue
                
                val ajaxResponse = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "halim_ajax_player",
                            "nonce" to nonce,
                            "postid" to postId,
                            "episode" to episode,
                            "server" to server
                        ),
                        referer = dataUrl,
                        headers = requestHeaders(dataUrl, mapOf("X-Requested-With" to "XMLHttpRequest"))
                    ).text
                }.getOrNull() ?: continue

                tryM3u8FromText(ajaxResponse)
                
                val embeddedUrls = extractIframeUrlsFromText(ajaxResponse)
                embeddedUrls.forEach { loadExtractor(it, dataUrl, subtitleCallback, linkCallback) }
            }

            // Fallback: Quét toàn bộ iframe còn lại
            document.select("iframe[src]").mapNotNull { normalizeUrl(it.attr("src")) }.forEach {
                loadExtractor(it, dataUrl, subtitleCallback, linkCallback)
            }

            hasLinks
        }.getOrElse { false }
    }
}

@CloudstreamPlugin
class BocTem : Plugin() {
    override fun load() {
        registerMainAPI(BocTemProvider())
    }
}
