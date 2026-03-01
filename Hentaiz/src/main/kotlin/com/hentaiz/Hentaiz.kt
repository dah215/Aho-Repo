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
    // User-Agent cực quan trọng: Giả lập Chrome trên Android để lấy player HTML5
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    // ─── PHẦN QUAN TRỌNG: SPYWARE INTERCEPTOR ───

    // Script này sẽ "nghe lén" mọi yêu cầu mạng mà trình duyệt gửi đi
    // Ngay khi thấy link .m3u8, nó sẽ báo về ngay lập tức (kể cả khi request đó sau này bị lỗi)
    private val spyScript = """
        <script>
        (function() {
            console.log("HentaiZ Spy: Activated");
            
            function report(url) {
                if (url && (url.includes('.m3u8') || url.includes('master.m3u8') || url.includes('index.m3u8'))) {
                    console.log("HentaiZ Spy: Found " + url);
                    Android.foundLink(url);
                }
            }

            // 1. Nghe lén XMLHttpRequest (Cách JW Player thường dùng)
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                report(url); // Báo cáo URL ngay khi lệnh mở kết nối được gọi
                origOpen.apply(this, arguments);
            };

            // 2. Nghe lén Fetch API
            var origFetch = window.fetch;
            window.fetch = async (...args) => {
                if (args[0]) report(args[0].toString());
                return origFetch(...args);
            };
            
            // 3. Fake Ads để tránh bị chặn sớm
            window.adsbygoogle = { loaded: true, push: function(){} };
        })();
        </script>
    """.trimIndent()

    inner class LinkBridge(val onLinkFound: (String) -> Unit) {
        @JavascriptInterface
        fun foundLink(url: String) {
            // Xử lý link: Nếu là link tương đối, ghép với domain
            onLinkFound(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun spyOnWebView(url: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000L) { // Chờ tối đa 25s
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var found = false
                    
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        // Cho phép nội dung hỗn hợp để tải quảng cáo/script
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Cầu nối nhận link từ JS
                    wv.addJavascriptInterface(LinkBridge { link ->
                        if (!found) {
                            found = true
                            // Ngay khi tìm thấy link, hủy WebView và trả kết quả
                            wv.post { wv.destroy() }
                            if (cont.isActive) cont.resume(link)
                        }
                    }, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        // Chặn request tải trang Sonar để tiêm thuốc
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if (reqUrl.contains("play.sonar-cdn.com/watch")) {
                                try {
                                    // Tải trang thủ công
                                    val conn = URL(reqUrl).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("Referer", "$mainUrl/")
                                    conn.setRequestProperty("User-Agent", UA)
                                    
                                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                                    
                                    // Tiêm Spy Script vào đầu thẻ <head>
                                    val injected = if (html.contains("<head>")) {
                                        html.replaceFirst("<head>", "<head>$spyScript")
                                    } else {
                                        spyScript + html
                                    }
                                    
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray()))
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    
                    // Bắt đầu tải trang
                    wv.loadUrl(url, mapOf("Referer" to "$mainUrl/"))
                    
                    cont.invokeOnCancellation { wv.destroy() }
                }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(data, headers = headers)
        
        // Tìm link Iframe Sonar
        val sonarUrl = Regex("""https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""").find(res.text)?.value
            ?: Regex("""https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""").find(res.document.html())?.value

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // Thả điệp viên vào WebView
            val m3u8Link = spyOnWebView(fixedSonarUrl)

            if (m3u8Link != null) {
                // Xử lý link tương đối nếu cần
                val finalLink = if (m3u8Link.startsWith("http")) m3u8Link else "https://play.sonar-cdn.com$m3u8Link"
                
                // Trả về link cho Cloudstream
                // Quan trọng: Thử cả 2 loại Referer để xem cái nào ăn
                
                // Option 1: Referer là trang Player (Thường đúng nhất)
                callback(newExtractorLink("Sonar CDN", "Server VIP (Player Ref)", finalLink, ExtractorLinkType.M3U8) {
                    this.referer = fixedSonarUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("Origin" to "https://play.sonar-cdn.com")
                })
                
                // Option 2: Referer là trang Web chính
                callback(newExtractorLink("Sonar CDN", "Server VIP (Site Ref)", finalLink, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                })
            }
        }
        return true
    }
}
