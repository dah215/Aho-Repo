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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.ServerSocket
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

    // User-Agent giả lập Mobile để Sonar CDN trả về player HTML5
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/browse" to "Mới Cập Nhật",
        "/browse?animationType=THREE_D" to "Hentai 3D",
        "/browse?contentRating=UNCENSORED" to "Không Che",
        "/browse?contentRating=CENSORED" to "Có Che"
    )

    // --- PHẦN PARSING CƠ BẢN ---
    
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

    // ─── KỸ THUẬT CAO: WEBVIEW + BLOB INTERCEPTOR + LOCAL SERVER ───

    // 1. Đoạn mã JS dùng để tiêm vào trang web
    // Nó sẽ ghi đè hàm URL.createObjectURL để bắt lấy nội dung Blob
    private val blobInterceptor = """
        ;(function(){
            console.log("HentaiZ: Injector started");
            var _oc = URL.createObjectURL;
            URL.createObjectURL = function(b) {
                var u = _oc.apply(this, arguments);
                try {
                    // Kiểm tra nếu blob là mpegurl (m3u8)
                    if (b && (b.type === 'application/vnd.apple.mpegurl' || b.type === 'application/x-mpegurl' || (b.size > 0))) {
                        var r = new FileReader();
                        r.onload = function(e) {
                            try {
                                var content = e.target.result;
                                if (content.indexOf('#EXTM3U') !== -1) {
                                    // Gửi nội dung về Kotlin qua Interface
                                    Android.onM3U8(content, window.location.href);
                                }
                            } catch(x) {}
                        };
                        r.readAsText(b);
                    }
                } catch(x) {}
                return u;
            };
        })();
    """.trimIndent()

    // 2. Interface để nhận dữ liệu từ JS
    inner class M3U8Bridge(val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun onM3U8(content: String, pageUrl: String) {
            // Xử lý đường dẫn tương đối thành tuyệt đối ngay tại đây
            // Sonar CDN thường dùng link tương đối cho segment
            val baseUrl = pageUrl.substringBeforeLast("/") + "/"
            val fixedContent = content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) {
                    "$baseUrl$line"
                } else {
                    line
                }
            }
            onResult(fixedContent)
        }
    }

    // 3. Local HTTP Server (Chạy trên 127.0.0.1)
    // Dùng để phục vụ file M3U8 cho ExoPlayer, bypass lỗi file://
    private var localServer: LocalM3U8Server? = null

    inner class LocalM3U8Server(private val m3u8Content: String) {
        private var serverSocket: ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = ServerSocket(0)
            Thread {
                try {
                    val ss = serverSocket ?: return@Thread
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            // Đọc request (để clear buffer)
                            client.getInputStream().bufferedReader().readLine()
                            
                            val body = m3u8Content.toByteArray(Charsets.UTF_8)
                            val crlf = "\r\n"
                            val response = "HTTP/1.1 200 OK$crlf" +
                                    "Content-Type: application/vnd.apple.mpegurl$crlf" +
                                    "Content-Length: ${body.size}$crlf" +
                                    "Access-Control-Allow-Origin: *$crlf" +
                                    "Connection: close$crlf" +
                                    crlf
                            
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().write(body)
                            client.getOutputStream().flush()
                            client.close()
                        } catch (e: Exception) {
                            // Ignore connection errors
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.also { it.isDaemon = true }.start()
        }

        fun stop() {
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    // 4. Hàm khởi chạy WebView và bắt link
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8FromWebView(url: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) { // Timeout 30s
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val webView = WebView(ctx)
                    var hasResumed = false

                    // Cài đặt WebView
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        // Cho phép nội dung hỗn hợp (quan trọng cho CDN)
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Bridge nhận kết quả
                    val bridge = M3U8Bridge { content ->
                        if (!hasResumed) {
                            hasResumed = true
                            webView.stopLoading()
                            webView.destroy()
                            if (cont.isActive) cont.resume(content)
                        }
                    }
                    webView.addJavascriptInterface(bridge, "Android")

                    webView.webViewClient = object : WebViewClient() {
                        // Intercept request để tiêm JS vào đầu file
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            
                            // HentaiZ không dùng avs.watch.js, nhưng ta sẽ tiêm vào bất kỳ file JS nào
                            // hoặc tiêm vào chính trang HTML nếu cần.
                            // Ở đây ta dùng onPageFinished để tiêm cho chắc chắn với mọi loại player.
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Tiêm Blob Interceptor ngay khi trang tải xong
                            view?.evaluateJavascript(blobInterceptor, null)
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to "$mainUrl/"))

                    // Hủy nếu quá lâu
                    cont.invokeOnCancellation {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }
    }

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
            
            // 2. Dùng WebView để bắt nội dung M3U8 (Blob)
            val m3u8Content = getM3U8FromWebView(fixedSonarUrl)

            if (m3u8Content != null) {
                // 3. Khởi động Local Server để phục vụ file M3U8 này
                localServer?.stop()
                val server = LocalM3U8Server(m3u8Content)
                server.start()
                localServer = server

                // 4. Trả về link localhost
                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (Localhost)",
                        url = "http://127.0.0.1:${server.port}/video.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Quan trọng: Giữ nguyên Referer để tải Segment
                        this.referer = fixedSonarUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }

        return true
    }
}
