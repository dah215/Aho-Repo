package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    // User-Agent hiện đại để khớp với TLS Fingerprint của trình duyệt
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
    )

    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com|streamc\.xyz|amass15\.top"""))

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
        val poster = el.selectFirst("img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        }
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
        val slug = url.substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        
        val res = app.get(apiUrl, headers = commonHeaders, interceptor = cfInterceptor).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val title = movie.name ?: ""
        val poster = movie.poster_url ?: movie.thumb_url
        val plot = movie.description
        val year = movie.category?.get("3")?.list?.firstOrNull()?.name?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            server.items?.forEach { ep ->
                val m3u8 = ep.m3u8 ?: ""
                val embed = ep.embed ?: ""
                if (m3u8.isNotBlank()) {
                    // Gộp m3u8 và embed để loadLinks xử lý
                    val combinedData = "$m3u8|$embed"
                    episodes.add(newEpisode(combinedData) {
                        this.name = "Tập ${ep.name}"
                        this.episode = ep.name?.toIntOrNull()
                    })
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
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

        // THUẬT TOÁN MỚI 1: Khởi tạo phiên (Session Warm-up)
        // Truy cập trang embed để lấy Cookie cần thiết cho việc tải file .png
        val embedRes = app.get(embedUrl, headers = commonHeaders, interceptor = cfInterceptor)
        val cookies = embedRes.cookies
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        // THUẬT TOÁN MỚI 2: Giải mã Redirect thủ công
        // Lấy link CDN thật (amass15.top) để trình phát không bị mất Header khi tự redirect
        val finalRes = app.get(
            m3u8Url, 
            headers = commonHeaders.plus("Referer" to embedUrl).plus("Cookie" to cookieString),
            interceptor = cfInterceptor,
            timeout = 15
        )
        val finalVideoUrl = finalRes.url

        // THUẬT TOÁN MỚI 3: Header Injection "Siêu cấp"
        // Ép trình phát gửi đúng Referer và Cookie cho TẤT CẢ các request tải ảnh ngụy trang (.png)
        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl,
            "Origin" to embedUrl.substringBefore("/embed.php"),
            "Cookie" to cookieString,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Range" to "bytes=0-" // Ép CDN nhả luồng dữ liệu video thay vì file ảnh tĩnh
        )

        callback(
            newExtractorLink(
                source = "NguonC (PNG-Bypass)",
                name = "HLS - 1080p",
                url = finalVideoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.headers = videoHeaders // Header này cực kỳ quan trọng để bẻ khóa file .png
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
        @JsonProperty("category") val category: Map<String, NguonCCategory>? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )
    data class NguonCCategory(
        @JsonProperty("list") val list: List<NguonCItem>? = null
    )
    data class NguonCItem(@JsonProperty("name") val name: String? = null)
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
