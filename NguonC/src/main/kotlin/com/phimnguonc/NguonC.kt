package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64

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

    // Interceptor cho toàn bộ hệ sinh thái
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
        val slug = url.trim().trimEnd('/').substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        
        val res = app.get(apiUrl, headers = commonHeaders, interceptor = cfInterceptor).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            val items = server.items ?: server.list
            items?.forEach { ep ->
                // CHỈ LẤY LINK EMBED
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    episodes.add(newEpisode(embed) {
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
        val embedUrl = data // VD: https://embed18.streamc.xyz/embed.php?hash=87ccab...
        
        // Lấy domain gốc của embed (VD: https://embed18.streamc.xyz)
        val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: ""

        try {
            // BƯỚC 1: Tải mã nguồn trang embed
            val embedRes = app.get(
                embedUrl, 
                headers = mapOf(
                    "Referer" to "$mainUrl/", 
                    "User-Agent" to USER_AGENT
                ), 
                interceptor = cfInterceptor
            )
            val html = embedRes.text
            val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // BƯỚC 2: Tìm chuỗi Base64 chứa token/hash
            // URL thực tế: https://embed18.streamc.xyz/eyJoIjoi...J9.m3u8
            // Chuỗi Base64: eyJoIjoiMTJkOTA5YWYyYjg3ZjU1ZDBjMjk1OGU2MDFmNDA1ZjAiLCJ0IjoiNjI3OGRlNTE0ODg4NzY3NTkxZWQyNGM1NDcwZTJmNzNmOTQ1M2U5ODMzOTVhZjVkNjM2MWRiNjE1OWQ1Zjc0NSJ9
            
            var finalM3u8Url = ""
            var base64Token = ""

            // Các pattern để tìm chuỗi Base64
            // Chuỗi Base64 JSON hợp lệ bắt đầu bằng "ey" (vì JSON bắt đầu bằng "{" được encode thành "ey")
            val base64Patterns = listOf(
                // Pattern 1: Tìm trong biến file (player config)
                // file: "eyJoIjoi..." hoặc file:"eyJoIjoi..."
                Regex("""file\s*[:=]\s*["']([e][yA-Za-z0-9+/=]+)["']"""),
                
                // Pattern 2: Tìm source: "ey..." hoặc source:'ey...'
                Regex("""source\s*[:=]\s*["']([e][yA-Za-z0-9+/=]+)["']"""),
                
                // Pattern 3: Tìm trong URL hoặc tham số
                // hash=ey... hoặc ?ey...
                Regex("""[?&]hash=([A-Za-z0-9+/=]+)"""),
                
                // Pattern 4: Tìm chuỗi Base64 dài (>50 chars) bắt đầu bằng "ey"
                // Đây là pattern tổng quát nhất
                Regex("""["'\s]([e][yA-Za-z0-9+/=]{50,})["'\s]"""),
                
                // Pattern 5: Tìm trong Player initialization
                // new Player("ey...") hoặc Player.init("ey...")
                Regex("""Player\s*\(\s*["']([A-Za-z0-9+/=]+)["']"""),
                
                // Pattern 6: Tìm trực tiếp chuỗi có format eyJoIjoi (JSON với key "h")
                Regex("""(eyJoIjoi[A-Za-z0-9+/=]+)""")
            )

            for (pattern in base64Patterns) {
                val match = pattern.find(html)
                if (match != null && match.groupValues.size > 1) {
                    base64Token = match.groupValues[1].trim()
                    // Kiểm tra xem có phải chuỗi Base64 hợp lệ không
                    if (base64Token.length > 30) {
                        break
                    }
                }
            }

            // Nếu tìm thấy Base64 token, tạo URL m3u8
            if (base64Token.isNotBlank()) {
                // URL format: https://embedXX.streamc.xyz/{base64Token}.m3u8
                finalM3u8Url = "$embedDomain/$base64Token.m3u8"
                
                // Log để debug
                println("Found Base64 token: $base64Token")
                println("Generated m3u8 URL: $finalM3u8Url")
            }

            // Fallback: Tìm link m3u8 trực tiếp
            if (finalM3u8Url.isBlank()) {
                val m3u8Patterns = listOf(
                    Regex("""(https?://[^\s"'<>]+\.m3u8)"""),
                    Regex("""["']([^"']*(?:streamc|amass)[^"']*\.m3u8)["']"""),
                    Regex("""["']([^"']*\.m3u8)["']""")
                )
                
                for (pattern in m3u8Patterns) {
                    val match = pattern.find(html)
                    if (match != null && match.groupValues.size > 1) {
                        finalM3u8Url = match.groupValues[1].replace("\\/", "/")
                        break
                    }
                }
            }

            if (finalM3u8Url.isNotBlank()) {
                // BƯỚC 3: Headers để phát video
                val videoHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to embedDomain,
                    "Cookie" to cookies,
                    "Accept" to "*/*",
                    "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                )

                callback(
                    newExtractorLink(
                        source = "NguonC Server",
                        name = "HLS - 1080p",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = videoHeaders
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        // Fallback cuối cùng
        return loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
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
        @JsonProperty("items") val items: List<NguonCEpisode>? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
