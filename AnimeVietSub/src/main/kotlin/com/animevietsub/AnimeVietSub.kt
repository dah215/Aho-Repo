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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    // Bộ lọc bắt link video thực tế (m3u8/mp4)
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*)""")
    )

    private val headers = mapOf(
        "User-Agent"      to UA,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer"         to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (TV/Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie/OVA)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Anime Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Anime Trọn Bộ"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title")?.text() ?: a.attr("title")
        val poster = el.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: url

        val watchDoc = app.get(watchUrl, headers = headers).document
        val title = watchDoc.selectFirst("h1.Title")?.text() ?: ""
        val poster = watchDoc.selectFirst(".Image img")?.attr("src")

        val episodes = watchDoc.select("ul.list-episode a").map { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            newEpisode(href) {
                this.name = a.text().trim()
                this.episode = Regex("""\d+""").find(this.name ?: "")?.value?.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime, true) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val pageHtml = res.text
        
        // 1. Tìm chuỗi link mã hóa cực kỳ quan trọng trong HTML
        // Thường nằm trong attribute của nút tập phim đang chọn
        val doc = Jsoup.parse(pageHtml)
        val currentEpi = doc.selectFirst("a.episode-link.active") ?: doc.selectFirst("a.episode-link")
        val encryptedLink = currentEpi?.attr("data-link") ?: ""

        // 2. Cấu hình Header chính xác theo file.txt bạn gửi
        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to data
        )

        safeApiCall {
            // 3. Gửi Request POST với link mã hóa và các tham số mới
            val response = app.post(
                "$mainUrl/ajax/player",
                headers = ajaxHdr,
                data = mapOf(
                    "link" to encryptedLink,
                    "play" to "api",
                    "id"   to "0",
                    "backuplinks" to "1"
                )
            ).parsed<PlayerResponse>()

            // 4. Xử lý dữ liệu trả về theo cấu trúc _fxStatus và _fxHtml
            if (response.status == true && !response.html.isNullOrBlank()) {
                val playerDoc = Jsoup.parseBodyFragment(response.html)
                
                // Quét tất cả các server có trong HTML trả về
                playerDoc.select("a[data-href]").forEach { server ->
                    val videoType = server.attr("data-play")
                    val videoHref = server.attr("data-href")

                    if (videoType == "embed") {
                        val finalEmbed = if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref"
                        loadExtractor(finalEmbed, data, subtitleCallback, callback)
                    } else {
                        // Dùng WebView để bắt link cuối cùng (Mạnh nhất)
                        val capturedUrl = app.get(data, headers = headers, interceptor = videoInterceptor).url
                        if (capturedUrl.contains(".m3u8") || capturedUrl.contains(".mp4")) {
                            callback(
                                ExtractorLink(
                                    name, "$name Player", capturedUrl, data,
                                    Qualities.P1080.value, capturedUrl.contains(".m3u8")
                                )
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    // Class map đúng theo cấu trúc JSON mới của web
    data class PlayerResponse(
        @JsonProperty("_fxStatus") val status: Boolean? = false,
        @JsonProperty("_fxHtml")   val html: String?   = null
    )
}
