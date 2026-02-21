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

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    // Interceptor để bắt link từ JSON Sources hoặc WebSocket của Hydrax
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*iamcdn\.net.*|.*playhydrax.*|.*abysscdn.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // --- KHÔI PHỤC ĐẦY ĐỦ DANH MỤC PHIM ---
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi-cap-nhat/"        to "Anime Mới Cập Nhật",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title, .Title")?.text()?.trim() ?: ""
        val poster = el.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv, .list-anime li").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // BƯỚC 1: Lấy Token ban đầu và ID phim/tập
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        var currentCookies = webRes.cookies
        
        val filmId = Regex("""filmID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""episodeID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""

        safeApiCall {
            val ajaxHdr = headers + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")
            
            // BƯỚC 2: Gọi chuỗi AJAX xác thực theo Log bạn cung cấp
            // Player -> Get_Episode -> All (Để Unlock link trong JSON)
            val pCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies, data = mapOf("episodeId" to episodeID, "backup" to "1"))
            currentCookies = currentCookies + pCall.cookies
            
            app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
            
            val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID))
            currentCookies = currentCookies + allCall.cookies

            // BƯỚC 3: Xử lý dữ liệu JSON trả về từ Player
            val responseData = pCall.parsedSafe<PlayerResponse>()
            if (responseData?.success == 1 && responseData.html != null) {
                val playerDoc = Jsoup.parse(responseData.html)
                
                // Thuật toán: Nếu server trả về link api/embed, ta nạp vào WebView 
                // kèm theo bộ Cookie Token vừa lấy được từ bước /ajax/all
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val vHref = server.attr("data-href")
                    val isEmbed = server.attr("data-play") == "embed"
                    
                    if (isEmbed) {
                        val finalUrl = if (vHref.startsWith("http")) vHref else "$mainUrl/embed/$vHref"
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                }
                
                // BƯỚC 4: "Cưỡng bức" giải mã Hydrax/WebSocket
                // Nạp lại trang tập phim để WebView tự động kích hoạt script player nội bộ
                delay(1500)
                app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)
            }
        }
        return true
    }

    data class PlayerResponse(@JsonProperty("success") val success: Int? = 0, @JsonProperty("html") val html: String? = null)

    // Tận dụng code load/search từ các bản trước để đảm bảo ổn định
    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val watchUrl = res.document.selectFirst("a.btn-watch")?.attr("href")?.let { if(it.startsWith("http")) it else "$mainUrl$it" } ?: "$url/xem-phim.html"
        val watchDoc = app.get(watchUrl, headers = headers, cookies = res.cookies).document
        val episodes = watchDoc.select("a.btn-episode").map { a ->
            newEpisode(if(a.attr("href").startsWith("http")) a.attr("href") else "$mainUrl${a.attr("href")}") {
                this.name = "Tập " + a.text().trim()
            }
        }
        return newAnimeLoadResponse(watchDoc.selectFirst("h1.Title")?.text() ?: "Anime", url, TvType.Anime, true) {
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
