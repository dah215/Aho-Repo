package com.animevietsub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
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

    // Interceptor cực mạnh: Quét tất cả từ m3u8, mp4 đến các domain vệ tinh Hydrax/Abyss
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*iamcdn\.net.*|.*playhydrax.*|.*abysscdn.*|.*blob:.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "com.android.chrome"
    )

    // --- KHÔI PHỤC KỸ LƯỠNG DANH MỤC PHIM ---
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi-cap-nhat/"        to "Anime Mới Cập Nhật",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/the-loai/hentai/"           to "Hentai"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv, .list-anime li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            newAnimeSearchResponse(el.selectFirst("h2.Title, .Title")?.text() ?: "", fixUrl(a.attr("href")), TvType.Anime) {
                this.posterUrl = el.selectFirst("img")?.attr("src")
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val watchUrl = res.document.selectFirst("a.btn-watch")?.attr("href")?.let { fixUrl(it) } ?: "${url.trimEnd('/')}/xem-phim.html"
        val watchDoc = app.get(watchUrl, headers = headers, cookies = res.cookies).document

        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            newEpisode(fixUrl(a.attr("href"))) { this.name = "Tập " + a.text().trim() }
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
        // BƯỚC 1: Nạp WebView để lấy Token động (tokene26139...)
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        var currentCookies = webRes.cookies
        
        val filmId = Regex("""filmID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""episodeID\s*=\s*parseInt\('(\d+)'\)""").find(webRes.text)?.groupValues?.get(1) ?: ""

        safeApiCall {
            val ajaxHdr = headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded")
            
            // BƯỚC 2: Thực hiện chuỗi xác thực "Mở khóa luồng"
            val pCall = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = currentCookies, data = mapOf("episodeId" to episodeID, "backup" to "1"))
            currentCookies = currentCookies + pCall.cookies
            
            app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = currentCookies)
            
            val allCall = app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = currentCookies, data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID))
            currentCookies = currentCookies + allCall.cookies

            // BƯỚC 3: "Đánh lừa" WebSocket - Nạp lại WebView với toàn bộ Cookie đã xác thực
            // Đợi 2 giây để các Script playhydraxs.min.js kịp kích hoạt WebSocket
            delay(2000)
            app.get(data, headers = headers, cookies = currentCookies, interceptor = videoInterceptor)
            
            // Xử lý server API dự phòng
            val playerRes = pCall.parsedSafe<PlayerResponse>()
            if (playerRes?.success == 1) {
                Jsoup.parse(playerRes.html ?: "").select("a.btn3dsv").forEach { server ->
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
    data class PlayerResponse(@JsonProperty("success") val success: Int? = 0, @JsonProperty("html") val html: String? = null)
}
