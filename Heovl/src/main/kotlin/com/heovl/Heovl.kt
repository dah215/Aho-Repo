package com.heovl

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
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

    // --- PHẦN XỬ LÝ VIDEO: DATA URI EMBEDDING ---

    // Script bắt nội dung M3U8
    private val snifferScript = """
        (function() {
            function send(c, u) { 
                if (c && c.includes("#EXTM3U")) {
                    Android.onM3U8Captured(c, u); 
                }
            }
            
            // Hook Fetch
            var of = window.fetch;
            window.fetch = async (...args) => {
                var response = await of(...args);
                if (response.url && (response.url.includes('.m3u8') || response.url.includes('elifros.top'))) {
                    var clone = response.clone();
                    clone.text().then(t => send(t, response.url));
                }
                return response;
            };

            // Hook XHR
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('load', function() {
                    if (url.includes('.m3u8') || url.includes('elifros.top')) {
                        send(this.responseText, this.responseURL || url);
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

    data class CapturedData(val dataUri: String, val originalUrl: String)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureDataUri(iframeUrl: String): List<CapturedData> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            val results = CopyOnWriteArrayList<CapturedData>()
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
                fun onM3U8Captured(content: String, url: String) {
                    try {
                        // 1. Xử lý link segment: Biến link tương đối thành tuyệt đối
                        val baseUrl = url.substringBeforeLast("/") + "/"
                        val fixedContent = content.lines().joinToString("\n") { line ->
                            if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) {
                                "$baseUrl$line"
                            } else {
                                line
                            }
                        }
                        
                        // 2. Mã hóa Base64
                        val base64Content = Base64.encodeToString(fixedContent.toByteArray(), Base64.NO_WRAP)
                        val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64Content"
                        
                        // 3. Lưu kết quả
                        if (url.contains("elifros") || content.contains("#EXTINF")) {
                            results.add(CapturedData(dataUri, url))
                            latch.countDown()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }, "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(snifferScript, null)
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
            
            // Bắt Data URI
            val capturedList = captureDataUri(fixedUrl)

            capturedList.forEachIndexed { index, captured ->
                val label = if (index == 0) "$serverName (Video Chính)" else "$serverName (Link $index)"
                
                // Trả về Data URI
                callback(newExtractorLink("HeoVL VIP", label, captured.dataUri, ExtractorLinkType.M3U8) {
                    // Vẫn cần Referer để tải các segment (file .ts)
                    this.referer = captured.originalUrl
                    this.quality = Qualities.P1080.value
                    // Thêm Origin cho chắc
                    val host = java.net.URL(captured.originalUrl).host
                    this.headers = mapOf("Origin" to "https://$host")
                })
            }
        }
        
        return true
    }
}
