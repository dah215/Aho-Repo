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

    private fun requestHeaders(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = defaultHeaders.toMutableMap()
        headers["Referer"] = referer ?: mainUrl
        headers.putAll(extra)
        return headers
    }

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanStreamUrl(raw: String): String {
        return raw.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim('"', '\'', ' ')
    }

    override val mainPage = mainPageOf(
        "danh-sach/phim-moi/page/" to "Phim Mới Cập Nhật",
        "danh-sach/anime-bo/page/" to "Anime Bộ",
        "danh-sach/anime-le/page/" to "Anime Lẻ",
        "the-loai/hoc-duong/page/" to "Học Đường",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document
        val items = document.select("article.thumb.grid-item").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items)
    }

    private fun parseCard(article: Element): SearchResponse? {
        val anchor = article.selectFirst("a") ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null
        val title = article.selectFirst(".entry-title")?.text()?.trim() ?: anchor.attr("title").trim()
        if (title.isEmpty()) return null

        val image = article.selectFirst("img")
        val poster = normalizeUrl(image?.attr("data-src")?.takeIf { it.isNotBlank() } ?: image?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document
        return document.select("article.thumb.grid-item").mapNotNull { parseCard(it) }
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
        )

        val seen = HashSet<String>()
        // FIX: Chỉ lấy link tập phim trong đúng khu vực chứa danh sách tập
        val episodes = document.select(".list-episode a[href*=/xem-phim/], .halim-list-eps a[href*=/xem-phim/]")
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null

                val epName = link.text().trim()
                val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = document.selectFirst(".entry-content")?.text()?.trim()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataUrl = normalizeUrl(data) ?: return false
        val document = app.get(dataUrl, headers = requestHeaders(dataUrl)).document
        var hasLinks = false

        val linkCallback: (ExtractorLink) -> Unit = {
            hasLinks = true
            callback(it)
        }

        suspend fun invokeExtractor(url: String) {
            loadExtractor(url, dataUrl, subtitleCallback, linkCallback)
        }

        // 1. Thử lấy m3u8 trực tiếp từ script
        document.select("script").forEach { script ->
            val text = script.data()
            Regex("""(?:file|link|src)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""").find(text)?.let {
                val m3u8 = cleanStreamUrl(it.groupValues[1])
                M3u8Helper.generateM3u8(name, m3u8, dataUrl, requestHeaders(dataUrl)).forEach(linkCallback)
            }
        }

        // 2. Xử lý AJAX để lấy các server khác (Tránh lỗi 3001)
        val bodyClass = document.selectFirst("body")?.attr("class").orEmpty()
        val postId = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
        val nonce = Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1)
        val episode = Regex("""tap-(\d+)""").find(dataUrl)?.groupValues?.get(1) ?: "1"

        if (!postId.isNullOrBlank() && !nonce.isNullOrBlank()) {
            for (server in listOf("1", "2", "3", "4", "5")) {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "halim_ajax_player", "nonce" to nonce, "postid" to postId, "episode" to episode, "server" to server),
                    headers = requestHeaders(dataUrl, mapOf("X-Requested-With" to "XMLHttpRequest"))
                ).text

                if (response.isNotBlank()) {
                    // Tìm iframe trong phản hồi AJAX
                    Regex("""iframe[^>]+src=["']([^"']+)["']""").find(response.replace("\\/", "/"))?.let {
                        invokeExtractor(normalizeUrl(it.groupValues[1])!!)
                    }
                }
            }
        }

        return hasLinks
    }
}

@CloudstreamPlugin
class BocTem : Plugin() {
    override fun load() {
        registerMainAPI(BocTemProvider())
    }
}
