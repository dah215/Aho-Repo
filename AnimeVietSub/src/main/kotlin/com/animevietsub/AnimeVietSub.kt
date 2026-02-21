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
import android.util.Base64

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

    // WebViewResolver: chỉ dùng cho DU server khi /ajax/all thất bại
    // Intercept chunk HLS từ storage.googleapiscdn.com
    private val duInterceptor = WebViewResolver(
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
    // Từ network capture thực tế:
    //
    //  POST /ajax/player {episodeId, backup=1}
    //  → set-cookie: tokenXXX=YYY (bắt buộc gửi kèm /ajax/all)
    //  → html: data-play="api" (DU server, hash), data-play="embed" (HDX, key)
    //
    //  Server HDX (embed):
    //    - data-href = video ID trên Hydrax/AbyssCDN
    //    - Player script: iamcdn.net/players/playhydraxs.min.js
    //    - API: POST https://ping.iamcdn.net/ {slug=KEY}
    //    - Response: {url: "BASE64=O", sources: ["sd","hd"], ...}
    //    - Decode url: last char → prepend → base64 decode → CDN domain
    //    - File ID: cần parse từ response (field "data" hoặc trong sources)
    //    - Video: https://{cdn}/{file_id}          (360p/SD)
    //             https://{cdn}/www{file_id}        (720p/HD)
    //             https://{cdn}/whw{file_id}        (1080p/FHD)
    //
    //  Server DU (api):
    //    - Cần cookie từ /ajax/player
    //    - POST /ajax/all {EpisodeMess=1, EpisodeID=X} → response chứa video
    //    - Fallback: WebViewResolver intercept storage.googleapiscdn.com chunk
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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

        // Bước 1: POST /ajax/player → lấy server list + session cookie
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to episodeID, "backup" to "1")
        )

        // Cookie bắt buộc cho /ajax/all
        val cookie = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val ajaxHdrWithCookie = ajaxHdr + mapOf("Cookie" to cookie)

        val playerJson = playerResp.parsed<PlayerResponse>()
        if (playerJson.success != 1 || playerJson.html.isNullOrBlank()) return true

        val serverDoc    = Jsoup.parseBodyFragment(playerJson.html)
        var hasApiServer = false

        serverDoc.select("a.btn3dsv").forEach { btn ->
            val play = btn.attr("data-play")
            val href = btn.attr("data-href").trim()
            if (href.isBlank()) return@forEach

            when (play) {

                // ── Server DU (api) ───────────────────────────────────────────
                "api" -> {
                    hasApiServer = true
                    safeApiCall {
                        // Bước 2: GET get_episode (warm up session)
                        app.get(
                            "$mainUrl/ajax/get_episode?filmId=$filmID&episodeId=$episodeID",
                            headers = ajaxHdrWithCookie
                        )
                        // Bước 3: POST /ajax/all (cần cookie) → chứa video source
                        val allText = app.post(
                            "$mainUrl/ajax/all",
                            headers = ajaxHdrWithCookie,
                            data    = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                        ).text

                        val videoUrl = extractAnyUrl(allText)
                        if (!videoUrl.isNullOrBlank()) {
                            callback(newExtractorLink(
                                source = name, name = "$name - DU",
                                url  = videoUrl,
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("Referer" to data, "User-Agent" to UA)
                            })
                        }
                    }
                }

                // ── Server HDX (embed) = Hydrax/AbyssCDN ─────────────────────
                // API: POST ping.iamcdn.net {slug=KEY}
                // Decode: prepend last char → base64 → CDN domain
                "embed" -> safeApiCall {
                    extractHydrax(href, data, callback)
                }
            }
        }

        // ── Fallback WebView cho DU server ────────────────────────────────────
        // Nếu /ajax/all không trả về URL trực tiếp (response là encoded/blob)
        // WebView chạy JS → decode hash → fetch chunk → ta intercept
        if (hasApiServer) {
            safeApiCall {
                val resolved = app.get(data, headers = headers, interceptor = duInterceptor).url
                val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]+)/""")
                    .find(resolved)?.groupValues?.get(1)

                if (!storageId.isNullOrBlank()) {
                    val m3u8 = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"
                    callback(newExtractorLink(
                        source = name, name = "$name - DU (WebView)",
                        url  = m3u8, type = ExtractorLinkType.M3U8
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

    // ── Hydrax extractor ──────────────────────────────────────────────────────
    //
    // Từ ABPVN filter + GitHub research:
    //   POST https://ping.iamcdn.net/ {slug=KEY}
    //   → {status:true, url:"BASE64=O", sources:["sd","hd"], ...}
    //
    //   Decode CDN domain:
    //     encoded = "WFleHh2bmc2aC54eXo=O"
    //     lastChar = 'O' (ký tự cuối)
    //     b64 = lastChar + encoded.dropLast(1)  → "OWFleHh2bmc2aC54eXo="
    //     cdnDomain = base64Decode(b64)          → "9aexxvng6h.xyz"
    //
    //   Video URLs (fileId = slug hoặc từ response):
    //     SD:   https://{cdn}/{fileId}
    //     HD:   https://{cdn}/www{fileId}
    //     FHD:  https://{cdn}/whw{fileId}
    private suspend fun extractHydrax(
        slug: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val pingHeaders = mapOf(
            "User-Agent"   to UA,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer"      to "https://playhydrax.com/",
            "Origin"       to "https://playhydrax.com"
        )

        val resp = app.post(
            "https://ping.iamcdn.net/",
            headers = pingHeaders,
            data    = mapOf("slug" to slug)
        ).parsed<HydraxResponse>()

        if (resp.status != true || resp.url.isNullOrBlank()) return

        // Decode CDN domain:
        // encoded = "XYZ...=O" → take last char, prepend → base64 decode
        val encoded  = resp.url
        val lastChar = encoded.last().toString()
        val b64input = lastChar + encoded.dropLast(1)
        val cdnDomain = try {
            String(Base64.decode(b64input, Base64.DEFAULT)).trim()
        } catch (_: Exception) {
            return
        }

        if (cdnDomain.isBlank() || !cdnDomain.contains('.')) return

        // File ID: có thể là slug, hoặc từ resp.data
        val fileId = resp.data?.takeIf { it.isNotBlank() } ?: slug

        val videoHeaders = mapOf(
            "Referer"    to referer,
            "User-Agent" to UA,
            "Origin"     to "https://playhydrax.com"
        )

        // Build quality URLs theo pattern đã xác nhận
        val qualities = listOf(
            Triple("https://$cdnDomain/$fileId",        Qualities.P360.value, "HDX 360p"),
            Triple("https://$cdnDomain/www$fileId",     Qualities.P720.value, "HDX 720p"),
            Triple("https://$cdnDomain/whw$fileId",     Qualities.P1080.value, "HDX 1080p"),
        )

        // Thêm từ sources array (sd/hd) nếu có
        resp.sources?.forEachIndexed { i, src ->
            val q = when (src.lowercase()) {
                "sd"  -> Qualities.P480.value
                "hd"  -> Qualities.P720.value
                "fhd" -> Qualities.P1080.value
                else  -> Qualities.Unknown.value
            }
            val prefix = when (src.lowercase()) { "hd" -> "www"; "fhd" -> "whw"; else -> "" }
            callback(newExtractorLink(
                source = name, name = "$name - HDX $src",
                url  = "https://$cdnDomain/$prefix$fileId",
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = q
                this.headers = videoHeaders
            })
        }

        // Nếu sources trống → thêm tất cả 3 qualities
        if (resp.sources.isNullOrEmpty()) {
            qualities.forEach { (url, q, label) ->
                callback(newExtractorLink(
                    source = name, name = "$name - $label",
                    url = url, type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = q
                    this.headers = videoHeaders
                })
            }
        }
    }

    private fun extractAnyUrl(text: String): String? {
        if (text.isBlank()) return null
        return Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(text)?.value
            ?: Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(text)?.value
            ?: Regex(""""(?:file|src|url|link|source)"\s*:\s*"(https?://[^"\\]+)"""")
               .find(text)?.groupValues?.get(1)
            ?: Regex("""<file>(https?://[^<]+)</file>""").find(text)?.groupValues?.get(1)
    }

    // ── Data classes ─────────────────────────────────────────────────────────
    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )

    data class HydraxResponse(
        @JsonProperty("status")  val status: Boolean?      = null,
        @JsonProperty("url")     val url: String?          = null,
        @JsonProperty("sources") val sources: List<String>? = null,
        @JsonProperty("data")    val data: String?         = null,
        @JsonProperty("id")      val id: String?           = null
    )
}
