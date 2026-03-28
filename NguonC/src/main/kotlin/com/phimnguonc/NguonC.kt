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
        
        val episodeCount = Regex("""(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
 
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
        val episodeCount = Regex("""(\d+)""").find(currentEp)?.groupValues?.get(1)?.toIntOrNull()
        
        val lang      = item.language ?: ""
        val hasDub    = lang.contains("Thuyết Minh", ignoreCase = true)
        val dubStatus = if (hasDub) EnumSet.of(DubStatus.Subbed, DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
        
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
            this.dubStatus = dubStatus
            this.episodes  = mutableMapOf(DubStatus.Subbed to (episodeCount ?: 0))
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
            val serverName = server.server_name ?: server.name ?: "Server ${idx + 1}"
            val items = server.items ?: server.list
            items?.forEach { ep ->
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    epMap.getOrPut(ep.name ?: "0") { mutableListOf() }
                        .add("$serverName::$embed")
                }
            }
        }

        val episodes = epMap.map { (epName, embeds) ->
            newEpisode(embeds.distinct().joinToString("|")) {
                this.name    = "Tập $epName"
                this.episode = epName.toIntOrNull()
            }
        }.sortedBy { it.episode ?: 0 }

        val categories = movie.category ?: emptyMap()
        val theLoai = categories.values.find { it.group?.name == "Thể loại" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val namPhatHanh = categories.values.find { it.group?.name == "Năm" }?.list?.map { it.name }?.joinToString(", ") ?: ""
        val quocGia = categories.values.find { it.group?.name == "Quốc gia" }?.list?.map { it.name }?.joinToString(", ") ?: ""

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot      = buildBeautifulDescription(movie, theLoai, namPhatHanh, quocGia)
            this.tags      = theLoai.split(", ").filter { it.isNotBlank() }
            this.year      = namPhatHanh.toIntOrNull()
        }
    }

    private fun buildBeautifulDescription(
        movie: NguonCMovie,
        theLoai: String,
        namPhatHanh: String,
        quocGia: String
    ): String {
        return buildString {
            // Tên gốc
            movie.original_name?.takeIf { it.isNotBlank() && it != movie.name }?.let {
                append("<font color='#AAAAAA'><i>$it</i></font><br><br>")
            }

            // Bảng thông tin (Label: Giá trị)
            fun addRow(label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank()) {
                    append("<b>$label:</b> <font color='$color'>$value</font><br>")
                }
            }

            val statusColor = if (movie.current_episode?.contains("hoàn tất", true) == true) "#4CAF50" else "#2196F3"
            
            addRow("📺 Trạng thái", movie.current_episode, statusColor)
            if (movie.total_episodes != null && movie.total_episodes != 0) 
                addRow("🎞 Số tập", "${movie.total_episodes} tập")
            
            addRow("⏱ Thời lượng", movie.time)
            addRow("🎬 Chất lượng", movie.quality, "#E91E63")
            addRow("🌍 Quốc gia", quocGia)
            addRow("📅 Năm", namPhatHanh)
            addRow("🎥 Đạo diễn", movie.director)
            addRow("🎭 Diễn viên", movie.casts)
            addRow("🏷 Thể loại", theLoai)

            // Nội dung phim (Phân cách rõ ràng)
            movie.description?.takeIf { it.isNotBlank() }?.let {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(it.trim())
            }
        }
    }

    private val activeServers = mutableListOf<NguonCProxyServer>()

    inner class NguonCProxyServer(private val segReferer: String) {
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
                
                if (path == "/stream.m3u8") {
                    val body = _m3u8.toByteArray(Charsets.UTF_8)
                    output.write(("HTTP/1.1 200 OK${crlf}Content-Type: application/vnd.apple.mpegurl${crlf}Content-Length: ${body.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                    output.write(body)
                } else if (path.startsWith("/seg/")) {
                    val segUrl = java.net.URLDecoder.decode(path.removePrefix("/seg/"), "UTF-8")
                    val conn = java.net.URL(segUrl).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Referer", segReferer)
                    val bytes = conn.inputStream.readBytes()
                    output.write(("HTTP/1.1 200 OK${crlf}Content-Length: ${bytes.size}${crlf}Access-Control-Allow-Origin: *${crlf}${crlf}").toByteArray())
                    output.write(bytes)
                }
                client.close()
            } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
        }

        @Volatile private var _m3u8: String = ""
        fun setM3U8(content: String) { _m3u8 = content }
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val embedEntries = data.split("|").mapNotNull { entry ->
            val parts = entry.trim().split("::", limit = 2)
            if (parts.size == 2) Pair(parts[0], parts[1]) else null
        }
        var linkFound = false

        coroutineScope {
            embedEntries.map { (serverName, embedUrl) ->
                async {
                    try {
                        val embedRes = app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/"))
                        val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(embedRes.text) ?: return@async
                        val jsonData = String(Base64.decode(obfMatch.groupValues[1], Base64.DEFAULT))
                        val streamData = AppUtils.parseJson<StreamData>(jsonData)
                        
                        val m3u8Path = streamData.sUb ?: streamData.hD ?: return@async
                        val embedDomain = Regex("""https?://[^/]+""").find(embedUrl)?.value ?: return@async
                        val m3u8Url = "$embedDomain/$m3u8Path.m3u8"
                        
                        val m3u8Raw = app.get(m3u8Url, headers = mapOf("Referer" to embedUrl)).text
                        val server = NguonCProxyServer(embedUrl).apply { start() }
                        activeServers.add(server)
                        
                        val rewritten = m3u8Raw.lines().joinToString("\n") { line ->
                            if (line.startsWith("http")) "http://127.0.0.1:${server.port}/seg/${URLEncoder.encode(line, "UTF-8")}" else line
                        }
                        server.setM3U8(rewritten)
                        
                        callback(newExtractorLink(
                            source = "NguonC",
                            name   = serverName,
                            url    = "http://127.0.0.1:${server.port}/stream.m3u8",
                            referer = embedUrl,
                            quality = Qualities.P1080.value,
                            isM3u8  = true
                        ))
                        linkFound = true
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }
        return linkFound
    }

    // ── Data Classes ──────────────────────────────────────────────────────────
    data class StreamData(@JsonProperty("sUb") val sUb: String?, @JsonProperty("hD") val hD: String?)
    data class NguonCApiResponse(@JsonProperty("items") val items: List<NguonCApiItem>?)
    data class NguonCApiItem(@JsonProperty("name") val name: String?, @JsonProperty("slug") val slug: String?, @JsonProperty("poster_url") val poster_url: String?, @JsonProperty("thumb_url") val thumb_url: String?, @JsonProperty("current_episode") val current_episode: String?, @JsonProperty("language") val language: String?)
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie?)
    data class NguonCMovie(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val original_name: String?, @JsonProperty("description") val description: String?, @JsonProperty("poster_url") val poster_url: String?, @JsonProperty("thumb_url") val thumb_url: String?, @JsonProperty("total_episodes") val total_episodes: Int?, @JsonProperty("current_episode") val current_episode: String?, @JsonProperty("time") val time: String?, @JsonProperty("quality") val quality: String?, @JsonProperty("director") val director: String?, @JsonProperty("casts") val casts: String?, @JsonProperty("category") val category: Map<String, NguonCCategory>?, @JsonProperty("episodes") val episodes: List<NguonCServer>?)
    data class NguonCCategory(@JsonProperty("group") val group: NguonCGroup?, @JsonProperty("list") val list: List<NguonCGroupItem>?)
    data class NguonCGroup(@JsonProperty("name") val name: String?)
    data class NguonCGroupItem(@JsonProperty("name") val name: String?)
    data class NguonCServer(@JsonProperty("server_name") val server_name: String?, @JsonProperty("name") val name: String?, @JsonProperty("items") val items: List<NguonCEpisode>?, @JsonProperty("list") val list: List<NguonCEpisode>?)
    data class NguonCEpisode(@JsonProperty("name") val name: String?, @JsonProperty("embed") val embed: String?)
}
