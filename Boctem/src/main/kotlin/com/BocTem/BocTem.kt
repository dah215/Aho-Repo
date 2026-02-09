package com.BocTem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
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

    private fun getDocument(url: String): Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .referrer(mainUrl)
            .get()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = getDocument(url)
        val items = ArrayList<SearchResponse>()
        
        for (article in document.select("article.thumb.grid-item")) {
            val result = articleToSearchResponse(article)
            if (result != null) {
                items.add(result)
            }
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
        val linkElement = article.selectFirst("a")
        if (linkElement == null) return null
        
        val href = linkElement.attr("href")
        if (href.isEmpty()) return null
        
        val titleElement = article.selectFirst(".entry-title")
        val title = if (titleElement != null) {
            titleElement.text()
        } else {
            linkElement.attr("title")
        }
        if (title.isEmpty()) return null
        
        val imgElement = article.selectFirst("img")
        val posterUrl = if (imgElement != null) {
            val dataSrc = imgElement.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else imgElement.attr("src")
        } else null
        
        val statusElement = article.selectFirst(".status")
        val episodeInfo = if (statusElement != null) statusElement.text() else null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (episodeInfo != null && episodeInfo.isNotEmpty()) {
                // Lọc lấy các ký tự số từ chuỗi và chuyển thành Int
                val epNum = episodeInfo.filter { it.isDigit() }.toIntOrNull()
                addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = getDocument(url)
        val results = ArrayList<SearchResponse>()
        
        for (article in document.select("article.thumb.grid-item")) {
            val result = articleToSearchResponse(article)
            if (result != null) {
                results.add(result)
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)

        val titleElement = document.selectFirst("h1.entry-title")
            ?: document.selectFirst(".halim-movie-title")
        if (titleElement == null) return null
        val title = titleElement.text()

        val posterImg = document.selectFirst(".halim-movie-poster img")
        val poster = if (posterImg != null) {
            val dataSrc = posterImg.attr("data-src")
            if (dataSrc.isNotEmpty()) dataSrc else posterImg.attr("src")
        } else {
            val metaImg = document.selectFirst("meta[property=og:image]")
            if (metaImg != null) metaImg.attr("content") else null
        }

        val descElement = document.selectFirst(".entry-content")
        val description = if (descElement != null) {
            descElement.text()
        } else {
            val metaDesc = document.selectFirst("meta[property=og:description]")
            if (metaDesc != null) metaDesc.attr("content") else null
        }

        val allLinks = document.select("a[href*=/xem-phim/]")
        val episodeLinks = ArrayList<Element>()
        val seenUrls = HashSet<String>()
        
        for (link in allLinks) {
            val linkHref = link.attr("href")
            if (linkHref.contains("-tap-") && !seenUrls.contains(linkHref)) {
                episodeLinks.add(link)
                seenUrls.add(linkHref)
            }
        }

        val episodes = ArrayList<Episode>()
        for (link in episodeLinks) {
            val epUrl = link.attr("href")
            val epText = link.text().trim()
            
            val tapMatch = Regex("""tap-(\d+)""").find(epUrl)
            val epNum = if (tapMatch != null) {
                tapMatch.groupValues[1].toIntOrNull()
            } else null

            val episode = newEpisode(epUrl) {
                this.name = epText
                this.episode = epNum
                this.posterUrl = poster
            }
            episodes.add(episode)
        }
        
        episodes.sortBy { it.episode ?: 0 }

        val tagElements = document.select(".halim-movie-genres a, .post-category a")
        val tags = ArrayList<String>()
        for (tagElement in tagElements) {
            tags.add(tagElement.text())
        }

        val yearMatch = Regex("""/release/(\d+)/""").find(url)
        val year = if (yearMatch != null) {
            yearMatch.groupValues[1].toIntOrNull()
        } else {
            val yearElement = document.selectFirst(".halim-movie-year")
            if (yearElement != null) yearElement.text().toIntOrNull() else null
        }

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
        val document = getDocument(data)

        val bodyElement = document.selectFirst("body")
        val bodyClass = if (bodyElement != null) bodyElement.attr("class") else ""
        val postIdMatch = Regex("""postid-(\d+)""").find(bodyClass)
        val postId = if (postIdMatch != null) {
            postIdMatch.groupValues[1]
        } else {
            val articleElement = document.selectFirst("article")
            val articleId = if (articleElement != null) articleElement.attr("id") else ""
            val articleMatch = Regex("""post-(\d+)""").find(articleId)
            if (articleMatch != null) articleMatch.groupValues[1] else return false
        }

        val episodeMatch = Regex("""tap-(\d+)""").find(data)
        val episode = if (episodeMatch != null) episodeMatch.groupValues[1] else "1"

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

        // Make AJAX request using Jsoup
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val ajaxResponse = Jsoup.connect(ajaxUrl)
            .userAgent("Mozilla/5.0")
            .referrer(data)
            .header("X-Requested-With", "XMLHttpRequest")
            .data("action", "halim_ajax_player")
            .data("nonce", nonce)
            .data("postid", postId)
            .data("episode", episode)
            .data("server", "1")
            .ignoreContentType(true)
            .post()

        val responseText = ajaxResponse.body().text()

        val m3u8Match = Regex("""file["']?\s*:\s*["']([^"']+\.m3u8)["']""").find(responseText)
        val m3u8Url = if (m3u8Match != null) {
            m3u8Match.groupValues[1]
        } else {
            Regex("""https?://[^"'<>\s]+\.m3u8""").find(responseText)?.value
        }

        if (m3u8Url != null) {
            val link = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = mainUrl
            ).firstOrNull()
            
            if (link != null) {
                callback.invoke(link)
                return true
            }
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
