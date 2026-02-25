package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        // Step 1: parse page lấy filmId, episodeId, fileHash từ HTML
        val pageText  = app.get(epUrl, headers = baseHeaders).text
        val filmId    = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageText)?.groupValues?.get(1) ?: return true
        val episodeId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageText)?.groupValues?.get(1)
                        ?: Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1)
                        ?: return true

        // DU server hash từ data-href của button
        val duHash = Regex("""data-play="api"[^>]+data-href="([^"]+)"""")
                     .find(pageText)?.groupValues?.get(1)
                     ?: Regex("""data-href="([^"]+)"[^>]+data-play="api"""")
                     .find(pageText)?.groupValues?.get(1)

        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language"  to "vi-VN,vi;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to epUrl
        )

        // Step 2: POST /ajax/player → lấy cookie
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to episodeId, "backup" to "1")
        )
        val cookieStr = playerResp.cookies.entries
                        .joinToString("; ") { "${it.key}=${it.value}" }
        if (cookieStr.isBlank()) return true

        val ajaxHdrWithCookie = ajaxHdr + mapOf("Cookie" to cookieStr) - "Content-Type"

        // Step 3: GET /ajax/get_episode → fileHash
        val rssText  = app.get(
            "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
            headers = ajaxHdrWithCookie
        ).text
        val fileHash = Regex("""<file>\s*([A-Za-z0-9_\-+/=]+)\s*</file>""")
                       .find(rssText)?.groupValues?.get(1)?.trim() ?: return true

        // Step 4: POST /ajax/all với đúng params: EpisodeMess=1&EpisodeID={id}
        val allHdr = ajaxHdr + mapOf("Cookie" to cookieStr)

        val allResp = app.post(
            "$mainUrl/ajax/all",
            headers = allHdr,
            data    = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeId)
        )
        if (allResp.text.contains("storage.googleapiscdn.com") || allResp.text.contains("#EXTM3U")) {
            return handleM3U8Response(allResp.text, epUrl, callback)
        }

        return true
    }

    private suspend fun handleM3U8Response(
        text:     String,
        epUrl:    String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val segHdr = mapOf("Referer" to "$mainUrl/", "User-Agent" to UA)

        // Nếu là M3U8 text
        if (text.contains("#EXTM3U")) {
            resolveM3U8AndCallback(text, segHdr, callback)
            return true
        }

        // Nếu là JSON chứa URLs
        val urls = Regex("""https://storage\.googleapiscdn\.com[^"'\s<>]+""")
                   .findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return true

        // Build M3U8 từ danh sách URLs
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n")
        urls.forEachIndexed { _, url ->
            sb.append("#EXTINF:10.0,\n$url\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        resolveM3U8AndCallback(sb.toString(), segHdr, callback)
        return true
    }

    private suspend fun resolveM3U8AndCallback(
        m3u8:     String,
        segHdr:   Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val lines    = m3u8.lines()
        val resolved = coroutineScope {
            lines.map { line ->
                async {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("https://storage.googleapiscdn.com") ||
                        trimmed.startsWith("https://storage.googleapis.com")) {
                        try {
                            app.get(trimmed, headers = segHdr, allowRedirects = false)
                               .headers["location"]?.trim() ?: trimmed
                        } catch (_: Exception) { trimmed }
                    } else line
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
