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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val videoInterceptor = WebViewResolver(
        Regex("""(.*\.m3u8.*|.*\.mp4.*|.*googlevideo.*|.*storage\.googleapis.*|.*chunks.*)""")
    )

    private val headers = mapOf(
        "User-Agent"      to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer"         to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ (TV/Series)",
        "$mainUrl/anime-le/"                  to "Anime Lẻ (Movie/OVA)",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a       = article.selectFirst("a[href]") ?: return null
        val href    = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title   = article.selectFirst("h2.Title")?.text()?.trim() ?: a.attr("title").trim()
        val poster  = article.selectFirst("div.Image img, figure img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${request.data.trimEnd('/')}/" else "${request.data.trimEnd('/')}/trang-$page.html"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", headers = headers).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = headers).document
        val title    = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: "Anime"

        val episodes = watchDoc.select("a.btn-episode").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            newEpisode(href) {
                this.name    = "Tập ${a.text().trim()}"
                this.episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime, true) {
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
        // Sử dụng WebView để vượt Cloudflare và lấy Cookie sạch
        val webRes = app.get(data, headers = headers, interceptor = videoInterceptor)
        val pageHtml = webRes.text
        val cookies = webRes.cookies

        // Lấy ID phim và ID tập phim từ HTML
        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: "0"
        val episodeID = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""").find(pageHtml)?.groupValues?.get(1) ?: ""

        val ajaxHdr = headers + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer"      to data,
            "Origin"       to mainUrl
        )

        safeApiCall {
            // ── BƯỚC MỚI: Gọi ajax/all để kích hoạt phiên làm việc (Theo dữ liệu bạn tìm được) ──
            if (episodeID.isNotBlank()) {
                app.post("$mainUrl/ajax/all", 
                    headers = ajaxHdr, 
                    cookies = cookies,
                    data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeID)
                )
                delay(500) // Nghỉ ngắn để server cập nhật trạng thái
            }

            // ── BƯỚC TIẾP THEO: Lấy danh sách server như bình thường ──
            val currentEpi = Jsoup.parse(pageHtml).selectFirst("a.btn-episode[href='$data']") ?: Jsoup.parse(pageHtml).selectFirst("a.btn-episode.active")
            val hash = currentEpi?.attr("data-hash") ?: ""

            val resp = app.post("$mainUrl/ajax/player", headers = ajaxHdr, cookies = cookies,
                data = mapOf("link" to hash, "play" to "api", "id" to filmId, "backuplinks" to "1")
            ).parsed<PlayerResponse>()

            if (resp.status == true && !resp.html.isNullOrBlank()) {
                val playerDoc = Jsoup.parseBodyFragment(resp.html)
                playerDoc.select("a").forEach { server ->
                    val videoHref = server.attr("data-href")
                    val type = server.attr("data-play")
                    
                    if (type == "embed") {
                        loadExtractor(if (videoHref.startsWith("http")) videoHref else "$mainUrl/embed/$videoHref", data, subtitleCallback, callback)
                    } else if (!videoHref.isNullOrBlank()) {
                        // Vét link video cuối cùng qua WebView
                        val finalUrl = app.get(data, headers = headers, cookies = cookies, interceptor = videoInterceptor).url
                        if (finalUrl != data && (finalUrl.contains(".m3u8") || finalUrl.contains(".mp4"))) {
                            callback(newExtractorLink(name, "$name Player", finalUrl, 
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
        return true
    }

    data class PlayerResponse(
        @JsonProperty("_fxStatus") val status: Boolean? = false,
        @JsonProperty("_fxHtml")   val html: String?   = null
    )
}
