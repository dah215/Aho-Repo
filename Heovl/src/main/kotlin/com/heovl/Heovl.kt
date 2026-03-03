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
            this.tags = doc.select(".video-info__tags a").map { it.text() }
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: NATIVE INTERCEPTOR ---

    data class CapturedLink(val url: String, val headers: Map<String, String>)

    // Script tự động bấm Play
    private val autoClickScript = """
        (function() {
            var attempts = 0;
            var interval = setInterval(function() {
                // Bấm mọi nút có vẻ là nút Play
                var btns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .plyr__control--overlaid, #play-button');
                btns.forEach(b => b.click());
                
                // Bấm vào video
                var vid = document.querySelector('video');
                if(vid && vid.paused) vid.play();

                attempts++;
                if (attempts > 20) clearInterval(interval);
            }, 1000);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun nativeSniff(iframeUrl: String): List<CapturedLink> {
        return withContext(Dispatchers.Main) {
            val foundLinks = mutableListOf<CapturedLink>()
            val latch = CountDownLatch(1) // Dùng để đợi, nhưng không chặn luồng chính
            
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

            wv.webViewClient = object : WebViewClient() {
                // CỔNG KIỂM SOÁT MẠNG CẤP THẤP
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    // Kiểm tra các từ khóa quan trọng: m3u8, elifros, master
                    if (url.contains(".m3u8") || url.contains("elifros.top") || url.contains("master")) {
                        // Bắt link và Header
                        val headers = request.requestHeaders ?: emptyMap()
                        foundLinks.add(CapturedLink(url, headers))
                    }
                    
                    // Cho phép request đi qua bình thường để video chạy tiếp (và hiện quảng cáo nếu có)
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Tiêm script bấm Play
                    view?.evaluateJavascript(autoClickScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi 20 giây để bắt hết các link (bao gồm cả link sau quảng cáo)
            withContext(Dispatchers.IO) {
                try {
                    // Chúng ta không dùng latch.await() để chặn, mà dùng delay để cho phép WebView hoạt động
                    // Tuy nhiên trong coroutine, delay là non-blocking
                    delay(20000) 
                } catch (e: Exception) {}
            }

            wv.stopLoading()
            wv.destroy()
            
            // Lọc trùng và trả về
            foundLinks.distinctBy { it.url }
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
            
            // Chạy Native Sniffer
            val capturedLinks = nativeSniff(fixedSonarUrl)

            capturedLinks.forEachIndexed { index, captured ->
                val url = captured.url
                // Xác định loại link
                val isM3u8 = url.contains(".m3u8") || url.contains("mpegurl")
                
                // Tạo Header chuẩn
                val videoHeaders = captured.headers.toMutableMap()
                if (!videoHeaders.containsKey("Origin")) {
                    val host = java.net.URL(fixedSonarUrl).host
                    videoHeaders["Origin"] = "https://$host"
                }
                if (!videoHeaders.containsKey("Referer")) {
                    videoHeaders["Referer"] = fixedSonarUrl
                }

                // Đặt tên server
                val name = if (url.contains("elifros")) "Server VIP (Elifros)" else "Server VIP (Link $index)"

                callback(newExtractorLink("HeoVL VIP", name, url, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.headers = videoHeaders
                    this.quality = Qualities.P1080.value
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
