package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.coroutines.resume

@CloudstreamPlugin
class HeoVLPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HeoVLProvider())
    }
}

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.moe"
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- SCRIPT CHIẾM QUYỀN (INJECTED SCRIPT) ---
    private val masterScript = """
        (function() {
            console.log("Master Key: Hijacking started");
            
            // 1. Vô hiệu hóa các bẫy bảo mật
            window.console.clear = function() {};
            var _constructor = window.Function.prototype.constructor;
            window.Function.prototype.constructor = function(s) {
                if (s === "debugger") return function() {};
                return _constructor.apply(this, arguments);
            };

            // 2. Giả lập môi trường sạch (Bypass Bot Detection)
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            window.adsbygoogle = { loaded: true, push: function(){} };

            function sendToAndroid(content, sourceUrl) {
                if (content && content.includes("#EXTM3U")) {
                    Android.onM3U8Captured(content, sourceUrl);
                }
            }

            // 3. Hook URL.createObjectURL (Bắt Blob M3U8)
            var _createObjectURL = URL.createObjectURL;
            URL.createObjectURL = function(obj) {
                var url = _createObjectURL.apply(this, arguments);
                if (obj instanceof Blob && (obj.type.includes('mpegurl') || obj.size > 100)) {
                    var reader = new FileReader();
                    reader.onload = function() { sendToAndroid(reader.result, window.location.href); };
                    reader.readAsText(obj);
                }
                return url;
            };

            // 4. Hook Fetch & XHR
            var _fetch = window.fetch;
            window.fetch = function() {
                return _fetch.apply(this, arguments).then(res => {
                    if (res.url.includes(".m3u8")) {
                        res.clone().text().then(t => sendToAndroid(t, res.url));
                    }
                    return res;
                });
            };

            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes(".m3u8") || (this.responseText && this.responseText.includes("#EXTM3U"))) {
                        sendToAndroid(this.responseText, url);
                    }
                });
                _open.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // --- SERVER NỘI BỘ (LOCAL PROXY) ---
    private var localServer: LocalM3U8Server? = null
    inner class LocalM3U8Server(private val content: String) {
        private var socket: ServerSocket? = null
        val port: Int get() = socket?.localPort ?: 0
        fun start() {
            socket = ServerSocket(0)
            Thread {
                try {
                    val s = socket ?: return@Thread
                    while (!s.isClosed) {
                        val client = s.accept()
                        client.getInputStream().bufferedReader().readLine()
                        val body = content.toByteArray()
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().write(header.toByteArray())
                        client.getOutputStream().write(body)
                        client.getOutputStream().flush()
                        client.close()
                    }
                } catch (e: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
        fun stop() { try { socket?.close() } catch (e: Exception) {} }
    }

    inner class AndroidBridge(val onCaptured: (String, String) -> Unit) {
        @JavascriptInterface
        fun onM3U8Captured(content: String, url: String) { onCaptured(content, url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun masterCapture(targetUrl: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var isFinished = false

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(AndroidBridge { content, url ->
                        if (!isFinished) {
                            isFinished = true
                            // Biến link segment tương đối thành tuyệt đối
                            val baseUrl = url.substringBeforeLast("/") + "/"
                            val fixed = content.lines().joinToString("\n") { 
                                if (it.isNotBlank() && !it.startsWith("#") && !it.startsWith("http")) "$baseUrl$it" else it 
                            }
                            wv.post { wv.destroy() }
                            if (cont.isActive) cont.resume(fixed)
                        }
                    }, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            // Chặn trang Iframe để tiêm mã Master
                            if (reqUrl.contains("streamqq") || reqUrl.contains("spexliu") || reqUrl.contains("flimora")) {
                                try {
                                    val conn = URL(reqUrl).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("Referer", "$mainUrl/")
                                    conn.setRequestProperty("User-Agent", UA)
                                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                                    
                                    // Tiêm script vào ngay đầu trang
                                    val injectedHtml = "<html><head><script>$masterScript</script></head><body>$html</body></html>"
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injectedHtml.toByteArray()))
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
                    cont.invokeOnCancellation { wv.destroy() }
                }
            }
        }
    }

    // --- PHẦN GIAO DIỆN (GIỮ NGUYÊN) ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val items = org.jsoup.Jsoup.parse(html).select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val sonarUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu|flimora)[^"']*)["']""").find(html)?.groupValues?.get(1)

        if (!sonarUrl.isNullOrBlank()) {
            val m3u8Content = masterCapture(fixUrl(sonarUrl))
            if (m3u8Content != null) {
                localServer?.stop()
                val server = LocalM3U8Server(m3u8Content)
                server.start()
                localServer = server

                callback(newExtractorLink("HeoVL VIP", "Server VIP (Master)", "http://127.0.0.1:${server.port}/video.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = sonarUrl
                    this.quality = Qualities.P1080.value
                })
            }
        }
        return true
    }
}
