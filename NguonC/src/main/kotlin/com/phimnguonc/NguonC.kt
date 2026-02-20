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
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document

        // ---------- Lấy thông tin cơ bản ----------
        val title = doc.selectFirst("h1.uppercase.text-lg.font-bold")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Không tìm thấy tên phim")

        val poster = doc.selectFirst("div.relative.w-full.h-full img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.trim()

        val plot = doc.selectFirst("article")?.text()?.trim()

        // ---------- Parse bảng thông tin ----------
        var statusText = ""
        var episodeCountText = ""
        var durationText = ""
        var qualityText = ""
        var languageText = ""
        var directorText = ""
        var castText = ""
        var listTypeText = ""
        var genreText = ""
        var yearText = ""
        var countryText = ""
        var ratingText = ""  // không có trong html, nhưng để trống

        val rows = doc.select("table tbody tr")
        for (row in rows) {
            val tds = row.select("td")
            if (tds.size < 2) continue
            val key = tds[0].text().trim()
            val value = tds[1].text().trim()
            when (key) {
                "Trạng thái" -> statusText = value
                "Số tập" -> episodeCountText = value
                "Thời Lượng" -> durationText = value
                "Chất Lượng" -> qualityText = value
                "Ngôn Ngữ" -> languageText = value
                "Đạo Diễn" -> directorText = value
                "Diễn Viên" -> castText = value
                "Danh sách" -> listTypeText = value
                "Thể loại" -> genreText = value
                "Năm phát hành" -> yearText = value
                "Quốc gia" -> countryText = value
            }
        }

        // Xác định loại phim
        val tvType = when {
            listTypeText.contains("Phim lẻ", ignoreCase = true) -> TvType.Movie
            listTypeText.contains("Phim bộ", ignoreCase = true) -> TvType.TvSeries
            else -> {
                // Nếu không có, dựa vào số tập
                val episodeCount = episodeCountText.toIntOrNull()
                if (episodeCount != null && episodeCount > 1) TvType.TvSeries else TvType.Movie
            }
        }

        // ---------- Lấy danh sách tập từ biến episodes trong script ----------
        val episodesJson = doc.select("script:containsData(var episodes)").firstOrNull()?.data()
            ?.let { script ->
                Regex("""var episodes\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
            } ?: throw ErrorLoadingException("Không tìm thấy dữ liệu tập phim")

        val episodeServers = AppUtils.parseJson<List<EpisodeServer>>(episodesJson)
        val episodeList = episodeServers.flatMap { it.list ?: emptyList() }

        if (episodeList.isEmpty()) throw ErrorLoadingException("Danh sách tập rỗng")

        val episodes = episodeList.map { ep ->
            newEpisode(ep.embed ?: "") {
                this.name = "Tập ${ep.name}"
                this.episode = ep.name?.toIntOrNull()
                // Có thể thêm m3u8 nếu cần, nhưng loadLinks sẽ xử lý embed
            }
        }

        // ---------- Xử lý các trường cho LoadResponse ----------
        val year = yearText.toIntOrNull()
        val duration = durationText.replace(Regex("[^0-9]"), "").toIntOrNull()
        val tags = genreText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val cast = castText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val director = directorText.takeIf { it.isNotBlank() }
        val language = languageText.takeIf { it.isNotBlank() }
        val quality = qualityText.takeIf { it.isNotBlank() }
        val country = countryText.takeIf { it.isNotBlank() }
        val status = when {
            statusText.contains("hoàn tất", ignoreCase = true) -> Status.COMPLETED
            statusText.contains("đang chiếu", ignoreCase = true) -> Status.ONGOING
            else -> null
        }

        // Trả về đúng loại phim
        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.cast = cast
                this.director = director
                this.duration = duration
                this.quality = quality
                this.language = language
                this.country = country
                this.rating = ratingText.toFloatOrNull()
                // Đối với phim lẻ, chỉ có một tập, ta có thể truyền data là link embed của tập đầu
                // Nhưng LoadResponse của movie không chứa episodes, nên loadLinks sẽ nhận data = url (ở trên)
                // và trong loadLinks ta xử lý từ url đó (embed). Tuy nhiên, để đồng nhất, ta vẫn giữ data = url
                // và trong loadLinks ta sẽ parse url để lấy embed? Không, vì data lúc này là url trang phim, không phải embed.
                // Cần điều chỉnh: với movie, ta nên set data là link embed của tập duy nhất.
                // Ta có thể lấy embed đầu tiên từ episodeList.
                val firstEmbed = episodeList.firstOrNull()?.embed
                if (firstEmbed != null) {
                    // Ghi đè data bằng embed
                    this.data = firstEmbed
                }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.cast = cast
                this.director = director
                this.duration = duration
                this.quality = quality
                this.language = language
                this.country = country
                this.rating = ratingText.toFloatOrNull()
                this.status = status
            }
        }
    }

    // Data class cho episodes từ script
    data class EpisodeServer(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("list") val list: List<EpisodeData>? = null
    )
    data class EpisodeData(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )

    // Data class cho stream data từ data-obf
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
        // data có thể là embedUrl (từ episode) hoặc url trang phim (trong trường hợp movie)
        // Nếu data là trang phim, ta cần lấy embed từ episode đầu tiên? Nhưng trong load movie, ta đã set data = firstEmbed.
        // Vậy data luôn là embedUrl.
        val embedUrl = data

        val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: return false

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

            // Tìm data-obf
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
}
