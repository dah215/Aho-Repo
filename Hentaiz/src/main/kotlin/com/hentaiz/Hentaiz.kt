package com.hentaiz

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

@CloudstreamPlugin
class HentaiZPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HentaiZProvider())
    }
}

class HentaiZProvider : MainAPI() {
    override var mainUrl = "https://hentaiz.lol"
    override var name = "HentaiZ"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)
    
    private val imageBaseUrl = "https://storage.haiten.org"
    
    // User-Agent chuẩn của Chrome Android
    private val UA = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "/browse" to "Mới Cập Nhật",
        "/browse?animationType=THREE_D" to "Hentai 3D",
        "/browse?contentRating=UNCENSORED" to "Không Che",
        "/browse?contentRating=CENSORED" to "Có Che"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    private fun decodeUnicode(input: String): String {
        var res = input
        val regex = "\\\\u([0-9a-fA-F]{4})".toRegex()
        regex.findAll(input).forEach { match ->
            val charCode = match.groupValues[1].toInt(16).toChar()
            res = res.replace(match.value, charCode.toString())
        }
        return res
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) "$mainUrl${request.data}&page=$page" else "$mainUrl${request.data}?page=$page"
        val res = app.get(url, headers = headers)
        val html = res.text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        val items = regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?q=$query"
        val html = app.get(url, headers = headers).text
        val regex = """title:"([^"]+)",slug:"([^"]+)",episodeNumber:(\d+|null).*?posterImage:\{filePath:"([^"]+)"""".toRegex()
        return regex.findAll(html).map { match ->
            val (title, slug, ep, posterPath) = match.destructured
            val fullTitle = if (ep != "null") "$title - Tập $ep" else title
            newMovieSearchResponse(fullTitle, "$mainUrl/watch/$slug", TvType.NSFW) {
                this.posterUrl = "$imageBaseUrl$posterPath"
            }
        }.toList().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, headers = headers)
        val html = res.text
        val title = Regex("""title:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "HentaiZ Video"
        val posterPath = Regex("""posterImage:\{filePath:"([^"]+)"""").find(html)?.groupValues?.get(1)
        val rawDesc = Regex("""description:"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val desc = decodeUnicode(rawDesc).replace(Regex("<[^>]*>"), "").trim()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = if (posterPath != null) "$imageBaseUrl$posterPath" else null
            this.plot = desc
        }
    }

    // ─── PHẦN QUYỀN LỰC TỐI CAO: GOD MODE INTERCEPTOR ───

    data class CapturedLink(val url: String, val headers: Map<String, String>)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureRealRequest(url: String): CapturedLink? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000L) { // Cho phép trang web load tối đa 25s để giải mã
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (e: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val wv = WebView(ctx)
                    var isCaptured = false

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = UA
                        mediaPlaybackRequiresUserGesture = false // Cho phép video tự chạy để bắt link
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Bật Cookie để lưu Token của Sonar
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.webViewClient = object : WebViewClient() {
                        // ĐÂY LÀ TRÁI TIM CỦA THUẬT TOÁN
                        // Chúng ta đứng ở cổng mạng, kiểm tra MỌI request mà trang web gửi đi
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val reqUrl = request.url.toString()

                            // Nếu phát hiện trình duyệt đang cố tải file m3u8
                            if (reqUrl.contains(".m3u8") && !isCaptured) {
                                isCaptured = true
                                
                                // Lấy toàn bộ Header (Cookie, Token, Referer) mà trình duyệt vừa tạo ra
                                val reqHeaders = request.requestHeaders ?: emptyMap()
                                
                                // Trả kết quả về cho Kotlin
                                view.post { view.destroy() }
                                if (cont.isActive) cont.resume(CapturedLink(reqUrl, reqHeaders))
                                
                                // Chặn request này lại để video không phát ngầm trong WebView
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            
                            // Để cho các request khác (JS, CSS, API xác thực) chạy bình thường
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    // Tải trang Player với Referer chuẩn
                    wv.loadUrl(url, mapOf("Referer" to "$mainUrl/"))

                    cont.invokeOnCancellation {
                        wv.destroy()
                    }
                }
            }
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

        // 1. Tìm URL của Sonar Player
        val sonarRegex = """https?://play\.sonar-cdn\.com/watch\?v=([a-zA-Z0-9-]+)""".toRegex()
        val sonarMatch = sonarRegex.find(html) ?: sonarRegex.find(res.document.html())
        val sonarUrl = sonarMatch?.value

        if (!sonarUrl.isNullOrBlank()) {
            val fixedSonarUrl = fixUrl(sonarUrl)
            
            // 2. Kích hoạt God Mode: Mở WebView ẩn và cướp request
            val captured = captureRealRequest(fixedSonarUrl)

            if (captured != null) {
                // 3. Trả về link với CHÍNH XÁC những Header mà trình duyệt đã dùng
                val finalHeaders = captured.headers.toMutableMap()
                // Đảm bảo luôn có Origin chuẩn
                if (!finalHeaders.containsKey("Origin")) {
                    finalHeaders["Origin"] = "https://play.sonar-cdn.com"
                }

                callback(
                    newExtractorLink(
                        source = "Sonar CDN",
                        name = "Server VIP (God Mode)",
                        url = captured.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = fixedSonarUrl
                        this.quality = Qualities.P1080.value
                        this.headers = finalHeaders // Truyền toàn bộ Header cướp được vào đây
                    }
                )
            }
        }

        // Quét các iframe khác (nếu có)
        res.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("sonar-cdn")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
