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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        }
    }

    // --- PHẦN QUAN TRỌNG: GHOST PROTOCOL SNIFFER ---

    data class SniffedResult(val url: String, val headers: Map<String, String>)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ghostSniff(iframeUrl: String): SniffedResult? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var result: SniffedResult? = null
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext null

            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                mediaPlaybackRequiresUserGesture = false
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    // CHỈ BẮT LINK ELIFROS (Link video thật theo manh mối của bạn)
                    if (url.contains("elifros.top/s/")) {
                        if (result == null) {
                            result = SniffedResult(url, request.requestHeaders ?: emptyMap())
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Script tự động phá quảng cáo và bấm Play video thật
                    view?.evaluateJavascript("""
                        (function() {
                            setInterval(function() {
                                // 1. Bấm nút Play
                                var play = document.querySelector('.jw-display-icon-display') || document.querySelector('video');
                                if (play) play.click();
                                
                                // 2. Bỏ qua quảng cáo (Skip Ads)
                                var skip = document.querySelector('.jw-skip, .skip-ad, [class*="skip"]');
                                if (skip) skip.click();
                                
                                // 3. Xóa các thông báo lỗi giả
                                var err = document.querySelector('.jw-error');
                                if (err) err.remove();
                            }, 1000);
                        })();
                    """.trimIndent(), null)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))

            withContext(Dispatchers.IO) {
                latch.await(25, TimeUnit.SECONDS) // Đợi tối đa 25s để vượt qua quảng cáo
            }

            wv.post { wv.destroy() }
            result
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val doc = org.jsoup.Jsoup.parse(html)

        // Lấy link từ các nút Server (Server 1, 2, 3)
        val sources = doc.select("button.set-player-source").map { 
            it.attr("data-source") to it.attr("data-cdn-name") 
        }.filter { it.first.isNotBlank() }

        sources.forEach { (source, cdnName) ->
            val fixedSource = fixUrl(source)
            
            // Kích hoạt Ghost Protocol cho từng server
            val sniffed = ghostSniff(fixedSource)

            if (sniffed != null) {
                val videoHeaders = sniffed.headers.toMutableMap()
                // Thiết lập Referer là link Iframe để server video chấp nhận
                videoHeaders["Referer"] = fixedSource
                videoHeaders["Origin"] = "https://elifros.top"

                callback(newExtractorLink("HeoVL VIP", "$cdnName (Video Chính)", sniffed.url, ExtractorLinkType.M3U8) {
                    this.headers = videoHeaders
                    this.quality = Qualities.P1080.value
                })
            }
        }
        
        // Fallback cho các host phụ
        Regex("""https?[:\\]+[/\\/]+[^\s"'<>]+""").findAll(html).forEach { 
            val link = it.value.replace("\\/", "/")
            if (link.contains("dood") || link.contains("streamwish") || link.contains("filemoon") || link.contains("voe")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
