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

    // --- PHẦN GIAO DIỆN ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = headers).document
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
        val doc = app.get(url, headers = headers).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: ""
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
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

    // --- PHẦN XỬ LÝ VIDEO: PASSIVE SNIFFER + DOM SCANNER ---

    // Script quét DOM sau khi trang tải xong
    private val scannerScript = """
        (function() {
            function findLinks() {
                var links = [];
                // 1. Quét thẻ Video
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    if (videos[i].src && videos[i].src.includes('.m3u8')) links.push(videos[i].src);
                }
                // 2. Quét nội dung HTML
                var html = document.body.innerHTML;
                var regex = /["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/g;
                var match;
                while ((match = regex.exec(html)) !== null) {
                    links.push(match[1]);
                }
                // 3. Quét JW Player
                if (window.jwplayer) {
                    try {
                        var player = window.jwplayer();
                        var item = player.getPlaylistItem();
                        if (item && item.file) links.push(item.file);
                    } catch(e) {}
                }
                
                if (links.length > 0) {
                    Android.onLinksFound(JSON.stringify([...new Set(links)])); // Gửi danh sách link duy nhất
                }
            }
            // Chạy ngay và chạy lại sau 2s để chắc chắn
            findLinks();
            setTimeout(findLinks, 2000);
        })();
    """.trimIndent()

    inner class SnifferBridge(val onResult: (List<String>) -> Unit) {
        @JavascriptInterface
        fun onLinksFound(jsonLinks: String) {
            try {
                val links = AppUtils.parseJson<List<String>>(jsonLinks)
                onResult(links)
            } catch (e: Exception) { }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoLinks(iframeUrl: String): List<String> {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(emptyList()); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    val foundLinks = mutableSetOf<String>()
                    var hasResumed = false

                    fun finish() {
                        if (!hasResumed) {
                            hasResumed = true
                            wv.stopLoading()
                            wv.destroy()
                            if (cont.isActive) cont.resume(foundLinks.toList())
                        }
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.addJavascriptInterface(SnifferBridge { links ->
                        foundLinks.addAll(links)
                        if (foundLinks.isNotEmpty()) finish()
                    }, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        // 1. Nghe lén mạng (Passive Sniffing)
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (url.contains(".m3u8") || url.contains("master")) {
                                foundLinks.add(url)
                                // Nếu tìm thấy link mạng, có thể dừng sớm hoặc đợi thêm DOM scan
                                // Ở đây ta đợi thêm chút để chắc chắn
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 2. Quét DOM khi tải xong
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(scannerScript, null)
                        }
                    }

                    // Load Iframe với Referer giả lập
                    wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

                    // Timeout an toàn
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 20000)
                    
                    cont.invokeOnCancellation { wv.destroy() }
                }
            } ?: emptyList()
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
        var iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu|flimora)[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (iframeUrl == null) iframeUrl = doc.select("iframe").attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            val fixedIframeUrl = fixUrl(iframeUrl)
            
            // 2. Dùng WebView để săn link (Cả mạng và DOM)
            val links = sniffVideoLinks(fixedIframeUrl)

            links.forEach { link ->
                val fixedLink = link.replace("\\/", "/")
                
                // Trả về link với Referer là Iframe
                callback(
                    newExtractorLink(
                        source = "HeoVL VIP",
                        name = "Server VIP (Sniffed)",
                        url = fixedLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedIframeUrl
                        this.quality = Qualities.P1080.value
                        // Thêm Origin của server video
                        val origin = "https://${java.net.URI(fixedIframeUrl).host}"
                        this.headers = mapOf("Origin" to origin)
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
