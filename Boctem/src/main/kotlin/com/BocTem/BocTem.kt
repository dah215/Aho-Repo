package com.boctem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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
        
        // SỬA: Thêm ": Element" để định rõ kiểu dữ liệu
        val items = document.select("article.thumb.grid-item").mapNotNull { article: Element ->
            articleToSearchResponse(article)
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
        } else null
        
        val statusElement = article.selectFirst(".status")
        val episodeInfo = statusElement?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeInfo != null && episodeInfo.isNotEmpty()) {
                val epNum = episodeInfo.filter { it.isDigit() }.toIntOrNull()
                addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        
        // SỬA: Thêm ": Element"
        return document.select("article.thumb.grid-item").mapNotNull { article: Element ->
            articleToSearchResponse(article)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1.entry-title")
            ?: document.selectFirst(".halim-movie-title")
        if (titleElement == null) return null
        val title = titleElement.text()

        val posterImg = document.selectFirst(".halim-movie-poster img")
        val poster = if (posterImg != null) {
            val dataSrc = posterImg.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else posterImg.attr("src")
        } else {
            document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        val descElement = document.selectFirst(".entry-content")
        val description = descElement?.text() ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val episodeLinks = ArrayList<Element>()
        val seenUrls = HashSet<String>()
        
        // SỬA: Thêm ": Element"
        document.select("a[href*=/xem-phim/]").forEach { link: Element ->
            val linkHref = link.attr("href")
            if (linkHref.contains("-tap-") && !seenUrls.contains(linkHref)) {
                episodeLinks.add(link)
                seenUrls.add(linkHref)
            }
        }

        // SỬA: Thêm ": Element"
        val episodes = episodeLinks.map { link: Element ->
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            
            val tapMatch = Regex("""tap-(\d+)""").find(epUrl)
            val epNum = tapMatch?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epUrl) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
        }.toMutableList()
        
        episodes.sortBy { it.episode ?: 0 }

        val tagElements = document.select(".halim-movie-genres a, .post-category a")
        val tags = tagElements.map { it.text() }

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
        val document = app.get(data).document

        val bodyElement = document.selectFirst("body")
        val bodyClass = bodyElement?.attr("class") ?: ""
        val postIdMatch = Regex("""postid-(\d+)""").find(bodyClass)
        
        val postId = if (postIdMatch != null) {
            postIdMatch.groupValues[1]
        } else {
            val articleId = document.selectFirst("article")?.attr("id") ?: ""
            Regex("""post-(\d+)""").find(articleId)?.groupValues?.get(1) ?: return false
        }

        val episodeMatch = Regex("""tap-(\d+)""").find(data)
        val episode = episodeMatch?.groupValues?.get(1) ?: "1"

        val scripts = document.select("script")
        var nonce: String? = null
        
        // SỬA: Thêm ": Element"
        scripts.forEach { script: Element ->
            val scriptContent = script.data()
            if (scriptContent.contains("ajax_player") || scriptContent.contains("nonce")) {
                val nonceMatch = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(scriptContent)
                if (nonceMatch != null) {
                    nonce = nonceMatch.groupValues[1]
                    return@forEach
                }
            }
        }
        
        if (nonce == null) return false

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        
        val formData = mapOf(
            "action" to "halim_ajax_player",
            "nonce" to nonce,
            "postid" to postId,
            "episode" to episode,
            "server" to "1"
        )

        val ajaxResponse = app.post(
            ajaxUrl,
            data = formData,
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val m3u8Match = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""").find(ajaxResponse)
        val m3u8Url = m3u8Match?.groupValues?.get(1) 
            ?: Regex("""https?://[^"'<>\s]+\.m3u8""").find(ajaxResponse)?.value

        if (m3u8Url != null) {
            val safeM3u8Url = m3u8Url.replace("\\/", "/")
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = safeM3u8Url,
                referer = mainUrl
            ).forEach(callback)
            return true
        }

        val iframeMatch = Regex("""iframe[^>]+src=["']([^"']+)["']""").find(ajaxResponse)
        val iframeUrl = iframeMatch?.groupValues?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+embed[^"']*)["']""").find(ajaxResponse)?.groupValues?.get(1)

        if (iframeUrl != null) {
            val safeIframeUrl = iframeUrl.replace("\\/", "/")
            loadExtractor(safeIframeUrl, data, subtitleCallback, callback)
            return true
        }

        return false
    }
}
