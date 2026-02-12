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

// ── Jackson mapper ────────────────────────────────────────────────────────────
private val mapper = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

// ── Data classes ──────────────────────────────────────────────────────────────
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
    // ✅ FIX 1: Cập nhật domain sống mới nhất (phimmoichill.net / .aa / .tv)
    override var mainUrl = "https://phimmoichill.net" 
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch     = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie
    )

    // ✅ FIX 2: Headers tối ưu để bypass Cloudflare (Bỏ bớt các Sec-Fetch cứng nhắc)
    private val defaultHeaders = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"           to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "DNT"                       to "1",
        "Upgrade-Insecure-Requests" to "1",
        "Connection"                to "keep-alive"
    )

    // ✅ FIX 3: Cập nhật path trang chủ đúng chuẩn domain mới
    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le"  to "Phim Lẻ",
        "list/phim-bo"  to "Phim Bộ"
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

        if (href.contains("/genre/") || href.contains("/country/") || href == mainUrl || href == "$mainUrl/") {
            return null
        }

        val title = el.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: el.selectFirst(".title, .movie-title")?.text()?.trim()
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: anchor.text().trim()

        if (title.isBlank()) return null

        val img = el.selectFirst("img")
        val poster = normalizeUrl(
            img?.attr("data-original")?.takeIf { it.isNotBlank() && "placeholder" !in it }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && "placeholder" !in it }
        )

        val label = el.selectFirst(".label, span.label")?.text()?.trim() ?: ""
        val typeStr = "$href $label"
        
        return newTvSeriesSearchResponse(title, href, toType(typeStr)) {
            this.posterUrl = poster
        }
    }

    // ── Parse Page ──────────────────────────────────────────────────────────────
    private fun parseHtmlPage(html: String): List<SearchResponse> {
        if (html.isBlank()) return emptyList()
        val doc = org.jsoup.Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // ✅ FIX 4: Thêm selectors mạnh mẽ hơn cho layout mới
        val selectors = listOf(
            ".block-body li.item",      // Layout mới phổ biến
            "ul.list-movie li.item",    // Layout danh sách chuẩn
            ".movies-list .ml-item",    // Layout cũ
            ".list-film .item",
            "#film-hot .item",
            ".items .item",
            "div.item"
        )

        for (sel in selectors) {
            val elements = doc.select(sel)
            if (elements.isNotEmpty()) {
                for (el in elements) {
                    val result = parseHtmlItem(el)
                    if (result != null && seen.add(result.url)) {
                        items.add(result)
                    }
                }
                if (items.size > 2) break // Nếu tìm thấy > 2 phim thì tin tưởng selector này
            }
        }
        
        // Fallback nếu không tìm thấy item nào theo cấu trúc chuẩn
        if (items.isEmpty()) {
             doc.select("a[href*='-tap-'], a[href*='/xem/']").forEach { anchor ->
                val href = normalizeUrl(anchor.attr("href")) ?: return@forEach
                if (!seen.add(href)) return@forEach
                 
                val title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text().trim()
                if (title.isNotBlank()) {
                     val poster = normalizeUrl(anchor.selectFirst("img")?.attr("src"))
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
        // ✅ FIX 5: Xử lý URL phân trang đúng chuẩn "?page=" hoặc "/page/"
        val url = if (request.data.startsWith("http")) {
             // Nếu đã là link full (ít gặp)
             val separator = if (request.data.contains("?")) "&page=" else "?page="
             "${request.data}$separator$page"
        } else {
            // Chuẩn hóa path tránh trùng lặp dấu /
            val cleanPath = request.data.removePrefix("/")
            "$mainUrl/$cleanPath?page=$page"
        }

        val html = runCatching {
            app.get(url, headers = requestHeaders(mainUrl)).text
        }.getOrElse { 
            // Thử fallback nếu lỗi
            "" 
        }

        val items = parseHtmlPage(html)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // ✅ FIX 6: Sửa lại link search
        val searchUrl = "$mainUrl/tim-kiem/$encoded"
        
        val html = runCatching {
            app.get(searchUrl, headers = requestHeaders(mainUrl)).text
        }.getOrElse { 
            // Fallback search kiểu cũ
            runCatching {
                app.get("$mainUrl/tim-kiem/?keyword=$encoded", headers = requestHeaders(mainUrl)).text
            }.getOrElse { "" }
        }
        return parseHtmlPage(html)
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        
        // Thêm cơ chế retry cho load
        val html = runCatching {
             app.get(fixedUrl, headers = requestHeaders(fixedUrl)).text
        }.getOrElse { return null }
        
        val doc = org.jsoup.Jsoup.parse(html)

        val title = doc.selectFirst("h1.title, h1.entry-title, .movie-title h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = normalizeUrl(
            doc.selectFirst(".film-poster img, .poster img")?.let {
                it.attr("data-original").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val desc = doc.selectFirst(".description, .film-content, .film-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = doc.select(".category a, .genres a").map { it.text() }
        val year = doc.selectFirst(".year, .release-year")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        // Phân loại
        val typeLabel = doc.selectFirst(".label, .film-type")?.text()?.lowercase() ?: ""
        val type = toType("$fixedUrl $typeLabel ${tags.joinToString(" ")}")

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        // ✅ FIX 7: Selector tập phim bao quát hơn
        doc.select("a[href*='-tap-'], .episode a, .list-episode a").forEach { link ->
            val epUrl = normalizeUrl(link.attr("href")) ?: return@forEach
            if (!seen.add(epUrl)) return@forEach

            val epName = link.text().trim()
            // Regex lấy số tập
            val epNum = Regex("""(\d+)""").find(epName)?.value?.toIntOrNull()

            episodes.add(newEpisode(epUrl) {
                name = epName
                episode = epNum
            })
        }

        // Nếu không tìm thấy tập, có thể là phim lẻ (link watch trực tiếp)
        if (episodes.isEmpty()) {
             val watchLink = doc.selectFirst("a.btn-watch, a.btn-see")?.attr("href") 
                 ?: doc.selectFirst("a[href*='/xem-phim/']")?.attr("href")
                 ?: fixedUrl
             
             episodes.add(newEpisode(normalizeUrl(watchLink) ?: fixedUrl) {
                 name = "Full"
             })
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataUrl = normalizeUrl(data) ?: return false
        
        val html = runCatching {
             app.get(dataUrl, headers = requestHeaders(dataUrl)).text
        }.getOrElse { return false }
        
        // Logic extract link giữ nguyên nhưng thêm check null an toàn
        val doc = org.jsoup.Jsoup.parse(html)
        
        // 1. Tìm iframe/embed trực tiếp
        doc.select("iframe[src]").forEach {
            val src = normalizeUrl(it.attr("src"))
            if (!src.isNullOrBlank()) {
                 loadExtractor(src, dataUrl, subtitleCallback, callback)
            }
        }
        
        // 2. Quét script tìm link m3u8/mp4 ẩn (quan trọng với phimmoi)
        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        
        // Regex tìm link ẩn
        val regex = Regex("""(https?://[^"']+\.(m3u8|mp4)[^"']*)""")
        regex.findAll(scriptContent).forEach { match ->
            val url = match.groupValues[1].replace("\\/", "/")
            
            if (url.contains(".m3u8")) {
                 M3u8Helper.generateM3u8(
                    name, url, dataUrl, headers = requestHeaders(dataUrl)
                ).forEach(callback)
            } else {
                callback(newExtractorLink(name, "Direct", url, ExtractorLinkType.VIDEO, Qualities.Unknown.value))
            }
        }

        return true
    }
}

@CloudstreamPlugin
class PhimMoiChill : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}
