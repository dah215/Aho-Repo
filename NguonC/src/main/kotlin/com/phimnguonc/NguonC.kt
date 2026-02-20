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

        // Lấy thể loại
        val genres = movie.category?.get("2")?.list?.mapNotNull { it.name } ?: emptyList()
        
        // Lấy năm
        val year = movie.category?.get("3")?.list?.firstOrNull()?.name?.toIntOrNull()
        
        // Lấy quốc gia
        val country = movie.category?.get("4")?.list?.firstOrNull()?.name ?: ""

        // Xác định loại phim
        val isMovie = movie.category?.get("1")?.list?.any { it.name == "Phim lẻ" } == true
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        // Tạo danh sách tập phim
        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            val serverName = server.server_name ?: "Server"
            server.items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    episodes.add(newEpisode(embed) {
                        this.name = "Tập ${ep.name}"
                        this.episode = ep.name?.toIntOrNull()
                        this.season = null
                        this.data = embed
                    })
                }
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        // Tạo tên hiển thị với thông tin bổ sung
        val displayName = buildString {
            append(movie.name ?: "")
            if (!movie.original_name.isNullOrBlank()) {
                append(" - ${movie.original_name}")
            }
        }

        // Tạo thông tin bổ sung cho rating text
        val ratingText = buildString {
            if (!movie.quality.isNullOrBlank()) {
                append(movie.quality)
            }
            if (!movie.language.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append(movie.language)
            }
            if (!movie.time.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append(movie.time)
            }
        }

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(movie.name ?: "", url, tvType, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = movie.poster_url ?: movie.thumb_url
                this.plot = movie.description
                this.year = year
                this.tags = genres
                this.rating = ratingText
                this.duration = movie.time
                this.recommendations = ArrayList()
            }
        } else {
            newTvSeriesLoadResponse(movie.name ?: "", url, tvType, episodes) {
                this.posterUrl = movie.poster_url ?: movie.thumb_url
                this.plot = movie.description
                this.year = year
                this.tags = genres
                this.rating = ratingText
                this.duration = movie.time
                this.recommendations = ArrayList()
            }
        }
    }

    // Data class cho StreamData từ data-obf
    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,
        @JsonProperty("hD") val hD: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = data
        
        val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: ""

        try {
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
            val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)
            
            if (obfMatch != null) {
                val obfBase64 = obfMatch.groupValues[1]
                
                // Decode Base64 → JSON
                val jsonData = String(Base64.decode(obfBase64, Base64.DEFAULT))
                val streamData = AppUtils.parseJson<StreamData>(jsonData)
                
                val sUb = streamData.sUb
                if (!sUb.isNullOrBlank()) {
                    val finalM3u8Url = "$embedDomain/$sUb.m3u8"
                    
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

        return loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
    }

    // ==================== DATA CLASSES ====================
    
    data class NguonCDetailResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("movie") val movie: NguonCMovie? = null
    )
    
    data class NguonCMovie(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("original_name") val original_name: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("total_episodes") val total_episodes: Int? = null,
        @JsonProperty("current_episode") val current_episode: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("director") val director: String? = null,
        @JsonProperty("casts") val casts: String? = null,
        @JsonProperty("category") val category: Map<String, NguonCCategoryGroup>? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )
    
    data class NguonCCategoryGroup(
        @JsonProperty("group") val group: NguonCCategoryInfo? = null,
        @JsonProperty("list") val list: List<NguonCCategoryInfo>? = null
    )
    
    data class NguonCCategoryInfo(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )
    
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null
    )
    
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
