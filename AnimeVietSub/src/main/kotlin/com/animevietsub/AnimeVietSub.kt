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

    // Chỉ dùng cho DU server fallback — intercept chunk HLS
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

    // ── Load detail ───────────────────────────────────────────────────────────
    // Episode data format: "URL|episodeId|duHash"
    // duHash lấy từ data-hash trên episode link → không cần gọi /ajax/player
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

                val episodeId = a.attr("data-id").trim()
                val duHash    = a.attr("data-hash").trim()
                val num       = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()

                // Pack episodeId + duHash vào data để dùng trong loadLinks
                // Format: "URL|episodeId|duHash"
                val epData = "$href|$episodeId|$duHash"

                newEpisode(epData) {
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
    // data = "episodeUrl|episodeId|duHash"
    //
    // SERVER HDX (Hydrax):
    //   POST /ajax/player → lấy embed key (data-href)
    //   POST ping.iamcdn.net {slug=key}
    //   → {status:true, url:"WFleHh2bmc2aC54eXo=O", sources:["sd","hd"]}
    //   Decode CDN domain: lastChar + dropLast(1) → base64 → "9aexxvng6h.xyz"
    //   Video URLs (slug = file ID):
    //     SD:   https://{cdn}/{slug}
    //     HD:   https://{cdn}/www{slug}
    //     FHD:  https://{cdn}/whw{slug}
    //
    // SERVER DU:
    //   duHash có sẵn từ data-hash → không cần /ajax/player
    //   POST /ajax/all {EpisodeMess=1, EpisodeID=X} với session cookie
    //   Nếu response không chứa URL → WebView fallback
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse episode data (format: "url|episodeId|duHash")
        val parts     = data.split("|")
        val epUrl     = parts.getOrNull(0) ?: data
        val episodeID = parts.getOrNull(1) ?: ""
        val duHash    = parts.getOrNull(2) ?: ""

        // Lấy filmID từ trang tập (cần cho /ajax/get_episode)
        val pageHtml = app.get(epUrl, headers = headers).text
        val filmID   = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                       .find(pageHtml)?.groupValues?.get(1) ?: ""
        // Fallback: lấy episodeID từ JS nếu data-id không có
        val epID = episodeID.ifBlank {
            Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                .find(pageHtml)?.groupValues?.get(1) ?: ""
        }

        if (filmID.isBlank() || epID.isBlank()) return true

        val ajaxHdr = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to epUrl,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // POST /ajax/player → cần để lấy: (1) cookie, (2) HDX embed key
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to epID, "backup" to "1")
        )
        val cookie  = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val ajaxCookie = ajaxHdr + mapOf("Cookie" to cookie)

        val playerJson = playerResp.parsed<PlayerResponse>()
        if (playerJson.success == 1 && !playerJson.html.isNullOrBlank()) {
            Jsoup.parseBodyFragment(playerJson.html).select("a.btn3dsv").forEach { btn ->
                val play = btn.attr("data-play")
                val href = btn.attr("data-href").trim()
                if (href.isBlank()) return@forEach

                when (play) {
                    // ── DU server: POST /ajax/all với cookie ──────────────────
                    "api" -> safeApiCall {
                        // Warm up session
                        app.get(
                            "$mainUrl/ajax/get_episode?filmId=$filmID&episodeId=$epID",
                            headers = ajaxCookie
                        )
                        val allText = app.post(
                            "$mainUrl/ajax/all",
                            headers = ajaxCookie,
                            data    = mapOf("EpisodeMess" to "1", "EpisodeID" to epID)
                        ).text
                        extractAnyUrl(allText)?.let { videoUrl ->
                            callback(newExtractorLink(
                                source = name, name = "$name - DU",
                                url  = videoUrl,
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf("Referer" to epUrl, "User-Agent" to UA)
                            })
                        }
                    }

                    // ── HDX server: Hydrax API ────────────────────────────────
                    // Đã xác nhận từ network capture + ping.iamcdn.net test:
                    //   POST ping.iamcdn.net {slug=href}
                    //   → {url: "XYZ=O", sources: ["sd","hd"]}
                    //   lastChar + dropLast(1) → base64 → CDN domain
                    //   slug = file ID (không có field data/id riêng)
                    "embed" -> safeApiCall {
                        extractHydrax(href, epUrl, callback)
                    }
                }
            }
        }

        // ── DU server WebView fallback ────────────────────────────────────────
        // Khi /ajax/all không trả URL trực tiếp (response là JS-encoded)
        // WebView load trang tập → JS decode duHash → fetch chunk đầu tiên
        // Ta intercept chunk URL → extract storageId → build master.m3u8
        if (duHash.isNotBlank()) {
            safeApiCall {
                val resolved = app.get(epUrl, headers = headers, interceptor = duInterceptor).url
                val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]+)/""")
                    .find(resolved)?.groupValues?.get(1)
                if (!storageId.isNullOrBlank()) {
                    val m3u8 = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"
                    callback(newExtractorLink(
                        source = name, name = "$name - DU",
                        url  = m3u8, type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer"    to epUrl,
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
    // Đã xác nhận từ người dùng test thực tế:
    //
    //   POST https://ping.iamcdn.net/ {slug="7fUvtRbiYk"}
    //   → {"status":true,"url":"WFleHh2bmc2aC54eXo=O","sources":["sd","hd"],
    //      "isCdn":true,"isDownload":false,"ads":"..."}
    //
    //   Decode "WFleHh2bmc2aC54eXo=O":
    //     lastChar = 'O'
    //     b64str   = 'O' + "WFleHh2bmc2aC54eXo=" = "OWFleHh2bmc2aC54eXo="
    //     decode   → "9aexxvng6h.xyz"  (CDN domain)
    //
    //   Không có field data/id → slug chính là file ID
    //   SD:   https://{cdn}/{slug}
    //   HD:   https://{cdn}/www{slug}
    //   FHD:  https://{cdn}/whw{slug}
    private suspend fun extractHydrax(
        slug: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val resp = app.post(
            "https://ping.iamcdn.net/",
            headers = mapOf(
                "User-Agent"   to UA,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer"      to "https://playhydrax.com/",
                "Origin"       to "https://playhydrax.com"
            ),
            data = mapOf("slug" to slug)
        ).parsed<HydraxResponse>()

        if (resp.status != true || resp.url.isNullOrBlank()) return

        // Decode CDN domain
        // "WFleHh2bmc2aC54eXo=O" → lastChar='O' → "OWFleHh2bmc2aC54eXo=" → base64 → domain
        val encoded   = resp.url
        val lastChar  = encoded.last().toString()
        val b64input  = lastChar + encoded.dropLast(1)
        val cdnDomain = try {
            String(Base64.decode(b64input, Base64.DEFAULT)).trim()
        } catch (_: Exception) { return }

        if (cdnDomain.isBlank() || !cdnDomain.contains('.')) return

        // slug = file ID (đã xác nhận: response không có field data/id)
        val videoHdr = mapOf(
            "Referer"    to referer,
            "User-Agent" to UA,
            "Origin"     to "https://playhydrax.com"
        )

        val sources = resp.sources ?: listOf("sd", "hd")
        sources.forEach { src ->
            val (prefix, quality, label) = when (src.lowercase()) {
                "sd"  -> Triple("",    Qualities.P360.value,  "360p")
                "hd"  -> Triple("www", Qualities.P720.value,  "720p")
                "fhd" -> Triple("whw", Qualities.P1080.value, "1080p")
                else  -> Triple("",    Qualities.Unknown.value, src)
            }
            callback(newExtractorLink(
                source = name,
                name   = "$name - HDX $label",
                url    = "https://$cdnDomain/$prefix$slug",
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.headers = videoHdr
            })
        }

        // Luôn thêm HD nếu sources chỉ có sd (đảm bảo có ít nhất 720p)
        if (sources.map { it.lowercase() }.none { it == "hd" || it == "fhd" }) {
            callback(newExtractorLink(
                source = name, name = "$name - HDX 720p",
                url    = "https://$cdnDomain/www$slug",
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P720.value
                this.headers = videoHdr
            })
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

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )

    data class HydraxResponse(
        @JsonProperty("status")     val status: Boolean?      = null,
        @JsonProperty("url")        val url: String?          = null,
        @JsonProperty("sources")    val sources: List<String>? = null,
        @JsonProperty("isCdn")      val isCdn: Boolean?       = null,
        @JsonProperty("isDownload") val isDownload: Boolean?  = null
    )
}
