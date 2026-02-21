package com.animevietsub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.EnumSet

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSubProvider()) }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl     = "https://animevietsub.be"
    override var name        = "AnimeVietSub"
    override val hasMainPage = true
    override var lang        = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val headers = mapOf(
        "User-Agent"      to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer"         to "$mainUrl/"
    )

    // ── Đã xác nhận từ console log:
    // JWPlayer load blob m3u8 → HLS.js fetch chunks từ storage.googleapiscdn.com
    // Pattern chunk: chunks/{24-hex-storageId}/original/{encodedKey}/videoN.html
    // Intercept chunk đầu tiên → extract storageId → build master.m3u8
    private val webViewInterceptor = WebViewResolver(
        Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]{24})/""")
    )

    // ── Trang chủ ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (TV/Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie/OVA)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Anime Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Anime Trọn Bộ",
        "$mainUrl/the-loai/hanh-dong/"        to "Action",
        "$mainUrl/the-loai/tinh-cam/"         to "Romance",
        "$mainUrl/the-loai/phep-thuat/"       to "Fantasy",
        "$mainUrl/the-loai/kinh-di/"          to "Horror",
        "$mainUrl/the-loai/hai-huoc/"         to "Comedy",
        "$mainUrl/the-loai/phieu-luu/"        to "Adventure",
        "$mainUrl/the-loai/shounen/"          to "Shounen",
        "$mainUrl/the-loai/sci-fi/"           to "Sci-Fi"
    )

    private fun pageUrl(base: String, page: Int) =
        if (page == 1) "${base.trimEnd('/')}/"
        else "${base.trimEnd('/')}/trang-$page.html"

    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a       = article.selectFirst("a[href]") ?: return null
        val href    = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title   = article.selectFirst("h2.Title")?.text()?.trim()
                      ?.takeIf { it.isNotBlank() } ?: return null
        val poster  = article.selectFirst("div.Image img, figure img")?.attr("src")
                      ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum  = article.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        val quality = when (article.selectFirst(".Qlty")?.text()?.uppercase()) {
            "FHD" -> SearchQuality.HD; "HD" -> SearchQuality.HD
            "CAM" -> SearchQuality.Cam; else -> SearchQuality.HD
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality   = quality
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get(pageUrl(request.data, page), headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/",
            headers = headers
        ).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = headers).document

        val title  = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster = watchDoc.selectFirst("div.Image figure img")?.attr("src")
                     ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot   = watchDoc.selectFirst("div.Description")?.text()?.trim()
        val year   = watchDoc.selectFirst("p.Info .Date a, p.Info .Date")
                     ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags   = watchDoc.select("p.Genre a")
                     .map { it.text().trim() }.filter { it.isNotBlank() }

        val seen     = mutableSetOf<String>()
        val episodes = watchDoc.select("#list-server .list-episode a.episode-link")
            .mapNotNull { a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                if (href.isBlank() || !seen.add(href)) return@mapNotNull null
                val num  = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                newEpisode(href) {
                    this.name    = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
                    this.episode = num
                }
            }.sortedBy { it.episode ?: 0 }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(
                title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html"
            ) { this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ── Load video ────────────────────────────────────────────────────────────
    //
    // Đã xác nhận từ console log thực tế:
    //
    //   /ajax/all → trả về empty (PromiseResult: undefined) → DEAD END
    //   ping.iamcdn.net → 502 Bad Gateway → DEAD END
    //
    //   DU Server flow thực tế:
    //   trang tập → JS decode data-hash → tạo blob m3u8 → JWPlayer/HLS.js
    //   → fetch chunk: storage.googleapiscdn.com/chunks/{storageId}/original/{key}/videoN.html
    //   → extract storageId → master.m3u8
    //
    //   storageId từ log: "6999e84f896b718999906bd7" (24 hex chars)
    //
    //   HDX Server: ping.iamcdn.net đang 502 → không có API để gọi
    //   → Dùng WebView load HDX page và intercept video chunks từ storage
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = episode URL (plain, không pack thêm gì vì đã bỏ /ajax/all)
        val epUrl = data.substringBefore("|")  // tương thích với format cũ nếu có

        // WebView load trang tập → JS chạy → HLS.js fetch chunk đầu tiên
        // Interceptor bắt URL chunk storage.googleapiscdn.com
        safeApiCall {
            val interceptedUrl = app.get(
                epUrl,
                headers     = headers,
                interceptor = webViewInterceptor
            ).url

            // Extract storageId từ chunk URL
            // "https://storage.googleapiscdn.com/chunks/6999e84f896b718999906bd7/original/.../videoN.html"
            val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]{24})/""")
                .find(interceptedUrl)?.groupValues?.get(1)

            if (!storageId.isNullOrBlank()) {
                // master.m3u8 chứa danh sách các quality (đã xác nhận từ các file capture trước)
                val m3u8Url = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"

                callback(newExtractorLink(
                    source = name,
                    name   = "$name - DU",
                    url    = m3u8Url,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Referer"    to epUrl,
                        "User-Agent" to UA,
                        "Origin"     to mainUrl
                    )
                })
            } else if (interceptedUrl.isNotBlank()
                       && interceptedUrl != epUrl
                       && !interceptedUrl.startsWith("blob:")) {
                // Fallback: nếu interceptor bắt được URL khác (mp4, m3u8 trực tiếp)
                val isM3u8 = interceptedUrl.contains(".m3u8")
                callback(newExtractorLink(
                    source = name,
                    name   = "$name - DU",
                    url    = interceptedUrl,
                    type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Referer" to epUrl, "User-Agent" to UA)
                })
            }
        }

        return true
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
