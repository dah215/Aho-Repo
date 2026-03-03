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

    // --- PHẦN XỬ LÝ VIDEO: AD-BUSTER & CONTENT-TYPE SNIFFER ---

    // Script này làm 2 việc:
    // 1. Tự động bấm Play và Skip Ad
    // 2. Kiểm tra Content-Type của mọi request để tìm video thật
    private val snifferScript = """
        <script>
        (function() {
            console.log("HeoVL Ad-Buster: Active");
            
            // --- PHẦN 1: CLICKER ---
            function clickPlay() {
                // Tìm nút Play
                var playBtns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .plyr__control--overlaid');
                playBtns.forEach(btn => btn.click());
                
                // Tìm nút Skip Ad (nếu có)
                var skipBtns = document.querySelectorAll('.jw-skip, .videoAdUiSkipButton, .skip-ad');
                skipBtns.forEach(btn => btn.click());
                
                // Click thẳng vào video nếu cần
                var video = document.querySelector('video');
                if (video && video.paused) video.play();
            }
            // Click liên tục mỗi giây để vượt qua các lớp quảng cáo
            setInterval(clickPlay, 1000);

            // --- PHẦN 2: SNIFFER ---
            function check(url, type) {
                // Nếu Content-Type là video hoặc mpegurl -> BẮT NGAY
                if (type && (type.includes('mpegurl') || type.includes('video/mp4'))) {
                    Android.foundLink(url, type);
                }
                // Fallback: Kiểm tra đuôi file nếu không có type
                else if (url && (url.includes('.m3u8') || url.includes('master'))) {
                    Android.foundLink(url, 'application/x-mpegurl');
                }
            }

            // Hook Fetch để lấy Content-Type
            var of = window.fetch;
            window.fetch = async (...args) => {
                var response = await of(...args);
                var type = response.headers.get('Content-Type');
                check(response.url, type);
                return response;
            };

            // Hook XHR để lấy Content-Type
            var xo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.addEventListener('readystatechange', function() {
                    if (this.readyState === 4) { // Khi request hoàn tất
                        var type = this.getResponseHeader('Content-Type');
                        check(this.responseURL || url, type);
                    }
                });
                xo.apply(this, arguments);
            };
        })();
        </script>
    """.trimIndent()

    inner class LinkBridge(val onLinkFound: (String, String) -> Unit) {
        @JavascriptInterface
        fun foundLink(url: String, type: String) {
            onLinkFound(url, type)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffWithAdBuster(iframeUrl: String): List<Pair<String, String>> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            val foundLinks = mutableSetOf<Pair<String, String>>() // Lưu URL và Type
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext emptyList()

            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false // Cho phép tự phát
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.addJavascriptInterface(LinkBridge { url, type ->
                // Lọc bỏ các link quảng cáo (nếu biết pattern)
                // Nhưng tốt nhất cứ lấy hết, Cloudstream sẽ tự test
                if (foundLinks.add(url to type)) {
                    // Nếu tìm thấy nhiều link, đợi thêm chút rồi dừng
                    // latch.countDown() // Không dừng ngay, để bắt thêm link thật sau quảng cáo
                }
            }, "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Tiêm thuốc ngay khi trang load
                    view?.evaluateJavascript(snifferScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi 30 giây để quảng cáo chạy xong và video thật hiện ra
            withContext(Dispatchers.IO) {
                latch.await(30, TimeUnit.SECONDS)
            }

            wv.post { wv.destroy() }
            foundLinks.toList()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(data, headers = mapOf("User-Agent" to UA))
        val html = res.text

        // Tìm Iframe
        val sonarUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu|flimora)[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: res.document.select("iframe").attr("src")

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // Chạy Ad-Buster
            val links = sniffWithAdBuster(fixedSonarUrl)

            links.forEachIndexed { index, (url, type) ->
                val fixedLink = url.replace("\\/", "/")
                val isM3u8 = type.contains("mpegurl") || url.contains(".m3u8")
                
                // Đặt tên để phân biệt
                val name = if (index == 0) "Server VIP (Có thể là QC)" else "Server VIP (Video $index)"
                
                callback(newExtractorLink("HeoVL VIP", name, fixedLink, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = fixedSonarUrl
                    this.quality = Qualities.P1080.value
                    val host = java.net.URL(fixedLink).host
                    this.headers = mapOf("Origin" to "https://$host")
                })
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
