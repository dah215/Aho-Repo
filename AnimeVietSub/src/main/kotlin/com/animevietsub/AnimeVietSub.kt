package com.animevietsub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
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
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
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
        val title    = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster   = watchDoc.selectFirst("div.Image figure img")?.attr("src")
                       ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot     = watchDoc.selectFirst("div.Description")?.text()?.trim()
        val year     = watchDoc.selectFirst("p.Info .Date a, p.Info .Date")
                       ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags     = watchDoc.select("p.Genre a").map { it.text().trim() }.filter { it.isNotBlank() }

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
    // Đã xác nhận từ DevTools:
    //
    // HDX server (embed):
    //   POST /ajax/player: link=DATA_HREF&play=embed&id=3&backuplinks=1
    //   Response: {"link":"https://short.icu/xxx","playTech":"embed"}
    //   short.icu redirect → storage.googleapis.com/.../file.mp4
    //
    // DU server (api):
    //   POST /ajax/player: link=DATA_HREF&play=api&id=0&backuplinks=1
    //   Fallback: /ajax/get_episode với cookie → RSS <file>
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "Accept-Language"  to "vi-VN,vi;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to epUrl,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // POST /ajax/player để lấy server list HTML + cookie
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to extractEpisodeId(epUrl), "backup" to "1")
        )
        val cookie     = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val playerJson = try { playerResp.parsed<PlayerResponse>() } catch (_: Exception) { null }
        val html       = playerJson?.html ?: return true
        val doc        = Jsoup.parseBodyFragment(html)

        doc.select("a.btn3dsv").forEach { btn ->
            val play = btn.attr("data-play")
            val href = btn.attr("data-href").trim()
            val id   = btn.attr("data-id").trim()
            if (href.isBlank()) return@forEach

            safeApiCall {
                val linkResp = app.post(
                    "$mainUrl/ajax/player",
                    headers = ajaxHdr,
                    data    = mapOf(
                        "link"        to href,
                        "play"        to play,
                        "id"          to id.ifBlank { "0" },
                        "backuplinks" to "1"
                    )
                ).parsed<EmbedResponse>()

                var videoUrl = linkResp.link ?: return@safeApiCall

                // short.icu hoặc URL rút gọn → follow redirect → URL video thật
                if (!videoUrl.contains(".mp4") && !videoUrl.contains(".m3u8")) {
                    val redirectResp = app.get(
                        videoUrl,
                        headers        = mapOf("User-Agent" to UA, "Referer" to epUrl),
                        allowRedirects = true
                    )
                    val redirectedUrl = redirectResp.url
                    if (redirectedUrl != videoUrl) {
                        videoUrl = redirectedUrl
                    }
                    // Nếu vẫn không phải video, parse từ HTML response
                    if (!videoUrl.contains(".mp4") && !videoUrl.contains(".m3u8")) {
                        val body = redirectResp.text
                        videoUrl = Regex("""(https?://[^"'\s]+\.mp4(?:\?[^"'\s]*)?)""")
                            .find(body)?.groupValues?.get(1)
                            ?: Regex("""(https?://[^"'\s]+\.m3u8(?:\?[^"'\s]*)?)""")
                            .find(body)?.groupValues?.get(1)
                            ?: return@safeApiCall
                    }
                }

                if (videoUrl.isBlank() || videoUrl.startsWith("blob:")) return@safeApiCall

                val serverName = when (play) {
                    "embed" -> "HDX"
                    "api"   -> "DU"
                    else    -> play.uppercase()
                }

                callback(newExtractorLink(
                    source = name,
                    name   = "$name - $serverName",
                    url    = videoUrl,
                    type   = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO
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

        return true
    }

    private fun extractEpisodeId(epUrl: String): String {
        return Regex("""-(\d+)\.html$""").find(epUrl)?.groupValues?.get(1) ?: ""
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int    = 0,
        @JsonProperty("html")    val html: String?   = null
    )

    data class EmbedResponse(
        @JsonProperty("success")   val success: Int      = 0,
        @JsonProperty("link")      val link: String?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("_fxStatus") val fxStatus: Int     = 0
    )
}
