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

    // Khớp User-Agent từ Log 2 và 5 (Rất quan trọng để tránh bị Cloudflare chặn)
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*iamcdn\.net.*|.*playhydrax.*|.*abysscdn.*|.*blob:.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept"     to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer"    to "$mainUrl/",
        "Origin"     to mainUrl
    )

    // --- KHÔI PHỤC KỸ LƯỠNG DANH MỤC PHIM ---
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi-cap-nhat/"        to "Anime Mới Cập Nhật",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/sap-chieu/"                 to "Anime Sắp Chiếu"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = el.selectFirst("h2.Title, .Title")?.text()?.trim() ?: a.attr("title") ?: ""
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
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/"
        val doc = app.get(url, headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val doc = res.document
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { fixUrl(it) } ?: "${url.trimEnd('/')}/xem-phim.html"
        
        val watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
        val watchDoc = Jsoup.parse(watchRes.text)

        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            newEpisode(fixUrl(a.attr("href"))) {
                this.name = "Tập " + a.text().trim()
            }
        }.distinctBy { it.data }

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
        // BƯỚC 1: Khởi tạo phiên qua WebView (Bắt Token ban đầu)
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        var currentCookies = webRes.cookies
        val pageHtml = webRes.text

        // Trích xuất ID cần thiết cho AJAX
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        safeApiCall {
            val ajaxHdr = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")

            // BƯỚC 2: Gọi AJAX PLAYER (Log 2) - Đây là Endpoint lấy link ẩn
            val pCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies,
                data = mapOf("episodeId" to episodeID, "backup" to "1")
            )
            currentCookies = currentCookies + pCall.cookies
            val playerRes = pCall.parsedSafe<PlayerResponse>()

            // BƯỚC 3: Gọi AJAX GET_EPISODE (Log 4) - Đồng bộ hóa trạng thái tập phim
            if (filmId.isNotEmpty()) {
                val getEpi = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
                currentCookies = currentCookies + getEpi.cookies
            }

            // BƯỚC 4: Gọi AJAX ALL (Log 5) - CHỐT PHIÊN (Vô cùng quan trọng để link video có hiệu lực)
            if (episodeID.isNotEmpty()) {
                val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                )
                currentCookies = currentCookies + allCall.cookies
                // Đợi 1.5 giây để server cập nhật Token giải mã vào Session
                delay(1500) 
            }

            // BƯỚC 5: Xử lý kết quả trả về từ Player AJAX
            if (playerRes?.success == 1 && !playerRes.html.isNullOrBlank()) {
                val playerDoc = Jsoup.parse(playerRes.html)
                
                // Thuật toán: Luôn ưu tiên dùng WebView "vét" lại link sau khi đã hoàn tất AJAX ALL
                // Điều này giúp bắt được link m3u8 đã được server "mở khóa"
                app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)

                // Dự phòng quét link embed trực tiếp trong JSON HTML
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val vHref = server.attr("data-href")
                    if (server.attr("data-play") == "embed") {
                        loadExtractor(fixUrl(vHref), data, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }

    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"

    data class PlayerResponse(
        @JsonProperty("success") val success: Int? = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
