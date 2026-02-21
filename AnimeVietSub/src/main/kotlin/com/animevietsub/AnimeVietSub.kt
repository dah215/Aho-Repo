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

    // Khớp User-Agent chính xác từ file log bạn gửi
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*chunks.*|.*playlist.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "com.android.chrome"
    )

    // KHÔI PHỤC ĐẦY ĐỦ DANH SÁCH MỤC PHÍM
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi-cap-nhat/"        to "Anime Mới Cập Nhật",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/sap-chieu/"                 to "Anime Sắp Chiếu"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title")?.text() ?: el.selectFirst(".Title")?.text() ?: ""
        val poster = el.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv, .list-anime li").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val doc = res.document
        
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it" 
        } ?: "${url.trimEnd('/')}/xem-phim.html"
        
        val watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
        val watchDoc = Jsoup.parse(watchRes.text)

        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val name = a.text().trim()
            newEpisode(href) {
                this.name = "Tập $name"
                this.episode = Regex("""\d+""").find(name)?.value?.toIntOrNull()
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
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        var currentCookies = webRes.cookies
        val pageHtml = webRes.text

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = mapOf(
            "User-Agent" to UA,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to mainUrl
        )

        safeApiCall {
            // 1. GỌI PLAYER (Log 1 & 2)
            val playerCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies,
                data = mapOf("episodeId" to episodeID, "backup" to "1")
            )
            currentCookies = currentCookies + playerCall.cookies
            val playerRes = playerCall.parsedSafe<PlayerResponse>()

            // 2. GỌI GET_EPISODE (Log 4) - BƯỚC ĐỆM BẮT BUỘC
            if (filmId.isNotEmpty()) {
                val getEpi = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
                currentCookies = currentCookies + getEpi.cookies
            }

            // 3. GỌI ALL (Log 5) - CHỐT PHIÊN ĐỂ MỞ KHÓA M3U8
            if (episodeID.isNotEmpty()) {
                val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                )
                currentCookies = currentCookies + allCall.cookies
                delay(1200) // Đợi server xử lý token mở khóa
            }

            // 4. XỬ LÝ SERVER
            if (playerRes?.success == 1) {
                // Ép WebView chạy lại trang với FULL COOKIE TOKEN mới nhất
                app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)

                val playerDoc = Jsoup.parse(playerRes.html ?: "")
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val vHref = server.attr("data-href")
                    if (server.attr("data-play") == "embed") {
                        loadExtractor(if (vHref.startsWith("http")) vHref else "$mainUrl/embed/$vHref", data, subtitleCallback, callback)
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
