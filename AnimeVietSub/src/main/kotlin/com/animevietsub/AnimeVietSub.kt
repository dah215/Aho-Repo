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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    // Chỉ bắt các link video thực sự, bỏ qua các link rác
    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*fbcdn\.net.*|.*storage\.googleapis.*)""")
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
        val title = el.selectFirst("h2.Title")?.text() ?: a.attr("title")
        val poster = el.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
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
        val res = app.get(url, headers = headers)
        val doc = res.document
        val watchUrl = if (url.contains("xem-phim.html")) url 
                      else doc.selectFirst("a.btn-watch")?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" } 
                      ?: "${url.trimEnd('/')}/xem-phim.html"
        
        val watchDoc = app.get(watchUrl, headers = headers).document
        
        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (href.contains("javascript")) return@mapNotNull null
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
        // TỐI ƯU: Sử dụng app.get với Interceptor để bắt link nhanh
        // Chế độ này sẽ chạy ngầm và tóm link m3u8 ngay khi nó vừa xuất hiện
        val response = app.get(
            data, 
            headers = headers, 
            interceptor = videoInterceptor,
            timeout = 30 // Giới hạn 30s để tránh load vô tận
        )

        val capturedUrl = response.url
        
        // 1. Kiểm tra nếu WebView đã bắt được link trực tiếp (nhanh nhất)
        if (capturedUrl != data && (capturedUrl.contains(".m3u8") || capturedUrl.contains(".mp4"))) {
            callback(newExtractorLink(name, "$name VIP", capturedUrl, 
                type = if (capturedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P1080.value
                this.referer = data
            })
            // Không return ngay, để tiếp tục quét các server dự phòng bên dưới
        }

        // 2. Dự phòng: Quét API bằng Cookie đã có từ WebView
        val doc = Jsoup.parse(response.text)
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(response.text)?.groupValues?.get(1) ?: "0"
        val currentEpi = doc.selectFirst("a.btn-episode[href='$data']") ?: doc.selectFirst("a.btn-episode.active")
        val hash = currentEpi?.attr("data-hash") ?: ""

        if (hash.isNotBlank()) {
            val ajaxHdr = mapOf(
                "User-Agent" to UA,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data,
                "Origin" to mainUrl
            )

            safeApiCall {
                val apiRes = app.post("$mainUrl/ajax/player", headers = ajaxHdr, 
                    cookies = response.cookies, // Quan trọng: Dùng cookie từ WebView
                    data = mapOf("link" to hash, "play" to "api", "id" to filmId, "backuplinks" to "1")
                ).text

                // Tìm tất cả các link m3u8 ẩn trong JSON trả về
                val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
                m3u8Regex.findAll(apiRes).forEach { match ->
                    callback(newExtractorLink(name, "$name Player", match.value, type = ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.referer = data
                    })
                }

                // Xử lý các link Embed (như Facebook, Youtube, v.v.)
                val fxHtml = Regex("""\"_fxHtml\":\"(.*?)\"""").find(apiRes)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!fxHtml.isNullOrBlank()) {
                    Jsoup.parse(fxHtml).select("a[data-href]").forEach { server ->
                        val videoHref = server.attr("data-href")
                        if (server.attr("data-play") == "embed") {
                            loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
        return true
    }
}
