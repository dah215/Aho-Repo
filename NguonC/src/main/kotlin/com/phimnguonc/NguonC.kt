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

    // Chỉ dùng WebView cho trang chủ để vượt Cloudflare ban đầu
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com"""))

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
        val doc = app.get(url, interceptor = cfInterceptor).document
        val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, interceptor = cfInterceptor).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        // Ưu tiên dùng HTML để lấy script 'var episodes' vì nó ổn định hơn API khi bị Cloudflare quét
        val res = app.get(url, interceptor = cfInterceptor)
        val doc = res.document
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.rounded-md")?.attr("src")
        val plot = doc.selectFirst("article")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val scriptData = doc.select("script").find { it.data().contains("var episodes =") }?.data()
        
        if (scriptData != null) {
            try {
                val jsonStr = scriptData.substringAfter("var episodes = ").substringBefore("];") + "]"
                val servers = AppUtils.parseJson<List<NguonCServer>>(jsonStr)
                
                servers.forEach { server ->
                    server.list?.forEach { ep ->
                        val m3u8 = ep.m3u8?.replace("\\/", "/") ?: ""
                        val embed = ep.embed?.replace("\\/", "/") ?: ""
                        if (m3u8.isNotBlank()) {
                            // Gộp m3u8 và embed để truyền sang loadLinks
                            episodes.add(newEpisode("$m3u8|$embed") {
                                this.name = "Tập ${ep.name}"
                                this.episode = ep.name?.toIntOrNull()
                            })
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        // Nếu script thất bại, thử gọi API dự phòng
        if (episodes.isEmpty()) {
            val slug = url.substringAfterLast("/")
            val apiRes = app.get("$mainUrl/api/film/$slug").parsedSafe<NguonCDetailResponse>()
            apiRes?.movie?.episodes?.forEach { server ->
                server.items?.forEach { ep ->
                    val m3u8 = ep.m3u8 ?: ""
                    val embed = ep.embed ?: ""
                    if (m3u8.isNotBlank()) {
                        episodes.add(newEpisode("$m3u8|$embed") {
                            this.name = "Tập ${ep.name}"
                            this.episode = ep.name?.toIntOrNull()
                        })
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val parts = data.split("|")
        val m3u8Url = parts[0]
        val embedUrl = if (parts.size > 1) parts[1] else ""

        // THUẬT TOÁN MỚI: Không thực hiện request ngầm để tránh văng lỗi.
        // Thay vào đó, ép trình phát video sử dụng bộ Header "giả dạng trình duyệt đang xem ảnh"
        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (if (embedUrl.isNotBlank()) embedUrl else "$mainUrl/"),
            "Origin" to (if (embedUrl.isNotBlank()) embedUrl.substringBefore("/embed.php") else mainUrl),
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", // Chấp nhận ảnh để bẻ khóa .png
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        callback(
            newExtractorLink(
                source = "NguonC (VIP)",
                name = "HLS - 1080p",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.headers = videoHeaders
            }
        )
        return true
    }

    // Data classes hỗ trợ cả HTML Script và API JSON
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null, // Cho HTML
        @JsonProperty("items") val items: List<NguonCEpisode>? = null // Cho API
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(@JsonProperty("episodes") val episodes: List<NguonCServer>? = null)
}
