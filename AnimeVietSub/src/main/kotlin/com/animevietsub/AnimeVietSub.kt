package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
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
    // JWPlayer KHÔNG autoplay → phải inject JS gọi jwplayer().play()
    // Sau khi play → HLS.js fetch chunks từ storage.googleapiscdn.com
    // storageId = 24 hex chars (ví dụ: 6999e84f896b718999906bd7)
    // Intercept chunk đầu → build master.m3u8 URL
    //
    // JS injection: retry gọi play() mỗi 300ms trong 15s
    // (JWPlayer cần thời gian init sau khi decode hash)
    // Android WebView trong CloudStream có mediaPlaybackRequiresUserGesture=false
    // → JWPlayer/HLS.js có thể autoplay → fetch chunks tự động
    // Intercept chunk đầu tiên → extract storageId → build master.m3u8
    private val webViewInterceptor = WebViewResolver(
        Regex("""storage\.googleapiscdn\.com/chunks/[a-f0-9]{12,}/""")
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
    // Root cause đã xác nhận từ console log thực tế:
    //
    //   /ajax/all → PromiseResult: undefined (empty response) → DEAD
    //   ping.iamcdn.net → 502 Bad Gateway → DEAD
    //
    //   DU server THỰC TẾ:
    //     trang tập load → JS decode data-hash → tạo blob m3u8 (KHÔNG fetch)
    //     JWPlayer khởi tạo nhưng DỪNG (không autoplay)
    //     User click play → JWPlayer.play() → HLS.js bắt đầu
    //     HLS.js fetch: storage.googleapiscdn.com/chunks/{24hex}/original/{key}/videoN.html
    //
    //   Vấn đề của WebViewResolver trước: timeout vì JWPlayer không autoplay
    //   Fix: inject JS gọi jwplayer().play() sau khi init
    //
    //   Sau khi play() → WebViewResolver bắt chunk URL đầu tiên
    //   Extract storageId → build master.m3u8
    //   (master.m3u8 cần test: có thể tồn tại hoặc 403)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        safeApiCall {
            // WebView load trang → JS inject → jwplayer().play() →
            // HLS.js fetch chunk → interceptor bắt URL
            val chunkUrl = app.get(
                epUrl,
                headers     = headers,
                interceptor = webViewInterceptor
            ).url

            // Extract storageId từ chunk URL
            // Ví dụ: .../chunks/6999e84f896b718999906bd7/original/.../video7.html
            val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]{12,})/""")
                .find(chunkUrl)?.groupValues?.get(1)

            if (!storageId.isNullOrBlank()) {
                // master.m3u8: CloudStream sẽ tự detect nếu 200 hoặc báo lỗi nếu 403
                val m3u8 = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"
                callback(newExtractorLink(
                    source = name,
                    name   = "$name - DU",
                    url    = m3u8,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Referer"    to epUrl,
                        "User-Agent" to UA,
                        "Origin"     to mainUrl
                    )
                })
            } else if (chunkUrl.isNotBlank() && chunkUrl != epUrl
                       && !chunkUrl.startsWith("blob:")) {
                // Fallback: URL khác được intercept (mp4, m3u8 trực tiếp)
                callback(newExtractorLink(
                    source = name,
                    name   = "$name - DU",
                    url    = chunkUrl,
                    type   = if (chunkUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Referer" to epUrl, "User-Agent" to UA)
                })
            }
        }

        return true
    }
}
