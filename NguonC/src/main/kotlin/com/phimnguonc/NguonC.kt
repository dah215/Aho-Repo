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
                // CHỈ LẤY LINK EMBED, BỎ QUA LINK M3U8 ẢO
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
        val embedDomain = if (embedUrl.startsWith("http")) {
            Regex("""https?://+""").find(embedUrl)?.value ?: ""
        } else ""

        try {
            // BƯỚC 1: Tải mã nguồn trang embed để lấy Token/Hash thật
            val embedRes = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT), interceptor = cfInterceptor)
            val html = embedRes.text
            val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // BƯỚC 2: Tìm chuỗi Base64 chứa link m3u8 thật sự (Dựa trên manh mối của bạn)
            // Thường nó nằm trong một biến JS như: var player = new Player("eyJoIjoi...");
            // Hoặc file: "eyJoIjoi..."
            val base64Regex = Regex("""(eyJoIjoi+)""")
            val match = base64Regex.find(html)

            var finalM3u8Url = ""

            if (match != null) {
                // Nếu tìm thấy chuỗi Base64, ghép nó với domain embed để tạo link m3u8 thật
                val base64String = match.groupValues
                finalM3u8Url = "$embedDomain/$base64String.m3u8"
            } else {
                // Fallback: Nếu không tìm thấy Base64, thử tìm link m3u8 trực tiếp trong HTML
                val directM3u8Regex = Regex("""(https?://+\.m3u8)""")
                val directMatch = directM3u8Regex.find(html)
                if (directMatch != null) {
                    finalM3u8Url = directMatch.value.replace("\\/", "/")
                }
            }

            if (finalM3u8Url.isNotBlank()) {
                // BƯỚC 3: Xây dựng Header "Bất tử" để bẻ khóa các file .png
                // Dựa trên manh mối 2, CDN amass15.top cần Referer là trang embed
                val videoHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl, // Cực kỳ quan trọng
                    "Origin" to embedDomain,
                    "Cookie" to cookies,
                    "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8", // Đánh lừa CDN
                    "Accept-Language" to "vi-VN,vi;q=0.9",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Range" to "bytes=0-" // Ép CDN nhả luồng video
                )

                callback(
                    newExtractorLink(
                        source = "NguonC (Base64-Bypass)",
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
            e.printStackTrace()
        }

        // Fallback cuối cùng nếu mọi cách cào đều thất bại
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
