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
    override fun load() {
        registerMainAPI(AnimeVietSubProvider())
    }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl        = "https://animevietsub.be"
    override var name           = "AnimeVietSub"
    override val hasMainPage    = true
    override var lang           = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

    // Bypass Cloudflare trên tất cả request HTML
    private val cfInterceptor = WebViewResolver(
        Regex("""animevietsub\.be""")
    )

    // Intercept m3u8 / video stream trên trang xem
    private val videoInterceptor = WebViewResolver(
        Regex("""\.m3u8|/stream|/hls|/video|cdn\d*\.[a-z.]+/(hls|stream|video|play)""")
    )

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Referer"         to "$mainUrl/"
    )

    // ── Trang chủ ─────────────────────────────────────────────────────────────
    // URL mẫu: /anime-moi/trang-2.html → dùng PREFIX "PAGED::" để phân biệt
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                     to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                      to "Anime Bộ (TV/Series)",
        "$mainUrl/anime-le/"                      to "Anime Lẻ (Movie/OVA)",
        "$mainUrl/hoat-hinh-trung-quoc/"          to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/list-dang-chieu/"     to "Anime Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"        to "Anime Trọn Bộ",
        "$mainUrl/the-loai/hanh-dong/"            to "Action",
        "$mainUrl/the-loai/tinh-cam/"             to "Romance",
        "$mainUrl/the-loai/phep-thuat/"           to "Fantasy",
        "$mainUrl/the-loai/kinh-di/"              to "Horror",
        "$mainUrl/the-loai/hai-huoc/"             to "Comedy",
        "$mainUrl/the-loai/phieu-luu/"            to "Adventure",
        "$mainUrl/the-loai/shounen/"              to "Shounen",
        "$mainUrl/the-loai/sci-fi/"               to "Sci-Fi"
    )

    // ── Tạo URL phân trang ────────────────────────────────────────────────────
    // Trang 1: /anime-moi/  → /anime-moi/trang-1.html (hoặc không cần suffix)
    // Trang N: /anime-moi/trang-N.html
    private fun buildPageUrl(baseUrl: String, page: Int): String {
        val base = baseUrl.trimEnd('/')
        return if (page == 1) "$base/" else "$base/trang-$page.html"
    }

    // ── Parse card từ danh sách ───────────────────────────────────────────────
    // HTML: <li class="TPostMv">
    //         <article class="TPost C ...">
    //           <a href="/phim/slug-aNUM/">
    //             <div class="Image">
    //               <figure><img src="poster.jpg" /></figure>
    //               <span class="mli-eps">TẬP<i>07</i></span>
    //             </div>
    //             <h2 class="Title">Tên Anime</h2>
    //           </a>
    //           <div class="TPMvCn">
    //             <p class="Info">
    //               <span class="Qlty">FHD</span>
    //               <span class="Time">07/12</span>
    //               <span class="Date">2026</span>
    //             </p>
    //           </div>
    //         </article>
    //       </li>
    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a       = article.selectFirst("a") ?: return null
        val href    = a.attr("href").let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        val title   = article.selectFirst("h2.Title")?.text()?.trim()
            ?: a.attr("title").trim()
        if (title.isBlank()) return null

        // Poster: src (không dùng data-src)
        val poster  = article.selectFirst("div.Image figure img")?.attr("src")

        // Badge tập: <span class="mli-eps">TẬP<i>07</i></span>
        val epiBadge = article.selectFirst("span.mli-eps")
        val epiText  = epiBadge?.selectFirst("i")?.text()?.trim()
            ?: epiBadge?.ownText()?.replace("TẬP","")?.trim()
        val epiNum   = epiText?.toIntOrNull()

        // Chất lượng từ hover card
        val quality = when (article.selectFirst(".Info .Qlty")?.text()?.uppercase()) {
            "FHD" -> SearchQuality.HD
            "HD"  -> SearchQuality.HD
            "CAM" -> SearchQuality.Cam
            "SD"  -> SearchQuality.SD
            else  -> SearchQuality.HD
        }

        // Loại phim: movie nếu href chứa anime-le hoặc tổng số tập == 1
        val isMovie = href.contains("/anime-le/") ||
                      article.selectFirst(".Info .Time")?.text()?.trim() == "1/1"

        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.quality   = quality
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) {
                this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = buildPageUrl(request.data, page)
        val doc  = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document

        // ul.MovieList li.TPostMv
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Tìm kiếm ──────────────────────────────────────────────────────────────
    // URL: /tim-kiem/{keyword}/
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url     = "$mainUrl/tim-kiem/$encoded/"
        val doc     = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    // ── Chi tiết anime ────────────────────────────────────────────────────────
    // Load trang detail để lấy info, load xem-phim.html để lấy danh sách tập
    override suspend fun load(url: String): LoadResponse {
        val detailUrl = url.trimEnd('/')

        // 1. Load trang detail
        val detailDoc = app.get(
            "$detailUrl/",
            headers = commonHeaders,
            interceptor = cfInterceptor
        ).document

        val title    = detailDoc.selectFirst("h1.Title")?.text()?.trim() ?: ""
        val subTitle = detailDoc.selectFirst("h2.SubTitle")?.text()?.trim()

        val poster   = detailDoc.selectFirst("div.Image figure img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        val plot     = detailDoc.selectFirst("div.Description")?.text()?.trim()

        val tags     = detailDoc.select("p.Genre a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year     = detailDoc.selectFirst("span.Date, p.Info span.Date a")
            ?.text()?.trim()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val quality = when (detailDoc.selectFirst("span.Qlty")?.text()?.uppercase()) {
            "FHD" -> SearchQuality.HD
            "HD"  -> SearchQuality.HD
            "CAM" -> SearchQuality.Cam
            else  -> SearchQuality.HD
        }

        // 2. Load trang xem-phim.html để lấy đầy đủ danh sách tập
        // Trong trang này có: <div id="list-server">
        //   <div class="server-group"><ul class="list-episode">
        //     <li class="episode"><a class="episode-link" href="...tap-01-110533.html"
        //        title="Tập 01" data-id="110533" data-hash="...">01</a>
        val watchDoc = try {
            app.get(
                "$detailUrl/xem-phim.html",
                headers = commonHeaders,
                interceptor = cfInterceptor
            ).document
        } catch (e: Exception) {
            detailDoc // fallback về trang detail nếu xem-phim.html không tải được
        }

        // Lấy tất cả episode link, deduplicate theo href (nhiều server có thể trùng)
        val seenHrefs = mutableSetOf<String>()
        val episodes  = mutableListOf<Episode>()

        watchDoc.select("#list-server .list-episode a.episode-link").forEach { a ->
            val epHref = a.attr("href").let {
                if (it.startsWith("http")) it else "$mainUrl$it"
            }
            if (epHref.isBlank() || !seenHrefs.add(epHref)) return@forEach

            val epTitle = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
            val epNum   = Regex("""(\d+)""").find(a.text().trim())?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name    = epTitle
                this.episode = epNum
            })
        }

        // Nếu watch page không có episode → thử lấy từ detail page (latest_eps)
        if (episodes.isEmpty()) {
            detailDoc.select("li.latest_eps a").forEach { a ->
                val epHref = a.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                if (epHref.isBlank() || !seenHrefs.add(epHref)) return@forEach
                val epNum = a.text().trim().toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name    = "Tập ${a.text().trim()}"
                    this.episode = epNum
                })
            }
        }

        // Sắp xếp tăng dần theo số tập
        episodes.sortBy { it.episode ?: 0 }

        // Phân biệt movie / series
        val isMovie = tags.any { it.contains("Movie", ignoreCase = true) ||
                                 it.contains("OVA",   ignoreCase = true) } ||
                      episodes.size == 1

        return if (isMovie && episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$detailUrl/xem-phim.html") {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
                this.year      = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, episodes.isNotEmpty()) {
                this.posterUrl    = poster
                this.plot         = plot
                this.tags         = tags
                this.year         = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ── Load link video ───────────────────────────────────────────────────────
    // data = URL tập, ví dụ: /phim/slug/tap-01-110533.html
    //
    // Cơ chế player của animevietsub:
    // 1. Trang tập có script: AnimeVsub('HASH', filmID)
    // 2. JS gọi AJAX đến AjaxURL = https://animevietsub.be/ajax/all
    // 3. Server trả về URL video (m3u8/mp4)
    //
    // Chiến lược:
    //   Bước 1: Dùng WebViewResolver intercept m3u8 (để browser tự chạy JS)
    //   Bước 2 (fallback): Extract hash từ HTML → POST AJAX → parse video URL
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ── Bước 1: WebViewResolver – intercept video stream URL ──────────────
        // WebView sẽ chạy JS (AnimeVsub), gọi AJAX và tải video.
        // videoInterceptor bắt URL đầu tiên khớp với pattern video/m3u8.
        safeApiCall {
            val resolvedUrl = app.get(
                data,
                headers = commonHeaders,
                interceptor = videoInterceptor
            ).url

            if (resolvedUrl != data && resolvedUrl.isNotBlank()) {
                val isM3u8 = resolvedUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name   = "$name - Server 1",
                        url    = resolvedUrl,
                        type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer"    to data,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
        }

        // ── Bước 2: Fallback – load HTML, extract hash, gọi AJAX ─────────────
        safeApiCall {
            val doc = app.get(data, headers = commonHeaders, interceptor = cfInterceptor).document
            val html = doc.html()

            // Lấy filmID và episodeID từ script
            val filmID    = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                .find(html)?.groupValues?.get(1) ?: ""
            val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                .find(html)?.groupValues?.get(1) ?: ""

            // Lấy hash từ: AnimeVsub('HASH', filmInfo.filmID)
            val hash = Regex("""AnimeVsub\(['"]([^'"]+)['"]""")
                .find(html)?.groupValues?.get(1) ?: ""

            if (filmID.isBlank() || hash.isBlank()) return@safeApiCall

            // Gọi AJAX endpoint
            val ajaxUrl = "https://animevietsub.be/ajax/all"
            val ajaxRes = app.post(
                ajaxUrl,
                headers = commonHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type"     to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "hash"      to hash,
                    "filmID"    to filmID,
                    "episodeID" to episodeID,
                    "source"    to "du"
                ),
                interceptor = cfInterceptor
            ).text

            // Parse video URL từ response JSON hoặc text
            val videoUrl = Regex(""""(?:file|src|url)"\s*:\s*"([^"]+)"""")
                .find(ajaxRes)?.groupValues?.get(1)
                ?.replace("\\", "")
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(ajaxRes)?.value
                ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(ajaxRes)?.value

            if (!videoUrl.isNullOrBlank()) {
                val isM3u8 = videoUrl.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = name,
                        name   = "$name - AJAX",
                        url    = videoUrl,
                        type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer"    to data,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }

            // Thử loadExtractor cho từng iframe nếu có
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src != data) {
                    safeApiCall {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }
}
