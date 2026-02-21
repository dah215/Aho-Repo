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

    // WebViewResolver chỉ dùng cho DU server (JS decode + blob m3u8)
    // Intercept chunk đầu tiên từ storage.googleapiscdn.com
    private val videoInterceptor = WebViewResolver(
        Regex("""storage\.googleapiscdn\.com/chunks/[a-f0-9]+/""")
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
    // Flow đã xác nhận từ network capture:
    //
    //  [1] POST /ajax/player {episodeId, backup=1}
    //      Response: set-cookie: tokenXXX=YYY  ← QUAN TRỌNG
    //      Response JSON: {html: "<a data-play='api' data-href=HASH>DU</a>
    //                            <a data-play='embed' data-href=KEY>HDX(ADS)</a>"}
    //
    //  [2] GET /ajax/get_episode?filmId=X&episodeId=Y   (với cookie từ bước 1)
    //
    //  [3] POST /ajax/all {EpisodeMess=1, EpisodeID=Y}  (với cookie từ bước 1)
    //      → Response chứa video source (format chưa biết)
    //      → JS decode → blob m3u8 → chunks storage.googleapiscdn.com
    //
    //  [4] embed server HDX = AbyssCDN player
    //      URL: https://abysscdn.com/?v={data-href}
    //      CloudStream có extractor AbyssCDN sẵn
    //
    //  KEY INSIGHT: Cookie từ /ajax/player PHẢI được gửi kèm /ajax/all
    //  Trước đây thiếu cookie này nên /ajax/all không hoạt động
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Load trang tập → lấy filmID, episodeID
        val pageHtml  = app.get(data, headers = headers).text
        val filmID    = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageHtml)?.groupValues?.get(1) ?: ""

        if (filmID.isBlank() || episodeID.isBlank()) return true

        val ajaxHdr = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to data,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // ── [1] POST /ajax/player → lấy server list + cookie ─────────────────
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to episodeID, "backup" to "1")
        )

        // Lấy cookie từ response (tokenXXX=YYY) - cần thiết cho /ajax/all
        val sessionCookie = playerResp.cookies.entries
            .joinToString("; ") { "${it.key}=${it.value}" }

        val headersWithCookie = ajaxHdr + mapOf("Cookie" to sessionCookie)

        val playerJson = playerResp.parsed<PlayerResponse>()
        if (playerJson.success != 1 || playerJson.html.isNullOrBlank()) return true

        val serverDoc = Jsoup.parseBodyFragment(playerJson.html)
        var hasApiServer = false

        serverDoc.select("a.btn3dsv").forEach { btn ->
            val play = btn.attr("data-play")
            val href = btn.attr("data-href").trim()
            if (href.isBlank()) return@forEach

            when (play) {
                // ── [3] Server DU (api): POST /ajax/all với cookie ────────────
                "api" -> {
                    hasApiServer = true
                    safeApiCall {
                        // Bước 2: GET get_episode (với cookie)
                        app.get(
                            "$mainUrl/ajax/get_episode?filmId=$filmID&episodeId=$episodeID",
                            headers = headersWithCookie
                        )

                        // Bước 3: POST /ajax/all (với cookie) → video source
                        val allResp = app.post(
                            "$mainUrl/ajax/all",
                            headers = headersWithCookie,
                            data    = mapOf(
                                "EpisodeMess" to "1",
                                "EpisodeID"   to episodeID
                            )
                        ).text

                        // Parse URL trực tiếp nếu response chứa URL
                        val videoUrl = extractVideoUrl(allResp)
                        if (!videoUrl.isNullOrBlank()) {
                            callback(newExtractorLink(
                                source = name,
                                name   = "$name - DU",
                                url    = videoUrl,
                                type   = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                         else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("Referer" to data, "User-Agent" to UA)
                            })
                        }
                    }
                }

                // ── [4] Server HDX (embed) = AbyssCDN ────────────────────────
                // data-href là video ID của AbyssCDN
                // URL: https://abysscdn.com/?v={id}
                // CloudStream có built-in extractor cho AbyssCDN
                "embed" -> safeApiCall {
                    val embedUrl = "https://abysscdn.com/?v=$href"
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        }

        // ── [5] Fallback: WebViewResolver cho DU server ───────────────────────
        // Nếu /ajax/all không trả về URL trực tiếp,
        // WebView sẽ chạy JS decode hash → tạo blob m3u8 → fetch chunks
        // Interceptor bắt chunk URL đầu tiên → extract storageId → master.m3u8
        if (hasApiServer) {
            safeApiCall {
                val chunkUrl = app.get(
                    data,
                    headers     = headers,
                    interceptor = videoInterceptor
                ).url

                val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]+)/""")
                    .find(chunkUrl)?.groupValues?.get(1)

                if (!storageId.isNullOrBlank()) {
                    // Build master.m3u8 từ storageId
                    // Dựa trên blob m3u8: chunks/{storageId}/original/{key}/videoN.html
                    // → master playlist tại: chunks/{storageId}/original/master.m3u8
                    val m3u8 = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"
                    callback(newExtractorLink(
                        source = name,
                        name   = "$name - DU (WebView)",
                        url    = m3u8,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer"    to data,
                            "User-Agent" to UA,
                            "Origin"     to mainUrl
                        )
                    })
                }
            }
        }

        return true
    }

    private fun extractVideoUrl(text: String): String? {
        if (text.isBlank()) return null
        return Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(text)?.value
            ?: Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(text)?.value
            ?: Regex(""""(?:file|src|url|link|source|video|stream)"\s*:\s*"(https?://[^"\\]+)"""")
               .find(text)?.groupValues?.get(1)
            ?: Regex("""<file>(https?://[^<]+)</file>""").find(text)?.groupValues?.get(1)
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
