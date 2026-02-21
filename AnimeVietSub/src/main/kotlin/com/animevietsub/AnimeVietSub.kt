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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // Interceptor cực mạnh: Bắt mọi thứ liên quan đến luồng video
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*chunks.*|.*playlist.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
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
        val doc = app.get(url, headers = headers).document
        val watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: "$url/xem-phim.html"
        val watchDoc = app.get(watchUrl, headers = headers).document

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
        // CHIẾN THUẬT MỚI: Dùng WebView để "nhấn" tập phim giả lập
        // Script này tìm nút tập phim đang chọn và nhấn vào đó để kích hoạt ajax/all và ajax/player một cách tự nhiên
        val jsCode = """
            (function() {
                var target = document.querySelector('a.btn-episode.active') || document.querySelector('a.btn-episode');
                if (target) {
                    target.click(); 
                }
            })();
        """.trimIndent()

        val webRes = app.get(
            data, 
            headers = headers, 
            interceptor = videoInterceptor
        )

        val capturedUrl = webRes.url
        
        // 1. Nếu WebView đã tóm được link ngay khi vừa nhấn (hoặc tự động load)
        if (capturedUrl != data && (capturedUrl.contains(".m3u8") || capturedUrl.contains(".mp4"))) {
            callback(newExtractorLink(name, "$name VIP", capturedUrl, 
                type = if (capturedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P1080.value
                this.referer = data
            })
        }

        // 2. Dự phòng: Nếu sau khi "click" vẫn chưa có link, quét JSON từ log mạng ngầm
        val pageHtml = webRes.text
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: "0"
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val hash = Jsoup.parse(pageHtml).selectFirst("a.btn-episode[href='$data']")?.attr("data-hash") ?: ""

        if (hash.isNotEmpty()) {
            safeApiCall {
                // Sử dụng Cookie đã được xác thực từ bước Click WebView trên
                val responseText = app.post("$mainUrl/ajax/player", 
                    headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                    cookies = webRes.cookies,
                    data = mapOf("link" to hash, "play" to "api", "id" to filmId)
                ).text

                val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                m3u8Regex.findAll(responseText).forEach { match ->
                    callback(newExtractorLink(name, "$name Server", match.value, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.referer = data
                    })
                }
            }
        }
        return true
    }
}
