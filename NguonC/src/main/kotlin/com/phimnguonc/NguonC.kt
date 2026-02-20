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

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Giải quyết Cloudflare cho tất cả các domain liên quan
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com|.*streamc\.xyz|.*amass15\.top|.*phimmoi\.net"""))

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/hoat-hinh" to "Hoạt Hình",
        "danh-sach/tv-shows" to "TV Shows"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href")
        val title = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val label = el.selectFirst(".bg-green-300")?.text()?.trim() ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.otherName = label 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/")
        val episodes = mutableListOf<Episode>()
        var movieTitle = ""
        var moviePoster = ""
        var moviePlot = ""

        // CỐ GẮNG LẤY DỮ LIỆU TỪ 2 NGUỒN ĐỂ TRÁNH LỖI "KHÔNG TÌM THẤY LIÊN KẾT"
        try {
            val apiUrl = "$mainUrl/api/film/$slug"
            val res = app.get(apiUrl, headers = commonHeaders, timeout = 15).parsedSafe<NguonCDetailResponse>()
            res?.movie?.let { movie ->
                movieTitle = movie.name ?: ""
                moviePoster = movie.poster_url ?: movie.thumb_url ?: ""
                moviePlot = movie.description ?: ""
                movie.episodes?.forEach { server ->
                    val items = server.items ?: server.list
                    items?.forEach { ep ->
                        val m3u8 = ep.m3u8?.replace("\\/", "/") ?: ""
                        val embed = ep.embed?.replace("\\/", "/") ?: ""
                        if (m3u8.isNotBlank()) {
                            episodes.add(newEpisode("$m3u8|$embed") {
                                this.name = "Tập ${ep.name}"
                                this.episode = ep.name?.toIntOrNull()
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        if (episodes.isEmpty()) {
            val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
            movieTitle = doc.selectFirst("h1")?.text()?.trim() ?: ""
            moviePoster = doc.selectFirst("img.rounded-md")?.attr("src") ?: ""
            val scriptData = doc.select("script").find { it.data().contains("var episodes =") }?.data()
            if (scriptData != null) {
                try {
                    val jsonStr = scriptData.substringAfter("var episodes = ").substringBefore("];") + "]"
                    val servers = AppUtils.parseJson<List<NguonCServer>>(jsonStr)
                    servers.forEach { server ->
                        val items = server.list ?: server.items
                        items?.forEach { ep ->
                            val m3u8 = ep.m3u8?.replace("\\/", "/") ?: ""
                            val embed = ep.embed?.replace("\\/", "/") ?: ""
                            if (m3u8.isNotBlank()) {
                                episodes.add(newEpisode("$m3u8|$embed") {
                                    this.name = "Tập ${ep.name}"
                                    this.episode = ep.name?.toIntOrNull()
                                })
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("Dữ liệu tập phim trống, vui lòng thử lại")

        return newTvSeriesLoadResponse(movieTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = moviePoster
            this.plot = moviePlot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val m3u8Url = parts[0]
        val embedUrl = if (parts.size > 1) parts[1] else ""

        // BƯỚC 1: Giải quyết Redirect thủ công để lấy link CDN thật (amass15.top)
        // Việc này cực kỳ quan trọng để tránh lỗi 2001 do mất Header khi redirect
        val finalRes = try {
            app.get(m3u8Url, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT), timeout = 15)
        } catch (e: Exception) { null }
        
        val finalVideoUrl = finalRes?.url ?: m3u8Url
        val cookies = finalRes?.cookies?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""

        // BƯỚC 2: Xây dựng bộ Header "Bẻ khóa PNG"
        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (if (embedUrl.isNotBlank()) embedUrl else "$mainUrl/"),
            "Origin" to (if (embedUrl.isNotBlank()) embedUrl.substringBefore("/embed.php") else mainUrl),
            "Cookie" to cookies,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Range" to "bytes=0-",
            "Connection" to "keep-alive"
        )

        callback(
            newExtractorLink(
                source = "NguonC (Ultra-Bypass)",
                name = "HLS - 1080p",
                url = finalVideoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.headers = videoHeaders
            }
        )
        return true
    }

    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
