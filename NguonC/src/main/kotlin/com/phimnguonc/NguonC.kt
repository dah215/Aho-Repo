package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
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

    // 1. BỘ HEADERS CHUẨN TRÌNH DUYỆT PC ĐỂ VƯỢT CLOUDFLARE
    // Loại bỏ X-Requested-With vì dễ bị Cloudflare nhận diện là bot nếu TLS fingerprint không khớp
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.8,en-US;q=0.5,en;q=0.3",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    // 2. BỘ HEADERS DÀNH RIÊNG CHO API
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "vi-VN,vi;q=0.8,en-US;q=0.5,en;q=0.3",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // 3. SỬ DỤNG WEBVIEW RESOLVER ĐỂ TỰ ĐỘNG GIẢI MÃ CLOUDFLARE CHALLENGE
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com"""))

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
            if (epNum != null) addSub(epNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val url = "$mainUrl/api/films/${request.data}?page=$page"
            // Thêm interceptor và tăng timeout lên 30s để WebView có thời gian giải mã
            val res = app.get(url, headers = apiHeaders, interceptor = cfInterceptor, timeout = 30).parsedSafe<NguonCResponse>()
            if (res?.items != null && res.items.isNotEmpty()) {
                val items = res.items.mapNotNull { it.toSearchResponse() }
                return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val htmlUrl = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val doc = app.get(htmlUrl, headers = headers, interceptor = cfInterceptor, timeout = 30).document
        var items = doc.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
        
        if (items.isEmpty() && page > 1) {
            val url2 = "$mainUrl/${request.data}/page-$page"
            val doc2 = app.get(url2, headers = headers, interceptor = cfInterceptor, timeout = 30).document
            items = doc2.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val url = "$mainUrl/api/films/search?keyword=${URLEncoder.encode(query, "utf-8")}"
            val res = app.get(url, headers = apiHeaders, interceptor = cfInterceptor, timeout = 30).parsedSafe<NguonCResponse>()
            if (res?.items != null && res.items.isNotEmpty()) {
                return res.items.mapNotNull { it.toSearchResponse() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = headers, interceptor = cfInterceptor, timeout = 30).document
        return doc.select(".item, .movie-item, article, .halim-item, .col-md-2, .col-md-3").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        try {
            val apiUrl = "$mainUrl/api/film/$slug"
            val res = app.get(apiUrl, headers = apiHeaders, interceptor = cfInterceptor, timeout = 30).parsedSafe<NguonCDetailResponse>()?.movie
            if (res != null) {
                val title = res.name ?: ""
                val poster = res.poster_url ?: res.thumb_url
                val plot = res.description?.replace(Regex("<.*?>"), "")?.trim()
                val episodes = mutableListOf<Episode>()
                res.episodes?.forEach { server ->
                    server.items?.forEach { ep ->
                        val epName = ep.name ?: ""
                        val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                        val data = if (!ep.m3u8.isNullOrBlank()) ep.m3u8 else ep.embed ?: ""
                        if (data.isNotBlank()) {
                            episodes.add(newEpisode(data) {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val doc = app.get(url, headers = headers, interceptor = cfInterceptor, timeout = 30).document
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

        // 1. Xử lý link m3u8 trực tiếp
        if (fixedData.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    "NguonC",
                    "NguonC (HLS)",
                    fixedData,
                    null,
                    {
                        this.referer = ""
                        this.quality = Qualities.P1080.value
                    }
                )
            )
            return true
        }

        // 2. Xử lý link embed từ streamc.xyz (Giải mã thông minh)
        if (fixedData.contains("streamc.xyz/embed.php")) {
            val hash = fixedData.substringAfter("hash=").substringBefore("&")
            if (hash.isNotBlank()) {
                val m3u8Link = "https://sing.phimmoi.net/$hash/hls.m3u8"
                callback(
                    newExtractorLink(
                        "NguonC",
                        "NguonC (StreamC)",
                        m3u8Link,
                        null,
                        {
                            this.referer = "https://phim.nguonc.com/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                )
                return true
            }
        }

        // 3. Fallback: Cào link từ mã nguồn trang
        try {
            val doc = app.get(fixedData, headers = headers, interceptor = cfInterceptor, timeout = 30).document
            val html = doc.html()
            val m3u8Regex = Regex("""https?://+\.m3u8*""")
            val matches = m3u8Regex.findAll(html).map { it.value }.toList()
            var found = false
            matches.forEach { link ->
                callback(
                    newExtractorLink(
                        "NguonC",
                        "NguonC (HLS)",
                        link.replace("^http://".toRegex(), "https://"),
                        null,
                        {
                            this.referer = ""
                            this.quality = Qualities.P1080.value
                        }
                    )
                )
                found = true
            }
            return found
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = this.name ?: return null
        val href = "$mainUrl/phim/${this.slug}"
        val poster = this.thumb_url ?: this.poster_url
        val epText = this.current_episode ?: ""
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()
            if (epNum != null) addSub(epNum)
        }
    }

    data class NguonCResponse(@JsonProperty("items") val items: List<NguonCItem>? = null)
    data class NguonCItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("current_episode") val current_episode: String? = null
    )
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
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
