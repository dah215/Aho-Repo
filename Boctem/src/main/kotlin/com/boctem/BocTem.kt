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

    override val mainPage = mainPageOf(
        "anime-moi/page/" to "Anime Mới",
        "anime-hay/page/" to "Anime Hay",
        "anime-movie/page/" to "Anime Movie",
        "release/2025/page/" to "Anime 2025"
    )

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun parseCard(article: Element): SearchResponse? {
        val anchor = article.selectFirst("a") ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null

        val title = article.selectFirst(".entry-title")?.text()?.trim().orEmpty()
            .ifEmpty { anchor.attr("title").trim() }
            .ifEmpty { return null }

        val image = article.selectFirst("img")
        val poster = normalizeUrl(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )

        val statusText = article.selectFirst(".status")?.text().orEmpty()
        val episodeNumber = Regex("""(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(episodeNumber)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url).document

        val items = document
            .select("article.thumb.grid-item")
            .mapNotNull { parseCard(it) }

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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url).document

        return document
            .select("article.thumb.grid-item")
            .mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val document = app.get(fixedUrl).document

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
        val episodes = document
            .select("a[href*=/xem-phim/]")
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
            val document = app.get(dataUrl).document

            fun cleanStreamUrl(raw: String): String {
                return raw
                    .trim()
                    .trim('"', '\'')
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")
            }

            suspend fun tryM3u8FromText(text: String?): Boolean {
                if (text.isNullOrBlank()) return false

                val m3u8 = Regex("""(?:file|src|link|playlist)["']?\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""")
                        .find(text)
                        ?.value

                val cleanM3u8 = m3u8
                    ?.let(::cleanStreamUrl)
                    ?.let { normalizeUrl(it) ?: it }

                if (!cleanM3u8.isNullOrBlank()) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = cleanM3u8,
                        referer = dataUrl
                    ).forEach(callback)
                    return true
                }

                val directVideo = Regex("""(?:file|src|link)["']?\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|webm)(?:\?[^"']*)?)["']""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""https?://[^"'<>\s]+\.(?:mp4|mkv|webm)(?:\?[^"'<>\s]*)?""")
                        .find(text)
                        ?.value

                val cleanDirectVideo = directVideo
                    ?.let(::cleanStreamUrl)
                    ?.let { normalizeUrl(it) ?: it }

                if (!cleanDirectVideo.isNullOrBlank()) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name Direct",
                            url = cleanDirectVideo,
                            referer = dataUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                    return true
                }

                return false
            }

            if (tryM3u8FromText(document.html())) return true

            val inlineIframe = document.selectFirst("iframe[src]")?.attr("src")
            val inlineIframeUrl = normalizeUrl(inlineIframe)
            if (!inlineIframeUrl.isNullOrBlank()) {
                loadExtractor(inlineIframeUrl, dataUrl, subtitleCallback, callback)
                return true
            }

            val bodyClass = document.selectFirst("body")?.attr("class").orEmpty()
            val postId = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""")
                    .find(document.selectFirst("article")?.attr("id").orEmpty())
                    ?.groupValues
                    ?.get(1)

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

            if (!postId.isNullOrBlank() && !nonce.isNullOrBlank()) {
                val ajaxResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "halim_ajax_player",
                        "nonce" to nonce,
                        "postid" to postId,
                        "episode" to episode,
                        "server" to "1"
                    ),
                    referer = dataUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                if (tryM3u8FromText(ajaxResponse)) return true

                val iframe = Regex("""iframe[^>]+src=["']([^"']+)["']""")
                    .find(ajaxResponse)
                    ?.groupValues
                    ?.get(1)
                    ?: Regex("""(?:src|embed_url|link)["']?\s*:\s*["']([^"']+)["']""")
                        .find(ajaxResponse)
                        ?.groupValues
                        ?.get(1)

                val iframeUrl = normalizeUrl(iframe?.replace("\\/", "/"))
                if (!iframeUrl.isNullOrBlank()) {
                    loadExtractor(iframeUrl, dataUrl, subtitleCallback, callback)
                    return true
                }
            }

            val iframes = document.select("iframe[src]")
                .mapNotNull { normalizeUrl(it.attr("src")) }
                .distinct()

            var extracted = false
            for (iframe in iframes) {
                loadExtractor(iframe, dataUrl, subtitleCallback, callback)
                extracted = true
            }
            extracted
        }.getOrElse { false }
    }
}

@CloudstreamPlugin
class BocTem : Plugin() {
    override fun load() {
        registerMainAPI(BocTemProvider())
    }
}
