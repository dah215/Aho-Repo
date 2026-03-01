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
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
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
    // User-Agent giả lập điện thoại để lấy player HTML5 nhẹ
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

    // ─── PHẦN QUAN TRỌNG: BYPASS ADBLOCK & INTERCEPTOR ───

    // 1. Script giả lập quảng cáo (Fake Ads) để lừa trang web
    private val fakeAdsScript = """
        <script>
        // Giả lập Google Ads
        window.adsbygoogle = window.adsbygoogle || [];
        window.adsbygoogle.loaded = true;
        window.adsbygoogle.push = function() {};
        
        // Giả lập các biến phát hiện bot
        Object.defineProperty(navigator, 'webdriver', { get: () => false });
        </script>
    """.trimIndent()

    // 2. Script bắt link video (XHR, Fetch, Blob)
    private val videoSnifferScript = """
        <script>
        (function() {
            console.log("HentaiZ: Sniffer Active");
            function send(c, u) { if(c && (c.includes('#EXTM3U') || u.includes('.m3u8'))) Android.onM3U8(c, u); }
            
            // Hook XHR
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.addEventListener('load', function() { send(this.responseText, u); });
                xo.apply(this, arguments);
            };
            
            // Hook Fetch
            var of = window.fetch;
            window.fetch = async (...a) => {
                var r = await of(...a);
                var c = r.clone();
                c.text().then(t => send(t, r.url));
                return r;
            };
            
            // Hook Blob
            var oc = URL.createObjectURL;
            URL.createObjectURL = function(b) {
                var u = oc.apply(this, arguments);
                if (b && b.type && b.type.includes('mpegurl')) {
                    var r = new FileReader();
                    r.onload = function(e) { send(e.target.result, window.location.href); };
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
            val baseUrl = url.substringBeforeLast("/") + "/"
            val fixedContent = content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) "$baseUrl$line" else line
            }
            onResult(fixedContent)
        }
    }

    // Local Server
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
                        val c = s.accept()
                        c.getInputStream().bufferedReader().readLine()
                        val b = content.toByteArray()
                        val h = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${b.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().write(h.toByteArray())
                        c.getOutputStream().write(b)
                        c.getOutputStream().flush()
                        c.close()
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
        fun stop() { try { socket?.close() } catch (_: Exception) {} }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureM3U8(url: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var resumed = false
                    
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(M3U8Bridge { c ->
                        if (!resumed) { resumed = true; wv.destroy(); if (cont.isActive) cont.resume(c) }
                    }, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        // Dùng shouldInterceptRequest để chèn script TRƯỚC KHI trang web chạy
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            if (request.url.toString().contains("play.sonar-cdn.com/watch")) {
                                try {
                                    // Tải trang bằng Java thuần (Blocking) để tránh lỗi Coroutine
                                    val conn = URL(request.url.toString()).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("Referer", "$mainUrl/")
                                    conn.setRequestProperty("User-Agent", UA)
                                    
                                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                                    
                                    // Chèn Fake Ads + Sniffer vào ngay đầu thẻ <head>
                                    val injected = if (html.contains("<head>")) {
                                        html.replaceFirst("<head>", "<head>$fakeAdsScript$videoSnifferScript")
                                    } else {
                                        fakeAdsScript + videoSnifferScript + html
                                    }
                                    
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray()))
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    
                    wv.loadUrl(url, mapOf("Referer" to "$mainUrl/"))
                    cont.invokeOnCancellation { wv.destroy() }
                }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(data, headers = headers)
        val sonarUrl = Regex("""https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""").find(res.text)?.value
            ?: Regex("""https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""").find(res.document.html())?.value

        if (!sonarUrl.isNullOrBlank()) {
            val m3u8 = captureM3U8(fixUrl(sonarUrl))
            if (m3u8 != null) {
                localServer?.stop()
                val s = LocalM3U8Server(m3u8)
                s.start()
                localServer = s
                callback(newExtractorLink("Sonar CDN", "Server VIP (Localhost)", "http://127.0.0.1:${s.port}/video.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = fixUrl(sonarUrl)
                    this.quality = Qualities.P1080.value
                })
            }
        }
        return true
    }
}
