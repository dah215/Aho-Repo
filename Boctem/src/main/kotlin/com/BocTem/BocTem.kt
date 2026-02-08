package com.BocTem

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.loadExtractor
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url, referer = mainUrl).document
        val items = document.select("article.thumb.grid-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst(".entry-title")?.text()
            ?: this.selectFirst("a")?.attr("title")
            ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val episodeInfo = this.selectFirst(".status")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episodeInfo)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, referer = mainUrl).document
        return document.select("article.thumb.grid-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst(".halim-movie-title")?.text()
            ?: return null

        val poster = document.selectFirst(".halim-movie-poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // Get post ID from body class or scripts
        val postId = document.selectFirst("body")?.attr("class")
            ?.let { Regex("""postid-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("article")?.attr("id")
                ?.let { Regex("""post-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: return null

        // Get episodes
        val episodeLinks = document.select("a[href*=/xem-phim/]")
            .filter { it.attr("href").contains("-tap-") }
            .distinctBy { it.attr("href") }

        val episodes = episodeLinks.mapNotNull { link ->
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: epText.toIntOrNull()
                ?: 0

            Episode(
                data = epUrl,
                name = epText,
                episode = epNum,
                posterUrl = poster
            )
        }.sortedBy { it.episode }

        // Get tags/genres
        val tags = document.select(".halim-movie-genres a, .post-category a").map { it.text() }

        // Get year from URL or meta
        val year = Regex("""/release/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".halim-movie-year")?.text()?.toIntOrNull()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
        // Get the watch page
        val document = app.get(data, referer = mainUrl).document

        // Extract post ID
        val postId = document.selectFirst("body")?.attr("class")
            ?.let { Regex("""postid-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("article")?.attr("id")
                ?.let { Regex("""post-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: return false

        // Extract episode number from URL
        val episode = Regex("""tap-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

        // Get nonce from script
        val nonce = document.select("script").find { it.data().contains("ajax_player") }
            ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: document.select("script").find { it.data().contains("nonce") }
                ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: return false

        // Call AJAX to get player
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val ajaxData = mapOf(
            "action" to "halim_ajax_player",
            "nonce" to nonce,
            "postid" to postId,
            "episode" to episode,
            "server" to "1"
        )

        val ajaxResponse = app.post(
            ajaxUrl,
            data = ajaxData,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data
            ),
            referer = data
        )

        val responseText = ajaxResponse.text

        // Extract m3u8 URL from response
        val m3u8Url = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'<>\s]+\.m3u8""").find(responseText)?.value

        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$name - Server 1",
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        // Try to extract iframe URL as fallback
        val iframeUrl = Regex("""iframe[^>]+src=["']([^"']+)["']""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""").find(responseText)?.groupValues?.get(1)

        if (iframeUrl != null) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}
