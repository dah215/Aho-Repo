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

    // Khớp User-Agent chính xác từ log của bạn
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*chunks.*)""")
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
        val watchUrl = res.document.selectFirst("a.btn-watch")?.attr("href")?.let { 
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
        // 1. Dùng WebView lấy Session và Token quan trọng
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        val pageHtml = webRes.text
        val cookies = webRes.cookies

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to data
        )

        safeApiCall {
            // Bước 1: Gọi PLAYER (Theo Log 1 & 2)
            val playerRes = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = cookies,
                data = mapOf("episodeId" to episodeID, "backup" to "1")
            ).parsedSafe<PlayerResponse>()

            // Bước 2: Gọi GET_EPISODE (Theo Log 4)
            app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeID", headers = ajaxHdr, cookies = cookies)

            // Bước 3: Gọi ALL (Bước xác thực cuối cùng - Theo Log 5)
            app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = cookies, 
                data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
            )

            // Bước 4: Xử lý kết quả từ Player
            if (playerRes?.success == 1 && !playerRes.html.isNullOrBlank()) {
                val playerDoc = Jsoup.parse(playerRes.html!!)
                playerDoc.select("a.btn3dsv").forEach { server ->
                    val type = server.attr("data-play")
                    val videoHref = server.attr("data-href")

                    if (type == "embed") {
                        loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                    } else {
                        // Kích hoạt WebView lần cuối để "vét" link m3u8 sau khi đã qua bước /ajax/all
                        val finalRes = app.get(data, headers = headers, cookies = cookies, interceptor = videoInterceptor)
                        if (finalRes.url != data && (finalRes.url.contains(".m3u8") || finalRes.url.contains(".mp4"))) {
                            callback(newExtractorLink(name, "Server VIP", finalRes.url, 
                                type = if (finalRes.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = data
                            })
                        }
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
