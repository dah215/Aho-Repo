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

    // Interceptor mạnh tay: Bắt cả m3u8 và các chuỗi link đặc trưng của Hydrax/Cdn
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*iamcdn\.net.*|.*playhydrax.*|.*blob:.*)""")
    )

    private val headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")

    // --- KHÔI PHỤC KỸ LƯỠNG DANH MỤC ---
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi-cap-nhat/"        to "Anime Mới Cập Nhật",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/the-loai/hentai/"           to "Hentai"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/"
        val doc = app.get(url, headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { fixUrl(it) } ?: "${url.trimEnd('/')}/xem-phim.html"
        
        val watchDoc = app.get(watchUrl, headers = headers).document
        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            newEpisode(fixUrl(a.attr("href"))) {
                this.name = "Tập " + a.text().trim()
            }
        }

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
        // BƯỚC QUAN TRỌNG: Gọi tuần tự 3 API như Log bạn gửi để Unlock Session
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        val episodeID = Regex("""episodeID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""
        val filmId = Regex("""filmID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""

        safeApiCall {
            val ajaxHdr = headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded")
            
            // Theo trình tự Log: Player -> Get_Episode -> All
            val pRes = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = webRes.cookies, data = mapOf("episodeId" to episodeID, "backup" to "1")).parsedSafe<PlayerResponse>()
            app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = webRes.cookies)
            app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = webRes.cookies, data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID))

            // MẠNH TAY: Nếu là server Hydrax hoặc API, ta ép WebView đợi lâu hơn (15s) để WebSocket kết nối thành công
            if (pRes?.success == 1) {
                val playerDoc = Jsoup.parse(pRes.html ?: "")
                // Duyệt qua các server để kích hoạt
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val vHref = server.attr("data-href")
                    if (server.attr("data-play") == "embed") {
                        loadExtractor(fixUrl(vHref), data, subtitleCallback, callback)
                    }
                }
                
                // Cú chốt: Ép WebView nạp lại trang tập phim để "hứng" link từ WebSocket/Blob
                delay(2000)
                app.get(data, headers = headers, cookies = webRes.cookies, interceptor = videoInterceptor)
            }
        }
        return true
    }

    data class PlayerResponse(@JsonProperty("success") val success: Int? = 0, @JsonProperty("html") val html: String? = null)
    private fun fixUrl(url: String): String = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
}
