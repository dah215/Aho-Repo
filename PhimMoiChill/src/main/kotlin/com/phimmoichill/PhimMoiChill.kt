package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PhimMoiChill : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // API endpoint từ phân tích
    private val apiUrl = "https://api.phimmoi.mx"

    companion object {
        private const val TAG = "PhimMoiChill"

        fun decodeBase64(str: String): String {
            return try {
                java.util.Base64.getDecoder().decode(str).toString(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                str
            }
        }
    }

    // ==================== Homepage ====================
    override val mainPage = mainPageOf(
        "$mainUrl/list/phim-le" to "Phim Lẻ",
        "$mainUrl/list/phim-bo" to "Phim Bộ",
        "$mainUrl/list/phim-hot" to "Phim Hot",
        "$mainUrl/list/phim-chieu-rap" to "Phim Chiếu Rạp",
        "$mainUrl/genre/phim-hanh-dong" to "Phim Hành Động",
        "$mainUrl/genre/phim-tinh-cam" to "Phim Tình Cảm",
        "$mainUrl/genre/phim-hoat-hinh" to "Phim Hoạt Hình",
        "$mainUrl/genre/phim-anime" to "Phim Anime",
        "$mainUrl/country/phim-han-quoc" to "Phim Hàn Quốc",
        "$mainUrl/country/phim-trung-quoc" to "Phim Trung Quốc",
        "$mainUrl/country/phim-nhat-ban" to "Phim Nhật Bản",
        "$mainUrl/country/phim-au-my" to "Phim Âu Mỹ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("ul.list-movie li.movie-item").mapNotNull { item ->
            newItem(item)
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items, true),
            hasNext = items.isNotEmpty()
        )
    }

    private fun newItem(item: Element): SearchResponse? {
        val link = item.selectFirst("a.movie-link") ?: return null
        val href = link.attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }
        val title = link.attr("title") ?: link.text()
        val poster = item.selectFirst("img")?.attr("data-src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        // Xác định type dựa trên class
        val isSeries = item.hasClass("movie-series") ||
                       item.select(".movie-status").text().contains("Tập", ignoreCase = true)
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ==================== Search ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/"
        val document = app.get(url, mapOf("keyword" to query)).document

        return document.select("ul.list-movie li.movie-item").mapNotNull { item ->
            newItem(item)
        }
    }

    // ==================== Load Details ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Lấy thông tin cơ bản
        val title = document.selectFirst("h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.split("|")?.firstOrNull()?.trim()
            ?: return null

        val poster = document.selectFirst(".movie-poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.selectFirst(".movie-description")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val year = document.selectFirst(".movie-info span:contains(Năm) + span")?.text()?.toIntOrNull()

        // Lấy thể loại
        val genres = document.select(".movie-info span:contains(Thể loại) + span a, .movie-genre a").map {
            it.text()
        }

        // Lấy quốc gia
        val country = document.select(".movie-info span:contains(Quốc gia) + span a, .movie-country a").map {
            it.text()
        }.firstOrNull()

        // Lấy actors
        val actors = document.select(".movie-info span:contains(Diễn viên) + span, .movie-actor").text()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

        // Lấy directors
        val directors = document.select(".movie-info span:contains(Đạo diễn) + span, .movie-director").text()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }

        // Xác định type
        val isSeries = document.select(".episode-list").isNotEmpty() ||
                       document.select(".movie-status").text().contains("Tập", ignoreCase = true)
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        // Lấy danh sách tập
        val episodes = if (isSeries) {
            document.select(".episode-list a, .server-episode a, .list-episode a").map { ep ->
                val epName = ep.text().trim()
                val epUrl = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                val epNum = epName.filter { it.isDigit() }.toIntOrNull()
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            }.takeIf { it.isNotEmpty() }
                // Backup: tạo episode list từ select box nếu không có list a
                ?: document.select("select[name=episode] option").mapIndexed { idx, opt ->
                    val epName = opt.text().trim()
                    val epUrl = opt.attr("value").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = idx + 1
                    }
                }
        } else {
            null
        }

        // Lấy link xem ngay (cho phim lẻ)
        val watchUrl = document.selectFirst("a.btn-watch, a[href*=/watch-], a[href*=/xem-]")?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        return if (isSeries && episodes != null) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.rating = null
                this.actors = actors
                this.recommendations = null
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.rating = null
                this.actors = actors
            }
        }
    }

    // ==================== Load Links ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Method 1: Tìm link trực tiếp trong HTML (m3u8, mp4)
        val scripts = document.select("script").map { it.html() }
        for (script in scripts) {
            // Tìm m3u8 links
            val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            m3u8Regex.findAll(script).forEach { match ->
                val m3u8Url = match.value
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server M3U8",
                        url = m3u8Url
                    ) {
                        this.quality = Qualities.Unknown
                        this.isM3u8 = true
                        this.referer = mainUrl
                    }
                )
            }

            // Tìm mp4 links
            val mp4Regex = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""")
            mp4Regex.findAll(script).forEach { match ->
                val mp4Url = match.value
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Server MP4",
                        url = mp4Url
                    ) {
                        this.quality = Qualities.Unknown
                        this.isM3u8 = false
                        this.referer = mainUrl
                    }
                )
            }
        }

        // Method 2: Tìm iframe embeds
        val iframes = document.select("iframe[src*='embed'], iframe[src*='player'], iframe[data-src*='embed']")
        for (iframe in iframes) {
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        // Method 3: Tìm data-source hoặc data-embed
        val dataSources = document.select("[data-source], [data-embed], [data-link]")
        for (element in dataSources) {
            val src = element.attr("data-source").ifEmpty {
                element.attr("data-embed").ifEmpty {
                    element.attr("data-link")
                }
            }
            if (src.isNotBlank()) {
                // Decode nếu là base64
                val decodedUrl = try {
                    if (src.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
                        decodeBase64(src)
                    } else src
                } catch (e: Exception) {
                    src
                }

                if (decodedUrl.startsWith("http")) {
                    loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        // Method 4: Tìm trong player config
        val playerConfig = document.select("#player, .player, #embed-player, .embed-player")
        for (player in playerConfig) {
            val configUrl = player.attr("data-config")
                ?: player.attr("data-url")
                ?: player.attr("data-source")

            if (!configUrl.isNullOrBlank()) {
                loadExtractor(configUrl, mainUrl, subtitleCallback, callback)
            }
        }

        // Method 5: Parse JSON config trong script
        val jsonConfigRegex = Regex("""(?:sources|file|url)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (script in scripts) {
            jsonConfigRegex.findAll(script).forEach { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http")) {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
