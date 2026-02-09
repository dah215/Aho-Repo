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
        val document = app.get(url).document
        
        val items = document.select("article.thumb.grid-item").mapNotNull { article ->
            val link = article.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isEmpty()) return@mapNotNull null
            
            val title = article.selectFirst(".entry-title")?.text() 
                ?: link.attr("title")
            if (title.isEmpty()) return@mapNotNull null
            
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            
            val status = article.selectFirst(".status")?.text()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                status?.let { if (it.isNotEmpty()) addSub(it) }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        
        return document.select("article.thumb.grid-item").mapNotNull { article ->
            val link = article.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isEmpty()) return@mapNotNull null
            
            val title = article.selectFirst(".entry-title")?.text() 
                ?: link.attr("title")
            if (title.isEmpty()) return@mapNotNull null
            
            val img = article.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            
            val status = article.selectFirst(".status")?.text()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                status?.let { if (it.isNotEmpty()) addSub(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst(".halim-movie-title")?.text()
            ?: throw ErrorLoadingException("Title not found")

        val img = document.selectFirst(".halim-movie-poster img")
        val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodes = document.select("a[href*=/xem-phim/]")
            .filter { it.attr("href").contains("-tap-") }
            .distinctBy { it.attr("href") }
            .mapNotNull { link ->
                val epUrl = link.attr("href")
                val epText = link.text().trim()
                val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epText
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            .sortedBy { it.episode }

        val tags = document.select(".halim-movie-genres a, .post-category a")
            .map { it.text() }

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
        val document = app.get(data).document

        val postId = document.selectFirst("body")?.attr("class")
            ?.let { Regex("""postid-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("article")?.attr("id")
                ?.let { Regex("""post-(\d+)""").find(it)?.groupValues?.get(1) }
            ?: return false

        val episode = Regex("""tap-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

        val nonce = document.select("script")
            .firstOrNull { it.data().contains("ajax_player") }
            ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: document.select("script")
                .firstOrNull { it.data().contains("nonce") }
                ?.data()?.let { Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
            ?: return false

        val ajaxResponse = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "halim_ajax_player",
                "nonce" to nonce,
                "postid" to postId,
                "episode" to episode,
                "server" to "1"
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data
            )
        )

        val responseText = ajaxResponse.text

        val m3u8Url = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""")
            .find(responseText)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'<>\s]+\.m3u8""").find(responseText)?.value

        if (m3u8Url != null) {
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                mainUrl
            ).forEach(callback)
            return true
        }

        val iframeUrl = Regex("""iframe[^>]+src=["']([^"']+)["']""")
            .find(responseText)?.groupValues?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""")
                .find(responseText)?.groupValues?.get(1)

        if (iframeUrl != null) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}
