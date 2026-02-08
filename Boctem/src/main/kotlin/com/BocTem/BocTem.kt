package com.BocTem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
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
        val response = app.get(url, referer = mainUrl)
        val document = response.document
        val items = document.select("article.thumb.grid-item").mapNotNull { 
            toSearchResult(it)
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        if (href.isEmpty()) return null
        
        val title = element.selectFirst(".entry-title")?.text()
            ?: linkElement.attr("title")
        if (title.isEmpty()) return null
        
        val imgElement = element.selectFirst("img")
        val posterUrl = if (imgElement != null) {
            val dataSrc = imgElement.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else imgElement.attr("src")
        } else null
        
        val episodeInfo = element.selectFirst(".status")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeInfo != null && episodeInfo.isNotEmpty()) {
                addSub(episodeInfo)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url, referer = mainUrl)
        val document = response.document
        return document.select("article.thumb.grid-item").mapNotNull { 
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, referer = mainUrl)
        val document = response.document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst(".halim-movie-title")?.text()
            ?: return null

        val posterImg = document.selectFirst(".halim-movie-poster img")
        val poster = if (posterImg != null) {
            val dataSrc = posterImg.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else posterImg.attr("src")
        } else {
            document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val allLinks = document.select("a[href*=/xem-phim/]")
        val episodeLinks = allLinks.filter { link ->
            val href = link.attr("href")
            href.contains("-tap-")
        }.distinctBy { link ->
            link.attr("href")
        }

        val episodes = episodeLinks.mapNotNull { link ->
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            val epNumMatch = Regex("""tap-(\d+)""").find(epUrl)
            val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
        }.sortedBy { ep -> ep.episode ?: 0 }

        val tagElements = document.select(".halim-movie-genres a, .post-category a")
        val tags = tagElements.map { element ->
            element.text()
        }

        val yearMatch = Regex("""/release/(\d+)/""").find(url)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
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
        val response = app.get(data, referer = mainUrl)
        val document = response.document

        val bodyElement = document.selectFirst("body")
        val bodyClass = bodyElement?.attr("class") ?: ""
        val postIdMatch = Regex("""postid-(\d+)""").find(bodyClass)
        val postId = if (postIdMatch != null) {
            postIdMatch.groupValues[1]
        } else {
            val articleElement = document.selectFirst("article")
            val articleId = articleElement?.attr("id") ?: ""
            val articleMatch = Regex("""post-(\d+)""").find(articleId)
            articleMatch?.groupValues?.get(1) ?: return false
        }

        val episodeMatch = Regex("""tap-(\d+)""").find(data)
        val episode = episodeMatch?.groupValues?.get(1) ?: "1"

        val scripts = document.select("script")
        var nonce: String? = null
        
        for (script in scripts) {
            val scriptContent = script.data()
            if (scriptContent.contains("ajax_player")) {
                val nonceMatch = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
                if (nonceMatch != null) {
                    nonce = nonceMatch.groupValues[1]
                    break
                }
            }
        }
        
        if (nonce == null) {
            for (script in scripts) {
                val scriptContent = script.data()
                if (scriptContent.contains("nonce")) {
                    val nonceMatch = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
                    if (nonceMatch != null) {
                        nonce = nonceMatch.groupValues[1]
                        break
                    }
                }
            }
        }
        
        if (nonce == null) return false

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

        val m3u8Match = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""").find(responseText)
        val m3u8Url = if (m3u8Match != null) {
            m3u8Match.groupValues[1]
        } else {
            Regex("""https?://[^"'<>\s]+\.m3u8""").find(responseText)?.value
        }

        if (m3u8Url != null) {
            callback.invoke(
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = mainUrl
                ).first()
            )
            return true
        }

        val iframeMatch = Regex("""iframe[^>]+src=["']([^"']+)["']""").find(responseText)
        val iframeUrl = if (iframeMatch != null) {
            iframeMatch.groupValues[1]
        } else {
            Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""").find(responseText)?.groupValues?.get(1)
        }

        if (iframeUrl != null) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}
