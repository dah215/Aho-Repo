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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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

    // Khớp chính xác User-Agent từ log bạn gửi (Log 2)
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*chunks.*|.*playlist.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept"     to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer"    to "$mainUrl/",
        "Origin"     to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title")?.text() ?: a.attr("title")
        val poster = el.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val doc = res.document
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: "${url.trimEnd('/')}/xem-phim.html"
        val watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
        val watchDoc = watchRes.document

        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            newEpisode(href) {
                this.name = "Tập " + a.text().trim()
                this.episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(watchDoc.selectFirst("h1.Title")?.text() ?: "Anime", url, TvType.Anime, true) {
            this.posterUrl = watchDoc.selectFirst(".Image img")?.attr("src")
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // BƯỚC 1: Lấy Token ban đầu và FilmID/EpisodeID từ trang xem phim
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        var currentCookies = webRes.cookies
        val pageHtml = webRes.text

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to data
        )

        safeApiCall {
            // BƯỚC 2: Gọi AJAX PLAYER (Log 2) - Lấy HTML danh sách server
            val playerCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies,
                data = mapOf("episodeId" to episodeID, "backup" to "1")
            )
            val playerRes = playerCall.parsedSafe<PlayerResponse>()
            currentCookies = currentCookies + playerCall.cookies // Cập nhật token mới nếu có

            // BƯỚC 3: Giả lập nạp thông tin tập phim (Log 4 - Rất quan trọng để server không treo)
            if (filmId.isNotEmpty()) {
                val getEpiCall = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
                currentCookies = currentCookies + getEpiCall.cookies
                delay(300)
            }

            // BƯỚC 4: Kích hoạt ALL (Log 5 - Bước "Mở khóa" luồng video)
            if (episodeID.isNotEmpty()) {
                val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                )
                currentCookies = currentCookies + allCall.cookies
                delay(500) // Đợi server ghi nhận phiên xem phim
            }

            // BƯỚC 5: Xử lý Server và Bắt link bằng WebView "Mạnh tay"
            if (playerRes?.success == 1 && !playerRes.html.isNullOrBlank()) {
                val playerDoc = Jsoup.parse(playerRes.html!!)
                
                // Thuật toán: Luôn dùng WebView để quét sau khi đã thực hiện xong các bước POST xác thực
                // Điều này giúp "bẫy" link m3u8 ngay khi trình duyệt ngầm thực thi lệnh phát
                val finalRes = withTimeoutOrNull(25000) {
                    app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)
                }
                
                val finalUrl = finalRes?.url ?: ""
                if (finalUrl != data && (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4"))) {
                    callback(newExtractorLink(name, "Server VIP", finalUrl, 
                        type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = data
                    })
                }

                // Dự phòng: Nếu WebView không bắt được link tự động, quét các link embed
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val videoHref = server.attr("data-href")
                    if (server.attr("data-play") == "embed") {
                        loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int? = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
