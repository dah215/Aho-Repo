package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimNguonCProvider())
    }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "PhimNguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "max-age=0",
        "Sec-Ch-Ua" to "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/hoat-hinh" to "Hoạt Hình",
        "danh-sach/tv-shows" to "TV Shows"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url == "#" || url.startsWith("javascript")) return null
        return when {
            url.startsWith("http://") -> url.replace("http://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun imgUrl(el: Element?): String? {
        if (el == null) return null
        return listOf("data-src", "data-original", "data-lazy", "src")
            .mapNotNull { el.attr(it).takeIf { it.isNotBlank() && !it.startsWith("data:image") } }
            .firstOrNull()?.let { fixUrl(it) }
    }

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        if (!href.contains("/phim/")) return null
        
        val title = a.attr("title").ifBlank { el.selectFirst("h2, h3, .title, .name")?.text() }?.trim() ?: return null
        val poster = imgUrl(el.selectFirst("img"))
        
        val label = el.selectFirst(".status, .episode, .label, .ribbon, .quality, .ep-status")?.text()?.trim() ?: ""
        
        val isSeries = label.contains("Tập", true) || href.contains("phim-bo") || Regex("""\d+/\d+""").containsMatchIn(label)
        
        return newAnimeSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            
            val epNum = Regex("""\d+""").find(label)?.value?.toIntOrNull()
            if (epNum != null) {
                addSub(epNum)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val url = "$mainUrl/api/films/${request.data}?page=$page"
            val res = app.get(url, headers = headers, timeout = 15).parsedSafe<NguonCResponse>()
            if (res?.items != null && res.items.isNotEmpty()) {
                val items = res.items.mapNotNull { it.toSearchResponse() }
                return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
        } catch (e: Exception) {}

        val htmlUrl = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}/page/$page"
        }
        
        val doc = app.get(htmlUrl, headers = headers).document
        var items = doc.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
        
        if (items.isEmpty() && page > 1) {
            val url2 = "$mainUrl/${request.data}/page-$page"
            val doc2 = app.get(url2, headers = headers).document
            items = doc2.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val url = "$mainUrl/api/films/search?keyword=${URLEncoder.encode(query, "utf-8")}"
            val res = app.get(url, headers = headers).parsedSafe<NguonCResponse>()
            if (res?.items != null && res.items.isNotEmpty()) {
                return res.items.mapNotNull { it.toSearchResponse() }
            }
        } catch (e: Exception) {}

        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = headers).document
        return doc.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        
        try {
            val apiUrl = "$mainUrl/api/film/$slug"
            val res = app.get(apiUrl, headers = headers).parsedSafe<NguonCDetailResponse>()?.movie
            if (res != null) {
                val title = res.name ?: ""
                val poster = res.poster_url ?: res.thumb_url
                val plot = res.description?.replace(Regex("<.*?>"), "")?.trim()
                
                val episodes = mutableListOf<Episode>()
                res.episodes?.forEach { server ->
                    server.items?.forEach { ep ->
                        val epName = ep.name ?: ""
                        val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                        
                        val link = if (!ep.m3u8.isNullOrBlank()) ep.m3u8 else ep.embed ?: ""
                        
                        if (link.isNotBlank()) {
                            episodes.add(newEpisode(link) {
                                this.name = epName
                                this.episode = epNum
                            })
                        }
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = res.year
                }
            }
        } catch (e: Exception) {}

        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1, .title, .name")?.text()?.trim() ?: "Phim"
        val poster = imgUrl(doc.selectFirst(".film-poster img, .movie-thumb img, .poster img, img"))
        val plot = doc.selectFirst(".film-content, .description, .plot, #info-film")?.text()?.trim()
        
        val episodes = mutableListOf<Episode>()
        doc.select(".episodes a, .server-item a, .list-episode a, .halim-list-eps a").forEach { ep ->
            val epHref = fixUrl(ep.attr("href")) ?: return@forEach
            val epName = ep.text().trim()
            val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            
            episodes.add(newEpisode(epHref) {
                this.name = epName
                this.episode = epNum
            })
        }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = data.replace("^http://".toRegex(), "https://")

        if (fixedData.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    "NguonC",
                    "NguonC (HLS)",
                    fixedData,
                    "",
                    Qualities.P1080.value,
                    isM3u8 = true
                )
            )
            callback(
                newExtractorLink(
                    "NguonC",
                    "NguonC (Alt)",
                    fixedData,
                    "$mainUrl/",
                    Qualities.P1080.value,
                    isM3u8 = true
                )
            )
            return true
        }

        val doc = app.get(fixedData, headers = headers).document
        val html = doc.html()
        
        val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        val matches = m3u8Regex.findAll(html).map { it.value }.toList()
        
        var found = false
        matches.forEach { link ->
            val safeLink = link.replace("^http://".toRegex(), "https://")
            callback(
                newExtractorLink(
                    "NguonC",
                    "NguonC (HLS)",
                    safeLink,
                    "",
                    Qualities.P1080.value,
                    isM3u8 = true
                )
            )
            found = true
        }
        
        if (!found) {
            val iframe = doc.selectFirst("iframe")?.attr("src")
            if (iframe != null && iframe.startsWith("http")) {
                val safeIframe = iframe.replace("^http://".toRegex(), "https://")
                val iframeHtml = app.get(safeIframe, headers = headers).text
                val iframeMatches = m3u8Regex.findAll(iframeHtml).map { it.value }.toList()
                iframeMatches.forEach { link ->
                    val safeLink = link.replace("^http://".toRegex(), "https://")
                    callback(
                        newExtractorLink(
                            "NguonC",
                            "NguonC (HLS)",
                            safeLink,
                            "",
                            Qualities.P1080.value,
                            isM3u8 = true
                        )
                    )
                    found = true
                }
            }
        }
        
        return found
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = this.name ?: return null
        val href = "$mainUrl/phim/${this.slug}"
        val poster = this.thumb_url ?: this.poster_url
        val epText = this.current_episode ?: ""

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
            if (epNum != null) {
                addSub(epNum)
            }
        }
    }

    data class NguonCResponse(
        @JsonProperty("items") val items: List<NguonCItem>? = null
    )

    data class NguonCItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("current_episode") val current_episode: String? = null
    )

    data class NguonCDetailResponse(
        @JsonProperty("movie") val movie: NguonCMovie? = null
    )

    data class NguonCMovie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )

    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null
    )

    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("embed") val embed: String? = null
    )
}
