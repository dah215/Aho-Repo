package com.hentaiz

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
    
    // User-Agent của Chrome thật trên Android (Không dùng UA cũ nữa)
    private val STEALTH_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to STEALTH_UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Upgrade-Insecure-Requests" to "1"
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

    // ─── PHẦN QUAN TRỌNG: STEALTH WEBVIEW ───

    // Script ngụy trang (Anti-Detection)
    // Xóa dấu vết của WebView/Automation
    private val stealthScript = """
        <script>
        (function() {
            // 1. Xóa webdriver
            delete navigator.webdriver;
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            
            // 2. Giả lập Chrome
            window.chrome = { runtime: {} };
            
            // 3. Giả lập Plugins
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            Object.defineProperty(navigator, 'languages', { get: () => ['vi-VN', 'vi', 'en-US', 'en'] });
            
            // 4. Fake Ads (Để tránh lỗi Adblock)
            window.adsbygoogle = { loaded: true, push: function(){} };
        })();
        </script>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffLinkWithStealth(url: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) { // Chờ 30s
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var found = false
                    
                    // Cấu hình WebView như trình duyệt thật
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true // Quan trọng: Để lưu Token/Cookie
                        databaseEnabled = true
                        userAgentString = STEALTH_UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Đồng bộ Cookie (Nếu có)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.webViewClient = object : WebViewClient() {
                        // Chỉ nghe lén URL, KHÔNG can thiệp vào nội dung (để tránh bị phát hiện)
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            
                            // Nếu thấy link .m3u8 -> BẮT NGAY
                            if (reqUrl.contains(".m3u8") && !reqUrl.contains("favicon")) {
                                if (!found) {
                                    found = true
                                    // Trả về kết quả và hủy WebView
                                    view.post { view.destroy() }
                                    if (cont.isActive) cont.resume(reqUrl)
                                }
                            }
                            
                            // Nếu là trang chính Sonar -> Tiêm mã ngụy trang
                            // Lưu ý: Ta không dùng app.get ở đây mà để WebView tự tải, ta chỉ inject JS
                            // Tuy nhiên, để inject vào luồng tải tự nhiên rất khó trên Android cũ.
                            // Nên ta sẽ dùng onPageStarted để inject JS (kém hiệu quả hơn nhưng an toàn hơn)
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Tiêm mã ngụy trang sau khi trang tải (Backup)
                            view?.evaluateJavascript(stealthScript.replace("<script>", "").replace("</script>", ""), null)
                        }
                    }
                    
                    // Load URL với Referer chuẩn
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
            
            // Kích hoạt chế độ ẩn danh để bắt link
            val m3u8Link = sniffLinkWithStealth(fixedSonarUrl)

            if (m3u8Link != null) {
                // Trả về link bắt được
                // Quan trọng: Referer phải là trang Sonar Player
                callback(newExtractorLink("Sonar CDN", "Server VIP (Stealth)", m3u8Link, ExtractorLinkType.M3U8) {
                    this.referer = fixedSonarUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Origin" to "https://play.sonar-cdn.com",
                        "User-Agent" to STEALTH_UA
                    )
                })
            }
        }
        return true
    }
}
