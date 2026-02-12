package com.phimmoichill

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    // Bypass CloudFlare bằng WebView
    override val usesWebView = true
    override val useWebViewForLinks = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "/" to "Phim Đề Cử",
        "/list/phim-le" to "Phim Lẻ",
        "/list/phim-bo" to "Phim Bộ"
    )

    private fun String?.fixUrl(): String? {
        if (this.isNullOrBlank()) return null
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun decode(input: String): String? {
        return try {
            URLDecoder.decode(input, "UTF-8")
        } catch (e: Exception) {
            input
        }
    }

    private fun getType(url: String, label: String): TvType {
        val s = (url + " " + label).lowercase()
        return when {
            "anime" in s || "hoat-hinh" in s -> TvType.Anime
            "phim-le" in s -> TvType.Movie
            "phim-bo" in s -> TvType.TvSeries
            "chieu-rap" in s -> TvType.Movie
            label.contains(Regex("\\d+/\\d+")) -> TvType.TvSeries
            label.contains("Tập", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    // Parse movie item - dựa trên cấu trúc HTML thực tế
    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val linkEl = this.selectFirst("a[href]") ?: return null
            val href = linkEl.attr("href").fixUrl() ?: return null
            
            // Bỏ qua các link không phải trang phim
            if (!href.contains("/info/") && !href.contains("/xem-phim/")) {
                return null
            }

            // Lấy title từ <p> hoặc <h3> hoặc attribute title của <a>
            val title = this.selectFirst("p")?.text()?.trim()
                ?: this.selectFirst("h3")?.text()?.trim()
                ?: linkEl.attr("title").takeIf { it.isNotBlank() }
                ?: return null

            // Lấy poster - có thể là src hoặc data-src
            val imgEl = this.selectFirst("img")
            var poster = imgEl?.attr("src")?.fixUrl()
                ?: imgEl?.attr("data-src")?.fixUrl()
            
            // Decode poster URL nếu có parameter url=
            if (poster?.contains("url=") == true) {
                poster = decode(poster.substringAfter("url="))
            }

            // Lấy label (tập phim, chất lượng)
            val label = this.selectFirst("span.label")?.text()?.trim() ?: ""

            // Xác định type
            val type = getType(href, label)

            // Parse episode hoặc quality
            return if (label.contains(Regex("\\d+/\\d+")) || label.contains("Tập", ignoreCase = true)) {
                // Phim bộ
                val episode = Regex("(\\d+)").findAll(label).firstOrNull()?.value?.toIntOrNull()
                newAnimeSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    addSub(episode)
                }
            } else if (label.contains("Trailer", ignoreCase = true)) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                // Phim lẻ - lấy chất lượng từ label
                val quality = label.replace(Regex("(-.*)|(\\|.*)|(?i)(VietSub.*)|(?i)(Thuyết.*)|(?i)(HD.*)"), "").trim()
                newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    if (quality.isNotBlank()) addQuality(quality)
                }
            }
        } catch (e: Exception) {
            Log.e("PhimMoiChill", "Error parsing item: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (request.data == "/") {
                mainUrl
            } else {
                "$mainUrl${request.data}"
            }
            
            Log.d("PhimMoiChill", "Fetching: $url")
            
            val response = app.get(url, headers = headers, timeout = 60)
            val html = response.text
            
            Log.d("PhimMoiChill", "Response length: ${html.length}")
            
            val doc = Jsoup.parse(html)
            val items = mutableListOf<SearchResponse>()
            val seen = mutableSetOf<String>()

            // Parse từ carousel (Phim Đề Cử) - dùng selector chính xác với cấu trúc owl-carousel
            // Cấu trúc: <ul id="film-hot"><div class="owl-wrapper-outer"><div class="owl-wrapper"><div class="owl-item"><li class="item">...
            doc.select("#film-hot .item, #film-hot li.item").forEach { el ->
                val item = el.toSearchResult()
                if (item != null && seen.add(item.url)) {
                    items.add(item)
                }
            }

            // Parse từ các block list-film
            doc.select(".list-film .item, .list-film li.item").forEach { el ->
                val item = el.toSearchResult()
                if (item != null && seen.add(item.url)) {
                    items.add(item)
                }
            }

            Log.d("PhimMoiChill", "Found ${items.size} items for ${request.name}")

            // Nếu không tìm thấy item nào, thử parse tất cả li.item
            if (items.isEmpty()) {
                doc.select("li.item").forEach { el ->
                    val item = el.toSearchResult()
                    if (item != null && seen.add(item.url)) {
                        items.add(item)
                    }
                }
                Log.d("PhimMoiChill", "Fallback found ${items.size} items")
            }

            newHomePageResponse(
                HomePageList(request.name, items.take(30)),
                hasNext = false
            )
        } catch (e: Exception) {
            Log.e("PhimMoiChill", "Error loading main page", e)
            newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/tim-kiem/$encoded/"
            
            Log.d("PhimMoiChill", "Searching: $url")
            
            val html = app.get(url, headers = headers, timeout = 60).text
            val doc = Jsoup.parse(html)
            val seen = mutableSetOf<String>()
            
            doc.select(".list-film .item, li.item").mapNotNull { el ->
                el.toSearchResult()?.takeIf { seen.add(it.url) }
            }
        } catch (e: Exception) {
            Log.e("PhimMoiChill", "Search error", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val fixedUrl = url.fixUrl() ?: return null
            Log.d("PhimMoiChill", "Loading: $fixedUrl")
            
            val response = app.get(fixedUrl, headers = headers, timeout = 60)
            val baseUrl = getBaseUrl(response.url)
            val html = response.text
            val doc = Jsoup.parse(html)

            // Lấy title
            val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
                ?: return null

            // Lấy link danh sách tập
            val episodeListUrl = doc.select("ul.list-button li a, a[href*='/list-ep/']").firstOrNull()
                ?.attr("href")?.fixUrl()

            // Lấy poster
            val poster = doc.selectFirst("div.image img[itemprop=image]")?.attr("src")?.fixUrl()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.fixUrl()

            // Lấy description
            val desc = doc.selectFirst("div#film-content")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            // Lấy year
            val year = doc.select("ul.entry-meta.block-film li a[href*='/list/phim-nam-']")
                .firstOrNull()?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: doc.selectFirst("a[href*='/list/phim-nam-']")?.text()?.filter { it.isDigit() }?.toIntOrNull()

            // Lấy tags
            val tags = doc.select("ul.entry-meta.block-film li:nth-child(4) a, a[href*='/genre/']")
                .map { it.text().substringAfter("Phim") }

            // Lấy rating
            val rating = doc.select("ul.entry-meta.block-film li span, .rating-value")
                .firstOrNull()?.text()?.toRatingInt()

            // Lấy actors
            val actors = doc.select("ul.entry-meta.block-film li:last-child a, .actor-name")
                .map { it.text() }

            // Lấy trailer
            val trailer = doc.select("body script")
                .find { it.data().contains("youtube.com") }?.data()
                ?.substringAfterLast("file: \"")?.substringBefore("\",")

            // Kiểm tra phim bộ hay phim lẻ
            val isSeries = doc.select("div.latest-episode, .ep-list, #list_episodes").isNotEmpty()
                || episodeListUrl != null

            // Lấy recommendations
            val recommendations = doc.select("ul#list-film-realted li.item, .list-film li.item").mapNotNull {
                it.toSearchResult()
            }

            if (isSeries && episodeListUrl != null) {
                // Lấy danh sách tập
                val epDoc = app.get(episodeListUrl, headers = headers, timeout = 60).document
                val episodes = epDoc.select("ul#list_episodes > li, .ep-list li").mapNotNull { ep ->
                    val epUrl = ep.selectFirst("a")?.attr("href")?.fixUrl() ?: return@mapNotNull null
                    val epName = ep.selectFirst("a")?.text()?.trim() ?: ""
                    val epNum = Regex("\\d+").findAll(epName).lastOrNull()?.value?.toIntOrNull()
                    newEpisode(epUrl) {
                        name = epName.ifBlank { null }
                        episode = epNum
                    }
                }

                // Nếu không có episode list, tạo 1 episode từ URL hiện tại
                val finalEpisodes = if (episodes.isEmpty()) {
                    listOf(newEpisode(fixedUrl) { name = "Full" })
                } else {
                    episodes
                }

                newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, finalEpisodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.tags = tags
                    this.rating = rating
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                }
            } else {
                newMovieLoadResponse(title, fixedUrl, TvType.Movie, fixedUrl) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = desc
                    this.tags = tags
                    this.rating = rating
                    addActors(actors)
                    this.recommendations = recommendations
                    addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            Log.e("PhimMoiChill", "Load error", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val dataUrl = data.fixUrl() ?: return false
            Log.d("PhimMoiChill", "Loading links: $dataUrl")
            
            val response = app.get(dataUrl, headers = headers, timeout = 60)
            val baseUrl = getBaseUrl(response.url)
            val html = response.text
            val doc = Jsoup.parse(html)
            var hasLinks = false

            // Method 1: Tìm key từ script
            val key = doc.select("div#content script, script")
                .find { it.data().contains("filmInfo.episodeID") || it.data().contains("episodeID") }
                ?.data()?.let { script ->
                    val id = script.substringAfter("parseInt('").substringBefore("'")
                        .ifEmpty { script.substringAfter("episodeID = ").substringBefore(";").trim() }
                    
                    if (id.isNotBlank()) {
                        app.post(
                            url = "$baseUrl/chillsplayer.php",
                            data = mapOf("qcao" to id),
                            referer = dataUrl,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            )
                        ).text.substringAfterLast("iniPlayers(\"").substringBefore("\"")
                    } else null
                }

            // Nếu có key, tạo các link m3u8
            if (!key.isNullOrBlank()) {
                listOf(
                    Pair("https://sotrim.topphimmoi.org/raw/$key/index.m3u8", "PMFAST"),
                    Pair("https://dash.megacdn.xyz/raw/$key/index.m3u8", "PMHLS"),
                    Pair("https://so-trym.phimchill.net/dash/$key/index.m3u8", "PMPRO"),
                    Pair("https://dash.megacdn.xyz/dast/$key/index.m3u8", "PMBK")
                ).forEach { (link, source) ->
                    callback(
                        ExtractorLink(
                            source,
                            source,
                            link,
                            referer = "$baseUrl/",
                            quality = Qualities.P1080.value,
                            type = INFER_TYPE
                        )
                    )
                    hasLinks = true
                }
            }

            // Method 2: Tìm iframe
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src").fixUrl() ?: return@forEach
                if (loadExtractor(src, dataUrl, subtitleCallback, callback)) {
                    hasLinks = true
                }
            }

            // Method 3: Tìm link trực tiếp trong script
            val scripts = doc.select("script").joinToString("\n") { it.data() }
            
            // M3U8 links
            Regex("""(?:https?:)?//[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(scripts).forEach { match ->
                val url = match.value.fixUrl() ?: return@forEach
                M3u8Helper.generateM3u8(name, url, dataUrl).forEach(callback)
                hasLinks = true
            }

            // MP4/MKV/WebM links
            Regex("""(?:https?:)?//[^\s"'<>]+\.(?:mp4|mkv|webm)(?:\?[^\s"'<>]*)?""").findAll(scripts).forEach { match ->
                val url = match.value.fixUrl() ?: return@forEach
                callback(
                    newExtractorLink(name, "$name Direct", url, ExtractorLinkType.VIDEO) {
                        this.referer = dataUrl
                    }
                )
                hasLinks = true
            }

            hasLinks
        } catch (e: Exception) {
            Log.e("PhimMoiChill", "LoadLinks error", e)
            false
        }
    }
}

@CloudstreamPlugin
class PhimMoiChill : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}
