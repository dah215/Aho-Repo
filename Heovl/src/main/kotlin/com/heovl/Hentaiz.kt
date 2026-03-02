package com.hentaiz

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
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Tích hợp toàn bộ thể loại từ dữ liệu bạn cung cấp
    override val mainPage = mainPageOf(
        "/browse" to "Mới Cập Nhật",
        "/genres/big-boobs" to "Big Boobs",
        "/genres/bu-liem" to "Bú liếm",
        "/genres/hiep-dam" to "Hiếp dâm",
        "/genres/nu-sinh" to "Nữ sinh",
        "/genres/du-vu" to "Đụ Vú",
        "/genres/anal" to "Anal",
        "/genres/stocking" to "Stocking",
        "/genres/virgin" to "Virgin",
        "/genres/mind-break" to "Mind Break",
        "/genres/femdom" to "Femdom",
        "/genres/ahegao" to "Ahegao",
        "/genres/threesome" to "Threesome",
        "/genres/vanilla" to "Vanilla",
        "/genres/milf" to "MILF",
        "/genres/sex-toy" to "Sex Toy",
        "/genres/harem" to "Harem",
        "/genres/plot" to "Plot",
        "/genres/thu-dam" to "Thủ Dâm",
        "/genres/gang-bang" to "Gang Bang",
        "/genres/loan-luan" to "Loạn luân",
        "/genres/bondage" to "Bondage",
        "/genres/tsundere" to "Tsundere",
        "/genres/ntr" to "NTR",
        "/genres/double-penetration" to "Double Penetration",
        "/genres/giao-vien" to "Giáo viên",
        "/genres/loli" to "Loli",
        "/genres/megane" to "Megane",
        "/genres/yuri" to "Yuri",
        "/genres/do-boi" to "Đồ Bơi",
        "/genres/thac-loan" to "Thác loạn",
        "/genres/ugly-bastard" to "Ugly Bastard",
        "/genres/maid" to "Maid",
        "/genres/bao-dam" to "Bạo dâm",
        "/genres/thoi-mien" to "Thôi miên",
        "/genres/sua-me" to "Sữa mẹ",
        "/genres/tong-tinh" to "Tống tình",
        "/genres/da-ngam" to "Da ngăm",
        "/genres/3d" to "3D",
        "/genres/bao-cao-su" to "Bao cao su",
        "/genres/monster" to "Monster",
        "/genres/y-ta" to "Y Tá",
        "/genres/fantasy" to "Fantasy",
        "/genres/xuc-tu" to "Xúc tu",
        "/genres/foot-job" to "Foot Job",
        "/genres/guro" to "Guro",
        "/genres/kemonomimi" to "Kemonomimi",
        "/genres/shota" to "Shota",
        "/genres/x-ray" to "X-Ray",
        "/genres/futanari" to "Futanari",
        "/genres/wafuku" to "Wafuku",
        "/genres/elf" to "Elf",
        "/genres/softcore" to "Softcore",
        "/genres/tieu-tien" to "Tiểu tiện",
        "/genres/josei" to "Josei",
        "/genres/cong-cong" to "Công cộng",
        "/genres/gai-quay" to "Gái quậy",
        "/genres/scat" to "Scat",
        "/genres/idol" to "Idol",
        "/genres/thuoc-kich-duc" to "Thuốc kích dục",
        "/genres/succubus" to "Succubus",
        "/genres/cosplay" to "Cosplay",
        "/genres/mang-thai" to "Mang thai",
        "/genres/ngu" to "Ngủ",
        "/genres/trap" to "Trap",
        "/genres/yaoi" to "Yaoi",
        "/genres/vu-lep" to "Vú lép",
        "/genres/de-con" to "Đẻ con",
        "/genres/goblin" to "Goblin",
        "/genres/furry" to "Furry"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "$mainUrl${request.data}&page=$page"
        } else {
            "$mainUrl${request.data}?page=$page"
        }
        
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        
        // Quét phim từ dữ liệu SvelteKit
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        val items = regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val displayTitle = if (ep != "null" && ep.isNotBlank()) "$title - Tập $ep" else title
            newMovieSearchResponse(displayTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        return regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            newMovieSearchResponse(if (ep != "null") "$title - Tập $ep" else title, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
        val title = Regex("""title:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "HentaiZ Video"
        val posterPath = Regex("""posterImage:\{filePath:"([^"]+)"""").find(html)?.groupValues?.get(1)
        
        val desc = Regex("""description:"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
            ?.replace(Regex("<[^>]*>"), "")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = if (posterPath != null) "$imageBaseUrl$posterPath" else null
            this.plot = desc
        }
    }

    // --- GIỮ NGUYÊN LOGIC VIDEO SHADOW INTERCEPTOR ---

    private val masterScript = """
        (function() {
            var _constructor = window.Function.prototype.constructor;
            window.Function.prototype.constructor = function(s) {
                if (s === "debugger") return function() {};
                return _constructor.apply(this, arguments);
            };
            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
            window.chrome = { runtime: {} };
            function sendToAndroid(content, sourceUrl) {
                if (content && content.includes("#EXTM3U")) { Android.onM3U8Captured(content, sourceUrl); }
            }
            var _createObjectURL = URL.createObjectURL;
            URL.createObjectURL = function(obj) {
                var url = _createObjectURL.apply(this, arguments);
                if (obj instanceof Blob) {
                    var reader = new FileReader();
                    reader.onload = function() { sendToAndroid(reader.result, window.location.href); };
                    reader.readAsText(obj);
                }
                return url;
            };
            var _fetch = window.fetch;
            window.fetch = function() {
                return _fetch.apply(this, arguments).then(res => {
                    if (res.url.includes(".m3u8")) { res.clone().text().then(t => sendToAndroid(t, res.url)); }
                    return res;
                });
            };
            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes(".m3u8") || this.responseText.includes("#EXTM3U")) { sendToAndroid(this.responseText, url); }
                });
                _open.apply(this, arguments);
            };
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

    inner class AndroidBridge(val onCaptured: (String, String) -> Unit) {
        @JavascriptInterface
        fun onM3U8Captured(content: String, url: String) { onCaptured(content, url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun shadowCapture(targetUrl: String): String? {
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
                    wv.addJavascriptInterface(AndroidBridge { content, url ->
                        if (!isFinished) {
                            isFinished = true
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
                            if (reqUrl.contains("play.sonar-cdn.com/watch")) {
                                try {
                                    val response = runBlocking(Dispatchers.IO) {
                                        app.get(reqUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                                    }
                                    val injectedHtml = "<html><head><script>$masterScript</script></head><body>$response</body></html>"
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
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val sonarUrl = Regex("""https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""").find(res.text)?.value

        if (!sonarUrl.isNullOrBlank()) {
            val m3u8Content = shadowCapture(sonarUrl)
            if (m3u8Content != null) {
                localServer?.stop()
                val server = LocalM3U8Server(m3u8Content)
                server.start()
                localServer = server
                callback(newExtractorLink("Sonar CDN", "Server VIP (Shadow)", "http://127.0.0.1:${server.port}/video.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = sonarUrl
                    this.quality = Qualities.P1080.value
                })
            }
        }
        return true
    }
}
