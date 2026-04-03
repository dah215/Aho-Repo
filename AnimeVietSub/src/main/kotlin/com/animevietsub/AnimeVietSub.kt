package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * AnimeVietSub Plugin v4 — Fix Error 2004
 *
 * Root cause: segment URLs chứa token hết hạn rất nhanh (giây).
 * Giải pháp:
 *   1. Giữ WebView alive (không destroy) để dùng làm HTTP engine
 *   2. SmartProxy re-fetch M3U8 mỗi lần cần segment → lấy token FRESH
 *   3. Dùng WebView fetch() để tải segment (bypass TLS fingerprint / Cloudflare)
 *   4. Truyền binary data qua JS bridge bằng chunked base64
 */
@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        val provider = AnimeVietSubProvider()
        registerMainAPI(provider)
        GlobalScope.launch { provider.prefetchAvsJs() }
    }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl = "https://animevietsub.id"
    override var name = "AnimeVietSub"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer" to "$mainUrl/"
    )

    private var cachedAvsJs: String? = null
    @Volatile private var lastStreamCookies: String = ""

    // v4: session management
    @Volatile private var activeWebView: WebView? = null
    @Volatile private var activeBridge: SegmentBridge? = null
    @Volatile private var activeProxy: SmartProxyServer? = null

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/" to "Anime Mới",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    private fun pageUrl(base: String, page: Int) =
        if (page == 1) "${base.trimEnd('/')}/"
        else "${base.trimEnd('/')}/trang-$page.html"

    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a = article.selectFirst("a[href]") ?: return null
        val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title = article.selectFirst("h2.Title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = article.selectFirst("div.Image img, figure img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum = article.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), headers = baseHeaders).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/",
            headers = baseHeaders
        ).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base = url.trimEnd('/')
        val infoDoc = try { app.get("$base/", headers = baseHeaders).document }
            catch (_: Exception) { null }
        val watchDoc = app.get("$base/xem-phim.html", headers = baseHeaders).document
        val title = watchDoc.selectFirst("h1.Title")?.text()?.trim()
            ?: infoDoc?.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val altTitle = watchDoc.selectFirst("h2.SubTitle")?.text()?.trim()
            ?: infoDoc?.selectFirst("h2.SubTitle")?.text()?.trim()
        val poster = (watchDoc.selectFirst("div.Image figure img")?.attr("src")
            ?: infoDoc?.selectFirst("div.Image figure img")?.attr("src"))
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plotOriginal = watchDoc.selectFirst("div.Description")?.text()?.trim()
            ?: infoDoc?.selectFirst("div.Description")?.text()?.trim()
        fun metaValue(doc: org.jsoup.nodes.Document?, label: String): String? {
            if (doc == null) return null
            for (li in doc.select("li")) {
                val lbl = li.selectFirst("label")
                if (lbl != null && lbl.text().contains(label, ignoreCase = true))
                    return li.text().substringAfter(lbl.text()).trim().ifBlank { null }
            }
            val found = doc.selectFirst("li:contains($label)")
            if (found != null) return found.text().replace(label, "").trim().ifBlank { null }
            return null
        }
        val views = watchDoc.selectFirst("span.View")?.text()?.trim()
        val quality = watchDoc.selectFirst("span.Qlty")?.text()?.trim() ?: "HD"
        val year = (watchDoc.selectFirst("p.Info .Date a, p.Info .Date, span.Date a")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull())
            ?: (infoDoc?.selectFirst("p.Info .Date a, p.Info .Date, span.Date a")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull())
        val status = (metaValue(infoDoc, "Trạng thái") ?: metaValue(watchDoc, "Trạng thái"))
            ?.replace("VietSub", "Vietsub")
        val duration = metaValue(infoDoc, "Thời lượng") ?: metaValue(watchDoc, "Thời lượng")
        val country = infoDoc?.selectFirst("li:contains(Quốc gia:) a")?.text()?.trim()
            ?: watchDoc.selectFirst("li:contains(Quốc gia:) a")?.text()?.trim()
        val studio = (metaValue(infoDoc, "Studio") ?: metaValue(infoDoc, "Đạo diễn"))
            ?: (metaValue(watchDoc, "Studio") ?: metaValue(watchDoc, "Đạo diễn"))
        val followers = metaValue(infoDoc, "Theo dõi") ?: metaValue(watchDoc, "Theo dõi")
        val tags = (infoDoc?.select("p.Genre a, li:contains(Thể loại:) a")
            ?: watchDoc.select("p.Genre a, li:contains(Thể loại:) a"))
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val latestEps = (infoDoc?.select("li.latest_eps a")
            ?: watchDoc.select("li.latest_eps a"))
            .map { it.text().trim() }.take(3).joinToString(", ")
        val description = buildString {
            altTitle?.takeIf { it.isNotBlank() }?.let { append("<font color='#AAAAAA'><i>$it</i></font><br><br>") }
            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank()) append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
            }
            val sc = when {
                status?.contains("đang chiếu", true) == true -> "#4CAF50"
                status?.contains("hoàn thành", true) == true -> "#2196F3"
                else -> "#FF9800"
            }
            addInfo("📺", "Trạng thái", status, sc); addInfo("⏱", "Thời lượng", duration)
            addInfo("🎬", "Chất lượng", quality, "#E91E63"); addInfo("🌍", "Quốc gia", country)
            addInfo("📅", "Năm", year?.toString()); addInfo("🎥", "Studio", studio)
            addInfo("👥", "Theo dõi", followers); addInfo("👁", "Lượt xem", views)
            addInfo("🎞", "Tập mới", latestEps.ifBlank { null })
            addInfo("🏷", "Thể loại", tags.joinToString(", "))
            plotOriginal?.takeIf { it.isNotBlank() }?.let {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>"); append(it.trim())
            }
        }
        val seen = mutableSetOf<String>()
        val episodes = watchDoc.select(
            "#list-server .list-episode a.episode-link, .listing.items a[href*=/tap-], a[href*=-tap-]"
        ).mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (href.isBlank() || !seen.add(href)) return@mapNotNull null
            val epNum = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
            val epTitle = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
            newEpisode(href) { this.name = epTitle; this.episode = epNum }
        }.distinctBy { it.episode ?: it.data }.sortedBy { it.episode ?: 0 }
        return if (episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html") {
                this.posterUrl = poster; this.plot = description
                this.tags = tags; this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster; this.plot = description
                this.tags = tags; this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ================================================================
    // WebView M3U8 + Segment extraction
    // ================================================================

    private val blobInterceptor = """
;(function(){var _oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=_oc.apply(this,arguments);
try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){var r=new FileReader();
r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};
r.readAsText(b);}}catch(x){}return u;};})();
""".trimIndent()

    private val fakeAds = """
window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;
window.adsbygoogle.push=function(){};
""".trimIndent()

    // ================================================================
    // v4: SegmentBridge — JS↔Kotlin bridge cho chunked binary transfer
    // ================================================================
    inner class SegmentBridge {
        @Volatile var m3u8Content: String? = null
        @Volatile var m3u8Url: String? = null

        private val pending = ConcurrentHashMap<String, PendingSegment>()

        inner class PendingSegment {
            val latch = CountDownLatch(1)
            @Volatile var data: ByteArray? = null
            @Volatile var error: String? = null
            private val chunks = mutableListOf<String>()
            @Volatile var totalChunks = 0
            @Volatile var receivedChunks = 0

            @Synchronized fun addChunk(idx: Int, chunk: String) {
                // Ensure list is large enough
                while (chunks.size <= idx) chunks.add("")
                chunks[idx] = chunk
                receivedChunks++
            }

            @Synchronized fun assemble(): ByteArray? {
                if (chunks.isEmpty()) return null
                val full = chunks.joinToString("")
                return try { android.util.Base64.decode(full, android.util.Base64.DEFAULT) }
                catch (e: Exception) { null }
            }
        }

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) m3u8Content = content
        }

        @JavascriptInterface
        fun onSegmentStart(requestId: String, chunkCount: Int) {
            val p = pending[requestId] ?: return
            p.totalChunks = chunkCount
        }

        @JavascriptInterface
        fun onSegmentChunk(requestId: String, index: Int, data: String) {
            val p = pending[requestId] ?: return
            p.addChunk(index, data)
            if (p.receivedChunks >= p.totalChunks && p.totalChunks > 0) {
                p.data = p.assemble()
                p.latch.countDown()
            }
        }

        @JavascriptInterface
        fun onSegmentError(requestId: String, errorMsg: String) {
            val p = pending[requestId] ?: return
            p.error = errorMsg
            p.latch.countDown()
        }

        fun prepareSegment(requestId: String): PendingSegment {
            val p = PendingSegment()
            pending[requestId] = p
            return p
        }

        fun cleanup(requestId: String) { pending.remove(requestId) }
    }

    suspend fun prefetchAvsJs() {
        if (cachedAvsJs != null) return
        try {
            val pageHtml = try { app.get("$mainUrl/", headers = baseHeaders).text } catch(_: Exception) { "" }
            val detectedPath = Regex("""statics/default/js/((?:pl\.watchbk\d+|avs\.watch)\.js\?v=[0-9.]+)""")
                .find(pageHtml)?.groupValues?.get(1)
            val jsUrl = if (!detectedPath.isNullOrBlank())
                "$mainUrl/statics/default/js/$detectedPath"
            else "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9"
            val js = app.get(jsUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*")).text
            if (js.length > 500) cachedAvsJs = js
        } catch (_: Exception) {}
    }

    private suspend fun fetchJs(url: String, cookie: String): String? {
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*", "Cookie" to cookie
            ))
            if (resp.text.length > 500) return resp.text
        } catch (_: Exception) {}
        return try {
            val html = app.get("$mainUrl/", headers = baseHeaders).text
            val path = Regex("""statics/default/js/((?:pl\.watchbk\d+|avs\.watch)\.js\?v=[0-9.]+)""")
                .find(html)?.groupValues?.get(1) ?: return null
            val js = app.get("$mainUrl/statics/default/js/$path", headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/", "Cookie" to cookie
            )).text
            if (js.length > 500) js else null
        } catch (_: Exception) { null }
    }

    // ================================================================
    // v4: getM3U8 — Giữ WebView alive để dùng cho segment fetch
    // ================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8KeepAlive(epUrl: String, cookie: String, avsJs: String): Pair<String?, WebView?> {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null to null); return@suspendCancellableCoroutine }

                    val bridge = SegmentBridge()

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        cookie.split(";").forEach { kv ->
                            val t = kv.trim()
                            if (t.isNotBlank()) setCookie(mainUrl, t)
                        }
                        flush()
                    }

                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false; userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        databaseEnabled = true
                        allowFileAccess = true
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")

                    val patchedJs = (blobInterceptor + "\n" + avsJs).toByteArray(Charsets.UTF_8)
                    val fakeAdsBytes = fakeAds.toByteArray(Charsets.UTF_8)

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url.toString()
                            return when {
                                url.contains("watchbk") || url.contains("avs.watch") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(patchedJs))
                                url.contains("googleapiscdn.com") && url.contains(".m3u8") -> {
                                    bridge.m3u8Url = url; null
                                }
                                url.contains("adsbygoogle") || url.contains("googlesyndication") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(fakeAdsBytes))
                                url.contains("google-analytics") || url.contains("doubleclick") ||
                                url.contains("googletagmanager") || url.contains("facebook.com") ||
                                url.contains("hotjar") || url.contains("disqus") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream("".toByteArray()))
                                url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf") ||
                                url.endsWith(".eot") || (url.endsWith(".css") && !url.contains(mainUrl)) ->
                                    WebResourceResponse("text/css", "utf-8", ByteArrayInputStream("".toByteArray()))
                                url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                                url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".svg") ->
                                    WebResourceResponse("image/png", "utf-8", ByteArrayInputStream("".toByteArray()))
                                else -> null
                            }
                        }
                    }

                    wv.loadUrl(epUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9", "Referer" to "$mainUrl/"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            when {
                                bridge.m3u8Url != null || bridge.m3u8Content != null -> {
                                    try {
                                        val c1 = CookieManager.getInstance().getCookie("https://stream.googleapiscdn.com") ?: ""
                                        val c2 = CookieManager.getInstance().getCookie("http://stream.googleapiscdn.com") ?: ""
                                        val c3 = CookieManager.getInstance().getCookie("https://animevietsub.id") ?: ""
                                        lastStreamCookies = listOf(c1, c2, c3, cookie)
                                            .filter { it.isNotBlank() }.distinct().joinToString("; ")
                                    } catch (_: Exception) {}

                                    wv.stopLoading()
                                    // ★ v4: KHÔNG destroy WebView — giữ alive cho segment fetch
                                    activeWebView = wv
                                    activeBridge = bridge

                                    val res = bridge.m3u8Url?.let { "DIRECT_URL::$it" }
                                        ?: bridge.m3u8Content
                                    if (cont.isActive) cont.resume(res to wv)
                                }
                                elapsed >= 25_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null to null)
                                }
                                else -> { elapsed += 200; handler.postDelayed(this, 200) }
                            }
                        }
                    }
                    handler.postDelayed(checker, 800)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker); wv.stopLoading()
                    }
                }
            } ?: (null to null)
        }
    }

    // ================================================================
    // v4: SmartProxyServer — Dynamic M3U8 refresh + WebView segment fetch
    // ================================================================
    inner class SmartProxyServer(
        private val webView: WebView,
        private val bridge: SegmentBridge,
        private val m3u8SourceUrl: String?,   // URL gốc để re-fetch M3U8 (null nếu chỉ có blob)
        private val m3u8FetchHeaders: Map<String, String>,
        private val segHeaders: Map<String, String>,
        initialM3U8: String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private val m3u8Lock = Any()
        private var cachedM3U8: String = initialM3U8
        private var m3u8FetchTime: Long = System.currentTimeMillis()

        // M3U8 cache TTL: 8 giây — token thường hết hạn sau vài giây
        private val M3U8_CACHE_MS = 8_000L
        // Base64 chunk size cho JS bridge (512KB an toàn cho mọi Android)
        private val B64_CHUNK_SIZE = 512 * 1024

        val port: Int get() = serverSocket?.localPort ?: 0

        private val isMaster = cachedM3U8.contains("#EXT-X-STREAM-INF")

        fun start() {
            serverSocket = java.net.ServerSocket(0, 50)
            Thread {
                try {
                    val ss = serverSocket ?: return@Thread
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            Thread { handleClient(client) }.also { it.isDaemon = true }.start()
                        } catch (_: java.net.SocketException) { break }
                        catch (_: Exception) { break }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }

        fun stop() { try { serverSocket?.close() } catch (_: Exception) {} }

        // ----------------------------------------------------------
        // Re-fetch M3U8 để lấy token FRESH cho segments
        // ----------------------------------------------------------
        private fun refreshM3U8(): String {
            synchronized(m3u8Lock) {
                val now = System.currentTimeMillis()
                if (m3u8SourceUrl != null && now - m3u8FetchTime > M3U8_CACHE_MS) {
                    try {
                        val fresh = runBlocking {
                            app.get(m3u8SourceUrl, headers = m3u8FetchHeaders).text
                        }
                        if (fresh.contains("#EXTM3U")) {
                            cachedM3U8 = fresh
                            m3u8FetchTime = now
                        }
                    } catch (_: Exception) {}
                }
                return cachedM3U8
            }
        }

        // Parse URL thứ N từ M3U8 (media segment hoặc media playlist)
        private fun parseNthUrl(m3u8: String, index: Int, isSegment: Boolean): String? {
            var urlIdx = -1
            for (line in m3u8.lines()) {
                val t = line.trim()
                if (isSegment) {
                    if (t.startsWith("#EXTINF")) urlIdx++
                    if (urlIdx == index && t.startsWith("http")) return t
                } else {
                    if (t.startsWith("#EXT-X-STREAM-INF")) urlIdx++
                    if (urlIdx == index && t.startsWith("http")) return t
                }
            }
            return null
        }

        // Rewrite M3U8 cho ExoPlayer — thay thế URL bằng proxy path
        private fun rewriteForExoPlayer(content: String): String {
            val sb = StringBuilder()
            var idx = 0
            var urlType = 0 // 0=unknown, 1=master-quality, 2=media-segment
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) urlType = 1
                else if (t.startsWith("#EXTINF:")) urlType = 2
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    if (urlType == 1) {
                        sb.append("http://127.0.0.1:$port/media/$idx")
                        idx++; urlType = 0
                    } else {
                        sb.append("http://127.0.0.1:$port/seg/$idx")
                        idx++; urlType = 0
                    }
                } else {
                    sb.append(line)
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        // Rewrite media playlist — segment URLs → proxy paths
        private fun rewriteMediaPlaylist(content: String, qualityIdx: Int): String {
            val sb = StringBuilder()
            var idx = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    sb.append("http://127.0.0.1:$port/seg/${qualityIdx}_$idx")
                    idx++
                } else {
                    sb.append(line)
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        // ----------------------------------------------------------
        // ★ CORE: Fetch segment qua WebView fetch() API
        // Dùng browser engine thực sự → bypass TLS fingerprint / Cloudflare
        // ----------------------------------------------------------
        private fun fetchSegmentViaWebView(segUrl: String): ByteArray? {
            val requestId = "s${System.currentTimeMillis()}_${segUrl.hashCode()}"
            val pending = bridge.prepareSegment(requestId)

            // Escape URL cho JavaScript
            val escapedUrl = segUrl
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
                .replace("\r", "")

            val jsCode = """
(function() {
    try {
        var reqId = '$requestId';
        var url = '$escapedUrl';
        fetch(url, {
            credentials: 'include',
            headers: {
                'Accept': '*/*',
                'Accept-Encoding': 'identity'
            }
        }).then(function(response) {
            if (!response.ok) {
                Android.onSegmentError(reqId, 'HTTP_' + response.status);
                return;
            }
            return response.arrayBuffer();
        }).then(function(buffer) {
            if (!buffer) return;
            // Convert ArrayBuffer → base64 dùng FileReader (xử lý lớn an toàn)
            var blob = new Blob([buffer], {type: 'application/octet-stream'});
            var reader = new FileReader();
            reader.onload = function() {
                try {
                    var dataUrl = reader.result;
                    var base64 = dataUrl.split(',')[1];
                    if (!base64 || base64.length === 0) {
                        Android.onSegmentError(reqId, 'empty_data');
                        return;
                    }
                    // Chunked transfer: 512KB mỗi chunk
                    var chunkSize = $B64_CHUNK_SIZE;
                    var totalChunks = Math.ceil(base64.length / chunkSize);
                    Android.onSegmentStart(reqId, totalChunks);
                    for (var i = 0; i < totalChunks; i++) {
                        var start = i * chunkSize;
                        var end = Math.min(start + chunkSize, base64.length);
                        Android.onSegmentChunk(reqId, i, base64.substring(start, end));
                    }
                } catch(e) {
                    Android.onSegmentError(reqId, 'encode_' + e.message);
                }
            };
            reader.onerror = function() {
                Android.onSegmentError(reqId, 'reader_error');
            };
            reader.readAsDataURL(blob);
        }).catch(function(err) {
            Android.onSegmentError(reqId, 'fetch_' + err.message);
        });
    } catch(e) {
        Android.onSegmentError('$requestId', 'js_' + e.message);
    }
})();
""".trimIndent()

            try {
                // evaluateJavascript phải chạy trên main thread
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val jsException = arrayOf<Exception?>(null)
                val posted = java.util.concurrent.atomic.AtomicBoolean(false)

                mainHandler.post {
                    try {
                        webView.evaluateJavascript(jsCode, null)
                        posted.set(true)
                    } catch (e: Exception) {
                        jsException[0] = e
                        posted.set(true)
                    }
                }

                // Đợi WebView xử lý JS + fetch + transfer (tối đa 45 giây)
                val completed = pending.latch.await(45, TimeUnit.SECONDS)

                if (!completed) {
                    bridge.cleanup(requestId)
                    return null
                }

                if (pending.error != null) return null
                return pending.data
            } catch (_: InterruptedException) {
                return null
            } catch (_: Exception) {
                return null
            } finally {
                bridge.cleanup(requestId)
            }
        }

        // ----------------------------------------------------------
        // HTTP request handler
        // ----------------------------------------------------------
        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 60_000
                val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotBlank() == true) {} // consume headers

                val parts = requestLine.split(" ")
                if (parts.size < 2) { client.close(); return }
                val path = parts[1].substringBefore("?")

                when {
                    parts[0] == "OPTIONS" -> writeResponse(client, 200,
                        mapOf("Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                            "Access-Control-Allow-Headers" to "*",
                            "Content-Length" to "0"), ByteArray(0))

                    path == "/stream.m3u8" || path == "/master.m3u8" -> {
                        val fresh = refreshM3U8()
                        val rewritten = rewriteForExoPlayer(fresh)
                        val body = rewritten.toByteArray(Charsets.UTF_8)
                        writeResponse(client, 200,
                            mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                                "Content-Length" to body.size.toString(),
                                "Cache-Control" to "no-cache, no-store, must-revalidate"), body)
                    }

                    path.startsWith("/media/") -> {
                        val qi = path.removePrefix("/media/").toIntOrNull() ?: -1
                        serveMediaPlaylist(client, qi)
                    }

                    path.startsWith("/seg/") -> {
                        val segId = path.removePrefix("/seg/")
                        val sep = segId.indexOf("_")
                        if (sep >= 0) {
                            // qualityIndex_segmentIndex
                            val qi = segId.substring(0, sep).toIntOrNull() ?: -1
                            val si = segId.substring(sep + 1).toIntOrNull() ?: -1
                            serveSegment(client, qi, si)
                        } else {
                            val si = segId.toIntOrNull() ?: -1
                            serveSegment(client, -1, si)
                        }
                    }

                    else -> writeResponse(client, 404,
                        mapOf("Content-Type" to "text/plain"), "Not found".toByteArray())
                }
            } catch (_: java.net.SocketException) {}
            catch (_: Exception) {} finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        // ----------------------------------------------------------
        // Serve media playlist (cho master playlist)
        // ----------------------------------------------------------
        private fun serveMediaPlaylist(client: java.net.Socket, qualityIndex: Int) {
            val fresh = refreshM3U8()
            val mediaUrl = parseNthUrl(fresh, qualityIndex, isSegment = false)
            if (mediaUrl == null) {
                writeResponse(client, 404, mapOf("Content-Type" to "text/plain"),
                    "Media playlist not found".toByteArray())
                return
            }

            val content = try {
                runBlocking { app.get(mediaUrl, headers = m3u8FetchHeaders).text }
            } catch (_: Exception) {
                writeResponse(client, 502, mapOf("Content-Type" to "text/plain"),
                    "Failed to fetch media playlist".toByteArray())
                return
            }

            if (!content.contains("#EXTM3U")) {
                writeResponse(client, 502, mapOf("Content-Type" to "text/plain"),
                    "Invalid media playlist".toByteArray())
                return
            }

            val rewritten = rewriteMediaPlaylist(content, qualityIndex)
            val body = rewritten.toByteArray(Charsets.UTF_8)
            writeResponse(client, 200,
                mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                    "Content-Length" to body.size.toString(),
                    "Cache-Control" to "no-cache"), body)
        }

        // ----------------------------------------------------------
        // ★ Serve segment — core logic
        // ----------------------------------------------------------
        private fun serveSegment(client: java.net.Socket, qualityIndex: Int, segIndex: Int) {
            var segUrl: String? = null

            if (qualityIndex >= 0 && isMaster) {
                // Master playlist: cần fetch media playlist trước
                val fresh = refreshM3U8()
                val mediaUrl = parseNthUrl(fresh, qualityIndex, isSegment = false)
                if (mediaUrl != null) {
                    val mediaContent = try {
                        runBlocking { app.get(mediaUrl, headers = m3u8FetchHeaders).text }
                    } catch (_: Exception) { null }
                    if (mediaContent != null) {
                        segUrl = parseNthUrl(mediaContent, segIndex, isSegment = true)
                    }
                }
            } else {
                // Media playlist trực tiếp: re-fetch M3U8 → lấy segment URL mới (token fresh!)
                val fresh = refreshM3U8()
                segUrl = parseNthUrl(fresh, segIndex, isSegment = true)
            }

            if (segUrl == null) {
                writeResponse(client, 404, mapOf("Content-Type" to "text/plain"),
                    "Segment URL not found".toByteArray())
                return
            }

            // ★ PRIMARY: Fetch segment qua WebView (bypass mọi protection)
            val data = fetchSegmentViaWebView(segUrl)
            if (data != null && data.isNotEmpty()) {
                writeResponse(client, 200,
                    mapOf("Content-Type" to "video/mp2t",
                        "Content-Length" to data.size.toString(),
                        "Accept-Ranges" to "bytes",
                        "Connection" to "close"), data)
                return
            }

            // FALLBACK: Thử app.get() (có thể không work nhưng thử anyway)
            try {
                val resp = runBlocking { app.get(segUrl, headers = segHeaders) }
                val raw = try {
                    val f = resp.javaClass.declaredFields.firstOrNull {
                        it.name.contains("body", ignoreCase = true) ||
                        it.name.contains("response", ignoreCase = true)
                    }
                    if (f != null) {
                        f.isAccessible = true
                        val bodyObj = f.get(resp)
                        bodyObj?.javaClass?.methods?.firstOrNull {
                            it.name == "bytes" && it.parameterCount == 0 &&
                            it.returnType == ByteArray::class.java
                        }?.invoke(bodyObj) as? ByteArray
                    } else null
                } catch (_: Exception) { null }

                if (raw != null && raw.size > 100) {
                    writeResponse(client, 200,
                        mapOf("Content-Type" to "video/mp2t",
                            "Content-Length" to raw.size.toString()), raw)
                    return
                }
            } catch (_: Exception) {}

            // FAILED: trả về 502 để ExoPlayer retry
            writeResponse(client, 502, mapOf("Content-Type" to "text/plain"),
                "Segment fetch failed".toByteArray())
        }

        private fun writeResponse(client: java.net.Socket, code: Int, headers: Map<String, String>, body: ByteArray) {
            try {
                val os = client.getOutputStream()
                os.write("HTTP/1.1 $code\r\n".toByteArray())
                headers.forEach { (k, v) -> os.write("$k: $v\r\n".toByteArray()) }
                os.write("\r\n".toByteArray())
                if (body.isNotEmpty()) os.write(body)
                os.flush()
            } catch (_: Exception) {}
        }
    }

    // ================================================================
    // loadLinks — v4
    // ================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cleanup session trước
        cleanupSession()

        val epUrl = data.substringBefore("|")

        // 1. Tìm iframe URL
        val epPageHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://stream\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._-]+""")
            .find(epPageHtml)?.value?.replace("&amp;", "&")

        // 2. Lấy ajax cookies
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val ajaxCookie = try {
            app.post("$mainUrl/ajax/player", headers = mapOf(
                "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to epUrl, "Origin" to mainUrl
            ), data = mapOf("episodeId" to epId, "backup" to "1"))
                .cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (_: Exception) { "" }

        // 3. Lấy player JS
        val avsJs = cachedAvsJs ?: fetchJs(
            "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9", ajaxCookie
        )?.also { cachedAvsJs = it } ?: return true

        // 4. Lấy M3U8 từ WebView (GIỮ WEBVIEW ALIVE!)
        val targetUrl = iframeUrl ?: epUrl
        val (m3u8Result, webView) = getM3U8KeepAlive(targetUrl, ajaxCookie, avsJs)
        if (m3u8Result == null || webView == null) {
            cleanupSession()
            return true
        }
        val bridge = activeBridge

        // 5. Thu thập cookies
        val streamCookies = lastStreamCookies
        val allCookies = listOf(streamCookies, ajaxCookie)
            .filter { it.isNotBlank() }.distinct().joinToString("; ")

        // 6. Lấy nội dung M3U8
        val m3u8Content: String
        val directM3u8Url: String?

        if (m3u8Result.startsWith("DIRECT_URL::")) {
            directM3u8Url = m3u8Result.removePrefix("DIRECT_URL::")
            val fetchHdrs = mutableMapOf(
                "User-Agent" to UA,
                "Referer" to "https://stream.googleapiscdn.com/",
                "Accept" to "*/*"
            )
            if (allCookies.isNotBlank()) fetchHdrs["Cookie"] = allCookies
            m3u8Content = try { app.get(directM3u8Url, headers = fetchHdrs).text }
                catch (_: Exception) { "" }
            if (!m3u8Content.contains("#EXTM3U")) { cleanupSession(); return true }
        } else {
            directM3u8Url = null
            m3u8Content = m3u8Result
            if (!m3u8Content.contains("#EXTM3U")) { cleanupSession(); return true }
        }

        // 7. Headers
        val segHeaders = mutableMapOf(
            "User-Agent" to UA, "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Referer" to "https://stream.googleapiscdn.com/player/",
            "Origin" to "https://stream.googleapiscdn.com"
        )
        if (allCookies.isNotBlank()) segHeaders["Cookie"] = allCookies

        val m3u8Headers = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "https://stream.googleapiscdn.com/",
            "Accept" to "*/*"
        )
        if (allCookies.isNotBlank()) m3u8Headers["Cookie"] = allCookies

        // 8. ★ Khởi tạo Smart Proxy (v4)
        if (bridge == null) { cleanupSession(); return true }

        val server = SmartProxyServer(
            webView = webView,
            bridge = bridge,
            m3u8SourceUrl = directM3u8Url,
            m3u8FetchHeaders = m3u8Headers,
            segHeaders = segHeaders,
            initialM3U8 = m3u8Content
        )
        server.start()
        activeProxy = server

        callback(newExtractorLink(
            source = name, name = "$name",
            url = "http://127.0.0.1:${server.port}/stream.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })

        return true
    }

    // ================================================================
    // Cleanup session
    // ================================================================
    private fun cleanupSession() {
        activeProxy?.stop()
        activeProxy = null
        val wv = activeWebView
        activeWebView = null
        activeBridge = null
        if (wv != null) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { wv.stopLoading() } catch (_: Exception) {}
                    try { wv.destroy() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }
}
