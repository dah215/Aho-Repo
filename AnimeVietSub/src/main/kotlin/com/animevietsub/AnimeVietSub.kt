package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
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

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent"      to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer"         to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình TQ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/the-loai/hanh-dong/"        to "Action",
        "$mainUrl/the-loai/tinh-cam/"         to "Romance",
        "$mainUrl/the-loai/phep-thuat/"       to "Fantasy",
        "$mainUrl/the-loai/kinh-di/"          to "Horror",
        "$mainUrl/the-loai/hai-huoc/"         to "Comedy",
        "$mainUrl/the-loai/shounen/"          to "Shounen"
    )

    private fun pageUrl(base: String, page: Int) =
        if (page == 1) "${base.trimEnd('/')}/"
        else "${base.trimEnd('/')}/trang-$page.html"

    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a       = article.selectFirst("a[href]") ?: return null
        val href    = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title   = article.selectFirst("h2.Title")?.text()?.trim()
                      ?.takeIf { it.isNotBlank() } ?: return null
        val poster  = article.selectFirst("div.Image img, figure img")?.attr("src")
                      ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum  = article.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get(pageUrl(request.data, page), headers = baseHeaders).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/",
            headers = baseHeaders
        ).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = baseHeaders).document
        val title    = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster   = watchDoc.selectFirst("div.Image figure img")?.attr("src")
                       ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot     = watchDoc.selectFirst("div.Description")?.text()?.trim()
        val year     = watchDoc.selectFirst("p.Info .Date a, p.Info .Date")
                       ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags     = watchDoc.select("p.Genre a").map { it.text().trim() }.filter { it.isNotBlank() }

        val seen     = mutableSetOf<String>()
        val episodes = watchDoc.select("#list-server .list-episode a.episode-link")
            .mapNotNull { a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                if (href.isBlank() || !seen.add(href)) return@mapNotNull null
                val num  = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                newEpisode(href) {
                    this.name    = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
                    this.episode = num
                }
            }.sortedBy { it.episode ?: 0 }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(
                title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html"
            ) { this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        // Strategy: WebViewResolver bắt video0.html request
        // video0.html -> 302 -> lh3.googleusercontent.com/TOKEN=d (segment thật)
        // Từ video0.html URL ta biết được chunk_id và segment_token
        // -> tự build M3U8 với tất cả segments

        val resolver = WebViewResolver(
            // Bắt request đến storage.googleapiscdn.com (videoN.html)
            Regex("""storage\.googleapiscdn\.com/chunks/[a-f0-9]+/original/[^/]+/video0\.html""")
        )

        val video0Req = app.get(
            epUrl,
            interceptor = resolver,
            headers     = baseHeaders,
            timeout     = 60
        )

        val video0Url = video0Req.url
        if (!video0Url.contains("storage.googleapiscdn.com")) return true

        // Từ video0.html URL, extract chunk_id và token
        // Pattern: https://storage.googleapiscdn.com/chunks/{CHUNK_ID}/original/{TOKEN}/video0.html
        val match = Regex(
            """storage\.googleapiscdn\.com/chunks/([a-f0-9]+)/original/([^/]+)/video0\.html"""
        ).find(video0Url) ?: return true

        val chunkId = match.groupValues[1]
        val token0  = match.groupValues[2]

        val segHdr = mapOf(
            "Referer"    to "$mainUrl/",
            "User-Agent" to UA
        )

        // Resolve video0.html -> lh3 để verify và lấy duration pattern
        val seg0Resp = app.get(video0Url, headers = segHdr, allowRedirects = false)
        val seg0Lh3  = seg0Resp.headers["location"]?.trim() ?: return true

        // Probe tổng số segments bằng cách check video1, video2... cho đến 404
        // Mỗi segment ~10s, thường 100-200 segments cho 1 tập 20-40 phút
        // Ta cần fetch M3U8 master để biết tất cả token

        // Thử fetch master.m3u8 trực tiếp
        val masterUrl = "https://storage.googleapiscdn.com/chunks/$chunkId/original/$token0/master.m3u8"
        val masterResp = try {
            app.get(masterUrl, headers = segHdr + mapOf("Origin" to mainUrl))
        } catch (_: Exception) { null }

        if (masterResp != null && masterResp.text.contains("#EXTM3U")) {
            // Master M3U8 tồn tại - parse và resolve
            val masterText = masterResp.text
            buildM3U8AndCallback(masterText, masterUrl, segHdr, callback)
            return true
        }

        // Không có master.m3u8 - thử fetch playlist.m3u8
        val playlistUrl = "https://storage.googleapiscdn.com/chunks/$chunkId/original/$token0/playlist.m3u8"
        val playlistResp = try {
            app.get(playlistUrl, headers = segHdr + mapOf("Origin" to mainUrl))
        } catch (_: Exception) { null }

        if (playlistResp != null && playlistResp.text.contains("#EXTM3U")) {
            buildM3U8AndCallback(playlistResp.text, playlistUrl, segHdr, callback)
            return true
        }

        // Fallback: resolve video0 thành công -> feed trực tiếp lh3 URL
        // ExoPlayer có thể play single segment
        callback(newExtractorLink(
            source = name,
            name   = "$name - DU",
            url    = seg0Lh3,
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })

        return true
    }

    private suspend fun buildM3U8AndCallback(
        m3u8Text:  String,
        baseUrl:   String,
        segHdr:    Map<String, String>,
        callback:  (ExtractorLink) -> Unit
    ) {
        val lines    = m3u8Text.lines()
        val resolved = kotlinx.coroutines.coroutineScope {
            lines.map { line ->
                kotlinx.coroutines.async {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("https://storage.googleapiscdn.com") ||
                        trimmed.startsWith("https://storage.googleapis.com") -> {
                            try {
                                app.get(trimmed, headers = segHdr, allowRedirects = false)
                                   .headers["location"]?.trim() ?: trimmed
                            } catch (_: Exception) { trimmed }
                        }
                        trimmed.startsWith("video") && trimmed.endsWith(".html") -> {
                            // Relative URL
                            val absUrl = baseUrl.substringBeforeLast("/") + "/" + trimmed
                            try {
                                app.get(absUrl, headers = segHdr, allowRedirects = false)
                                   .headers["location"]?.trim() ?: trimmed
                            } catch (_: Exception) { trimmed }
                        }
                        else -> line
                    }
                }
            }.awaitAll()
        }

        val sep      = "\n"
        val newM3u8  = resolved.joinToString(sep)
        val cacheDir = AcraApplication.context?.cacheDir ?: return
        cacheDir.mkdirs()
        val file = java.io.File(cacheDir, "avs_${System.currentTimeMillis()}.m3u8")
        file.writeText(newM3u8, Charsets.UTF_8)

        callback(newExtractorLink(
            source = name, name = "$name - DU",
            url    = "file://${file.absolutePath}",
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })
    }
}
