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

    // Khớp User-Agent từ Log 5 của bạn
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*chunks.*|.*playlist.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept"     to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer"    to "$mainUrl/",
        "Origin"     to mainUrl,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val title = el.selectFirst("h2.Title")?.text() ?: a.attr("title")
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = el.selectFirst("img")?.attr("src") }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val watchUrl = res.document.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: "${url.trimEnd('/')}/xem-phim.html"
        val watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
        val watchDoc = Jsoup.parse(watchRes.text)

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
        // BƯỚC 1: Lấy Token ban đầu qua WebView
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        val pageHtml = webRes.text
        var currentCookies = webRes.cookies

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to data
        )

        safeApiCall {
            // BƯỚC 2: Gọi AJAX PLAYER (Bắt đầu chuỗi xác thực - Log 2)
            val playerCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies,
                data = mapOf("episodeId" to episodeID, "backup" to "1")
            )
            currentCookies = currentCookies + playerCall.cookies
            val playerRes = playerCall.parsedSafe<PlayerResponse>()

            // BƯỚC 3: Bước đệm GET_EPISODE (Rất quan trọng - Log 4)
            if (filmId.isNotEmpty()) {
                val getEpi = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
                currentCookies = currentCookies + getEpi.cookies
            }

            // BƯỚC 4: Bước CHỐT HẠ - AJAX ALL (Unlock Session - Log 5)
            if (episodeID.isNotEmpty()) {
                val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                )
                currentCookies = currentCookies + allCall.cookies
                delay(1000) // Nghỉ 1s để Server kịp mở khóa Token
            }

            // BƯỚC 5: Sau khi đã UNLOCK, gọi WebView lần cuối để bốc link đã có hiệu lực
            if (playerRes?.success == 1) {
                // Ta gọi WebView TRỰC TIẾP lên trang xem phim với Cookie đã được xác thực
                // Lúc này các script trên web sẽ tự động chạy và nhả link m3u8 thật
                app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)
                
                // Nếu WebView bắt được link, ExtractorLink sẽ tự động được gửi về qua videoInterceptor
                // Ngoài ra ta quét thêm trong player HTML phòng hờ link embed
                val playerDoc = Jsoup.parse(playerRes.html ?: "")
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val videoHref = server.attr("data-href")
                    if (server.attr("data-play") == "embed") {
                        val finalEmbed = if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref"
                        loadExtractor(finalEmbed, data, subtitleCallback, callback)
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
