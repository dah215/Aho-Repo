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

    // User-Agent mới nhất của Chrome Android để tránh bị phát hiện là Bot cũ
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

    // --- PHẦN XỬ LÝ VIDEO: HEADER HIJACKING ---

    data class HijackedData(val url: String, val headers: Map<String, String>)

    // Script bấm Play cực mạnh, chạy liên tục
    private val autoClickScript = """
        (function() {
            var interval = setInterval(function() {
                // Bấm nút Play của JW Player, VideoJS, Plyr...
                var btns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .plyr__control--overlaid, #play-button');
                for (var i = 0; i < btns.length; i++) {
                    btns[i].click();
                }
                
                // Bấm trực tiếp vào thẻ video
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    if (vids[i].paused) vids[i].play();
                }
            }, 500); // Lặp lại mỗi 0.5s
            
            // Dừng sau 15s để tiết kiệm tài nguyên
            setTimeout(function() { clearInterval(interval); }, 15000);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun hijackLink(iframeUrl: String): List<HijackedData> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            val results = mutableListOf<HijackedData>()
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
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    // Bắt TẤT CẢ các link .m3u8 (Không lọc tên miền nữa để tránh sót)
                    // Loại bỏ các link quảng cáo Google/Doubleclick
                    if (url.contains(".m3u8") && !url.contains("google") && !url.contains("doubleclick")) {
                        
                        val headers = request.requestHeaders?.toMutableMap() ?: mutableMapOf()
                        
                        // Bổ sung Header nếu thiếu (Quan trọng!)
                        if (!headers.containsKey("Referer")) headers["Referer"] = iframeUrl
                        if (!headers.containsKey("Origin")) headers["Origin"] = "https://${java.net.URI(iframeUrl).host}"
                        if (!headers.containsKey("User-Agent")) headers["User-Agent"] = UA
                        
                        results.add(HijackedData(url, headers))
                        
                        // Nếu bắt được link có vẻ là link thật (không phải master.m3u8 ngắn ngủn), thì dừng sớm
                        if (url.contains("elifros") || url.length > 100) {
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(autoClickScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi tối đa 20s
            withContext(Dispatchers.IO) {
                try { latch.await(20, TimeUnit.SECONDS) } catch (e: Exception) {}
            }

            wv.stopLoading()
            wv.destroy()
            
            // Trả về danh sách link duy nhất
            results.distinctBy { it.url }
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
            
            // Chạy Hijacker
            val hijackedList = hijackLink(fixedUrl)

            hijackedList.forEachIndexed { index, data ->
                // Đặt tên server
                val isElifros = data.url.contains("elifros")
                val label = if (isElifros) "$serverName (Video Chính)" else "$serverName (Link $index)"
                
                // Trả về link trực tiếp kèm Header xịn
                callback(newExtractorLink("HeoVL VIP", label, data.url, ExtractorLinkType.M3U8) {
                    this.headers = data.headers
                    this.quality = Qualities.P1080.value
                })
            }
        }
        
        return true
    }
}
