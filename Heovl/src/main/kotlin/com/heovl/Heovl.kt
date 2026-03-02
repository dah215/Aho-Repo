package com.heovl

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

    // --- PHẦN GIAO DIỆN (GIỮ NGUYÊN) ---

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

    // --- PHẦN XỬ LÝ VIDEO: WEBVIEW HUNTER ---

    // Script nghe lén request mạng (XHR/Fetch)
    private val snifferScript = """
        <script>
        (function() {
            console.log("HeoVL Sniffer: Active");
            function check(url) {
                if (url && (url.includes('.m3u8') || url.includes('master'))) {
                    Android.foundLink(url);
                }
            }
            
            // Hook XHR
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                check(u);
                xo.apply(this, arguments);
            };
            
            // Hook Fetch
            var of = window.fetch;
            window.fetch = async (...a) => {
                if (a[0]) check(a[0].toString());
                return of(...a);
            };
        })();
        </script>
    """.trimIndent()

    inner class LinkBridge(val onLinkFound: (String) -> Unit) {
        @JavascriptInterface
        fun foundLink(url: String) {
            onLinkFound(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffM3u8(iframeUrl: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000L) { // Chờ tối đa 20s
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var found = false
                    
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(LinkBridge { link ->
                        if (!found) {
                            found = true
                            wv.post { wv.destroy() }
                            if (cont.isActive) cont.resume(link)
                        }
                    }, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Nếu là request tải trang chính của Iframe -> Tiêm thuốc
                            if (request.url.toString() == iframeUrl) {
                                try {
                                    val conn = URL(iframeUrl).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("Referer", "$mainUrl/") // Fake Referer từ HeoVL
                                    conn.setRequestProperty("User-Agent", UA)
                                    
                                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                                    // Chèn script vào đầu
                                    val injected = snifferScript + html
                                    
                                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(injected.toByteArray()))
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    
                    // Load Iframe với Referer giả lập
                    wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))
                    
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
        val doc = res.document

        // 1. Tìm Iframe chứa video
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu)[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (iframeUrl == null) {
             iframeUrl = doc.select("iframe").attr("src")
        }

        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            
            // 2. Dùng WebView để bắt link m3u8 từ Iframe
            val m3u8Link = sniffM3u8(fixedIframeUrl)

            if (m3u8Link != null) {
                callback(
                    newExtractorLink(
                        source = "HeoVL VIP",
                        name = "Server VIP (Sniffed)",
                        url = m3u8Link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedIframeUrl // Referer là link của Iframe
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Origin" to "https://e.streamqq.com") // Thử Origin
                    }
                )
            }
        }
        
        // Fallback: Các host khác
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
