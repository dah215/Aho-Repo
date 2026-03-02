package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

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

    private val mapper = jacksonObjectMapper()
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
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: doc.selectFirst("div.video-player-container img")?.attr("src")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: STEALTH SNIFFER ---

    private val snifferScript = """
        (function() {
            console.log("HeoVL Stealth: Active");
            // Ngụy trang trình duyệt
            try { delete navigator.webdriver; } catch(e) {}
            window.adsbygoogle = { loaded: true, push: function(){} };

            function report(u) {
                if (u && (u.includes('.m3u8') || u.includes('master'))) {
                    Android.onLinkFound(u);
                }
            }
            
            // Hook XHR
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) {
                this.addEventListener('load', function() { report(u); });
                xo.apply(this, arguments);
            };
            
            // Hook Fetch
            var of = window.fetch;
            window.fetch = async (...a) => {
                if (a[0]) report(a[0].toString());
                return of(...a);
            };
        })();
    """.trimIndent()

    inner class WebBridge(val latch: CountDownLatch, var result: MutableSet<String>) {
        @JavascriptInterface
        fun onLinkFound(url: String) {
            result.add(url)
            latch.countDown() // Giải phóng khi tìm thấy link
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getLinksViaWebView(iframeUrl: String): List<String> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            val foundLinks = mutableSetOf<String>()
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext emptyList<String>()
            
            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            wv.addJavascriptInterface(WebBridge(latch, foundLinks), "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val reqUrl = request.url.toString()
                    if (reqUrl.contains(".m3u8")) {
                        foundLinks.add(reqUrl)
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(snifferScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Chạy ngầm và đợi kết quả (tối đa 20s)
            withContext(Dispatchers.IO) {
                latch.await(20, TimeUnit.SECONDS)
            }

            wv.post { wv.destroy() }
            foundLinks.toList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val html = res.text

        // Tìm Iframe
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu|flimora)[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (iframeUrl == null) iframeUrl = res.document.select("iframe").attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            val links = getLinksViaWebView(fixedIframeUrl)

            links.forEach { link ->
                callback(
                    newExtractorLink(
                        source = "HeoVL VIP",
                        name = "Server VIP (Stealth)",
                        url = link.replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedIframeUrl
                        this.quality = Qualities.P1080.value
                    }
                )
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
