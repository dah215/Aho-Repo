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

    // Chuyển đổi string quality sang SearchQuality enum
    // Các giá trị có thể: Cam, CamRip, HdCam, Telesync, WorkPrint, 
    // SD, HD, FHD (FullHD), UHD (UltraHD), BlueRay, DVD
    private fun getSearchQuality(quality: String?): SearchQuality? {
        return when (quality?.uppercase()?.replace(" ", "")?.replace("-", "")) {
            "4K", "UHD", "ULTRAHD" -> SearchQuality.UHD
            "FHD", "FULLHD", "FULL HD" -> SearchQuality.FHD
            "HD", "HDTV" -> SearchQuality.HD
            "SD" -> SearchQuality.SD
            "CAM", "CAMRIP" -> SearchQuality.Cam
            "HDCAM" -> SearchQuality.HdCam
            "TELESYNC", "TS" -> SearchQuality.Telesync
            "WORKPRINT", "WP" -> SearchQuality.WorkPrint
            "BLURAY", "BLU-RAY", "BD" -> SearchQuality.BlueRay
            "DVD" -> SearchQuality.DVD
            else -> null
        }
    }

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { 
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }

        // Lấy chất lượng từ badge (HD, FHD, 4K...)
        val qualityText = el.selectFirst(".bg-green-300, .bg-blue-300, .bg-red-300, .bg-yellow-300, .bg-violet-300")?.text()?.trim() ?: ""
        val quality = getSearchQuality(qualityText)

        // Lấy số tập hiện tại/tổng số tập
        val episodeText = el.selectFirst(".bg-gray-800, .bg-black, .bg-slate-800, .bg-gray-900")?.text()?.trim() ?: ""
        val episodeNum = episodeText.replace(Regex("[^0-9/]"), "")

        // Xác định loại phim
        val type = when {
            href.contains("/phim-le/") -> TvType.Movie
            href.contains("/hoat-hinh/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.quality = quality
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
        var totalEpisodes = 0

        movie.episodes?.forEach { server ->
            val items = server.items ?: server.list
            totalEpisodes = items?.size ?: 0
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

        // Xác định trạng thái và thông tin tập
        val status = movie.status ?: ""
        val episodeCurrent = movie.episode_current ?: ""
        val episodeTotal = movie.episode_total ?: totalEpisodes.toString()

        val statusText = when {
            status.contains("Hoàn tất", ignoreCase = true) -> "Hoàn tất"
            status.contains("Đang chiếu", ignoreCase = true) -> "Đang chiếu"
            episodeCurrent.contains("Hoàn tất", ignoreCase = true) -> "Hoàn tất"
            else -> "Đang cập nhật"
        }

        val episodeInfo = if (statusText == "Hoàn tất") {
            "Hoàn tất ($episodeTotal/$episodeTotal)"
        } else {
            "$episodeCurrent/$episodeTotal"
        }

        // Tạo danh sách tags từ thể loại và quốc gia
        val tags = mutableListOf<String>()
        movie.category?.forEach { it.name?.let { name -> tags.add(name) } }
        movie.country?.forEach { it.name?.let { name -> tags.add(name) } }

        // Parse duration từ string (vd: "35 phút/tập" -> 35)
        val durationMin = movie.time?.let { timeStr ->
            Regex("""(\d+)""").find(timeStr)?.groupValues?.get(1)?.toIntOrNull()
        }

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrl(movie.poster_url ?: movie.thumb_url ?: "")
            this.year = movie.year?.toIntOrNull()
            this.tags = tags
            this.duration = durationMin

            // Tạo plot với đầy đủ thông tin
            val infoBuilder = StringBuilder()
            infoBuilder.appendLine("📺 Trạng thái: $statusText")
            infoBuilder.appendLine("🎬 Số tập: $episodeInfo")
            infoBuilder.appendLine("🎞️ Chất lượng: ${movie.quality ?: "HD"}")
            infoBuilder.appendLine("🌐 Ngôn ngữ: ${movie.lang ?: "Vietsub"}")
            if (!movie.time.isNullOrBlank()) infoBuilder.appendLine("⏱️ Thời lượng: ${movie.time}")
            if (!movie.director.isNullOrBlank()) infoBuilder.appendLine("🎬 Đạo diễn: ${movie.director}")
            if (!movie.actor.isNullOrBlank()) infoBuilder.appendLine("🎭 Diễn viên: ${movie.actor}")
            if (!movie.year.isNullOrBlank()) infoBuilder.appendLine("📅 Năm: ${movie.year}")
            infoBuilder.appendLine()
            infoBuilder.appendLine("📖 Nội dung:")
            infoBuilder.appendLine(movie.description ?: movie.content ?: "Không có mô tả")

            this.plot = infoBuilder.toString()

            // Phim liên quan
            this.recommendations = movie.related?.mapNotNull { related ->
                val relatedHref = related.link ?: return@mapNotNull null
                val relatedTitle = related.name ?: return@mapNotNull null
                val relatedPoster = related.poster ?: related.thumb
                val relatedQuality = getSearchQuality(related.quality)
                newMovieSearchResponse(relatedTitle, fixUrl(relatedHref), TvType.TvSeries) {
                    this.posterUrl = fixUrl(relatedPoster ?: "")
                    this.quality = relatedQuality
                }
            }
        }
    }

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

    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,
        @JsonProperty("hD") val hD: String? = null
    )

    data class NguonCDetailResponse(
        @JsonProperty("movie") val movie: NguonCMovie? = null,
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("msg") val msg: String? = null
    )

    data class NguonCMovie(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("origin_name") val origin_name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("content") val content: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("trailer_url") val trailer_url: String? = null,
        @JsonProperty("time") val time: String? = null,
        @JsonProperty("episode_current") val episode_current: String? = null,
        @JsonProperty("episode_total") val episode_total: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("director") val director: String? = null,
        @JsonProperty("actor") val actor: String? = null,
        @JsonProperty("category") val category: List<NguonCCategory>? = null,
        @JsonProperty("country") val country: List<NguonCCountry>? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null,
        @JsonProperty("related") val related: List<NguonCRelated>? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("view") val view: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class NguonCCategory(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    data class NguonCCountry(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null,
        @JsonProperty("list") val list: List<NguonCEpisode>? = null
    )

    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null,
        @JsonProperty("link_embed") val link_embed: String? = null,
        @JsonProperty("link_m3u8") val link_m3u8: String? = null
    )

    data class NguonCRelated(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("episode_current") val episode_current: String? = null
    )
}
