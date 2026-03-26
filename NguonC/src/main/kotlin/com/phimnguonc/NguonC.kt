package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URLDecoder
import android.util.Base64
import java.util.EnumSet
import java.net.ServerSocket
import java.net.Socket
import java.io.*
import kotlinx.coroutines.*

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    companion object {
        var proxyPort = 0
    }

    override fun load() {
        registerMainAPI(PhimNguonCProvider())
        
        // Khởi động Proxy Server tại đây
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverSocket = ServerSocket(0)
                proxyPort = serverSocket.localPort
                while (isActive) {
                    val client = serverSocket.accept()
                    handleProxyRequest(client)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private suspend fun handleProxyRequest(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return@withContext
                val url = requestLine.split(" ")[1].removePrefix("/proxy?url=")
                val targetUrl = URLDecoder.decode(url, "UTF-8")

                val response = Requests().get(targetUrl, headers = mapOf("Referer" to "https://embed12.streamc.xyz/", "User-Agent" to "Mozilla/5.0"))
                
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                out.write(response.body.bytes())
                out.flush()
                client.close()
            } catch (e: Exception) { client.close() }
        }
    }
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
    private val commonHeaders = mapOf("User-Agent" to USER_AGENT)
    private val API_PREFIX = "API::"

    override val mainPage = mainPageOf(
        "${API_PREFIX}api/films/phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/phim-bo" to "Phim Bộ"
    )

    // --- CÁC HÀM PARSE ---
    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href")
        val title = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        return newAnimeSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.startsWith(API_PREFIX)) "$mainUrl/${request.data.removePrefix(API_PREFIX)}?page=$page" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}", headers = commonHeaders, interceptor = cfInterceptor).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/")
        val res = app.get("$mainUrl/api/film/$slug", headers = commonHeaders, interceptor = cfInterceptor).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu")
        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            server.items?.forEach { ep ->
                episodes.add(newEpisode(ep.embed ?: "") {
                    this.name = "Tập ${ep.name}"
                    this.episode = ep.name?.toIntOrNull()
                })
            }
        }
        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url
            this.plot = movie.description
        }
    }

    data class StreamData(@JsonProperty("sUb") val sUb: String? = null, @JsonProperty("hD") val hD: String? = null)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val embedRes = app.get(data, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT), interceptor = cfInterceptor)
            val obfMatch = Regex("""data-obf\s*=\s*["']([A-Za-z0-9+/=]+)["']""").find(embedRes.text)
            if (obfMatch != null) {
                val jsonData = String(Base64.decode(obfMatch.groupValues[1], Base64.DEFAULT))
                val streamData = AppUtils.parseJson<StreamData>(jsonData)
                val embedDomain = Regex("""https?://[^/]+""").find(data)?.value ?: ""

                fun addProxyLink(name: String, path: String?) {
                    if (path.isNullOrBlank()) return
                    val targetUrl = if (path.startsWith("http")) path else "$embedDomain/$path.m3u8"
                    val localUrl = "http://127.0.0.1:${PhimNguonCPlugin.proxyPort}/proxy?url=${URLEncoder.encode(targetUrl, "UTF-8")}"
                    callback(newExtractorLink("NguonC", name, localUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                    })
                }
                addProxyLink("Vietsub", streamData.sUb)
                addProxyLink("Thuyết minh", streamData.hD)
                return true
            }
        } catch (e: Exception) { e.printStackTrace() }
        return false
    }

    // Data Classes
    data class NguonCApiResponse(@JsonProperty("items") val items: List<NguonCApiItem>? = null)
    data class NguonCApiItem(@JsonProperty("name") val name: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("poster_url") val poster_url: String? = null, @JsonProperty("thumb_url") val thumb_url: String? = null, @JsonProperty("current_episode") val current_episode: String? = null, @JsonProperty("language") val language: String? = null)
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(@JsonProperty("name") val name: String? = null, @JsonProperty("description") val description: String? = null, @JsonProperty("poster_url") val poster_url: String? = null, @JsonProperty("thumb_url") val thumb_url: String? = null, @JsonProperty("episodes") val episodes: List<NguonCServer>? = null)
    data class NguonCServer(@JsonProperty("items") val items: List<NguonCEpisode>? = null, @JsonProperty("list") val list: List<NguonCEpisode>? = null)
    data class NguonCEpisode(@JsonProperty("name") val name: String? = null, @JsonProperty("embed") val embed: String? = null)
}
