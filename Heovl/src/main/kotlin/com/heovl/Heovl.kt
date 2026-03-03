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
import java.util.concurrent.ConcurrentLinkedQueue
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

    // --- PHẦN GIAO DIỆN (GIỮ NGUYÊN) ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: MULTI-SERVER SHADOW SNIFFER ---

    private val masterScript = """
        (function() {
            console.log("HeoVL Master: Sniffer Active");
            function send(c, u) { if (c && c.includes("#EXTM3U")) Android.onM3U8Captured(c, u); }
            
            var _fetch = window.fetch;
            window.fetch = function() {
                return _fetch.apply(this, arguments).then(res => {
                    if (res.url.includes(".m3u8")) res.clone().text().then(t => send(t, res.url));
                    return res;
                });
            };

            var _open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes(".m3u8") || (this.responseText && this.responseText.includes("#EXTM3U"))) {
                        send(this.responseText, url);
                    }
                });
                _open.apply(this, arguments);
            };

            // Tự động bấm Play để kích hoạt video
            setInterval(() => {
                var btn = document.querySelector('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"]');
                if (btn) btn.click();
                var vid = document.querySelector('video');
                if (vid && vid.paused) vid.play();
            }, 1000);
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
    private suspend fun captureFromSource(targetUrl: String, serverName: String, callback: (ExtractorLink) -> Unit) {
        withContext(Dispatchers.Main) {
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext
            val wv = WebView(ctx)
            var capturedCount = 0

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            wv.addJavascriptInterface(AndroidBridge { content, url ->
                capturedCount++
                // Xử lý link segment
                val baseUrl = url.substringBeforeLast("/") + "/"
                val fixed = content.lines().joinToString("\n") { 
                    if (it.isNotBlank() && !it.startsWith("#") && !it.startsWith("http")) "$baseUrl$it" else it 
                }
                
                // Dựng server cho từng link bắt được
                val server = LocalM3U8Server(fixed)
                server.start()
                
                // Trả về link. Link đầu tiên (capturedCount=1) thường là QC, link sau là phim.
                val label = if (capturedCount == 1) "$serverName (Có thể là QC)" else "$serverName (Video $capturedCount)"
                
                callback(newExtractorLink("HeoVL VIP", label, "http://127.0.0.1:${server.port}/video.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = targetUrl
                    this.quality = Qualities.P1080.value
                })
            }, "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(masterScript, null)
                }
            }

            wv.loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/"))
            
            // Đợi 25s cho mỗi server để bắt link
            delay(25000)
            wv.stopLoading()
            wv.destroy()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        // 1. Tìm tất cả các nút Server dựa trên manh mối bạn cung cấp
        val serverButtons = doc.select("button.set-player-source")
        
        if (serverButtons.isNotEmpty()) {
            serverButtons.forEach { btn ->
                val sourceUrl = btn.attr("data-source")
                val cdnName = btn.attr("data-cdn-name").ifBlank { "Server VIP" }
                
                if (sourceUrl.isNotBlank()) {
                    // Chạy WebView cho từng server tìm được
                    captureFromSource(fixUrl(sourceUrl), cdnName, callback)
                }
            }
        } else {
            // Fallback nếu không thấy nút bấm (tìm iframe như cũ)
            val iframeUrl = doc.select("iframe").attr("src")
            if (iframeUrl.isNotBlank()) {
                captureFromSource(fixUrl(iframeUrl), "Default Server", callback)
            }
        }
        
        return true
    }
}
