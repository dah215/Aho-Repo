package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    // FIX: thêm "?page=" vào cuối mỗi data string
    // Khi page=1 thì URL = "https://phimmoichill.now/list/phim-moi?page=1"
    // Thay vì trước đây bị thành "https://phimmoichill.now/list/phim-moi1" (sai)
    override val mainPage = mainPageOf(
        "list/phim-moi?page="       to "Phim Mới",
        "list/phim-le?page="        to "Phim Lẻ",
        "list/phim-bo?page="        to "Phim Bộ"
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

    private fun decodeUnicode(text: String): String {
        return text.replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun cleanStreamUrl(raw: String): String {
        return decodeUnicode(raw)
            .trim()
            .trim('"', '\'')
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\n", "")
            .replace("\\t", "")
            .replace("\\", "")
    }

    private fun toType(value: String): TvType {
        val lower = value.lowercase()
        return when {
            "anime" in lower || "hoat-hinh" in lower -> TvType.Anime
            "phim-le" in lower || "movie" in lower   -> TvType.Movie
            else                                      -> TvType.TvSeries
        }
    }

    // FIX: hàm lấy ảnh từ element - thử nhiều attribute
    private fun extractPoster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return normalizeUrl(
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-original").takeIf { it.isNotBlank() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-thumb").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.contains("placeholder") }
        )
    }

    private fun parseCard(el: Element): SearchResponse? {
        // FIX: loại bỏ element nằm trong nav/menu để không pick nhầm
        val isInNav = el.parents().any { parent ->
            val cls = parent.className().lowercase()
            val tag = parent.tagName().lowercase()
            tag == "nav" || "nav" in cls || "menu" in cls || "header" in cls || "footer" in cls
        }
        if (isInNav) return null

        val anchor = el.selectFirst("a[href]") ?: return null
        val href   = normalizeUrl(anchor.attr("href")) ?: return null

        // Bỏ qua nếu href chỉ trỏ tới danh mục/trang list (không phải trang phim)
        if (href.contains("/list/") || href == mainUrl || href == "$mainUrl/") return null

        // FIX: ưu tiên lấy tên từ selector cụ thể, nếu không có thì dùng title/alt
        val title = listOf(
            el.selectFirst(".film-title, .item-title, .entry-title, .halim-post-title, h3, h2, h4")?.text(),
            anchor.attr("title"),
            el.selectFirst("img")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        // Bỏ qua nếu tiêu đề trông giống label danh mục
        val categoryLabels = setOf("phimmoi", "phim lẻ", "phim bộ", "phim hành động", "hoạt hình", "phim việt nam")
        if (title.lowercase() in categoryLabels) return null

        val poster = extractPoster(el)
        val typeText = (el.attr("class") + " " + href).lowercase()
        val type = toType(typeText)

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FIX: URL giờ đã đúng vì data đã có "?page=" ở cuối
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document

        // FIX: selector cụ thể hơn cho card phim - ưu tiên từ cao xuống thấp
        val movieCardSelectors = listOf(
            ".items .item",
            ".list-film .item",
            ".film_list-wrap .flw-item",
            ".movie-list .item",
            ".halim_box",
            ".item.thumb",
            "ul.list-movie li",
            ".content-left .item",
            "article.item",
            "article"
        )

        var items = emptyList<SearchResponse>()
        for (selector in movieCardSelectors) {
            val found = document.select(selector)
                .mapNotNull { parseCard(it) }
                .distinctBy { it.url }
            if (found.isNotEmpty()) {
                items = found
                break
            }
        }

        // Fallback: lấy tất cả link trỏ tới trang phim cụ thể
        if (items.isEmpty()) {
            items = document
                .select("a[href]")
                .filter { a ->
                    val href = a.attr("href")
                    (href.contains("/phim/") || href.contains("/xem-phim/")) &&
                            !href.contains("/list/")
                }
                .mapNotNull { a ->
                    val href = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
                    val title = (a.attr("title").takeIf { it.isNotBlank() }
                        ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val poster = normalizeUrl(
                        a.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: a.selectFirst("img")?.attr("src")
                    )
                    newTvSeriesSearchResponse(title, href, toType(href)) {
                        this.posterUrl = poster
                    }
                }
                .distinctBy { it.url }
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded  = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = requestHeaders(mainUrl)).document

        val movieCardSelectors = listOf(
            ".items .item", ".list-film .item", ".film_list-wrap .flw-item",
            ".movie-list .item", ".halim_box", ".item.thumb", "article.item", "article"
        )

        for (selector in movieCardSelectors) {
            val found = document.select(selector)
                .mapNotNull { parseCard(it) }
                .distinctBy { it.url }
            if (found.isNotEmpty()) return found
        }

        return document.select("a[href*=/phim/]")
            .mapNotNull { a ->
                val href  = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
                val title = (a.attr("title").takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newTvSeriesSearchResponse(title, href, toType(href)) {
                    this.posterUrl = normalizeUrl(a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src"))
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val document = app.get(fixedUrl, headers = requestHeaders(fixedUrl)).document

        val title = document.selectFirst(
            "h1.entry-title, h1.halim-movie-title, h1.film-title, .detail-title h1, h1"
        )?.text()?.trim() ?: return null

        val poster = normalizeUrl(
            document.selectFirst(".halim-movie-poster img, .poster img, .thumb img, .film-poster img")
                ?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("src")
                }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(
            ".entry-content, .halim-movie-content, .desc, .film-content"
        )?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select(
            ".halim-movie-genres a, .post-category a, .genres a, .category a"
        ).map { it.text().trim() }

        val year = document.selectFirst(".halim-movie-year, .year, .film-year")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val seen = HashSet<String>()
        val episodes = document.select(
            "a[href*=/xem-phim/], .episode a[href], .halim-episode a[href], .server-list a[href], .ep-item a[href]"
        )
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null
                val epName = link.text().trim().ifBlank { null }
                val epNum  = Regex("""(?:tap-|episode-|ep-)(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epUrl) {
                    this.name      = epName
                    this.episode   = epNum
                    this.posterUrl = poster
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val type = toType(document.body().className() + " " + fixedUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
            else -> newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val dataUrl  = normalizeUrl(data) ?: return false
            val document = app.get(dataUrl, headers = requestHeaders(dataUrl)).document

            var hasLinks = false
            val linkCallback: (ExtractorLink) -> Unit = { hasLinks = true; callback(it) }

            fun extractUrls(text: String?): List<String> {
                if (text.isNullOrBlank()) return emptyList()
                val byKey = Regex("""(?:src|file|link|embed_url|iframe|player|url)["']?\s*[:=]\s*["']((?:https?:)?//[^"'<>\s]+)["']""")
                    .findAll(text).map { it.groupValues[1] }
                val byRaw = Regex("""(?:https?:)?//[^"'<>\s]+(?:m3u8|mp4|embed|player|stream|video|watch)[^"'<>\s]*""")
                    .findAll(text).map { it.value }
                return (byKey + byRaw)
                    .map { cleanStreamUrl(it) }
                    .mapNotNull { normalizeUrl(it) }
                    .distinct().toList()
            }

            suspend fun tryText(text: String?): Boolean {
                if (text.isNullOrBlank()) return false

                val m3u8Patterns = listOf(
                    Regex("""(?:file|src|link|playlist)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                    Regex("""(?:https?:)?//[^"'<>\s]+\.m3u8[^"'<>\s]*"""),
                    Regex("""(?:master|hls|stream)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                )
                val m3u8 = m3u8Patterns.firstNotNullOfOrNull { p ->
                    val m = p.find(text) ?: return@firstNotNullOfOrNull null
                    if (m.groupValues.size > 1) m.groupValues[1] else m.value
                }
                val cleanM3u8 = m3u8?.let(::cleanStreamUrl)?.let { normalizeUrl(it) ?: it }
                if (!cleanM3u8.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = cleanM3u8,
                        referer = dataUrl,
                        headers = requestHeaders(dataUrl)
                    ).forEach(linkCallback)
                    if (hasLinks) return true
                    linkCallback(newExtractorLink(name, "$name M3U8", cleanM3u8, ExtractorLinkType.M3U8) {
                        this.referer = dataUrl; this.headers = requestHeaders(dataUrl); this.quality = Qualities.Unknown.value
                    })
                    return hasLinks
                }

                val direct = Regex("""(?:file|src|link)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|webm)(?:\?[^"']*)?)["']""")
                    .find(text)?.groupValues?.get(1)
                    ?: Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv|webm)(?:\?[^"'<>\s]*)?""").find(text)?.value
                val cleanDirect = direct?.let(::cleanStreamUrl)?.let { normalizeUrl(it) ?: it }
                if (!cleanDirect.isNullOrBlank()) {
                    linkCallback(newExtractorLink(name, "$name Direct", cleanDirect, ExtractorLinkType.VIDEO) {
                        this.referer = dataUrl; this.headers = requestHeaders(dataUrl); this.quality = Qualities.Unknown.value
                    })
                    return hasLinks
                }

                val urls = extractUrls(text)
                if (urls.isNotEmpty()) {
                    urls.forEach { loadExtractor(it, dataUrl, subtitleCallback, linkCallback) }
                    if (hasLinks) return true
                }
                return false
            }

            if (tryText(document.html())) return true

            val inlineIframe = normalizeUrl(document.selectFirst("iframe[src]")?.attr("src"))
            if (!inlineIframe.isNullOrBlank()) {
                loadExtractor(inlineIframe, dataUrl, subtitleCallback, linkCallback)
                if (hasLinks) return true
            }

            for (script in document.select("script")) {
                if (tryText(script.data() + "\n" + script.html())) return true
            }

            val bodyClass = document.selectFirst("body")?.attr("class").orEmpty()
            val postId = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(document.selectFirst("article")?.attr("id").orEmpty())?.groupValues?.get(1)

            val episode = Regex("""tap-(\d+)""").find(dataUrl)?.groupValues?.get(1)
                ?: Regex("""episode=(\d+)""").find(dataUrl)?.groupValues?.get(1)
                ?: "1"

            var nonce: String? = null
            for (script in document.select("script")) {
                val content = script.data() + "\n" + script.html()
                if (content.contains("ajax_player") || content.contains("nonce")) {
                    nonce = Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']""").find(content)?.groupValues?.get(1)
                    if (!nonce.isNullOrBlank()) break
                }
            }

            suspend fun requestAjax(server: String): String? {
                if (postId.isNullOrBlank() || nonce.isNullOrBlank()) return null
                val payloads = listOf(
                    mapOf("action" to "halim_ajax_player", "nonce" to nonce, "postid" to postId, "episode" to episode, "server" to server),
                    mapOf("action" to "ajax_player",       "nonce" to nonce, "postid" to postId, "episode" to episode, "server" to server)
                )
                for (payload in payloads) {
                    val response = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php", data = payload, referer = dataUrl,
                            headers = requestHeaders(dataUrl, mapOf("Origin" to mainUrl, "X-Requested-With" to "XMLHttpRequest"))
                        ).text
                    }.getOrNull()
                    if (!response.isNullOrBlank()) return response
                }
                return null
            }

            for (server in listOf("1", "2", "3", "4", "5")) {
                val ajaxResponse = requestAjax(server) ?: continue
                if (tryText(ajaxResponse)) return true
            }

            loadExtractor(dataUrl, dataUrl, subtitleCallback, linkCallback)
            hasLinks
        }.getOrElse { false }
    }
}

@CloudstreamPlugin
class PhimMoiChill : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}
