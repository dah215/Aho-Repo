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

    // Giải quyết Cloudflare cho toàn bộ hệ sinh thái NguonC
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com|.*streamc\.xyz|.*amass15\.top"""))

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9",
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
        // 1. Lấy slug chuẩn (xử lý cả trường hợp có dấu / ở cuối)
        val slug = url.trimEnd('/').substringAfterLast("/")
        
        // 2. Ưu tiên gọi API vì nó chứa đầy đủ link m3u8 và embed
        val apiUrl = "$mainUrl/api/film/$slug"
        val res = app.get(apiUrl, headers = commonHeaders, interceptor = cfInterceptor).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu từ API")

        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            // Xử lý cả 'items' (từ API) và 'list' (từ HTML script)
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val m3u8 = ep.m3u8?.replace("\\/", "/") ?: ""
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (m3u8.isNotBlank()) {
                    // Gộp m3u8 và embed để loadLinks xử lý bẻ khóa PNG
                    episodes.add(newEpisode("$m3u8|$embed") {
                        this.name = "Tập ${ep.name}"
                        this.episode = ep.name?.toIntOrNull()
                    })
                }
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot = movie.description
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

        // Lấy domain của server embed (VD: https://embed18.streamc.xyz)
        val embedDomain = if (embedUrl.startsWith("http")) {
            Regex("""https?://[^/]+""").find(embedUrl)?.value ?: ""
        } else ""

        // THUẬT TOÁN BẺ KHÓA PNG: Bộ Header giả dạng trình duyệt đang xem ảnh
        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (if (embedUrl.isNotBlank()) embedUrl else "$mainUrl/"),
            "Origin" to embedDomain,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Range" to "bytes=0-", // Ép CDN nhả luồng dữ liệu thay vì file tĩnh
            "Connection" to "keep-alive"
        )

        callback(
            newExtractorLink(
                source = "NguonC (Deep-Bypass)",
                name = "HLS - 1080p",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.headers = videoHeaders // Header này sẽ bẻ khóa các phân đoạn .png
            }
        )
        return true
    }

    // Data classes cực kỳ linh hoạt để không bao giờ bị "Dữ liệu trống"
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
