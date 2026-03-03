package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

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
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("meta[property=og:description]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = doc.select(".video-info__tags a").map { it.text() }
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: ULTIMATE SNIFFER ---

    data class CapturedLink(val url: String, val headers: Map<String, String>)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ultimateSniff(iframeUrl: String): List<CapturedLink> {
        return withContext(Dispatchers.Main) {
            // Sử dụng ConcurrentLinkedQueue để tránh lỗi đa luồng khi WebView trả kết quả liên tục
            val foundLinks = ConcurrentLinkedQueue<CapturedLink>()
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

            // Cầu nối nhận link trực tiếp từ JW Player
            val jsBridge = object {
                @JavascriptInterface
                fun onLinkFound(url: String) {
                    if (url.contains(".m3u8") || url.contains("master")) {
                        foundLinks.add(CapturedLink(url, mapOf(
                            "Origin" to "https://${java.net.URI(iframeUrl).host}",
                            "Referer" to iframeUrl,
                            "User-Agent" to UA
                        )))
                    }
                }
            }
            wv.addJavascriptInterface(jsBridge, "Android")

            // Script "Hủy diệt quảng cáo" và ép JW Player nôn link ra
            val injectScript = """
                (function() {
                    // Nghe lén XHR
                    var xo = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(m, u) {
                        if(u && (u.includes('.m3u8') || u.includes('master'))) Android.onLinkFound(u);
                        xo.apply(this, arguments);
                    };
                    
                    // Nghe lén Fetch
                    var of = window.fetch;
                    window.fetch = async (...a) => {
                        if(a[0] && (a[0].includes('.m3u8') || a[0].includes('master'))) Android.onLinkFound(a[0].toString());
                        return of(...a);
                    };
                    
                    // Vòng lặp phá quảng cáo và lấy playlist
                    setInterval(() => {
                        // Xóa sạch các lớp phủ quảng cáo
                        document.querySelectorAll('div[style*="z-index: 9999"], .overlay-ad, .chat-button, iframe[src*="chat"]').forEach(e => e.remove());
                        
                        // Ép JW Player
                        if (window.jwplayer) {
                            try {
                                var p = window.jwplayer();
                                if (p.getState() !== 'playing') p.play();
                                
                                // Lấy trực tiếp danh sách phát (Bỏ qua quảng cáo)
                                var playlist = p.getPlaylist();
                                if (playlist) {
                                    playlist.forEach(item => {
                                        if (item.file) Android.onLinkFound(item.file);
                                    });
                                }
                            } catch(e){}
                        }
                        
                        // Bấm mọi nút Play có thể thấy
                        var vids = document.querySelectorAll('video');
                        vids.forEach(v => { if(v.paused) v.play(); });
                        document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button').forEach(b => b.click());
                    }, 1000);
                })();
            """.trimIndent()

            wv.webViewClient = object : WebViewClient() {
                // Nghe lén ở tầng Network
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.contains(".m3u8") || url.contains("master")) {
                        val headers = request.requestHeaders ?: emptyMap()
                        foundLinks.add(CapturedLink(url, headers))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(injectScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi 20s để JW Player load xong playlist và quảng cáo chạy qua
            withContext(Dispatchers.IO) {
                delay(20000) 
            }

            wv.stopLoading()
            wv.destroy()
            
            // Lọc bỏ các link trùng lặp
            foundLinks.toList().distinctBy { it.url }
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
            
            // Chạy Ultimate Sniffer
            val capturedLinks = ultimateSniff(fixedSonarUrl)

            // Lọc ra các link m3u8
            val m3u8Links = capturedLinks.filter { it.url.contains(".m3u8") || it.url.contains("master") }

            m3u8Links.forEachIndexed { index, captured ->
                val url = captured.url
                
                val videoHeaders = captured.headers.toMutableMap()
                if (!videoHeaders.containsKey("Origin")) {
                    videoHeaders["Origin"] = "https://${java.net.URI(fixedSonarUrl).host}"
                }
                if (!videoHeaders.containsKey("Referer")) {
                    videoHeaders["Referer"] = fixedSonarUrl
                }

                // Đặt tên server. Link đầu tiên thường là quảng cáo, link thứ 2 là video thật.
                val name = if (index == 0) "Server VIP (Có thể là QC)" else "Server VIP (Video Chính $index)"

                callback(newExtractorLink("HeoVL VIP", name, url, ExtractorLinkType.M3U8) {
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
