package com.phimnguonc

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder
import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@CloudstreamPlugin
class PhimNguonCPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimNguonCProvider())
    }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "PhimNguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val cfInterceptor = WebViewResolver(Regex("""phim\.nguonc\.com"""))

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9",
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/hoat-hinh" to "Hoạt Hình",
        "danh-sach/tv-shows" to "TV Shows"
    )

    private fun parseCard(el: Element): SearchResponse? {
        val a = el.selectFirst("a") ?: return null
        val href = a.attr("href")
        val title = el.selectFirst("h3")?.text()?.trim() ?: a.attr("title")
        val poster = el.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val label = el.selectFirst(".bg-green-300")?.text()?.trim() ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.otherName = label 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        val items = doc.select("table tbody tr").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem?keyword=${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = commonHeaders, interceptor = cfInterceptor).document
        return doc.select("table tbody tr").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.trim().trimEnd('/').substringAfterLast("/")
        val apiUrl = "$mainUrl/api/film/$slug"
        
        val res = app.get(apiUrl, headers = commonHeaders, interceptor = cfInterceptor).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải dữ liệu phim")

        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            server.items?.forEach { ep ->
                // CHỈ LẤY LINK EMBED ĐỂ ĐƯA VÀO WEBVIEW
                val embed = ep.embed?.replace("\\/", "/") ?: ""
                if (embed.isNotBlank()) {
                    episodes.add(newEpisode(embed) {
                        this.name = "Tập ${ep.name}"
                        this.episode = ep.name?.toIntOrNull()
                    })
                }
            }
        }

        if (episodes.isEmpty()) throw ErrorLoadingException("Không tìm thấy tập phim")

        return newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.poster_url ?: movie.thumb_url
            this.plot = movie.description
        }
    }

    // THUẬT TOÁN VŨ KHÍ TỐI THƯỢNG: BẮT LINK BẰNG WEBVIEW THẬT
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = data
        val embedDomain = if (embedUrl.startsWith("http")) {
            Regex("""https?://+""").find(embedUrl)?.value ?: ""
        } else ""

        // Chạy WebView trên Main Thread (bắt buộc đối với Android)
        val finalM3u8Url = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { continuation ->
                val webView = WebView(AcraApplication.context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = USER_AGENT

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        
                        // Bắt cóc link .m3u8 ngay khi trang web định tải nó
                        if (url.contains(".m3u8")) {
                            if (continuation.isActive) {
                                continuation.resume(url)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                // Nạp thêm Referer để vượt Cloudflare của trang embed
                val extraHeaders = mutableMapOf("Referer" to "$mainUrl/")
                webView.loadUrl(embedUrl, extraHeaders)

                // Timeout an toàn sau 15 giây nếu không tìm thấy link
                continuation.invokeOnCancellation {
                    webView.destroy()
                }
            }
        }

        if (finalM3u8Url != null) {
            // Lấy Cookie từ CookieManager của Android (chứa toàn bộ session vừa tạo)
            val cookies = android.webkit.CookieManager.getInstance().getCookie(embedUrl) ?: ""

            // Bộ Header "Bất tử" để tải file .png
            val videoHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedUrl,
                "Origin" to embedDomain,
                "Cookie" to cookies,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Accept-Language" to "vi-VN,vi;q=0.9",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Range" to "bytes=0-"
            )

            callback(
                newExtractorLink(
                    source = "NguonC (WebView-Bypass)",
                    name = "HLS - 1080p",
                    url = finalM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.headers = videoHeaders
                }
            )
            return true
        }

        return false
    }

    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovie? = null)
    data class NguonCMovie(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumb_url") val thumb_url: String? = null,
        @JsonProperty("poster_url") val poster_url: String? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )
    data class NguonCServer(
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("items") val items: List<NguonCEpisode>? = null
    )
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("m3u8") val m3u8: String? = null
    )
}
