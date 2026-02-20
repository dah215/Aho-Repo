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
        
        // Parse chất lượng (HD, Full HD, 4K...)
        val quality = el.selectFirst(".bg-green-300")?.text()?.trim() ?: ""
        
        // Parse số tập hiện tại (nếu có)
        val episodeText = el.selectFirst(".text-blue-400, .episode")?.text()?.trim() ?: ""
        
        // Parse loại phim (Phim lẻ, Phim bộ...)
        val typeText = el.selectFirst("td:nth-child(2)")?.text()?.trim() ?: ""
        
        // Xác định TVType
        val tvType = when {
            typeText.contains("phim bộ", ignoreCase = true) || typeText.contains("hoạt hình", ignoreCase = true) -> TvType.TvSeries
            typeText.contains("tv show", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.otherName = quality
            
            // Hiển thị chất lượng và số tập
            this.quality = when {
                quality.contains("4K", ignoreCase = true) -> SearchQuality._4K
                quality.contains("Full HD", ignoreCase = true) -> SearchQuality.FHD
                quality.contains("HD", ignoreCase = true) -> SearchQuality.HD
                quality.contains("SD", ignoreCase = true) -> SearchQuality.SD
                else -> null
            }
            
            // Hiển thị số tập trong tên (nếu là phim bộ)
            if (episodeText.isNotBlank()) {
                this.name = "$title - $episodeText"
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

        // Xác định loại phim
        val tvType = when {
            movie.type?.contains("phim bộ", ignoreCase = true) == true -> TvType.TvSeries
            movie.type?.contains("hoạt hình", ignoreCase = true) == true -> TvType.Anime
            movie.type?.contains("tv show", ignoreCase = true) == true -> TvType.TvSeries
            movie.category?.contains("phim bộ", ignoreCase = true) == true -> TvType.TvSeries
            movie.category?.contains("hoạt hình", ignoreCase = true) == true -> TvType.Anime
            else -> TvType.Movie
        }

        // Đếm tổng số tập
        val totalEpisodes = movie.episodes?.firstOrNull()?.items?.size 
            ?: movie.episodes?.firstOrNull()?.list?.size 
            ?: movie.episodes?.sumOf { (it.items?.size ?: 0) + (it.list?.size ?: 0) }
            ?: 0

        // Tạo danh sách tập phim với thông tin phụ đề
        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEachIndexed { serverIndex, server ->
            val serverName = server.serverName ?: "Server ${serverIndex + 1}"
            val items = server.items ?: server.list
            
            items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    val epName = ep.name ?: ""
                    val epNumber = epName.toIntOrNull()
                    
                    // Kiểm tra phụ đề từ thông tin episode
                    val subtitleInfo = ep.subtitle ?: ""
                    val hasSub = subtitleInfo.isNotBlank() || movie.lang?.isNotBlank() == true
                    
                    episodes.add(newEpisode(embed) {
                        this.name = "Tập $epName"
                        this.episode = epNumber
                        this.season = null
                        
                        // Hiển thị thông tin phụ đề trong rating (workaround)
                        this.rating = if (hasSub && subtitleInfo.isNotBlank()) {
                            "Phụ đề: $subtitleInfo"
                        } else null
                        this.description = if (hasSub) "Vietsub" else null
                    })
                }
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        // Parse chất lượng
        val quality = movie.quality ?: ""

        // Xác định chất lượng video
        val videoQuality = when {
            quality.contains("4K", ignoreCase = true) -> Qualities._4K
            quality.contains("Full HD", ignoreCase = true) || quality.contains("FHD", ignoreCase = true) -> Qualities.P1080
            quality.contains("HD", ignoreCase = true) -> Qualities.P720
            else -> Qualities.Unknown
        }

        // Tạo tên hiển thị với số tập
        val displayName = if (tvType == TvType.TvSeries && totalEpisodes > 0) {
            "${movie.name ?: ""} ($totalEpisodes tập)"
        } else {
            movie.name ?: ""
        }

        // Thông tin phụ đề
        val subtitleInfo = movie.lang ?: ""
        val hasVietsub = movie.lang?.contains("vietsub", ignoreCase = true) == true || 
                        movie.lang?.contains("phụ đề", ignoreCase = true) == true ||
                        movie.lang?.isNotBlank() == true
        val hasThuyetMinh = movie.lang?.contains("thuyết minh", ignoreCase = true) == true ||
                           movie.lang?.contains("lồng tiếng", ignoreCase = true) == true

        return if (tvType == TvType.Movie && episodes.size == 1) {
            newMovieLoadResponse(displayName, url, tvType, episodes.first().data) {
                this.posterUrl = movie.poster_url ?: movie.thumb_url
                this.plot = movie.description
                this.year = movie.year?.toIntOrNull()
                this.rating = movie.rating?.toDoubleOrNull()
                this.tags = movie.genre?.split(",")?.map { it.trim() }
                this.recommendations = arrayListOf()
                
                // Hiển thị chất lượng
                this.quality = videoQuality
            }
        } else {
            newTvSeriesLoadResponse(displayName, url, tvType, episodes) {
                this.posterUrl = movie.poster_url ?: movie.thumb_url
                this.plot = buildString {
                    append(movie.description ?: "")
                    // Thêm thông tin phụ đề vào plot
                    if (subtitleInfo.isNotBlank()) {
                        append("\n\n📍 Phụ đề: $subtitleInfo")
                    }
                    if (totalEpisodes > 0) {
                        append("\n📺 Tổng số tập: $totalEpisodes")
                    }
                    if (quality.isNotBlank()) {
                        append("\n🎬 Chất lượng: $quality")
                    }
                }
                this.year = movie.year?.toIntOrNull()
                this.rating = movie.rating?.toDoubleOrNull()
                this.tags = movie.genre?.split(",")?.map { it.trim() }
                this.recommendations = arrayListOf()
                
                // Hiển thị chất lượng
                this.quality = videoQuality
            }
        }
    }

    // Data class cho JSON từ data-obf
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
                            source = "NguonC",
                            name = "HLS - HD",
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

    // Response data classes
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    
    data class NguonCMovie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("status") val status: String? = null
    )
    
    data class NguonCServer(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )
    
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("subtitle") val subtitle: String? = null
    )
}
