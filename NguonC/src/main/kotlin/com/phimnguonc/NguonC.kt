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
    // Lấy tất cả các cột (td) trong dòng (tr)
    val cells = el.select("td")
    if (cells.size < 3) return null

    val titleEl = cells[0].selectFirst("h3") ?: return null
    val a = cells[0].selectFirst("a") ?: return null
    val href = a.attr("href")
    val title = titleEl.text().trim()
    
    // Lấy ảnh poster từ data-src hoặc src
    val img = cells[0].selectFirst("img")
    val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }

    // Cột 2: Tình trạng (Ví dụ: "Tập 4", "Hoàn tất (15/15)", "FULL")
    val episodeStatus = cells[1].text().trim()
    
    // Cột 3: Định dạng (Ví dụ: "Phim bộ", "Phim lẻ")
    val movieType = cells[2].text().trim()

    // Xác định TvType dựa trên định dạng
    val type = if (movieType.contains("Phim lẻ", ignoreCase = true)) TvType.Movie else TvType.TvSeries

    // Tạo nhãn hiển thị (Badge) giống trong ảnh: HD | Phụ Đề | Tập ...
    // Vì web này đa số là HD và Phụ đề nên ta có thể thêm vào cho đẹp
    val quality = "HD"
    val sub = "Vietsub"
    val displayLabel = "$quality | $sub | $episodeStatus".replace("Hoàn tất", "Full")

    return if (type == TvType.TvSeries) {
        newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.otherName = displayLabel // Đây là nơi hiển thị các nhãn trên card
        }
    } else {
        newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.otherName = displayLabel
        }
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

    // Data class cho JSON từ data-obf
    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,  // Token URL
        @JsonProperty("hD") val hD: String? = null     // Hash
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = https://embed13.streamc.xyz/embed.php?hash=f71c4bddcc66969be2cd4d29e709cfa2
        val embedUrl = data
        
        // Lấy domain: https://embed13.streamc.xyz
        val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: ""

        try {
            // Tải trang embed
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

            // Tìm data-obf trong HTML
            // <div id="player" data-obf="eyJzVWIiOi...J9">
            val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)
            
            if (obfMatch != null) {
                val obfBase64 = obfMatch.groupValues[1]
                
                // Decode Base64 layer 1 → JSON {"sUb": "...", "hD": "..."}
                val jsonData = String(Base64.decode(obfBase64, Base64.DEFAULT))
                val streamData = AppUtils.parseJson<StreamData>(jsonData)
                
                // sUb chính là token cho URL m3u8
                val sUb = streamData.sUb
                if (!sUb.isNullOrBlank()) {
                    // URL m3u8: https://embed13.streamc.xyz/{sUb}.m3u8
                    val finalM3u8Url = "$embedDomain/$sUb.m3u8"
                    
                    // Headers để phát video
                    val videoHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to embedDomain,
                        "Cookie" to cookies,
                        "Accept" to "*/*",
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin"
                    )

                    callback(
                        newExtractorLink(
                            source = "NguonC Server",
                            name = "HLS",
                            url = finalM3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.headers = videoHeaders
                        }
                    )
                    return true
                }
            }

        } catch (e: Exception) {
            println("Error in loadLinks: ${e.message}")
            e.printStackTrace()
        }

        // Fallback
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
