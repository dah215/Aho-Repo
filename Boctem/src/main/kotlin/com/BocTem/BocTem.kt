package com.BocTem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val episodeInfo = this.selectFirst(".status")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            episodeInfo?.let { addSub(it) }
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

        val poster = document.selectFirst(".halim-movie-poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodeLinks = document.select("a[href*=/xem-phim/]")
            .filter { it.attr("href").contains("-tap-") }
            .distinctBy { it.attr("href") }

        val episodes = episodeLinks.mapNotNull { link ->
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: epText.toIntOrNull()

            Episode(
                data = epUrl,
                name = epText,
                season = null,
                episode = epNum,
                posterUrl = poster,
                date = null
            )
        }.sortedBy { it.episode }

        val tags = document.select(".halim-movie-genres a, .post-category a").map { it.text() }

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
        val document = app.get(data, referer = mainUrl).document

        val postId = document.selectFirst("body")?.attr("class")
            ?.let { classAttr -> Regex("""postid-(\d+)""").find(classAttr)?.groupValues?.get(1) }
            ?: document.selectFirst("article")?.attr("id")
                ?.let { idAttr -> Regex("""post-(\d+)""").find(idAttr)?.groupValues?.get(1) }
            ?: return false

        val episode = Regex("""tap-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

        val nonce = document.select("script").firstOrNull { it.data().contains("ajax_player") }
            ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: document.select("script").firstOrNull { it.data().contains("nonce") }
                ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: return false

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

        val m3u8Url = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'<>\s]+\.m3u8""").find(responseText)?.value

        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - Server 1",
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        val iframeUrl = Regex("""iframe[^>]+src=["']([^"']+)["']""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""").find(responseText)?.groupValues?.get(1)

        if (iframeUrl != null) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}
