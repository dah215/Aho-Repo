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

    // User-Agent phải đồng nhất tuyệt đối để giữ Session
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
        val res = app.get(url, headers = commonHeaders, interceptor = cfInterceptor)
        val doc = res.document
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img.rounded-md")?.attr("src")
        val plot = doc.selectFirst("article")?.text()?.trim()
        val year = doc.select("td:contains(Năm phát hành) + td").text().trim().toIntOrNull()

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
                            // Gộp m3u8 và embed để loadLinks xử lý
                            val combinedData = "$m3u8|$embed"
                            episodes.add(newEpisode(combinedData) {
                                this.name = "Tập ${ep.name}"
                                this.episode = ep.name?.toIntOrNull()
                            })
                        }
                    }
                }
            } catch (e: Exception) { }
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

        // KỸ THUẬT MỚI 1: Lấy Cookie từ trang Embed để duy trì Session
        val embedRes = app.get(embedUrl, headers = commonHeaders, interceptor = cfInterceptor)
        val cookies = embedRes.cookies
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        // KỸ THUẬT MỚI 2: Tự giải mã Redirect và lấy link CDN cuối cùng
        val finalRes = app.get(
            m3u8Url, 
            headers = commonHeaders.plus("Referer" to embedUrl).plus("Cookie" to cookieString),
            interceptor = cfInterceptor
        )
        val finalVideoUrl = finalRes.url

        // KỸ THUẬT MỚI 3: Header "Siêu giả lập" để tải file .png
        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl,
            "Origin" to embedUrl.substringBefore("/embed.php"),
            "Cookie" to cookieString, // Ép trình phát gửi kèm Cookie
            "Accept" to "*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Range" to "bytes=0-" // Cần thiết để CDN nhả luồng video
        )

        callback(
            newExtractorLink(
                source = "NguonC (PNG-Fix)",
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

    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
