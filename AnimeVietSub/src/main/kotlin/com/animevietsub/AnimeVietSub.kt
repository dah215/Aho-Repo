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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    // Mở rộng bộ lọc để bắt các server dự phòng (FB, Google, VIP)
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*fvs.*|.*fbcdn.*|.*cache.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "com.android.chrome"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (TV/Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie/OVA)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = el.selectFirst("h2.Title") ?: el.selectFirst(".Title")
        val poster = el.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title?.text() ?: "Anime", href, TvType.Anime) { this.posterUrl = poster }
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
        val watchUrl = res.document.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: "$url/xem-phim.html"
        val watchDoc = app.get(watchUrl, headers = headers, cookies = res.cookies).document

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
        // Bước 1: Vượt Cloudflare bằng WebView để lấy Cookie "sạch"
        val webViewRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        val cookies = webViewRes.cookies
        val pageHtml = webViewRes.text

        // Bước 2: Trích xuất ID và Hash từ trang xem phim
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: "0"
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val hash = Jsoup.parse(pageHtml).selectFirst("a.btn-episode[href='$data']")?.attr("data-hash") 
                  ?: Jsoup.parse(pageHtml).selectFirst("a.btn-episode.active")?.attr("data-hash") ?: ""

        if (hash.isEmpty()) return false

        val ajaxHdr = mapOf(
            "User-Agent" to UA,
            "Accept"     to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer"    to data,
            "Origin"     to mainUrl
        )

        safeApiCall {
            // Bước 3: Gửi AJAX ALL để kích hoạt phiên (Dựa trên dữ liệu bạn cung cấp)
            if (episodeID.isNotEmpty()) {
                app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = cookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID))
                delay(800) // Tăng delay lên một chút để server kịp ghi nhận session
            }

            // Bước 4: Gọi AJAX PLAYER để lấy link server
            val apiResponse = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = cookies,
                data = mapOf("link" to hash, "play" to "api", "id" to filmId, "backuplinks" to "1")
            ).text

            // Bước 5: Phân tích link từ kết quả trả về
            if (apiResponse.contains("\"_fxStatus\":true")) {
                val fxHtml = Regex("""\"_fxHtml\":\"(.*?)\"""").find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!fxHtml.isNullOrBlank()) {
                    val playerDoc = Jsoup.parse(fxHtml)
                    playerDoc.select("a").forEach { server ->
                        val videoHref = server.attr("data-href")
                        if (server.attr("data-play") == "embed") {
                            loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                        } else if (videoHref.isNotEmpty()) {
                            // Gọi WebView lần cuối để bốc link video trực tiếp từ server API
                            val finalUrl = app.get(data, headers = headers, cookies = cookies, interceptor = videoInterceptor).url
                            if (finalUrl != data && (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4"))) {
                                callback(newExtractorLink(name, "Server VIP", finalUrl, 
                                    type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.referer = data
                                })
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
