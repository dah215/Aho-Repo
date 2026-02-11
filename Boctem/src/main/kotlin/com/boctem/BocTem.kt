package com.boctem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class BocTem : MainAPI() {
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
        "release/2025/page/" to "Anime 2025",
    )

    private fun toAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url).document

        val items = document.select("article.thumb.grid-item").mapNotNull { article ->
            articleToSearchResponse(article)
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false,
            ),
            hasNext = items.isNotEmpty(),
        )
    }

    private fun articleToSearchResponse(article: Element): SearchResponse? {
        val linkElement = article.selectFirst("a") ?: return null
        val href = toAbsoluteUrl(linkElement.attr("href")) ?: return null

        val title = article.selectFirst(".entry-title")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val imgElement = article.selectFirst("img")
        val posterUrl = toAbsoluteUrl(
            imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")
        )

        val episodeInfo = article.selectFirst(".status")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (!episodeInfo.isNullOrEmpty()) {
                addSub(Regex("""(\d+)""").find(episodeInfo)?.groupValues?.get(1)?.toIntOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document

        return document.select("article.thumb.grid-item").mapNotNull { article ->
            articleToSearchResponse(article)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = toAbsoluteUrl(url) ?: return null
        val document = app.get(fixedUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst(".halim-movie-title")?.text()
            ?: return null

        val poster = toAbsoluteUrl(
            document.selectFirst(".halim-movie-poster img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".halim-movie-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val seenUrls = HashSet<String>()
        val episodes = document.select("a[href*=/xem-phim/]").mapNotNull { link ->
            val epUrl = toAbsoluteUrl(link.attr("href")) ?: return@mapNotNull null
            if (!epUrl.contains("-tap-") || !seenUrls.add(epUrl)) return@mapNotNull null

            val epText = link.text().trim().ifBlank { null }
            val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

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
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return runCatching {
            val dataUrl = toAbsoluteUrl(data) ?: return false
            val document = app.get(dataUrl).document

            val bodyClass = document.selectFirst("body")?.attr("class") ?: ""
            val postId = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""").find(bodyClass)?.groupValues?.get(1)
                ?: Regex("""post-(\d+)""")
                    .find(document.selectFirst("article")?.attr("id") ?: "")
                    ?.groupValues
                    ?.get(1)
                ?: return false

            val episode = Regex("""tap-(\d+)""").find(dataUrl)?.groupValues?.get(1)
                ?: Regex("""episode=(\d+)""").find(dataUrl)?.groupValues?.get(1)
                ?: "1"

            var nonce: String? = null
            for (script in document.select("script")) {
                val scriptContent = script.data() + "\n" + script.html()
                if (scriptContent.contains("ajax_player") || scriptContent.contains("nonce")) {
                    nonce = Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']""")
                        .find(scriptContent)
                        ?.groupValues
                        ?.get(1)
                    if (!nonce.isNullOrEmpty()) break
                }
            }
            if (nonce.isNullOrEmpty()) return false

            val ajaxResponse = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "halim_ajax_player",
                    "nonce" to nonce,
                    "postid" to postId,
                    "episode" to episode,
                    "server" to "1",
                ),
                referer = dataUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            ).text

            val m3u8Url = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(ajaxResponse)
                ?.groupValues
                ?.get(1)
                ?: Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").find(ajaxResponse)?.value

            if (!m3u8Url.isNullOrEmpty()) {
                val safeM3u8Url = m3u8Url.replace("\\/", "/")
                M3u8Helper.generateM3u8(name, safeM3u8Url, mainUrl).forEach(callback)
                return true
            }

            val iframeUrl = Regex("""iframe[^>]+src=["']([^"']+)["']""")
                .find(ajaxResponse)
                ?.groupValues
                ?.get(1)
                ?: Regex("""(?:src|embed_url|link)["']?\s*:\s*["']([^"']+)["']""")
                    .find(ajaxResponse)
                    ?.groupValues
                    ?.get(1)
                ?: document.selectFirst("iframe[src]")?.attr("src")

            val safeIframeUrl = toAbsoluteUrl(iframeUrl?.replace("\\/", "/"))
            if (!safeIframeUrl.isNullOrEmpty()) {
                loadExtractor(safeIframeUrl, dataUrl, subtitleCallback, callback)
                return true
            }

            false
        }.getOrElse { false }
    }
}
