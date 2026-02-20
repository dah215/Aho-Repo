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

        // 1. Lấy chất lượng (HD, FHD...) -> Hiển thị góc TRÁI poster
        val qualityText = el.selectFirst(".bg-green-300, .bg-blue-300, .bg-red-300, .bg-yellow-300, .bg-violet-300")?.text()?.trim()
        val quality = getSearchQuality(qualityText)

        // 2. Lấy thông tin tập/phụ đề (VD: "Phụ đề Tập 2", "Vietsub") -> Hiển thị góc PHẢI poster
        val episodeText = el.selectFirst(".bg-gray-800, .bg-black, .bg-slate-800, .bg-gray-900")?.text()?.trim()

        val type = when {
            href.contains("/phim-le/") -> TvType.Movie
            href.contains("/hoat-hinh/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.quality = quality
            // Gán text vào posterText để hiện badge góc phải
            this.posterText = episodeText 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        // Selector table tbody tr dành cho danh sách dạng bảng, nếu trang chủ dạng grid thì cần đổi selector
        val items = doc.select("table tbody tr, .grid .relative").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        return doc.select("table tbody tr, .grid .relative").mapNotNull { parseCard(it) }
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

        val tags = mutableListOf<String>()
        movie.category?.forEach { it.name?.let { name -> tags.add(name) } }
        movie.country?.forEach { it.name?.let { name -> tags.add(name) } }

        val infoBuilder = StringBuilder()
        infoBuilder.appendLine("📺 Trạng thái: ${movie.episode_current} / ${movie.episode_total}")
        infoBuilder.appendLine("🎞️ Chất lượng: ${movie.quality}")
        infoBuilder.appendLine("🌐 Ngôn ngữ: ${movie.lang}")
        if (!movie.director.isNullOrBlank()) infoBuilder.appendLine("🎬 Đạo diễn: ${movie.director}")
        if (!movie.actor.isNullOrBlank()) infoBuilder.appendLine("🎭 Diễn viên: ${movie.actor}")
        infoBuilder.appendLine("\n📖 Nội dung:\n${movie.description ?: movie.content ?: "Đang cập nhật..."}")

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrl(movie.poster_url ?: movie.thumb_url ?: "")
            this.year = movie.year?.toIntOrNull()
            this.tags = tags
            this.plot = infoBuilder.toString()
            this.recommendations = movie.related?.mapNotNull { related ->
                newMovieSearchResponse(related.name ?: "", fixUrl(related.link ?: ""), TvType.TvSeries) {
                    this.posterUrl = fixUrl(related.poster ?: related.thumb ?: "")
                    this.quality = getSearchQuality(related.quality)
                    this.posterText = related.episode_current
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
            val embedRes = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/"), interceptor = cfInterceptor)
            val html = embedRes.text
            val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)

            if (obfMatch != null) {
                val jsonData = String(Base64.decode(obfMatch.groupValues[1], Base64.DEFAULT))
                val streamData = AppUtils.parseJson<StreamData>(jsonData)
                val sUb = streamData.sUb

                if (!sUb.isNullOrBlank()) {
                    callback(newExtractorLink("NguonC", "HLS", "$embedDomain/$sUb.m3u8", ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Referer" to embedUrl, "Origin" to embedDomain)
                    })
                    return true
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
    }

    data class StreamData(@JsonProperty("sUb") val sUb: String? = null)
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(
        val name: String?, val description: String?, val content: String?,
        val thumb_url: String?, val poster_url: String?, val quality: String?,
        val lang: String?, val year: String?, val director: String?, val actor: String?,
        val episode_current: String?, val episode_total: String?,
        val category: List<NguonCCategory>?, val country: List<NguonCCountry>?,
        val episodes: List<NguonCServer>?, val related: List<NguonCRelated>?
    )
    data class NguonCCategory(val name: String?)
    data class NguonCCountry(val name: String?)
    data class NguonCServer(val items: List<NguonCEpisode>?, val list: List<NguonCEpisode>?)
    data class NguonCEpisode(val name: String?, val embed: String?)
    data class NguonCRelated(val name: String?, val link: String?, val thumb: String?, val poster: String?, val quality: String?, val episode_current: String?)
}
