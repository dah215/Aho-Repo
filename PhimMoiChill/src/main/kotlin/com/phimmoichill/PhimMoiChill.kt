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

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
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
            "phim-le" in lower || "movie" in lower -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    private fun parseCard(article: Element): SearchResponse? {
        val anchor = article.selectFirst("a[href]") ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null

        val title = article.selectFirst(".entry-title, .halim-post-title, .film-title")?.text()?.trim()
            ?: anchor.attr("title").trim().ifEmpty { null }
            ?: return null

        val image = article.selectFirst("img")
        val poster = normalizeUrl(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )

        val typeText = article.attr("class") + " " + article.text()
        val type = toType(typeText)

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document

        val items = document.select("article, .item, .movie-item, .halim-item").mapNotNull { parseCard(it) }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", headers = requestHeaders(mainUrl)).document
        return document.select("article, .item, .movie-item, .halim-item").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val document = app.get(fixedUrl, headers = requestHeaders(fixedUrl)).document

        val title = document.selectFirst("h1.entry-title, h1.halim-movie-title, .detail-title")?.text()?.trim() ?: return null

        val poster = normalizeUrl(
            document.selectFirst(".halim-movie-poster img, .poster img, .thumb img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".halim-movie-poster img, .poster img, .thumb img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".entry-content, .halim-movie-content, .desc")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select(".halim-movie-genres a, .post-category a, .genres a").map { it.text().trim() }
        val year = document.selectFirst(".halim-movie-year, .year")?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val seen = HashSet<String>()
        val episodes = document.select("a[href*=/xem-phim/], .episode a[href], .halim-episode a[href]")
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null

                val epName = link.text().trim().ifBlank { null }
                val epNum = Regex("""(?:tap-|episode-|ep-)(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val type = toType(document.body().className() + " " + fixedUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }

            else -> newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                this.posterUrl = poster
                this.plot = description
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
        return runCatching {
            val dataUrl = normalizeUrl(data) ?: return false
            val document = app.get(dataUrl, headers = requestHeaders(dataUrl)).document

            var hasLinks = false
            val linkCallback: (ExtractorLink) -> Unit = {
                hasLinks = true
                callback(it)
            }

            fun extractUrls(text: String?): List<String> {
                if (text.isNullOrBlank()) return emptyList()

                val byKey = Regex("""(?:src|file|link|embed_url|iframe|player|url)["']?\s*[:=]\s*["']((?:https?:)?//[^"'<>\s]+)["']""")
                    .findAll(text)
                    .map { it.groupValues[1] }

                val byRaw = Regex("""(?:https?:)?//[^"'<>\s]+(?:m3u8|mp4|embed|player|stream|video|watch)[^"'<>\s]*""")
                    .findAll(text)
                    .map { it.value }

                return (byKey + byRaw)
                    .map { cleanStreamUrl(it) }
                    .mapNotNull { normalizeUrl(it) }
                    .distinct()
                    .toList()
            }

            suspend fun tryText(text: String?): Boolean {
                if (text.isNullOrBlank()) return false

                val m3u8Patterns = listOf(
                    Regex("""(?:file|src|link|playlist)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""),
                    Regex("""(?:https?:)?//[^"'<>\s]+\.m3u8[^"'<>\s]*"""),
                    Regex("""(?:master|hls|stream)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                )

                val m3u8 = m3u8Patterns.firstNotNullOfOrNull { pattern ->
                    val match = pattern.find(text) ?: return@firstNotNullOfOrNull null
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

                    if (hasLinks) return true

                    linkCallback(
                        newExtractorLink(
                            source = name,
                            name = "$name M3U8",
                            url = cleanM3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = dataUrl
                            this.headers = requestHeaders(dataUrl)
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return hasLinks
                }

                val direct = Regex("""(?:file|src|link)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|webm)(?:\?[^"']*)?)["']""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv|webm)(?:\?[^"'<>\s]*)?""")
                        .find(text)
                        ?.value

                val cleanDirect = direct?.let(::cleanStreamUrl)?.let { normalizeUrl(it) ?: it }
                if (!cleanDirect.isNullOrBlank()) {
                    linkCallback(
                        newExtractorLink(
                            source = name,
                            name = "$name Direct",
                            url = cleanDirect,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = dataUrl
                            this.headers = requestHeaders(dataUrl)
                            this.quality = Qualities.Unknown.value
                        }
                    )
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
                    nonce = Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']""")
                        .find(content)
                        ?.groupValues
                        ?.get(1)
                    if (!nonce.isNullOrBlank()) break
                }
            }

            suspend fun requestAjax(server: String): String? {
                if (postId.isNullOrBlank() || nonce.isNullOrBlank()) return null

                val payloads = listOf(
                    mapOf(
                        "action" to "halim_ajax_player",
                        "nonce" to nonce,
                        "postid" to postId,
                        "episode" to episode,
                        "server" to server
                    ),
                    mapOf(
                        "action" to "ajax_player",
                        "nonce" to nonce,
                        "postid" to postId,
                        "episode" to episode,
                        "server" to server
                    )
                )

                for (payload in payloads) {
                    val response = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = payload,
                            referer = dataUrl,
                            headers = requestHeaders(
                                dataUrl,
                                mapOf(
                                    "Origin" to mainUrl,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            )
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
