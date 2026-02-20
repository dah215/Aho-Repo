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

    // Bộ Header giả lập trình duyệt máy tính để tránh bị Cloudflare nghi ngờ
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com"""))

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/hoat-hinh" to "Hoạt Hình",
        "danh-sach/tv-shows" to "TV Shows"
    )

    // Thuật toán bóc tách thẻ phim từ trang chủ (Dựa trên cấu hình Table trong HTML bạn gửi)
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
            addStatus(label)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        // Tìm trong bảng danh sách phim
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
        val year = doc.select("td:contains(Năm phát hành) + td").text().toIntOrNull()

        val episodes = mutableListOf<Episode>()

        // THUẬT TOÁN THÔNG MINH: Bóc tách JSON từ thẻ <script> như trong HTML bạn gửi
        val scriptData = doc.select("script").find { it.data().contains("var episodes =") }?.data()
        if (scriptData != null) {
            try {
                val jsonStr = scriptData.substringAfter("var episodes = ").substringBefore("];") + "]"
                val servers = parseJson<List<NguonCServer>>(jsonStr)
                
                servers.forEach { server ->
                    server.list?.forEach { ep ->
                        // Ưu tiên lấy link m3u8 trực tiếp từ JSON
                        val link = ep.m3u8?.replace("\\/", "/") ?: ep.embed?.replace("\\/", "/") ?: ""
                        if (link.isNotBlank()) {
                            episodes.add(newEpisode(link) {
                                this.name = "Tập ${ep.name}"
                                this.episode = ep.name?.toIntOrNull()
                            })
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
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
        // Header quan trọng nhất để vượt lỗi 2001
        val videoHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/", // Bắt buộc phải là domain gốc
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

        if (data.contains("phimmoi.net") || data.contains(".m3u8")) {
            callback(
                ExtractorLink(
                    source = "NguonC (VIP)",
                    name = "HLS - 1080p",
                    url = data,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = true,
                    headers = videoHeaders // Truyền header vào trình phát
                )
            )
            return true
        }

        // Nếu là link embed streamc.xyz, bóc hash để tạo link m3u8
        if (data.contains("streamc.xyz")) {
            val hash = data.substringAfter("hash=").substringBefore("&")
            val finalUrl = "https://sing.phimmoi.net/$hash/hls.m3u8"
            callback(
                ExtractorLink(
                    source = "NguonC (Embed)",
                    name = "HLS - StreamC",
                    url = finalUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.P1080.value,
                    isM3u8 = true,
                    headers = videoHeaders
                )
            )
            return true
        }

        return false
    }

    // Data classes để map với JSON trong HTML
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
