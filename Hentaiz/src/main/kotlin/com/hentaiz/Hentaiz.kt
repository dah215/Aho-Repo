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

    // ─── PHẦN XỬ LÝ MẠNH: INTERCEPTOR + LOCAL SERVER ───

    // JS Interceptor: Bắt cả XHR, Fetch và Blob
    private val jsInterceptor = """
        <script>
        (function() {
            console.log("HentaiZ: Interceptor Active");
            
            function checkAndSend(content, url) {
                if (content && (content.includes('#EXTM3U') || url.includes('.m3u8'))) {
                    Android.onM3U8(content, url);
                }
            }

            // 1. Hook XMLHttpRequest
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    checkAndSend(this.responseText, url);
                });
                origOpen.apply(this, arguments);
            };

            // 2. Hook Fetch
            var origFetch = window.fetch;
            window.fetch = async (...args) => {
                const response = await origFetch(...args);
                const clone = response.clone();
                clone.text().then(text => {
                    checkAndSend(text, response.url);
                });
                return response;
            };
            
            // 3. Hook Blob (Dự phòng)
            var _oc = URL.createObjectURL;
            URL.createObjectURL = function(b) {
                var u = _oc.apply(this, arguments);
                if (b && (b.type === 'application/vnd.apple.mpegurl' || b.type === 'application/x-mpegurl')) {
                    var r = new FileReader();
                    r.onload = function(e) { checkAndSend(e.target.result, window.location.href); };
                    r.readAsText(b);
                }
                return u;
            };
        })();
        </script>
    """.trimIndent()

    inner class M3U8Bridge(val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun onM3U8(content: String, url: String) {
            // Xử lý link segment: Biến link tương đối thành tuyệt đối
            val baseUrl = url.substringBeforeLast("/") + "/"
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

    // Local Server để serve M3U8 cho ExoPlayer
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
                            client.getInputStream().bufferedReader().readLine() // Đọc request
                            
                            val body = m3u8Content.toByteArray(Charsets.UTF_8)
                            val response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Connection: close\r\n\r\n"
                            
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().write(body)
                            client.getOutputStream().flush()
                            client.close()
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {}
            }.also { it.isDaemon = true }.start()
        }

        fun stop() {
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureM3U8(url: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000L) { // Timeout 20s
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val webView = WebView(ctx)
                    var hasResumed = false

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

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
                        // Can thiệp vào request để chèn JS ngay đầu trang
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            // Chỉ can thiệp vào trang HTML chính của Sonar
                            if (reqUrl.contains("play.sonar-cdn.com/watch")) {
                                try {
                                    // Tự tải nội dung trang web
                                    val response = app.get(reqUrl, headers = mapOf("Referer" to "$mainUrl/"))
                                    val html = response.text
                                    
                                    // Chèn JS Interceptor vào ngay sau thẻ <head> hoặc <body>
                                    val injectedHtml = if (html.contains("<head>")) {
                                        html.replaceFirst("<head>", "<head>$jsInterceptor")
                                    } else {
                                        jsInterceptor + html
                                    }

                                    return WebResourceResponse(
                                        "text/html",
                                        "utf-8",
                                        ByteArrayInputStream(injectedHtml.toByteArray())
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webView.loadUrl(url, mapOf("Referer" to "$mainUrl/"))

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

        val sonarRegex = """https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""".toRegex()
        val sonarMatch = sonarRegex.find(html) ?: sonarRegex.find(res.document.html())
        val sonarUrl = sonarMatch?.value

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // 1. Bắt nội dung M3U8 bằng WebView Interceptor
            val m3u8Content = captureM3U8(fixedSonarUrl)

            if (m3u8Content != null) {
                // 2. Khởi động Local Server
                localServer?.stop()
                val server = LocalM3U8Server(m3u8Content)
                server.start()
                localServer = server

                // 3. Trả về link localhost
                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (Localhost)",
                        url = "http://127.0.0.1:${server.port}/video.m3u8",
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
}
