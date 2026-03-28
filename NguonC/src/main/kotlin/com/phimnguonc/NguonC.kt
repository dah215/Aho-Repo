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
        val slug  = url.trim().trimEnd('/').substringAfterLast("/")
        val movie = try {
            app.get("$mainUrl/api/film/$slug", headers = commonHeaders)
               .parsedSafe<NguonCDetailResponse>()?.movie
        } catch (_: Exception) { null }
            ?: try {
            app.get("$mainUrl/api/film/$slug", headers = commonHeaders, interceptor = cfInterceptor)
               .parsedSafe<NguonCDetailResponse>()?.movie
        } catch (_: Exception) { null }
            ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val epMap = linkedMapOf<String, MutableList<String>>()
        movie.episodes?.forEachIndexed { idx, server ->
            val serverName = server.server_name ?: server.name
                ?: if (idx == 0) "Vietsub" else "Thuyết minh"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }
                        .add("$serverName::$embed")
                }
            }
        }
        if (epMap.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        val categories = movie.category ?: emptyMap()
        val dinhDang = categories.values.find { it.group?.name == "Định dạng" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val theLoai = categories.values.find { it.group?.name == "Thể loại" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "Năm" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Quốc gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        val beautifulPlot = buildBeautifulDescription(
            movie = movie,
            dinhDang = dinhDang,
            theLoai = theLoai,
            namPhatHanh = namPhatHanh,
            quocGia = quocGia
        )

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot      = beautifulPlot
            this.tags      = theLoai.split(", ").filter { it.isNotBlank() }
            this.year      = namPhatHanh.toIntOrNull()
        }
    }

    private fun buildBeautifulDescription(
        movie: NguonCMovie,
        dinhDang: String,
        theLoai: String,
        namPhatHanh: String,
        quocGia: String
    ): String {
        val originalName = movie.original_name ?: ""
        val description = movie.description ?: ""
        val currentEp = movie.current_episode ?: ""
        
        return buildString {
            // Tên tiếng Anh / Tên gốc
            if (originalName.isNotBlank() && originalName != (movie.name ?: "")) {
                append("<font color='#AAAAAA'><i>$originalName</i></font><br><br>") [cite: 15]
            }

            // Bảng thông tin chi tiết (Theo phong cách AnimeVietSub)
            append("<table cellpadding='2'>") [cite: 19]

            if (currentEp.isNotBlank()) {
                val statusColor = when {
                    currentEp.contains("hoàn tất", ignoreCase = true) -> "#2196F3"
                    currentEp.contains("tập", ignoreCase = true) -> "#4CAF50"
                    currentEp.contains("full", ignoreCase = true) -> "#9C27B0"
                    else -> "#FFFFFF"
                } [cite: 20]
                append("<tr><td>📺</td><td><b>Trạng thái:</b> <font color='$statusColor'>$currentEp</font></td></tr>") [cite: 21]
            }

            if (!movie.total_episodes.toString().isNullOrBlank() && movie.total_episodes != 0) {
                append("<tr><td>🎞</td><td><b>Số tập:</b> ${movie.total_episodes} tập</td></tr>") [cite: 24]
            }
            if (!movie.time.isNullOrBlank()) {
                append("<tr><td>⏱</td><td><b>Thời lượng:</b> ${movie.time}</td></tr>") [cite: 21]
            }
            if (!movie.quality.isNullOrBlank()) {
                append("<tr><td>🎬</td><td><b>Chất lượng:</b> <font color='#E91E63'>${movie.quality}</font></td></tr>") [cite: 21]
            }
            if (!quocGia.isBlank()) {
                append("<tr><td>🌍</td><td><b>Quốc gia:</b> $quocGia</td></tr>") [cite: 22]
            }
            if (!namPhatHanh.isBlank()) {
                append("<tr><td>📅</td><td><b>Năm:</b> $namPhatHanh</td></tr>") [cite: 26]
            }
            if (!movie.director.isNullOrBlank()) {
                append("<tr><td>🎥</td><td><b>Đạo diễn:</b> ${movie.director}</td></tr>") [cite: 23]
            }
            if (!movie.casts.isNullOrBlank()) {
                append("<tr><td>🎭</td><td><b>Diễn viên:</b> ${movie.casts}</td></tr>") [cite: 117]
            }
            if (!theLoai.isBlank()) {
                append("<tr><td>🏷</td><td><b>Thể loại:</b> $theLoai</td></tr>") [cite: 117]
            }

            append("</table>")

            // Nội dung phim
            if (description.isNotBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>") [cite: 24]
                append("<hr color='#333333' size='1'><br>") [cite: 25]
                append(description.trim()) [cite: 25]
            }

            // Footer
            append("<br><br><br>")
            append("<font color='#666666' size='2'><i>")
            append("Nguồn: $name") [cite: 25]
            append("</i></font>")
        }
    }

    private val activeServers = mutableListOf<NguonCProxyServer>()

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

    private fun rewriteM3U8(m3u8: String, proxyBase: String): String {
        return m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
                "$proxyBase/seg/${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
            } else line
        }
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val embedEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1])
            else if (parts.size == 1 && parts[0].startsWith("http")) Pair("Vietsub", parts[0])
            else null
        }
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

                    // FIX LỖI BIÊN DỊCH Ở ĐÂY: Sử dụng named arguments tương tự AnimeVietSub
                    callback(newExtractorLink(
                        source = "NguonC",
                        name   = serverName,
                        url    = "$proxyBase/stream.m3u8",
                        type   = ExtractorLinkType.M3U8 [cite: 80-81]
                    ) {
                        this.quality = Qualities.P1080.value [cite: 81]
                        this.headers = mapOf("User-Agent" to USER_AGENT) [cite: 82]
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
    data class StreamData(@JsonProperty("sUb") val sUb: String? = null, @JsonProperty("hD") val hD: String? = null)
    data class NguonCApiResponse(@JsonProperty("status") val status: String? = null, @JsonProperty("items") val items: List<NguonCApiItem>? = null)
    data class NguonCApiItem(@JsonProperty("name") val name: String?, @JsonProperty("slug") val slug: String?, @JsonProperty("poster_url") val poster_url: String?, @JsonProperty("thumb_url") val thumb_url: String?, @JsonProperty("current_episode") val current_episode: String?, @JsonProperty("quality") val quality: String?, @JsonProperty("language") val language: String?)
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val original_name: String?, @JsonProperty("description") val description: String?, @JsonProperty("poster_url") val poster_url: String?, @JsonProperty("thumb_url") val thumb_url: String?, @JsonProperty("total_episodes") val total_episodes: Int?, @JsonProperty("current_episode") val current_episode: String?, @JsonProperty("time") val time: String?, @JsonProperty("quality") val quality: String?, @JsonProperty("language") val language: String?, @JsonProperty("director") val director: String?, @JsonProperty("casts") val casts: String?, @JsonProperty("category") val category: Map<String, NguonCCategory>?, @JsonProperty("episodes") val episodes: List<NguonCServer>?)
    data class NguonCCategory(@JsonProperty("group") val group: NguonCGroup?, @JsonProperty("list") val list: List<NguonCGroupItem>?)
    data class NguonCGroup(@JsonProperty("name") val name: String?)
    data class NguonCGroupItem(@JsonProperty("name") val name: String?)
    data class NguonCServer(@JsonProperty("server_name") val server_name: String?, @JsonProperty("name") val name: String?, @JsonProperty("items") val items: List<NguonCEpisode>?, @JsonProperty("list") val list: List<NguonCEpisode>?)
    data class NguonCEpisode(@JsonProperty("name") val name: String?, @JsonProperty("embed") val embed: String?)
}
