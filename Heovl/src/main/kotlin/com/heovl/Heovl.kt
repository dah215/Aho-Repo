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
import java.util.concurrent.ConcurrentHashMap

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

    // --- PHẦN XỬ LÝ VIDEO: BRUTE FORCE SNIFFER ---

    // Script này sẽ "cướp" mọi request mạng
    private val bruteForceScript = """
        (function() {
            console.log("Brute Force: Active");
            
            function check(url) {
                // Bắt tất cả link có vẻ là video
                if (url && (url.includes('.m3u8') || url.includes('elifros.top') || url.includes('master'))) {
                    // Loại bỏ các domain quảng cáo rác nếu biết
                    if (!url.includes('google') && !url.includes('facebook')) {
                        Android.onLinkFound(url);
                    }
                }
            }

            // 1. Hook XHR (XMLHttpRequest)
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                check(url);
                this.addEventListener('load', function() {
                    // Kiểm tra nội dung trả về nếu URL không rõ ràng
                    if (this.responseText && this.responseText.includes('#EXTM3U')) {
                        check(this.responseURL);
                    }
                });
                xo.apply(this, arguments);
            };

            // 2. Hook Fetch API
            var of = window.fetch;
            window.fetch = async (...args) => {
                var url = args[0] ? args[0].toString() : '';
                check(url);
                return of(...args);
            };

            // 3. Auto Clicker (Bấm loạn xạ để kích hoạt video)
            setInterval(() => {
                var targets = [
                    '.jw-display-icon-display', 
                    '.vjs-big-play-button', 
                    'button[aria-label="Play"]',
                    'video',
                    '#play-button',
                    '.plyr__control--overlaid'
                ];
                targets.forEach(t => {
                    var el = document.querySelector(t);
                    if (el) el.click();
                });
                
                // Thử play video trực tiếp
                var v = document.querySelector('video');
                if (v && v.paused) v.play();
            }, 800);
        })();
    """.trimIndent()

    // Dùng ConcurrentHashMap để lưu link an toàn trong đa luồng
    private val capturedLinks = ConcurrentHashMap<String, String>()

    inner class SnifferBridge {
        @JavascriptInterface
        fun onLinkFound(url: String) {
            capturedLinks[url] = url
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bruteForceSniff(iframeUrl: String): List<String> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            capturedLinks.clear()
            
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
            wv.addJavascriptInterface(SnifferBridge(), "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(bruteForceScript, null)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    // Bắt thêm ở tầng Network cho chắc
                    if (url.contains("elifros.top") || url.contains(".m3u8")) {
                        capturedLinks[url] = url
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi 25 giây để vét cạn link
            withContext(Dispatchers.IO) {
                latch.await(25, TimeUnit.SECONDS)
            }

            wv.stopLoading()
            wv.destroy()
            
            capturedLinks.keys.toList()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        // Lấy tất cả nguồn phát
        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }
        
        // Nếu không có nút, lấy iframe
        val targets = if (sources.isNotEmpty()) sources else {
            val iframe = doc.select("iframe").attr("src")
            if (iframe.isNotBlank()) listOf(iframe to "Default Server") else emptyList()
        }

        targets.forEach { (sourceUrl, serverName) ->
            val fixedUrl = fixUrl(sourceUrl)
            
            // Chạy Brute Force Sniffer
            val links = bruteForceSniff(fixedUrl)

            links.forEachIndexed { index, link ->
                // Lọc link rác (quảng cáo thường có chữ ads, vast, doubleclick)
                if (!link.contains("googleads") && !link.contains("doubleclick")) {
                    val isElifros = link.contains("elifros.top")
                    val label = if (isElifros) "$serverName (Video Chính)" else "$serverName (Link $index)"
                    
                    // Sử dụng ExtractorLink trực tiếp để tránh lỗi biên dịch
                    callback(
                        ExtractorLink(
                            source = "HeoVL VIP",
                            name = label,
                            url = link,
                            referer = fixedUrl,
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                }
            }
        }
        
        return true
    }
}
