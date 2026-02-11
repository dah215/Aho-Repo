package com.boctem

import com.lagradost.cloudstream3.*
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
        "release/2025/page/" to "Anime 2025",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url).document

        val items = document.select("article.thumb.grid-item").mapNotNull { article ->
            articleToSearchResponse(article)
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false,
            ),
            hasNext = items.isNotEmpty(),
        )
    }

    private fun articleToSearchResponse(article: Element): SearchResponse? {
        val linkElement = article.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        if (href.isEmpty()) return null

        val titleElement = article.selectFirst(".entry-title")
        val title = titleElement?.text() ?: linkElement.attr("title")
        if (title.isEmpty()) return null

        val imgElement = article.selectFirst("img")
        val posterUrl = if (imgElement != null) {
            val dataSrc = imgElement.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else imgElement.attr("src")
        } else {
            null
        }

        val statusElement = article.selectFirst(".status")
        val episodeInfo = statusElement?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (!episodeInfo.isNullOrEmpty()) {
                val epNum = episodeInfo.filter { it.isDigit() }.toIntOrNull()
                addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        val document = app.get(url).document

        return document.select("article.thumb.grid-item").mapNotNull { article ->
            articleToSearchResponse(article)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.entry-title")
            ?: document.selectFirst(".halim-movie-title")
            ?: return null
        val title = titleElement.text()

        val posterImg = document.selectFirst(".halim-movie-poster img")
        val poster = if (posterImg != null) {
            val dataSrc = posterImg.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else posterImg.attr("src")
        } else {
            document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val description = document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodeLinks = ArrayList<Element>()
        val seenUrls = HashSet<String>()

        document.select("a[href*=/xem-phim/]").forEach { link ->
            val linkHref = link.attr("href")
            if (linkHref.contains("-tap-") && !seenUrls.contains(linkHref)) {
                episodeLinks.add(link)
                seenUrls.add(linkHref)
            }
        }

        val episodes = episodeLinks.map { link ->
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        val sortedEpisodes = episodes.sortedBy { it.episode ?: 0 }
        val tags = document.select(".halim-movie-genres a, .post-category a").map { it.text() }

        val year = Regex("""/release/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".halim-movie-year")?.text()?.toIntOrNull()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, sortedEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        val bodyClass = document.selectFirst("body")?.attr("class") ?: ""
        val postIdFromBody = Regex("""postid-(\d+)""").find(bodyClass)?.groupValues?.get(1)
        val postIdFromArticle = Regex("""post-(\d+)""")
            .find(document.selectFirst("article")?.attr("id") ?: "")
            ?.groupValues
            ?.get(1)
        val postId = postIdFromBody ?: postIdFromArticle ?: return false

        val episode = Regex("""tap-(\d+)""").find(data)?.groupValues?.get(1) ?: "1"

        var nonce: String? = null
        for (script in document.select("script")) {
            val scriptContent = script.data()
            if (scriptContent.contains("ajax_player") || scriptContent.contains("nonce")) {
                nonce = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""")
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
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ).text

        val m3u8Url = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""")
            .find(ajaxResponse)
            ?.groupValues
            ?.get(1)
            ?: Regex("""https?://[^"'<>\s]+\.m3u8""").find(ajaxResponse)?.value

        if (!m3u8Url.isNullOrEmpty()) {
            val safeM3u8Url = m3u8Url.replace("\\/", "/")
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = safeM3u8Url,
                referer = mainUrl,
            ).forEach(callback)
            return true
        }

        val iframeUrl = Regex("""iframe[^>]+src=["']([^"']+)["']""")
            .find(ajaxResponse)
            ?.groupValues
            ?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""")
                .find(ajaxResponse)
                ?.groupValues
                ?.get(1)

        if (!iframeUrl.isNullOrEmpty()) {
            val safeIframeUrl = iframeUrl.replace("\\/", "/")
            loadExtractor(safeIframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}

class BocTem : Plugin() {
    override fun load() {
        registerMainAPI(BocTemProvider())
    }
}
