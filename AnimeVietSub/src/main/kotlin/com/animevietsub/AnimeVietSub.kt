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

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*chunks.*)""")
    )

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer"    to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
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
        // Vượt Cloudflare ngay từ bước Load để tránh lỗi "Sắp có"
        val res = app.get(url, headers = headers, interceptor = videoInterceptor)
        var doc = Jsoup.parse(res.text)
        
        var watchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { 
            if (it.startsWith("http")) it else "$mainUrl$it" 
        } ?: "${url.trimEnd('/')}/xem-phim.html"
        
        var watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
        var watchDoc = Jsoup.parse(watchRes.text)

        // Nếu vẫn không thấy tập phim, đợi 1 giây và thử tải lại trang xem phim (tránh Cloudflare kẹt)
        if (watchDoc.select("a.btn-episode").isEmpty()) {
            delay(1500)
            watchRes = app.get(watchUrl, headers = headers, cookies = res.cookies)
            watchDoc = Jsoup.parse(watchRes.text)
        }

        val episodes = watchDoc.select("a.btn-episode, a.episode-link").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val name = a.text().trim()
            if (href.contains("javascript") || name.isEmpty()) return@mapNotNull null
            newEpisode(href) {
                this.name = if (name.startsWith("Tập")) name else "Tập $name"
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
        val pageHtml = webRes.text
        val cookies = webRes.cookies

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: "0"
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""
        val hash = Jsoup.parse(pageHtml).selectFirst("a.btn-episode[href='$data']")?.attr("data-hash") 
                  ?: Jsoup.parse(pageHtml).selectFirst("a.btn-episode.active")?.attr("data-hash") ?: ""

        val ajaxHdr = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data,
            "Origin" to mainUrl
        )

        safeApiCall {
            // 1. Gọi ajax/player TRƯỚC
            val apiRes = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = cookies,
                data = mapOf("link" to hash, "play" to "api", "id" to filmId, "backuplinks" to "1")
            ).text

            // 2. Gọi ajax/all SAU CÙNG (Như bạn đã chỉ ra)
            if (episodeID.isNotEmpty()) {
                app.post("$mainUrl/ajax/all", headers = ajaxHdr, cookies = cookies, 
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID))
            }

            if (apiRes.contains("\"_fxStatus\":true")) {
                val fxHtml = Regex("""\"_fxHtml\":\"(.*?)\"""").find(apiRes)?.groupValues?.get(1)?.replace("\\/", "/")
                if (!fxHtml.isNullOrBlank()) {
                    val playerDoc = Jsoup.parse(fxHtml)
                    playerDoc.select("a").forEach { server ->
                        val videoHref = server.attr("data-href")
                        if (server.attr("data-play") == "embed") {
                            loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                        } else if (videoHref.isNotEmpty()) {
                            // Dùng WebView quét link cuối cùng
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
