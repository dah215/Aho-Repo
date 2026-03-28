package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.EnumSet

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load() { registerMainAPI(PhimNguonCProvider()) }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com|.*streamc\.xyz|.*amass15\.top|.*hihihoho2\.top"""))

    private val commonHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private val API_PREFIX = "API::"

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le"                        to "Phim Lẻ",
        "danh-sach/phim-bo"                        to "Phim Bộ",
        "danh-sach/tv-shows"                       to "TV Shows"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a      = el.selectFirst("a") ?: return null
        val href   = a.attr("href")
        val title  = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val statusText = el.selectFirst("span.bg-green-300")?.text()?.trim() ?: ""
        val episodeCount: Int? = when {
            statusText.equals("FULL", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]ập\s*(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*\d+""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""^(\d+)$""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            this.episodes  = mutableMapOf(DubStatus.Subbed to (episodeCount ?: 0))
        }
    }

    private fun parseApiItem(item: NguonCApiItem): SearchResponse? {
        val slug   = item.slug ?: return null
        val title  = item.name ?: return null
        val href   = "$mainUrl/phim/$slug"
        val poster = item.poster_url ?: item.thumb_url
        val currentEp = item.current_episode ?: ""
        val episodeCount: Int? = when {
            currentEp.equals("FULL", ignoreCase = true)         -> null
            currentEp.startsWith("Hoàn tất", ignoreCase = true) -> null
            else ->
                Regex("""[Tt]ập\s*(\d+)""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\s*/\s*\d+""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
        }
        val lang      = item.language ?: ""
        val hasSub    = lang.contains("Vietsub",     ignoreCase = true)
        val hasDub    = lang.contains("Thuyết Minh", ignoreCase = true)
        val dubStatus = when {
            hasSub && hasDub -> EnumSet.of(DubStatus.Subbed, DubStatus.Dubbed)
            hasDub           -> EnumSet.of(DubStatus.Dubbed)
            else             -> EnumSet.of(DubStatus.Subbed)
        }
        val quality = when (item.quality?.uppercase()) {
            "FHD", "HD" -> SearchQuality.HD
            "CAM"       -> SearchQuality.Cam
            "SD"        -> SearchQuality.SD
            else        -> SearchQuality.HD
        }
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality   = quality
            this.dubStatus = dubStatus
            val ep = episodeCount
            this.episodes = mutableMapOf(DubStatus.Subbed to (ep ?: 0))
                .also { map -> if (hasDub) map[DubStatus.Dubbed] = ep ?: 0 }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.startsWith(API_PREFIX)) {
            val path  = request.data.removePrefix(API_PREFIX)
            val url   = "$mainUrl/$path?page=$page"
            val res   = app.get(url, headers = commonHeaders, interceptor = cfInterceptor)
                           .parsedSafe<NguonCApiResponse>()
            val items = res?.items?.mapNotNull { parseApiItem(it) } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } else {
            val url   = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
            val doc   = app.get(url, headers = commonHeaders).document
            val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = commonHeaders).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/")

        // Strategy 1: Try JSON API (may be 403 blocked by Cloudflare)
        val movie = try {
            app.get("$mainUrl/api/film/$slug", headers = commonHeaders)
               .parsedSafe<NguonCDetailResponse>()?.movie
        } catch (_: Exception) { null }

        if (movie != null) {
            return buildResponseFromMovie(movie, url, slug)
        }

        // Strategy 2: Parse HTML page via WebView (cfInterceptor handles Cloudflare)
        val doc = try {
            app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        } catch (_: Exception) { null }

        if (doc != null) {
            val title  = doc.selectFirst("h1")?.text()?.trim()
                         ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                         ?: slug.replace("-", " ")

            // Poster: og:image is most reliable for nguonc
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                         ?.takeIf { it.isNotBlank() && it.startsWith("http") }
                         ?: doc.selectFirst("meta[name=thumbnail]")?.attr("content")
                         ?: doc.selectFirst("img.poster-film, img[itemprop=image]")?.attr("src")
                            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                         ?: doc.select("img[src]").firstOrNull { el ->
                                val src = el.attr("src")
                                src.contains("poster") || src.contains("thumb") ||
                                src.contains("cdn") || src.contains("upload")
                            }?.attr("src")
                            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

            val plot   = doc.selectFirst("meta[property=og:description]")?.attr("content")
                         ?: doc.selectFirst("meta[name=description]")?.attr("content")

            // Parse embed URLs from episode buttons - these contain the real embed links
            val epMap = linkedMapOf<String, MutableList<String>>()

            // Nguonc episode buttons: <a class="ep-item" data-id="X" href="...">
            doc.select("a.ep-item, a[data-id][href*=xem], a.episode-item, #list-server a")
               .forEach { a ->
                   val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                   val num  = a.attr("data-id").ifBlank {
                       Regex("""(\d+)$""").find(a.text().trim())?.groupValues?.get(1)
                       ?: Regex("""tap-(\d+)""").find(href)?.groupValues?.get(1)
                   } ?: return@forEach
                   if (href.isNotBlank())
                       epMap.getOrPut(num) { mutableListOf() }.add("Vietsub::$href")
               }

            // Fallback: look for any episode-like links
            if (epMap.isEmpty()) {
                doc.select("a[href*='/xem/'], a[href*='/tap-']").forEach { a ->
                    val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    val num  = Regex("""tap-(\d+)|/(\d+)$""").find(href)
                                   ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                               ?: Regex("""\d+""").find(a.text())?.value
                               ?: return@forEach
                    epMap.getOrPut(num) { mutableListOf() }.add("Vietsub::$href")
                }
            }

            // Last resort: use the page URL itself as single episode
            if (epMap.isEmpty()) {
                epMap["1"] = mutableListOf("Vietsub::$url")
            }

            val episodes = epMap.map { (epName, embeds) ->
                newEpisode(embeds.distinct().joinToString("|")) {
                    this.name    = "Tập $epName"
                    this.episode = epName.toIntOrNull()
                }
            }.sortedBy { it.episode ?: 0 }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
            }
        }

        // Strategy 3: Minimal response
        return newTvSeriesLoadResponse(slug.replace("-", " "), url, TvType.TvSeries,
            listOf(newEpisode("Vietsub::$url") { this.name = "Tập 1"; this.episode = 1 })
        ) { this.plot = null }
    }

    private suspend fun buildResponseFromMovie(movie: NguonCMovie, url: String, slug: String): LoadResponse {
        val title    = movie.name ?: slug
        val poster   = movie.poster_url ?: movie.thumb_url
        val altTitle = (movie.original_name ?: movie.english_name ?: "").let {
            if (it.isNotBlank() && it != title) it else ""
        }
        val status   = movie.status ?: movie.current_episode ?: ""
        val quality  = movie.quality ?: "HD"
        val language = movie.language ?: ""
        val plotRaw  = movie.description ?: movie.content ?: ""

        val description = buildString {
            if (altTitle.isNotBlank())
                append("<font color='#AAAAAA'><i>$altTitle</i></font><br><br>")
            append("<table cellpadding='2'>")
            if (status.isNotBlank()) {
                val sc = when {
                    status.contains("chiếu", ignoreCase = true) -> "#4CAF50"
                    status.contains("hoàn", ignoreCase = true)  -> "#2196F3"
                    else -> "#FFFFFF"
                }
                append("<tr><td>📺</td><td><b>Trạng thái:</b> <font color='$sc'>$status</font></td></tr>")
            }
            if (quality.isNotBlank())
                append("<tr><td>🎬</td><td><b>Chất lượng:</b> <font color='#E91E63'>$quality</font></td></tr>")
            if (language.isNotBlank())
                append("<tr><td>🔊</td><td><b>Ngôn ngữ:</b> $language</td></tr>")
            append("</table>")
            if (plotRaw.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(plotRaw)
            }
        }

        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val sName = server.server_name ?: server.name
                ?: if (idx == 0) "Vietsub" else "Thuyết minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank())
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }.add("$sName::$embed")
            }
        }
        if (epMap.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    inner class NguonCProxyServer(
        private val m3u8Content: String,
        private val segReferer:  String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        private val threadPool = java.util.concurrent.Executors.newCachedThreadPool()

        fun start() {
            serverSocket = java.net.ServerSocket(0)
            Thread {
                val ss = serverSocket ?: return@Thread
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        threadPool.execute { handleClient(client) }
                    } catch (_: Exception) { break }
                }
            }.also { it.isDaemon = true }.start()
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                val input  = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                val requestLine = input.readLine() ?: return
                while (true) { if ((input.readLine() ?: "").isBlank()) break }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val crlf = "\r\n"
                when {
                    path == "/stream.m3u8" -> {
                        val body = getM3U8().toByteArray(Charsets.UTF_8)
                        output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                        output.write(body)
                    }
                    path.startsWith("/seg/") -> {
                        val segUrl = java.net.URLDecoder.decode(path.removePrefix("/seg/"), "UTF-8")
                        try {
                            val conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 15000
                            conn.readTimeout    = 30000
                            conn.setRequestProperty("User-Agent", USER_AGENT)
                            conn.setRequestProperty("Referer", segReferer)
                            conn.connect()
                            val bytes = conn.inputStream.readBytes()
                            conn.disconnect()
                            output.write(("HTTP/1.1 200 OK${crlf}Content-Type: video/mp2t${crlf}Content-Length: ${bytes.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                            output.write(bytes)
                        } catch (_: Exception) {
                            output.write("HTTP/1.1 502 Bad Gateway${crlf}${crlf}".toByteArray())
                        }
                    }
                    else -> output.write("HTTP/1.1 404 Not Found${crlf}${crlf}".toByteArray())
                }
                output.flush()
                client.close()
            } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
        }

        @Volatile private var _m3u8: String = ""
        fun setM3U8(content: String) { _m3u8 = content }
        private fun getM3U8(): String = _m3u8

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
            try { threadPool.shutdownNow() } catch (_: Exception) {}
        }
    }

    private val activeServers = mutableListOf<NguonCProxyServer>()

    private fun rewriteM3U8(m3u8: String, proxyBase: String): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else line
        }
    }

    data class StreamData(
        @JsonProperty("sUb") val sUb: String? = null,
        @JsonProperty("hD")  val hD:  String? = null
    )

    // Resolve watch page URL → find embed URL inside it
    private suspend fun resolveEmbedUrl(watchUrl: String): String? {
        return try {
            val doc = app.get(watchUrl, headers = commonHeaders, interceptor = cfInterceptor).document
            // Embed iframe src
            doc.selectFirst("iframe[src*=streamc], iframe[src*=embed], iframe[src*=amass]")
               ?.attr("src")
               ?.let { if (it.startsWith("http")) it else "https:$it" }
            // Or data-embed attribute
            ?: doc.selectFirst("[data-embed], [data-src*=streamc]")
                  ?.attr("data-embed")?.ifBlank { null }
                  ?.let { if (it.startsWith("http")) it else "https:$it" }
        } catch (_: Exception) { null }
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        // Parse entries - each entry is "ServerName::URL"
        // URL can be: embed URL (streamc.xyz) OR watch page URL (phim.nguonc.com)
        val rawEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }

        // Resolve watch page URLs to embed URLs if needed
        val embedEntries = rawEntries.map { (serverName, url) ->
            if (url.contains("streamc") || url.contains("embed") || url.contains("amass")) {
                Pair(serverName, url) // already embed URL
            } else {
                // It's a watch page - need to extract embed URL
                val embedUrl = resolveEmbedUrl(url)
                Pair(serverName, embedUrl ?: url)
            }
        }.filter { it.second.isNotBlank() }

        var linkFound = false

        coroutineScope {
            embedEntries.map { (serverName, embedUrl) ->
                async {
                    val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: return@async
                    try {
                        val embedRes = app.get(
                            embedUrl,
                            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
                        )
                        val html    = embedRes.text
                        val cookies = embedRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                        val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(html)
                            ?: return@async

                        val jsonData   = String(Base64.decode(obfMatch.groupValues[1], Base64.DEFAULT))
                        val streamData = AppUtils.parseJson<StreamData>(jsonData)

                        val fetchHdr = mapOf(
                            "User-Agent"      to USER_AGENT,
                            "Referer"         to embedUrl,
                            "Origin"          to embedDomain,
                            "Cookie"          to cookies,
                            "Accept"          to "*/*",
                            "Accept-Language" to "vi-VN,vi;q=0.9"
                        )

                        suspend fun serveStream(m3u8Url: String, serverName: String) {
                            try {
                                val m3u8Raw = app.get(m3u8Url, headers = fetchHdr).text
                                if (!m3u8Raw.contains("#EXTM3U")) return

                                val server = NguonCProxyServer("", embedUrl)
                                server.start()
                                activeServers.add(server)

                                val proxyBase     = "http://127.0.0.1:${server.port}"
                                val rewrittenM3U8 = rewriteM3U8(m3u8Raw, proxyBase)
                                server.setM3U8(rewrittenM3U8)

                                callback(newExtractorLink(
                                    source = "NguonC",
                                    name   = serverName,
                                    url    = "$proxyBase/stream.m3u8",
                                    type   = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                })
                                linkFound = true
                            } catch (_: Exception) {}
                        }

                        val m3u8Path = streamData.sUb ?: streamData.hD
                        if (!m3u8Path.isNullOrBlank()) {
                            serveStream("$embedDomain/$m3u8Path.m3u8", serverName)
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return linkFound
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class NguonCApiResponse(
        @JsonProperty("status") val status: String?             = null,
        @JsonProperty("items")  val items:  List<NguonCApiItem>? = null
    )
    data class NguonCApiItem(
        @JsonProperty("name")            val name:             String? = null,
        @JsonProperty("slug")            val slug:             String? = null,
        @JsonProperty("original_name")   val original_name:   String? = null,
        @JsonProperty("thumb_url")       val thumb_url:       String? = null,
        @JsonProperty("poster_url")      val poster_url:      String? = null,
        @JsonProperty("total_episodes")  val total_episodes:  Int?    = null,
        @JsonProperty("current_episode") val current_episode: String? = null,
        @JsonProperty("quality")         val quality:         String? = null,
        @JsonProperty("language")        val language:        String? = null
    )
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(
        @JsonProperty("name")            val name:            String?             = null,
        @JsonProperty("original_name")   val original_name:   String?             = null,
        @JsonProperty("english_name")     val english_name:     String?             = null,
        @JsonProperty("description")     val description:     String?             = null,
        @JsonProperty("content")          val content:          String?             = null,
        @JsonProperty("thumb_url")       val thumb_url:       String?             = null,
        @JsonProperty("poster_url")      val poster_url:      String?             = null,
        @JsonProperty("status")          val status:          String?             = null,
        @JsonProperty("current_episode") val current_episode: String?             = null,
        @JsonProperty("total_episodes")  val total_episodes:  Int?                = null,
        @JsonProperty("time")            val time:            String?             = null,
        @JsonProperty("duration")          val duration:         String?             = null,
        @JsonProperty("quality")          val quality:          String?             = null,
        @JsonProperty("language")         val language:         String?             = null,
        @JsonProperty("director")         val director:         String?             = null,
        @JsonProperty("directors")        val directors:        String?             = null,
        @JsonProperty("actor")            val actor:            String?             = null,
        @JsonProperty("actors")            val actors:            String?             = null,
        @JsonProperty("cast")             val cast:             String?             = null,
        @JsonProperty("category")         val category:         String?             = null,
        @JsonProperty("categories")       val categories:       String?             = null,
        @JsonProperty("genre")            val genre:            String?             = null,
        @JsonProperty("genres")            val genres:            String?             = null,
        @JsonProperty("year")             val year:             String?             = null,
        @JsonProperty("publish_year")     val publish_year:     String?             = null,
        @JsonProperty("country")          val country:          String?             = null,
        @JsonProperty("countries")         val countries:         String?             = null,
        @JsonProperty("episodes")         val episodes:        List<NguonCServer>? = null
    )
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String?              = null,
        @JsonProperty("name")        val name:         String?              = null,
        @JsonProperty("items")       val items:        List<NguonCEpisode>? = null,
        @JsonProperty("list")        val list:          List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name")  val name:  String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8")  val m3u8:  String? = null
    )
}
