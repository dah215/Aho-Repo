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

    // Giả lập Chrome Android mới nhất
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

    // --- PHẦN XỬ LÝ VIDEO: OMNI-SNIFFER ---

    // Script tự động tương tác cực mạnh
    private val aggressiveScript = """
        (function() {
            console.log("Omni-Sniffer: Active");
            
            // 1. Xóa quảng cáo che màn hình
            setInterval(function() {
                var overlays = document.querySelectorAll('div[style*="z-index: 2147483647"], .overlay-ad, iframe[src*="chat"]');
                overlays.forEach(el => el.remove());
            }, 500);

            // 2. Bấm Play liên tục
            setInterval(function() {
                var playBtns = document.querySelectorAll('.jw-display-icon-display, .vjs-big-play-button, button[aria-label="Play"], .plyr__control--overlaid');
                playBtns.forEach(btn => btn.click());
                
                var video = document.querySelector('video');
                if (video && video.paused) {
                    video.muted = true; // Tắt tiếng để trình duyệt cho phép tự phát
                    video.play();
                }
            }, 1000);
        })();
    """.trimIndent()

    // Lưu trữ link bắt được (Thread-safe)
    private val capturedLinks = ConcurrentHashMap<String, String>()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun omniSniff(iframeUrl: String): List<String> {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            capturedLinks.clear()
            
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext emptyList()
            val wv = WebView(ctx)

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false // Cho phép video tự chạy
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                blockNetworkImage = true // Chặn ảnh để load nhanh hơn
            }
            
            // Bật Cookie Manager
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                // Bắt link ở tầng tài nguyên (Resource Level) - Không thể bị ẩn bởi JS
                override fun onLoadResource(view: WebView?, url: String?) {
                    if (url != null) {
                        // Bắt link elifros hoặc m3u8
                        if (url.contains("elifros.top") || (url.contains(".m3u8") && !url.contains("master"))) {
                            capturedLinks[url] = url
                            // Nếu bắt được elifros thì có thể dừng sớm
                            if (url.contains("elifros.top")) {
                                // Đợi thêm 2s để bắt nốt các link phụ rồi dừng
                                view?.postDelayed({ latch.countDown() }, 2000)
                            }
                        }
                    }
                    super.onLoadResource(view, url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(aggressiveScript, null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            // Đợi tối đa 30s (Quảng cáo có thể dài)
            withContext(Dispatchers.IO) {
                try { latch.await(30, TimeUnit.SECONDS) } catch (e: Exception) {}
            }

            // Lấy Cookie cuối cùng
            val cookies = cookieManager.getCookie(iframeUrl)
            
            wv.stopLoading()
            wv.destroy()
            
            // Trả về danh sách link kèm cookie (nếu cần xử lý sau này)
            capturedLinks.keys.toList()
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
            
            // Chạy Omni-Sniffer
            val links = omniSniff(fixedUrl)

            links.forEachIndexed { index, link ->
                // Lọc link rác
                if (!link.contains("google") && !link.contains("facebook") && !link.contains("analytics")) {
                    val isElifros = link.contains("elifros.top")
                    val label = if (isElifros) "$serverName (Video Chính)" else "$serverName (Link $index)"
                    
                    // Tạo Header chuẩn để replay
                    val videoHeaders = mapOf(
                        "User-Agent" to UA,
                        "Referer" to fixedUrl, // Referer là link Iframe
                        "Origin" to "https://${java.net.URI(fixedUrl).host}"
                    )

                    callback(
                        newExtractorLink(
                            source = "HeoVL VIP",
                            name = label,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = videoHeaders
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        }
        
        return true
    }
}
