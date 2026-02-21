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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Chỉ dùng WebViewResolver cho video (intercept chunk HLS)
    // KHÔNG dùng cho HTML request bình thường
    private val videoInterceptor = WebViewResolver(
        Regex("""storage\.googleapiscdn\.com/chunks/""")
    )

    private val headers = mapOf(
        "User-Agent"      to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer"         to "$mainUrl/"
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
        val title   = article.selectFirst("h2.Title")?.text()?.trim().takeIf { !it.isNullOrBlank() }
                      ?: a.attr("title").trim().takeIf { it.isNotBlank() } ?: return null
        val poster  = article.selectFirst("div.Image img, figure img")?.attr("src")
                      ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum  = article.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        val quality = when (article.selectFirst(".Qlty")?.text()?.uppercase()) {
            "FHD" -> SearchQuality.HD
            "HD"  -> SearchQuality.HD
            "CAM" -> SearchQuality.Cam
            else  -> SearchQuality.HD
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

    // ── Chi tiết ─────────────────────────────────────────────────────────────
    // Chỉ 1 request: xem-phim.html (có đủ metadata + toàn bộ danh sách tập)
    override suspend fun load(url: String): LoadResponse {
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = headers).document

        val title  = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster = watchDoc.selectFirst("div.Image figure img")?.attr("src")
                     ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot   = watchDoc.selectFirst("div.Description")?.text()?.trim()
        val year   = watchDoc.selectFirst("p.Info .Date a, p.Info .Date")
                     ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags   = watchDoc.select("p.Genre a").map { it.text().trim() }.filter { it.isNotBlank() }

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

        val isMovie = episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(
                title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html"
            ) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
                this.year      = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
                this.year      = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ── Load video ────────────────────────────────────────────────────────────
    //
    // Network flow (đã xác nhận):
    //   1. POST /ajax/player {episodeId, backup=1}
    //      → JSON html chứa: data-play="api" (server DU, hash)
    //                        data-play="embed" (server HDX, key)
    //
    //   2. Server EMBED → loadExtractor trực tiếp
    //
    //   3. Server API (DU) → hash cần JS decode (pako) → chunks storage.googleapiscdn.com
    //      → WebViewResolver intercept chunk URL → extract storageId → master.m3u8
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml  = app.get(data, headers = headers).text
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
            .find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = headers + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to data
        )

        // ── Bước 1: Lấy danh sách server ─────────────────────────────────────
        var hasApiServer = false
        if (episodeID.isNotBlank()) {
            safeApiCall {
                val resp = app.post(
                    "$mainUrl/ajax/player",
                    headers = ajaxHdr,
                    data    = mapOf("episodeId" to episodeID, "backup" to "1")
                ).parsed<PlayerResponse>()

                if (resp.success == 1 && !resp.html.isNullOrBlank()) {
                    Jsoup.parseBodyFragment(resp.html).select("a.btn3dsv").forEach { btn ->
                        val type = btn.attr("data-play")
                        val href = btn.attr("data-href").trim()
                        if (href.isBlank()) return@forEach

                        when (type) {
                            "api"   -> hasApiServer = true
                            "embed" -> safeApiCall {
                                val embedUrl = if (href.startsWith("http")) href
                                              else "$mainUrl/embed/$href"
                                loadExtractor(embedUrl, data, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        }

        // ── Bước 2: Server DU (api) → WebView intercept HLS chunk ────────────
        // WebView mở trang → JS chạy AnimeVsub(hash) → player fetch chunk đầu tiên
        // WebViewResolver bắt URL chunk → extract storageId → build master.m3u8
        if (hasApiServer || episodeID.isNotBlank()) {
            safeApiCall {
                val chunkUrl = app.get(
                    data,
                    headers     = headers,
                    interceptor = videoInterceptor
                ).url

                val storageId = Regex("""storage\.googleapiscdn\.com/chunks/([a-f0-9]+)/""")
                    .find(chunkUrl)?.groupValues?.get(1)

                if (!storageId.isNullOrBlank()) {
                    // Build master playlist URL từ storageId
                    val m3u8 = "https://storage.googleapiscdn.com/chunks/$storageId/original/master.m3u8"
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name - DU",
                            url    = m3u8,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer"    to data,
                                "User-Agent" to UA,
                                "Origin"     to mainUrl
                            )
                        }
                    )
                } else if (chunkUrl.isNotBlank() && chunkUrl != data
                           && !chunkUrl.startsWith("blob:")
                           && (chunkUrl.contains(".m3u8") || chunkUrl.contains(".mp4"))) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name - Direct",
                            url    = chunkUrl,
                            type   = if (chunkUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                     else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("Referer" to data, "User-Agent" to UA)
                        }
                    )
                }
            }
        }

        return true
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
