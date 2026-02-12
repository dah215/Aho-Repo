package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Element
import java.net.URLEncoder

// ── Jackson mapper (chia sẻ trong toàn file) ─────────────────────────────────
private val mapper = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

// ── Data classes cho API JSON (nếu có) ─────────────────────────────────────────
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiMovieItem(
    @JsonProperty("name")       val name: String?      = null,
    @JsonProperty("title")      val title: String?     = null,
    @JsonProperty("slug")       val slug: String?      = null,
    @JsonProperty("_id")        val id: String?        = null,
    @JsonProperty("thumb_url")  val thumbUrl: String?  = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("thumbnail")  val thumbnail: String? = null,
    @JsonProperty("image")      val image: String?     = null,
    @JsonProperty("type")       val type: String?      = null
)

// ── Main provider ─────────────────────────────────────────────────────────────
class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch     = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie
    )

    // ✅ FIX #1: Headers đầy đủ hơn để bypass Cloudflare/anti-bot
    private val defaultHeaders = mapOf(
        "User-Agent"               to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept"                   to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"          to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding"          to "gzip, deflate, br",
        "Cache-Control"            to "no-cache",
        "Pragma"                   to "no-cache",
        "Connection"               to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"           to "document",
        "Sec-Fetch-Mode"           to "navigate",
        "Sec-Fetch-Site"           to "none",
        "Sec-Fetch-User"           to "?1",
        "sec-ch-ua"                to "\"Chromium\";v=\"120\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile"         to "?1",
        "sec-ch-ua-platform"       to "\"Android\""
    )

    override val mainPage = mainPageOf(
        "list/phim-moi?page="      to "Phim Mới",
        "list/phim-le?page="       to "Phim Lẻ",
        "list/phim-bo?page="       to "Phim Bộ"
    )

    private fun requestHeaders(
        referer: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val h = defaultHeaders.toMutableMap()
        h["Referer"] = referer ?: mainUrl
        h.putAll(extra)
        return h
    }

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//")  -> "https:$url"
            url.startsWith("/")   -> "$mainUrl$url"
            else                  -> "$mainUrl/$url"
        }
    }

    private fun decodeUnicode(text: String) =
        text.replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

    private fun cleanStreamUrl(raw: String) =
        decodeUnicode(raw).trim().trim('"', '\'')
            .replace("\\/", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")
            .replace("\\n", "").replace("\\t", "").replace("\\", "")

    private fun toType(value: String): TvType {
        val lower = value.lowercase()
        return when {
            "anime" in lower || "hoat-hinh" in lower -> TvType.Anime
            "phim-le" in lower || "movie" in lower || "phim lẻ" in lower -> TvType.Movie
            "phim-bo" in lower || "series" in lower -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    // ── Parse HTML item ─────────────────────────────────────────────────────────
    private fun parseHtmlItem(el: Element): SearchResponse? {
        val anchor = el.selectFirst("a[href]") ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null

        // Bỏ qua các link không phải trang phim
        if (href.contains("/list/") || href.contains("/genre/") ||
            href.contains("/country/") || href == mainUrl || href == "$mainUrl/") {
            return null
        }

        // ✅ FIX #2: Lấy tên phim - thêm h2 (cấu trúc .ml-item dùng h2)
        val title = el.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: el.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: el.selectFirst(".mli-info h2")?.text()?.trim()
            ?: anchor.text().trim()

        if (title.isBlank()) return null

        // ✅ FIX #3: Lấy poster - thêm data-original (cấu trúc .ml-item dùng data-original)
        val img = el.selectFirst("img")
        val poster = normalizeUrl(
            img?.attr("data-original")?.takeIf { it.isNotBlank() && "placeholder" !in it }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && "placeholder" !in it }
        )

        val label = el.selectFirst(".label, span.label, .jt-info, .mli-info .jt-info")?.text()?.trim() ?: ""
        val typeStr = "$href $label"
        val type = toType(typeStr)

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ── Parse trang HTML ────────────────────────────────────────────────────────
    private fun parseHtmlPage(html: String): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // ✅ FIX #4: Thêm selector .movies-list .ml-item (cấu trúc thực của phimmoichill.now)
        val selectors = listOf(
            ".movies-list .ml-item",     // ← Cấu trúc CHÍNH của phimmoichill.now
            ".movies-list div",          // Fallback movies-list
            "div.ml-item",               // ml-item trực tiếp
            "#film-hot .item",           // Slider chính
            ".list-film .item",          // Danh sách phim
            ".list-film li.item",        // Danh sách phim (alternate)
            ".movie-list .item",         // Danh sách phim
            ".items .item",              // Danh sách phim
            "ul.list-film > li",         // Danh sách phim trực tiếp
            ".film_list-wrap .flw-item", // Fallback khác
            ".block .item"               // Fallback block
        )

        for (sel in selectors) {
            val elements = doc.select(sel)
            for (el in elements) {
                val result = parseHtmlItem(el)
                if (result != null && seen.add(result.url)) {
                    items.add(result)
                }
            }
            if (items.isNotEmpty()) break
        }

        // Fallback: Tìm tất cả các link /info/ hoặc /xem/ 
        if (items.isEmpty()) {
            // ✅ FIX #5: Thêm href*='/xem/' vào fallback (phimmoichill dùng /xem/ không phải /xem-phim/)
            doc.select("a[href*='/info/'], a[href*='/xem/']").forEach { anchor ->
                val href = normalizeUrl(anchor.attr("href")) ?: return@forEach
                // Bỏ qua link watch trực tiếp trong fallback
                if (href.contains("/xem/") && href.contains("-tap-")) return@forEach
                if (!seen.add(href)) return@forEach

                val title = anchor.attr("title").takeIf { it.isNotBlank() }
                    ?: anchor.selectFirst("h2")?.text()?.trim()
                    ?: anchor.selectFirst("p")?.text()?.trim()
                    ?: anchor.text().trim()

                if (title.isNotBlank()) {
                    val poster = normalizeUrl(
                        anchor.selectFirst("img")?.attr("data-original")?.takeIf { it.isNotBlank() }
                            ?: anchor.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    )
                    items.add(newTvSeriesSearchResponse(title, href, toType(href)) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return items
    }

    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.startsWith("http")) {
            "${request.data}$page"
        } else {
            val path = if (request.data.startsWith("/")) request.data else "/${request.data}"
            "$mainUrl$path$page"
        }

        val html = runCatching {
            app.get(url, headers = requestHeaders(mainUrl), timeout = 30).text
        }.getOrElse {
            // Thử với www
            val altUrl = url.replace("://", "://www.")
            runCatching {
                app.get(altUrl, headers = requestHeaders(mainUrl), timeout = 30).text
            }.getOrElse { "" }
        }

        val items = parseHtmlPage(html)

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // ✅ FIX #6: Sửa URL search đúng cấu trúc của phimmoichill.now
        // Site dùng path /tim-kiem/keyword thay vì ?keyword=
        val html = runCatching {
            app.get("$mainUrl/tim-kiem/$encoded", headers = requestHeaders(mainUrl), timeout = 30).text
        }.getOrElse {
            // Thử URL format cũ với query param
            runCatching {
                app.get("$mainUrl/tim-kiem/?keyword=$encoded", headers = requestHeaders(mainUrl), timeout = 30).text
            }.getOrElse { "" }
        }
        return parseHtmlPage(html)
    }

    // ── Load chi tiết phim ────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val html = app.get(fixedUrl, headers = requestHeaders(fixedUrl), timeout = 30).text
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title, h1.title, .film-info h1, .movie-title h1, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = normalizeUrl(
            doc.selectFirst(".film-poster img, .poster img, .thumb img, .movie-thumb img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("src")
            }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val desc = doc.selectFirst(".film-content, .description, .entry-content, .film-desc, .movie-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = doc.selectFirst(".film-meta .year, .year, .release-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.selectFirst("a[href*='/list/phim-nam-']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val tags = doc.select(".film-meta a[href*='/genre/'], .genres a, .category a").map { it.text() }

        val typeLabel = doc.selectFirst(".label, .film-type, .type")?.text()?.lowercase() ?: ""
        val type = toType("$fixedUrl $typeLabel ${tags.joinToString(" ")}")

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        // ✅ FIX #7: Thêm /xem/ vào episode selector (phimmoichill dùng /xem/ thay vì /xem-phim/)
        doc.select(
            ".episode a[href*='/xem/'], .episode a[href*='/xem-phim/'], " +
            ".ep-list a[href], .server-item a[href], " +
            "a[href*='/xem/'][href*='-tap-'], a[href*='/xem/'][href*='tap-'], " +
            "a[href*='tap-'], a[href*='episode']"
        ).forEach { link ->
            val epUrl = normalizeUrl(link.attr("href")) ?: return@forEach
            if (!seen.add(epUrl)) return@forEach

            val epName = link.text().trim().ifBlank { null }
            val epNum = Regex("""(?:tap|ep|tập|episode)[\s-]*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(epName ?: "")?.value?.toIntOrNull()

            episodes.add(newEpisode(epUrl) {
                name = epName
                episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            if (type == TvType.Movie || type == TvType.AnimeMovie) {
                return newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                    this.posterUrl = poster
                    this.plot = desc
                    this.year = year
                    this.tags = tags
                }
            }
            // Kiểm tra xem có link /xem/ trực tiếp trên trang không
            val directWatch = doc.selectFirst("a[href*='/xem/']")?.attr("href")
            val watchUrl = if (directWatch != null) normalizeUrl(directWatch) ?: fixedUrl else fixedUrl
            episodes.add(newEpisode(watchUrl) { name = "Full" })
        }

        return when (type) {
            TvType.Movie, TvType.AnimeMovie ->
                newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                    this.posterUrl = poster
                    this.plot = desc
                    this.year = year
                    this.tags = tags
                }
            else -> newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
                this.tags = tags
            }
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val dataUrl = normalizeUrl(data) ?: return false
            val html = app.get(dataUrl, headers = requestHeaders(dataUrl), timeout = 30).text
            val document = org.jsoup.Jsoup.parse(html)

            var hasLinks = false
            val linkCallback: (ExtractorLink) -> Unit = { hasLinks = true; callback(it) }

            fun extractUrls(text: String?): List<String> {
                if (text.isNullOrBlank()) return emptyList()
                val byKey = Regex("""(?:src|file|link|embed_url|iframe|player|url)["']?\s*[:=]\s*["']((?:https?:)?//[^"'<>\s]+)["']""")
                    .findAll(text).map { it.groupValues[1] }
                val byRaw = Regex("""(?:https?:)?//[^"'<>\s]+(?:m3u8|mp4|embed|player|stream|video|watch)[^"'<>\s]*""")
                    .findAll(text).map { it.value }
                return (byKey + byRaw)
                    .map { cleanStreamUrl(it) }.mapNotNull { normalizeUrl(it) }
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
                        source = name, streamUrl = cleanM3u8, referer = dataUrl,
                        headers = requestHeaders(dataUrl)
                    ).forEach(linkCallback)
                    if (hasLinks) return true
                    linkCallback(newExtractorLink(name, "$name M3U8", cleanM3u8, ExtractorLinkType.M3U8) {
                        this.referer = dataUrl; this.headers = requestHeaders(dataUrl)
                        this.quality = Qualities.Unknown.value
                    })
                    return hasLinks
                }
                val direct = Regex("""(?:file|src|link)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|webm)(?:\?[^"']*)?)["']""")
                    .find(text)?.groupValues?.get(1)
                    ?: Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv|webm)(?:\?[^"'<>\s]*)?""").find(text)?.value
                val cleanDirect = direct?.let(::cleanStreamUrl)?.let { normalizeUrl(it) ?: it }
                if (!cleanDirect.isNullOrBlank()) {
                    linkCallback(newExtractorLink(name, "$name Direct", cleanDirect, ExtractorLinkType.VIDEO) {
                        this.referer = dataUrl; this.headers = requestHeaders(dataUrl)
                        this.quality = Qualities.Unknown.value
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

            val inlineIframe = normalizeUrl(document.selectFirst("iframe[src]")?.attr("src"))
            if (!inlineIframe.isNullOrBlank()) {
                loadExtractor(inlineIframe, dataUrl, subtitleCallback, linkCallback)
                if (hasLinks) return true
            }

            for (script in document.select("script")) {
                if (tryText(script.data() + "\n" + script.html())) return true
            }

            document.select("[data-src], [data-link], [data-url], [data-embed]").forEach { el ->
                val src = el.attr("data-src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-link").takeIf { it.isNotBlank() }
                    ?: el.attr("data-url").takeIf { it.isNotBlank() }
                    ?: el.attr("data-embed").takeIf { it.isNotBlank() }
                if (!src.isNullOrBlank()) {
                    val normalized = normalizeUrl(cleanStreamUrl(src))
                    if (!normalized.isNullOrBlank()) {
                        loadExtractor(normalized, dataUrl, subtitleCallback, linkCallback)
                    }
                }
            }

            if (hasLinks) return true
            if (tryText(html)) return true
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
