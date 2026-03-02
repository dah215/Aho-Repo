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

    private val imageBaseUrl = "https://storage.haiten.org"
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/" to "Mới Cập Nhật",
        "/categories/viet-nam" to "Việt Nam",
        "/categories/vietsub" to "Vietsub",
        "/categories/trung-quoc" to "Trung Quốc",
        "/categories/au-my" to "Âu - Mỹ",
        "/categories/khong-che" to "Không Che",
        "/categories/jav-hd" to "JAV HD",
        "/categories/gai-xinh" to "Gái Xinh",
        "/categories/nghiep-du" to "Nghiệp Dư",
        "/categories/xnxx" to "Xnxx",
        "/categories/vlxx" to "Vlxx",
        "/categories/tap-the" to "Tập Thể",
        "/categories/nhat-ban" to "Nhật Bản",
        "/categories/han-quoc" to "Hàn Quốc",
        "/categories/vung-trom" to "Vụng Trộm",
        "/categories/vu-to" to "Vú To",
        "/categories/tu-the-69" to "Tư Thế 69",
        "/categories/hoc-sinh" to "Học Sinh",
        "/categories/quay-len" to "Quay Lén",
        "/categories/tu-suong" to "Tự Sướng"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    // --- PHẦN GIAO DIỆN ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: ""
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst("div.video-player-container img")?.attr("src")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".video-info__tags a").map { it.text() }
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: ULTIMATE SHADOW INTERCEPTOR ---

    // Script "Hacker" tiêm vào nhân trình duyệt
    private val masterScript = """
        <script>
        (function() {
            console.log("Shadow Interceptor: Engaged");
            
            // 1. Bypass Anti-DevTools & Debugger
            var _constructor = window.Function.prototype.constructor;
            window.Function.prototype.constructor = function(s) {
                if (s === "debugger") return function() {};
                return _constructor.apply(this, arguments);
            };

            // 2. Ngụy trang môi trường
            try {
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            } catch(e) {}

            function sendToAndroid(content, sourceUrl) {
                if (content && (content.includes("#EXTM3U") || sourceUrl.includes(".m3u8"))) {
                    Android.onM3U8Captured(content, sourceUrl);
                }
            }

            // 3. Hook URL.createObjectURL (Bắt Blob)
            var _createObjectURL = URL.createObjectURL;
            URL.createObjectURL = function(obj) {
                var url = _createObjectURL.apply(this, arguments);
                if (obj instanceof Blob && (obj.type.includes('mpegurl') || obj.type === '')) {
                    var reader = new FileReader();
                    reader.onload = function() { sendToAndroid(reader.result, window.location.href); };
                    reader.readAsText(obj);
                }
                return url;
            };

            // 4. Hook Fetch API
            var _fetch = window.fetch;
            window.fetch = function() {
                return _fetch.apply(this, arguments).then(res => {
                    if (res.url.includes(".m3u8") || res.url.includes("master")) {
                        res.clone().text().then(t => sendToAndroid(t, res.url));
                    }
                    return res;
                });
            };

            // 5. Hook XMLHttpRequest
            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes(".m3u8") || this.responseText.includes("#EXTM3U")) {
                        sendToAndroid(this.responseText, url);
                    }
                });
                _open.apply(this, arguments);
            };
            
            // 6. Hook Video Tag (Trường hợp gán src trực tiếp)
            document.addEventListener('DOMContentLoaded', function() {
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.type === 'attributes' && mutation.attributeName === 'src') {
                            var target = mutation.target;
                            if (target.tagName === 'VIDEO' || target.tagName === 'SOURCE') {
                                if (target.src.includes('.m3u8')) {
                                    // Nếu bắt được link trực tiếp, gửi link về (nội dung rỗng để báo hiệu là link)
                                    Android.onLinkCaptured(target.src);
                                }
                            }
                        }
                    });
                });
                observer.observe(document.body, { attributes: true, subtree: true, childList: true });
            });
        })();
        </script>
    """.trimIndent()

    // Server nội bộ để phục vụ video từ bộ nhớ
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

    inner class AndroidBridge(val onCaptured: (String, String) -> Unit, val onLinkFound: (String) -> Unit) {
        @JavascriptInterface
        fun onM3U8Captured(content: String, url: String) { onCaptured(content, url) }
        @JavascriptInterface
        fun onLinkCaptured(url: String) { onLinkFound(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun shadowCapture(targetUrl: String): Any? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(35_000L) {
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

                    wv.addJavascriptInterface(AndroidBridge(
                        onCaptured = { content, url ->
                            if (!isFinished) {
                                isFinished = true
                                // Xử lý link segment tương đối -> tuyệt đối
                                val baseUrl = url.substringBeforeLast("/") + "/"
                                val fixed = content.lines().joinToString("\n") { 
                                    if (it.isNotBlank() && !it.startsWith("#") && !it.startsWith("http")) "$baseUrl$it" else it 
                                }
                                wv.post { wv.destroy() }
                                if (cont.isActive) cont.resume(fixed) // Trả về nội dung M3U8
                            }
                        },
                        onLinkFound = { url ->
                            if (!isFinished) {
                                isFinished = true
                                wv.post { wv.destroy() }
                                if (cont.isActive) cont.resume(url) // Trả về Link M3U8
                            }
                        }
                    ), "Android")

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            // Chặn request tải trang Iframe để tiêm thuốc
                            if (reqUrl == targetUrl || reqUrl.contains("streamqq") || reqUrl.contains("spexliu")) {
                                try {
                                    val conn = URL(reqUrl).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("Referer", "$mainUrl/")
                                    conn.setRequestProperty("User-Agent", UA)
                                    
                                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                                    // Tiêm Master Script vào ngay đầu HTML
                                    val injectedHtml = if (html.contains("<head>")) {
                                        html.replaceFirst("<head>", "<head>$masterScript")
                                    } else {
                                        masterScript + html
                                    }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text
        val doc = res.document

        // 1. Tìm Iframe chứa video
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu)[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (iframeUrl == null) iframeUrl = doc.select("iframe").attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            
            // 2. Kích hoạt Shadow Capture
            val result = shadowCapture(fixedIframeUrl)

            if (result != null) {
                if (result is String && result.contains("#EXTM3U")) {
                    // Case 1: Bắt được nội dung -> Dùng Local Server
                    localServer?.stop()
                    val server = LocalM3U8Server(result)
                    server.start()
                    localServer = server
                    callback(newExtractorLink("HeoVL VIP", "Server VIP (Shadow)", "http://127.0.0.1:${server.port}/video.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = fixedIframeUrl
                        this.quality = Qualities.P1080.value
                    })
                } else if (result is String && result.startsWith("http")) {
                    // Case 2: Bắt được link trực tiếp
                    callback(newExtractorLink("HeoVL VIP", "Server VIP (Direct)", result, ExtractorLinkType.M3U8) {
                        this.referer = fixedIframeUrl
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Origin" to "https://e.streamqq.com")
                    })
                }
            }
        }
        
        // Fallback
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
