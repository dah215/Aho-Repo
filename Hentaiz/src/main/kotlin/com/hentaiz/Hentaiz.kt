package com.hentaiz

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaiZProvider())
    }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.lol"
    override var name = "HentaiZ"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    
    private val imageBaseUrl = "https://storage.haiten.org"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/browse" to "Mới Cập Nhật",
        "/browse?animationType=THREE_D" to "Hentai 3D",
        "/browse?contentRating=UNCENSORED" to "Không Che",
        "/browse?contentRating=CENSORED" to "Có Che"
    )

    // --- PHẦN PARSING CƠ BẢN (GIỮ NGUYÊN VÌ ĐÃ CHẠY TỐT) ---
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    private fun decodeUnicode(input: String): String {
        var res = input
        val regex = "\\\\u([0-9a-fA-F]{4})".toRegex()
        regex.findAll(input).forEach { match ->
            val charCode = match.groupValues[1].toInt(16).toChar()
            res = res.replace(match.value, charCode.toString())
        }
        return res
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) "$mainUrl${request.data}&page=$page" else "$mainUrl${request.data}?page=$page"
        val res = app.get(url, headers = headers)
        val html = res.text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        val items = regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val html = app.get(url, headers = headers).text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        return regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val html = res.text
        val title = Regex("""title:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "HentaiZ Video"
        val posterPath = Regex("""posterImage:\{filePath:"([^"]+)"""").find(html)?.groupValues?.get(1)
        val rawDesc = Regex("""description:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val desc = decodeUnicode(rawDesc).replace(Regex("<[^>]*>"), "").trim()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = if (posterPath != null) "$imageBaseUrl$posterPath" else null
            this.plot = desc
        }
    }

    // --- PHẦN QUAN TRỌNG: WEBVIEW INTERCEPTOR ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text

        // 1. Tìm URL của Sonar Player
        val sonarRegex = """https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""".toRegex()
        val sonarMatch = sonarRegex.find(html) ?: sonarRegex.find(res.document.html())
        val sonarUrl = sonarMatch?.value

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // 2. Khởi chạy WebView để bắt Blob/M3U8
            val m3u8Content = captureM3u8WithWebView(fixedSonarUrl)

            if (!m3u8Content.isNullOrBlank()) {
                // 3. Tạo Data URI (Thay thế cho Local Server)
                // Mã hóa nội dung M3U8 thành Base64 để ExoPlayer đọc trực tiếp
                val base64M3u8 = Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)
                val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64M3u8"

                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (WebView)",
                        url = dataUri, // Link là nội dung file luôn
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedSonarUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
        return true
    }

    // Class Interface để nhận dữ liệu từ JS về Kotlin
    class JsInterface(val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun passM3U8(content: String) {
            onResult(content)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureM3u8WithWebView(url: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            
            mainHandler.post {
                val webView = WebView(ac()) // ac() là Activity Context của Cloudstream
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = headers["User-Agent"]
                }

                // Inject JS để bắt XHR (AJAX) Request
                val jsInterceptor = """
                    (function() {
                        var origOpen = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            this.addEventListener('load', function() {
                                // Kiểm tra nếu response là M3U8
                                if (this.responseText.includes('#EXTM3U')) {
                                    var m3u8 = this.responseText;
                                    var baseUrl = url.substring(0, url.lastIndexOf('/') + 1);
                                    
                                    // QUAN TRỌNG: Convert đường dẫn Segment từ tương đối -> tuyệt đối
                                    // Để đảm bảo ExoPlayer tải được segment từ server gốc
                                    var lines = m3u8.split('\n');
                                    var newM3u8 = '';
                                    for (var i = 0; i < lines.length; i++) {
                                        var line = lines[i].trim();
                                        if (line && !line.startsWith('#')) {
                                            if (!line.startsWith('http')) {
                                                line = baseUrl + line;
                                            }
                                        }
                                        newM3u8 += line + '\n';
                                    }
                                    
                                    // Gửi về Kotlin
                                    window.HentaiZInterface.passM3U8(newM3u8);
                                }
                            });
                            origOpen.apply(this, arguments);
                        };
                    })();
                """.trimIndent()

                var hasResumed = false
                
                // Interface nhận kết quả
                webView.addJavascriptInterface(JsInterface { content ->
                    if (!hasResumed) {
                        hasResumed = true
                        mainHandler.post {
                            webView.destroy()
                            continuation.resume(content)
                        }
                    }
                }, "HentaiZInterface")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Inject đoạn script bắt link ngay khi trang load xong
                        view?.evaluateJavascript(jsInterceptor, null)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to "$mainUrl/"))
                
                // Timeout an toàn: 15 giây
                mainHandler.postDelayed({
                    if (!hasResumed) {
                        hasResumed = true
                        webView.destroy()
                        continuation.resume(null)
                    }
                }, 15000)
            }
        }
    }
}
