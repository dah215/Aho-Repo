package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.ServerSocket
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

    // --- PHẦN GIAO DIỆN (ĐÃ HOẠT ĐỘNG TỐT) ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        }
        
        val doc = app.get(url, headers = headers).document
        
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
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

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
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

    // --- PHẦN XỬ LÝ VIDEO: SHADOW INTERCEPTOR (GIỐNG HENTAIZ) ---

    private val masterScript = """
        (function() {
            console.log("HeoVL Interceptor: Started");
            var _constructor = window.Function.prototype.constructor;
            window.Function.prototype.constructor = function(s) {
                if (s === "debugger") return function() {};
                return _constructor.apply(this, arguments);
            };
            
            function sendToAndroid(content, sourceUrl) {
                if (content && content.includes("#EXTM3U")) { 
                    Android.onM3U8Captured(content, sourceUrl); 
                }
            }

            // Hook Fetch
            var _fetch = window.fetch;
            window.fetch = function() {
                return _fetch.apply(this, arguments).then(res => {
                    if (res.url.includes(".m3u8") || res.url.includes("master")) { 
                        res.clone().text().then(t => sendToAndroid(t, res.url)); 
                    }
                    return res;
                });
            };

            // Hook XHR
            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes(".m3u8") || this.responseText.includes("#EXTM3U")) { 
                        sendToAndroid(this.responseText, url); 
                    }
                });
                _open.apply(this, arguments);
            };
            
            // Hook JW Player (Nếu có)
            if (window.jwplayer) {
                try {
                    const player = window.jwplayer();
                    player.on('playlistItem', function(item) {
                        if (item.file && item.file.includes('.m3u8')) {
                            // Gửi link về thay vì nội dung nếu bắt được trực tiếp
                            Android.onLinkCaptured(item.file);
                        }
                    });
                } catch(e) {}
            }
        })();
    """.trimIndent()

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
                        userAgentString = UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(AndroidBridge(
                        onCaptured = { content, url ->
                            if (!isFinished) {
                                isFinished = true
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
                            // Chặn request đến trang chứa player để tiêm thuốc
                            if (reqUrl.contains("spexliu.top") || reqUrl.contains("heovl.moe")) {
                                // Logic: Nếu là trang chính hoặc iframe player, ta tự tải và tiêm JS
                                // Tuy nhiên với HeoVL, player thường là iframe, nên ta chỉ cần tiêm vào iframe
                                return super.shouldInterceptRequest(view, request)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Tiêm script vào mọi trang được load để bắt link
                            view?.evaluateJavascript(masterScript, null)
                        }
                    }
                    
                    wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
                    cont.invokeOnCancellation { wv.destroy() }
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
        
        // 1. Tìm Iframe chứa video (Ưu tiên spexliu.top như bạn cung cấp)
        var videoUrl = Regex("""src=["'](https?://[^"']*spexliu[^"']*)["']""").find(html)?.groupValues?.get(1)
        
        // Nếu không thấy spexliu, tìm iframe bất kỳ
        if (videoUrl == null) {
             videoUrl = res.document.select("iframe").attr("src")
        }

        // Nếu tìm thấy iframe video
        if (!videoUrl.isNullOrBlank()) {
            val fixedVideoUrl = fixUrl(videoUrl)
            
            // Dùng Shadow Capture để bắt link/nội dung
            val result = shadowCapture(fixedVideoUrl)

            if (result != null) {
                if (result is String && result.contains("#EXTM3U")) {
                    // Trường hợp 1: Bắt được nội dung M3U8 -> Dùng Local Server
                    localServer?.stop()
                    val server = LocalM3U8Server(result)
                    server.start()
                    localServer = server
                    callback(newExtractorLink("HeoVL VIP", "Server VIP (Shadow)", "http://127.0.0.1:${server.port}/video.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = fixedVideoUrl
                        this.quality = Qualities.P1080.value
                    })
                } else if (result is String && result.startsWith("http")) {
                    // Trường hợp 2: Bắt được đường link trực tiếp
                    callback(newExtractorLink("HeoVL VIP", "Server VIP (Direct)", result, ExtractorLinkType.M3U8) {
                        this.referer = fixedVideoUrl
                        this.quality = Qualities.P1080.value
                    })
                }
            }
        }
        
        // Fallback: Quét các host khác (Dood, StreamWish...)
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
