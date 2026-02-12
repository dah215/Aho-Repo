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

// ── Data classes cho __NEXT_DATA__ ────────────────────────────────────────────
@JsonIgnoreProperties(ignoreUnknown = true)
data class NextDataRoot(
    @JsonProperty("props") val props: NextProps? = null,
    @JsonProperty("pageProps") val pageProps: PageProps? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NextProps(
    @JsonProperty("pageProps") val pageProps: PageProps? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PageProps(
    @JsonProperty("data")       val data: ListData?          = null,
    @JsonProperty("movies")     val movies: List<MovieItem>? = null,
    @JsonProperty("items")      val items: List<MovieItem>?  = null,
    @JsonProperty("films")      val films: List<MovieItem>?  = null,
    @JsonProperty("listFilm")   val listFilm: List<MovieItem>? = null,
    @JsonProperty("listMovie")  val listMovie: List<MovieItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListData(
    @JsonProperty("items")     val items: List<MovieItem>? = null,
    @JsonProperty("movies")    val movies: List<MovieItem>? = null,
    @JsonProperty("films")     val films: List<MovieItem>? = null,
    @JsonProperty("listFilm")  val listFilm: List<MovieItem>? = null,
    @JsonProperty("data")      val data: List<MovieItem>? = null,
    @JsonProperty("list")      val list: List<MovieItem>? = null,
    @JsonProperty("total")     val total: Int? = null,
    @JsonProperty("totalPage") val totalPage: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieItem(
    @JsonProperty("name")       val name: String?      = null,
    @JsonProperty("title")      val title: String?     = null,
    @JsonProperty("slug")       val slug: String?      = null,
    @JsonProperty("_id")        val id: String?        = null,
    @JsonProperty("id")         val idAlt: String?     = null,
    @JsonProperty("thumb_url")  val thumbUrl: String?  = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("thumbnail")  val thumbnail: String? = null,
    @JsonProperty("image")      val image: String?     = null,
    @JsonProperty("poster")     val poster: String?    = null,
    @JsonProperty("category")   val category: List<Category>? = null,
    @JsonProperty("type")       val type: String?      = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Category(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null
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

    private val defaultHeaders = mapOf(
        "User-Agent"               to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept"                   to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language"          to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control"            to "no-cache",
        "Pragma"                   to "no-cache",
        "Connection"               to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi?page="       to "Phim Mới",
        "list/phim-le?page="        to "Phim Lẻ",
        "list/phim-bo?page="        to "Phim Bộ"
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
            "phim-le" in lower || "movie" in lower   -> TvType.Movie
            else                                      -> TvType.TvSeries
        }
    }

    // ── Next.js __NEXT_DATA__ parser ─────────────────────────────────────────
    private fun extractNextData(html: String): List<MovieItem> {
        val jsonStr = Regex("""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1)?.trim() ?: return emptyList()

        return runCatching {
            val root = mapper.readValue(jsonStr, NextDataRoot::class.java)
            val pp   = root.props?.pageProps ?: root.pageProps ?: return emptyList()
            val ld   = pp.data
            (ld?.items ?: ld?.movies ?: ld?.films ?: ld?.listFilm
                ?: ld?.data ?: ld?.list
                ?: pp.movies ?: pp.items ?: pp.films ?: pp.listFilm ?: pp.listMovie)
                ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // ── Thử API JSON trực tiếp ────────────────────────────────────────────────
    private suspend fun tryApiEndpoint(path: String, page: Int): List<MovieItem> {
        val slug = path.removePrefix("list/").substringBefore("?")
        val candidates = listOf(
            "$mainUrl/api/$slug?page=$page",
            "$mainUrl/api/list/$slug?page=$page",
            "$mainUrl/api/phim?type=$slug&page=$page"
        )
        val jsonHeaders = requestHeaders(mainUrl, mapOf("Accept" to "application/json"))
        for (url in candidates) {
            val text = runCatching {
                app.get(url, headers = jsonHeaders).text
            }.getOrNull() ?: continue
            if (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("[")) continue

            val items: List<MovieItem> = runCatching {
                if (text.trimStart().startsWith("[")) {
                    mapper.readValue(text, mapper.typeFactory.constructCollectionType(List::class.java, MovieItem::class.java))
                } else {
                    val node = mapper.readTree(text)
                    val arr  = node["items"] ?: node["movies"] ?: node["films"]
                        ?: node["data"]?.get("items") ?: node["data"]?.get("movies")
                        ?: node["data"] ?: return@runCatching emptyList<MovieItem>()
                    mapper.convertValue(arr, mapper.typeFactory.constructCollectionType(List::class.java, MovieItem::class.java))
                }
            }.getOrElse { emptyList() }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    // ── MovieItem → SearchResponse ────────────────────────────────────────────
    private fun MovieItem.toSearchResponse(): SearchResponse? {
        val title  = name ?: title ?: return null
        val slug   = slug ?: return null
        val url    = normalizeUrl("/phim/$slug") ?: return null
        val poster = normalizeUrl(thumbUrl ?: posterUrl ?: thumbnail ?: image ?: poster)
        val typeHint = toType(
            ((category?.firstOrNull()?.slug ?: "") + " " + (type ?: "") + " " + url).lowercase()
        )
        return newTvSeriesSearchResponse(title, url, typeHint) { this.posterUrl = poster }
    }

    // ── HTML card parser (fallback) ───────────────────────────────────────────
    private fun parseHtmlCard(el: Element): SearchResponse? {
        if (el.parents().any { p ->
                val c = p.className().lowercase(); val t = p.tagName().lowercase()
                t == "nav" || "nav" in c || "menu" in c || "header" in c || "footer" in c
            }) return null

        val anchor = el.selectFirst("a[href]") ?: return null
        val href   = normalizeUrl(anchor.attr("href")) ?: return null
        if (href.contains("/list/") || href == mainUrl || href == "$mainUrl/") return null

        val title = listOf(
            el.selectFirst(".film-title, .item-title, .entry-title, .halim-post-title, h3, h2, h4")?.text(),
            anchor.attr("title"),
            el.selectFirst("img")?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val badLabels = setOf("phimmoi","phim lẻ","phim bộ","phim hành động","hoạt hình","phim việt nam")
        if (title.lowercase() in badLabels) return null

        val img    = el.selectFirst("img")
        val poster = normalizeUrl(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && "placeholder" !in it }
        )
        return newTvSeriesSearchResponse(title, href, toType("$href ${el.attr("class")}")) {
            this.posterUrl = poster
        }
    }

    private fun parseHtmlPage(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        val selectors = listOf(
            ".items .item", ".list-film .item", ".film_list-wrap .flw-item",
            ".movie-list .item", ".halim_box", ".item.thumb", "article.item", "article"
        )
        for (sel in selectors) {
            val found = doc.select(sel).mapNotNull { parseHtmlCard(it) }.distinctBy { it.url }
            if (found.isNotEmpty()) return found
        }
        return doc.select("a[href]")
            .filter { a ->
                val h = a.attr("href")
                (h.contains("/phim/") || h.contains("/xem-phim/")) && !h.contains("/list/")
            }
            .mapNotNull { a ->
                val href  = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
                val title = (a.attr("title").takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: a.text().trim()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newTvSeriesSearchResponse(title, href, toType(href)) {
                    posterUrl = normalizeUrl(
                        a.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: a.selectFirst("img")?.attr("src")
                    )
                }
            }
            .distinctBy { it.url }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = "$mainUrl/${request.data}$page"
        val html = app.get(url, headers = requestHeaders(mainUrl)).text

        // 1) Next.js __NEXT_DATA__
        var items = extractNextData(html).mapNotNull { it.toSearchResponse() }
        // 2) API JSON endpoint
        if (items.isEmpty()) items = tryApiEndpoint(request.data, page).mapNotNull { it.toSearchResponse() }
        // 3) HTML fallback
        if (items.isEmpty()) items = parseHtmlPage(html)

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html    = app.get("$mainUrl/?s=$encoded", headers = requestHeaders(mainUrl)).text

        var items = extractNextData(html).mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) items = parseHtmlPage(html)
        return items
    }

    // ── Load chi tiết phim ────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val html     = app.get(fixedUrl, headers = requestHeaders(fixedUrl)).text
        val doc      = org.jsoup.Jsoup.parse(html)

        val nextJson = Regex("""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1)?.trim()

        if (!nextJson.isNullOrBlank()) {
            runCatching {
                val root  = mapper.readTree(nextJson)
                val pp    = root.path("props").path("pageProps")
                val movie = pp.path("data").takeIf { !it.isMissingNode && !it.isNull }
                    ?: pp.path("movie").takeIf { !it.isMissingNode && !it.isNull }
                    ?: pp.path("film").takeIf  { !it.isMissingNode && !it.isNull }
                    ?: return@runCatching null

                val title = movie.path("name").asText().takeIf { it.isNotBlank() }
                    ?: movie.path("title").asText().takeIf { it.isNotBlank() }
                    ?: return@runCatching null

                val poster = normalizeUrl(
                    movie.path("thumb_url").asText().takeIf { it.isNotBlank() }
                        ?: movie.path("poster_url").asText().takeIf { it.isNotBlank() }
                        ?: movie.path("thumbnail").asText().takeIf { it.isNotBlank() }
                )
                val desc  = movie.path("content").asText().takeIf { it.isNotBlank() }
                    ?: movie.path("description").asText().takeIf { it.isNotBlank() }
                val year  = movie.path("year").asInt().let { if (it > 0) it else null }
                val type  = toType(
                    (movie.path("type").asText() + " " + movie.path("category").toString() + " " + fixedUrl).lowercase()
                )

                val episodes = mutableListOf<Episode>()
                val epNodes  = movie.path("episodes").let { e ->
                    if (!e.isMissingNode) e else movie.path("servers")
                }
                if (epNodes.isArray) {
                    for (serverNode in epNodes) {
                        val serverData = serverNode.path("server_data").let { sd ->
                            if (sd.isArray) sd else serverNode.path("items")
                        }
                        if (serverData.isArray) {
                            for (ep in serverData) {
                                val epName = ep.path("name").asText().takeIf { it.isNotBlank() }
                                val epUrl  = normalizeUrl(
                                    ep.path("link_embed").asText().takeIf { it.isNotBlank() }
                                        ?: ep.path("link_m3u8").asText().takeIf { it.isNotBlank() }
                                ) ?: continue
                                episodes.add(newEpisode(epUrl) {
                                    name    = epName
                                    episode = Regex("""\d+""").find(epName ?: "")?.value?.toIntOrNull()
                                })
                            }
                        }
                    }
                }

                return when (type) {
                    TvType.Movie, TvType.AnimeMovie ->
                        newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                            this.posterUrl = poster; this.plot = desc; this.year = year
                        }
                    else -> newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                        this.posterUrl = poster; this.plot = desc; this.year = year
                    }
                }
            }.getOrNull()?.let { return it }
        }

        // Fallback HTML
        val title = doc.selectFirst(
            "h1.entry-title, h1.halim-movie-title, h1.film-title, .detail-title h1, h1"
        )?.text()?.trim() ?: return null

        val poster = normalizeUrl(
            doc.selectFirst(".halim-movie-poster img, .poster img, .thumb img, .film-poster img")
                ?.let {
                    it.attr("data-src").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("src")
                }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val desc  = doc.selectFirst(".entry-content, .halim-movie-content, .desc")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tags  = doc.select(".halim-movie-genres a, .post-category a, .genres a").map { it.text() }
        val year  = doc.selectFirst(".halim-movie-year, .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val seen  = HashSet<String>()
        val eps   = doc.select("a[href*=/xem-phim/], .episode a[href], .ep-item a[href]")
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null
                newEpisode(epUrl) {
                    name    = link.text().trim().ifBlank { null }
                    episode = Regex("""(?:tap-|ep-)(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

        val type = toType(doc.body().className() + " " + fixedUrl)
        return when (type) {
            TvType.Movie, TvType.AnimeMovie ->
                newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                    this.posterUrl = poster; this.plot = desc; this.year = year; this.tags = tags
                }
            else -> newTvSeriesLoadResponse(title, fixedUrl, type, eps) {
                this.posterUrl = poster; this.plot = desc; this.year = year; this.tags = tags
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
            val dataUrl  = normalizeUrl(data) ?: return false
            val html     = app.get(dataUrl, headers = requestHeaders(dataUrl)).text
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

            val nextJson = Regex("""<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)</script>""")
                .find(html)?.groupValues?.get(1)
            if (!nextJson.isNullOrBlank() && tryText(nextJson)) return true
            if (tryText(html)) return true

            val inlineIframe = normalizeUrl(document.selectFirst("iframe[src]")?.attr("src"))
            if (!inlineIframe.isNullOrBlank()) {
                loadExtractor(inlineIframe, dataUrl, subtitleCallback, linkCallback)
                if (hasLinks) return true
            }
            for (script in document.select("script")) {
                if (tryText(script.data() + "\n" + script.html())) return true
            }

            val bodyClass = document.selectFirst("body")?.attr("class").orEmpty()
            val postId    = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(bodyClass)?.groupValues?.get(1)
            val episode   = Regex("""tap-(\d+)""").find(dataUrl)?.groupValues?.get(1)
                ?: Regex("""episode=(\d+)""").find(dataUrl)?.groupValues?.get(1) ?: "1"
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
                for (action in listOf("halim_ajax_player", "ajax_player")) {
                    val resp = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf("action" to action, "nonce" to nonce,
                                "postid" to postId, "episode" to episode, "server" to server),
                            referer = dataUrl,
                            headers = requestHeaders(dataUrl,
                                mapOf("Origin" to mainUrl, "X-Requested-With" to "XMLHttpRequest"))
                        ).text
                    }.getOrNull()
                    if (!resp.isNullOrBlank()) return resp
                }
                return null
            }
            for (server in listOf("1","2","3","4","5")) {
                val ajaxResp = requestAjax(server) ?: continue
                if (tryText(ajaxResp)) return true
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
