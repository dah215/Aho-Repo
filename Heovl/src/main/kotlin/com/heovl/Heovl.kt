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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

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

    // --- PHẦN GIAO DIỆN ---

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

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: ""
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: LOCAL PROXY SERVER ---

    // Script JS: Bắt link, tải nội dung m3u8 và gửi về Android
    private val proxyScript = """
        (function() {
            console.log("HeoVL Proxy: Active");
            
            function processM3U8(url, content) {
                if (content && content.includes("#EXTM3U")) {
                    Android.onM3U8Content(url, content);
                }
            }

            // Hook Fetch
            var of = window.fetch;
            window.fetch = async (...args) => {
                var response = await of(...args);
                if (response.url && (response.url.includes('.m3u8') || response.url.includes('elifros.top'))) {
                    var clone = response.clone();
                    clone.text().then(t => processM3U8(response.url, t));
                }
                return response;
            };

            // Hook XHR
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes('.m3u8') || url.includes('elifros.top')) {
                        processM3U8(this.responseURL || url, this.responseText);
                    }
                });
                xo.apply(this, arguments);
            };

            // Auto Clicker
            setInterval(() => {
                var btns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"]');
                btns.forEach(b => b.click());
                var vid = document.querySelector('video');
                if(vid && vid.paused) vid.play();
            }, 1000);
        })();
    """.trimIndent()

    // Server nội bộ
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

    data class CapturedM3U8(val originalUrl: String, val content: String)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureContent(iframeUrl: String): List<CapturedM3U8> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            val results = CopyOnWriteArrayList<CapturedM3U8>()
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext emptyList()
            
            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun onM3U8Content(url: String, content: String) {
                    // Xử lý nội dung: Chuyển link segment thành tuyệt đối
                    val baseUrl = url.substringBeforeLast("/") + "/"
                    val fixedContent = content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }
                    
                    // Chỉ lấy link elifros hoặc link có nội dung hợp lệ
                    if (url.contains("elifros") || content.contains("#EXTINF")) {
                        results.add(CapturedM3U8(url, fixedContent))
                        latch.countDown() // Dừng ngay khi bắt được
                    }
                }
            }, "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(proxyScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            withContext(Dispatchers.IO) {
                latch.await(25, TimeUnit.SECONDS)
            }

            wv.post { wv.destroy() }
            results.toList()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }
        
        val targets = if (sources.isNotEmpty()) sources else {
            val iframe = doc.select("iframe").attr("src")
            if (iframe.isNotBlank()) listOf(iframe to "Default Server") else emptyList()
        }

        targets.forEach { (sourceUrl, serverName) ->
            val fixedUrl = fixUrl(sourceUrl)
            
            // Bắt nội dung M3U8
            val capturedList = captureContent(fixedUrl)

            capturedList.forEachIndexed { index, captured ->
                // Khởi động Local Server cho mỗi link bắt được
                val server = LocalM3U8Server(captured.content)
                server.start()
                
                // Link localhost sẽ không bao giờ bị lỗi 3002
                val localUrl = "http://127.0.0.1:${server.port}/video.m3u8"
                val label = if (index == 0) "$serverName (Video Chính)" else "$serverName (Link $index)"

                callback(newExtractorLink("HeoVL VIP", label, localUrl, ExtractorLinkType.M3U8) {
                    // Vẫn giữ Referer gốc để tải segment
                    this.referer = captured.originalUrl
                    this.quality = Qualities.P1080.value
                })
            }
        }
        
        return true
    }
}
