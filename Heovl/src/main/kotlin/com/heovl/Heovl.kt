package com.heovl

import android.annotation.SuppressLint
import android.net.http.SslError
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

    // Giả lập Pixel 7 Pro để tăng độ uy tín
    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

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

    // --- PHẦN XỬ LÝ VIDEO: REAL BROWSER SIMULATION ---

    data class CapturedLink(val url: String, val headers: Map<String, String>)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffLink(iframeUrl: String): CapturedLink? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var result: CapturedLink? = null
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext null

            val wv = WebView(ctx)
            
            // Cấu hình WebView tối đa để giống thật nhất
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Cho phép popup để script chạy mượt hơn
                javaScriptCanOpenWindowsAutomatically = true
            }
            
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                // Bỏ qua lỗi SSL (Quan trọng với các web lậu)
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    // Bắt link elifros hoặc m3u8
                    // Thêm điều kiện loại trừ favicon, css, font để tối ưu
                    if ((url.contains("elifros.top") || url.contains(".m3u8")) && !url.contains("favicon")) {
                        // Bỏ qua link master.m3u8 nếu nó quá ngắn (thường là quảng cáo)
                        // Nhưng với elifros thì bắt luôn
                        if (url.contains("elifros") || !url.contains("master")) {
                            if (result == null) {
                                val headers = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                                headers["User-Agent"] = UA
                                headers["Referer"] = iframeUrl
                                headers["Origin"] = "https://${java.net.URI(iframeUrl).host}"
                                
                                result = CapturedLink(url, headers)
                                latch.countDown()
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Script bấm Play và Skip Ads liên tục
                    view?.evaluateJavascript("""
                        (function() {
                            setInterval(function() {
                                // Bấm Play
                                var playBtns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .plyr__control--overlaid');
                                playBtns.forEach(b => b.click());
                                
                                // Bấm Skip Ads
                                var skipBtns = document.querySelectorAll('.jw-skip, .videoAdUiSkipButton, .skip-ad');
                                skipBtns.forEach(b => b.click());
                                
                                // Bấm vào Video
                                var vid = document.querySelector('video');
                                if(vid && vid.paused) vid.play();
                            }, 800);
                        })();
                    """.trimIndent(), null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Tăng thời gian chờ lên 40s (Mạng chậm hoặc quảng cáo dài)
            withContext(Dispatchers.IO) {
                try { latch.await(40, TimeUnit.SECONDS) } catch (e: Exception) {}
            }

            wv.stopLoading()
            wv.destroy()
            result
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        // Lấy danh sách Server
        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }
        
        val targets = if (sources.isNotEmpty()) sources else {
            val iframe = doc.select("iframe").attr("src")
            if (iframe.isNotBlank()) listOf(iframe to "Default Server") else emptyList()
        }

        targets.forEach { (sourceUrl, serverName) ->
            val fixedUrl = fixUrl(sourceUrl)
            
            // Chạy Sniffer
            val captured = sniffLink(fixedUrl)

            if (captured != null) {
                val name = "$serverName (Video Chính)"
                
                callback(newExtractorLink("HeoVL VIP", name, captured.url, ExtractorLinkType.M3U8) {
                    this.headers = captured.headers
                    this.quality = Qualities.P1080.value
                })
            }
        }
        
        return true
    }
}
