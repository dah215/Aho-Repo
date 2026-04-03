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
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * AnimeVietSub Plugin v7 — Fix Error 2004
 *
 * Root cause (confirmed by network capture):
 *   - stream.googleapiscdn.com KHÔNG có CORS headers
 *   - XHR cross-origin bị block hoàn toàn
 *
 * Strategy:
 *   Phase 1: Load episode page (animevietsub.id) → inject playerJs + blobInterceptor
 *            → shouldInterceptRequest bắt M3U8 URL → lấy cookies
 *   Phase 2: Navigate WebView tới player URL (stream.googleapiscdn.com)
 *            → giờ XHR là same-origin → withCredentials gửi cookies
 *   Phase 3: Proxy dùng WebView XHR cho M3U8 refresh + segment fetch
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

    @Volatile private var activeWebView: WebView? = null
    @Volatile private var activeBridge: JsBridge? = null
    @Volatile private var activeProxy: StreamProxy? = null

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
    // JavaScript injections
    // ================================================================

    /** Blob interceptor — catch M3U8 content from URL.createObjectURL */
    private val blobInterceptor = """
;(function(){var _oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=_oc.apply(this,arguments);
try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){var r=new FileReader();
r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};
r.readAsText(b);}}catch(x){}return u;};})();
""".trimIndent()

    /**
     * ★ Network interceptor — catch M3U8 URL via fetch() and XHR
     * Injected via evaluateJavascript (not shouldInterceptRequest)
     */
    private val networkInterceptor = """
;(function(){
function rp(url){
    if(url&&(url.indexOf('.m3u8')!==-1||url.indexOf('mpegurl')!==-1||
       url.indexOf('/playlist/')!==-1||url.indexOf('/video/')!==-1)){
        try{Android.onM3U8Url(url);}catch(x){}
    }
}
var _f=window.fetch;
if(_f)window.fetch=function(){
    if(arguments.length>0&&typeof arguments[0]==='string')rp(arguments[0]);
    else if(arguments[0]&&arguments[0].url)rp(arguments[0].url);
    return _f.apply(this,arguments);
};
var _x=XMLHttpRequest.prototype.open;
if(_x)XMLHttpRequest.prototype.open=function(m,u){
    if(typeof u==='string')rp(u);
    return _x.apply(this,arguments);
};
})();
""".trimIndent()

    private val fakeAds = """
window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;
window.adsbygoogle.push=function(){};
""".trimIndent()

    // ================================================================
    // JsBridge
    // ================================================================
    inner class JsBridge {
        @Volatile var m3u8Content: String? = null
        @Volatile var m3u8Url: String? = null
        @Volatile var pageLoaded: Boolean = false
        private val pending = ConcurrentHashMap<String, PendingReq>()

        inner class PendingReq {
            val latch = CountDownLatch(1)
            @Volatile var data: ByteArray? = null
            @Volatile var text: String? = null
            @Volatile var error: String? = null
            @Volatile var done = false
            private val chunks = ConcurrentHashMap<Int, String>()
            @Volatile private var chunkTotal: Int = -1

            @Synchronized fun addChunk(idx: Int, chunk: String) {
                chunks[idx] = chunk
                if (chunkTotal > 0 && chunks.size >= chunkTotal) finishBinary()
            }

            @Synchronized fun setTotalChunks(n: Int) {
                chunkTotal = n
                if (n > 0 && chunks.size >= n) finishBinary()
            }

            @Synchronized fun finishBinary() {
                if (!done) {
                    done = true
                    val keys = chunks.keys.sorted()
                    val full = keys.joinToString("") { chunks[it] ?: "" }
                    data = try { android.util.Base64.decode(full, android.util.Base64.DEFAULT) }
                    catch (e: Exception) { null }
                    latch.countDown()
                }
            }

            @Synchronized fun finishText(t: String) {
                if (!done) { done = true; text = t; latch.countDown() }
            }

            @Synchronized fun finishError(msg: String) {
                if (!done) { done = true; error = msg; latch.countDown() }
            }
        }

        @JavascriptInterface fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) m3u8Content = content
        }

        @JavascriptInterface fun onM3U8Url(url: String) {
            if (url.contains("m3u8") || url.contains("googleapiscdn")) m3u8Url = url
        }

        @JavascriptInterface fun onPageReady() { pageLoaded = true }

        @JavascriptInterface fun onReqStart(id: String, total: Int) { pending[id]?.setTotalChunks(total) }
        @JavascriptInterface fun onReqChunk(id: String, index: Int, b64: String) { pending[id]?.addChunk(index, b64) }
        @JavascriptInterface fun onReqDone(id: String) { pending[id]?.finishBinary() }
        @JavascriptInterface fun onReqError(id: String, msg: String) { pending[id]?.finishError(msg) }
        @JavascriptInterface fun onTextDone(id: String, txt: String) { pending[id]?.finishText(txt) }

        fun prepare(id: String): PendingReq { val p = PendingReq(); pending[id] = p; return p }
        fun cleanup(id: String) { pending.remove(id) }
    }

    // ================================================================
    // Js helpers
    // ================================================================

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
    // ★ v7: TWO-PHASE WebView setup
    // ================================================================
    // Phase 1: Load episode page (animevietsub.id) → find M3U8 URL + cookies
    // Phase 2: Navigate to player URL (stream.googleapiscdn.com) → same-origin XHR
    // ================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initTwoPhase(
        epUrl: String,
        playerUrl: String,
        cookie: String,
        avsJs: String
    ): Triple<String?, String, WebView?> {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(45_000L) {
                suspendCancellableCoroutine<Triple<String?, String, WebView?>> { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) {
                        cont.resume(Triple(null, "", null))
                        return@suspendCancellableCoroutine
                    }

                    val bridge = JsBridge()

                    // Set cookies for animevietsub.id
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
                        databaseEnabled = true; allowFileAccess = true
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")

                    // Phase 1: Inject playerJs + blobInterceptor
                    val patchedJs = (blobInterceptor + "\n" + avsJs).toByteArray(Charsets.UTF_8)
                    val fakeAdsBytes = fakeAds.toByteArray(Charsets.UTF_8)

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url.toString()
                            return when {
                                // Phase 1: Inject patched playerJs
                                url.contains("watchbk") || url.contains("avs.watch") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(patchedJs))
                                // Catch M3U8 URL
                                url.contains("googleapiscdn.com") &&
                                    (url.contains(".m3u8") || url.contains("playlist")) -> {
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

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url != null && url.contains("googleapiscdn.com")) {
                                bridge.pageLoaded = true
                                // Phase 2: Inject network interceptor after page load
                                view.evaluateJavascript(networkInterceptor, null)
                            }
                        }
                    }

                    // ======== PHASE 1: Load episode page ========
                    wv.loadUrl(epUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9", "Referer" to "$mainUrl/"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var phase = 1  // 1=finding M3U8, 2=navigating to player
                    var elapsed = 0
                    var phase1M3u8Url: String? = null  // ★ Capture Phase 1 result

                    val checker = object : Runnable {
                        override fun run() {
                            when {
                                phase == 1 && (bridge.m3u8Url != null || bridge.m3u8Content != null) -> {
                                    // Phase 1 complete: found M3U8 URL
                                    handler.postDelayed({
                                        // Extract cookies
                                        try {
                                            val c1 = CookieManager.getInstance().getCookie("https://stream.googleapiscdn.com") ?: ""
                                            val c2 = CookieManager.getInstance().getCookie("http://stream.googleapiscdn.com") ?: ""
                                            val c3 = CookieManager.getInstance().getCookie("https://animevietsub.id") ?: ""
                                            lastStreamCookies = listOf(c1, c2, c3, cookie)
                                                .filter { it.isNotBlank() }.distinct().joinToString("; ")
                                        } catch (_: Exception) {}

                                        phase1M3u8Url = bridge.m3u8Url
                                            ?: bridge.m3u8Content?.let { "BLOB::$it" }

                                        // ======== PHASE 2: Navigate to player URL ========
                                        wv.stopLoading()
                                        bridge.pageLoaded = false
                                        bridge.m3u8Url = null  // Reset — player may load M3U8 again

                                        wv.loadUrl(playerUrl, mapOf(
                                            "Accept-Language" to "vi-VN,vi;q=0.9",
                                            "Referer" to "$mainUrl/"
                                        ))

                                        phase = 2
                                        elapsed = 0
                                        handler.postDelayed(this, 500)
                                    }, 1500)  // Delay 1.5s để cookies set
                                }

                                phase == 2 && bridge.pageLoaded -> {
                                    // Phase 2 complete: player page loaded on stream.googleapiscdn.com
                                    handler.postDelayed({
                                        // Inject network interceptor
                                        wv.evaluateJavascript(networkInterceptor, null)
                                        activeWebView = wv
                                        activeBridge = bridge

                                        val m3u8Result = bridge.m3u8Url
                                            ?: phase1M3u8Url
                                        if (cont.isActive) cont.resume(Triple(m3u8Result, lastStreamCookies, wv as WebView))
                                    }, 2000)  // Delay 2s for Cloudflare + player JS
                                }

                                elapsed >= 40_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(Triple(null, "", null))
                                }

                                else -> { elapsed += 300; handler.postDelayed(this, 300) }
                            }
                        }
                    }
                    handler.postDelayed(checker, 800)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker); wv.stopLoading()
                    }
                }
            } ?: Triple(null, "", null)
        }
    }

    // ================================================================
    // v7: StreamProxy — ALL requests via WebView XHR (same-origin)
    // ================================================================
    inner class StreamProxy(
        private val webView: WebView,
        private val bridge: JsBridge,
        initialM3U8: String
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private val m3u8Lock = Any()
        private var cachedM3U8: String = initialM3U8

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
        // Fetch TEXT via WebView XHR (same-origin, no CORS issues)
        // ----------------------------------------------------------
        private fun fetchTextViaWV(url: String): String? {
            val id = "t${System.currentTimeMillis()}"
            val p = bridge.prepare(id)
            val esc = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { webView.evaluateJavascript("""
(function(){try{var x=new XMLHttpRequest();x.open('GET','$esc',false);
x.withCredentials=true;try{Android.onTextDone('$id',x.responseText);}catch(e){}
}catch(e){Android.onReqError('$id',''+e);}})();
""".trimIndent(), null) } catch (_: Exception) {}
                }
                p.latch.await(15, TimeUnit.SECONDS)
                bridge.cleanup(id)
                return if (p.error != null) null else p.text
            } catch (_: Exception) { return null }
            finally { bridge.cleanup(id) }
        }

        // ----------------------------------------------------------
        // Fetch BINARY via WebView XHR (same-origin, no CORS issues)
        // ----------------------------------------------------------
        private fun fetchBinaryViaWV(url: String): ByteArray? {
            val id = "b${System.currentTimeMillis()}"
            val p = bridge.prepare(id)
            val esc = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { webView.evaluateJavascript("""
(function(){try{
var id='$id';var url='$esc';
var x=new XMLHttpRequest();x.open('GET',url,true);
x.responseType='arraybuffer';x.withCredentials=true;x.timeout=40000;
x.onload=function(){
if(x.status<200||x.status>=300){Android.onReqError(id,'HTTP_'+x.status);return;}
var b=new Uint8Array(x.response);var n=Math.ceil(b.length/32768);
Android.onReqStart(id,n);
for(var i=0;i<n;i++){var s=i*32768;var e=Math.min(s+32768,b.length);
var sl=b.subarray(s,e);var bin='';
for(var j=0;j<sl.length;j++)bin+=String.fromCharCode(sl[j]);
Android.onReqChunk(id,i,btoa(bin));}
Android.onReqDone(id);};
x.onerror=function(){Android.onReqError(id,'err');};
x.ontimeout=function(){Android.onReqError(id,'timeout');};
x.send();}catch(e){Android.onReqError('$id',''+e);}})();
""".trimIndent(), null) } catch (_: Exception) {}
                }
                p.latch.await(50, TimeUnit.SECONDS)
                bridge.cleanup(id)
                return if (p.error != null) null else p.data
            } catch (_: Exception) { return null }
            finally { bridge.cleanup(id) }
        }

        // ----------------------------------------------------------
        // Refresh M3U8 via WebView XHR
        // ----------------------------------------------------------
        private fun refreshM3U8(baseUrl: String): String {
            // Extract base URL (remove query params for clean re-fetch)
            val cleanUrl = baseUrl.substringBefore("?")
            val busterUrl = "$cleanUrl?_t=${System.currentTimeMillis()}"
            val text = fetchTextViaWV(busterUrl)
            if (text != null && text.contains("#EXTM3U") && text.contains("http")) {
                synchronized(m3u8Lock) { cachedM3U8 = text }
                return cachedM3U8
            }
            // Fallback: return cached
            return synchronized(m3u8Lock) { cachedM3U8 }
        }

        // Parse N-th URL
        private fun parseNthUrl(m3u8: String, index: Int, isSegment: Boolean): String? {
            var urlIdx = -1
            for (line in m3u8.lines()) {
                val t = line.trim()
                if (isSegment) {
                    if (t.startsWith("#EXTINF") || t.startsWith("#EXT-X-BYTERANGE")) urlIdx++
                    if (urlIdx == index && (t.startsWith("http://") || t.startsWith("https://"))) return t
                } else {
                    if (t.startsWith("#EXT-X-STREAM-INF")) urlIdx++
                    if (urlIdx == index && (t.startsWith("http://") || t.startsWith("https://"))) return t
                }
            }
            return null
        }

        // Rewrite master playlist
        private fun rewriteMaster(content: String): String {
            val sb = StringBuilder()
            var idx = 0; var uType = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) uType = 1
                else if (t.startsWith("#EXTINF:")) uType = 2
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    if (uType == 1) { sb.append("http://127.0.0.1:$port/media/$idx"); idx++; uType = 0 }
                    else if (uType == 2) { sb.append("http://127.0.0.1:$port/seg/$idx"); idx++; uType = 0 }
                    else sb.append(t)
                } else sb.append(line)
                sb.append("\n")
            }
            return sb.toString()
        }

        // Rewrite media playlist
        private fun rewriteMedia(content: String, qIdx: Int): String {
            val sb = StringBuilder(); var idx = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    sb.append("http://127.0.0.1:$port/seg/${qIdx}_$idx"); idx++
                } else sb.append(line)
                sb.append("\n")
            }
            return sb.toString()
        }

        // ----------------------------------------------------------
        // HTTP handler
        // ----------------------------------------------------------
        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 60_000
                val reader = java.io.BufferedReader(java.io.InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotBlank() == true) {}
                val parts = requestLine.split(" ")
                if (parts.size < 2) { client.close(); return }
                val path = parts[1].substringBefore("?")

                when {
                    parts[0] == "OPTIONS" -> writeResp(client, 200, mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Content-Length" to "0"), ByteArray(0))

                    path == "/stream.m3u8" -> {
                        val body = rewriteMaster(cachedM3U8).toByteArray(Charsets.UTF_8)
                        writeResp(client, 200, mapOf(
                            "Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                            "Content-Length" to body.size.toString(),
                            "Cache-Control" to "no-cache"), body)
                    }

                    path.startsWith("/media/") -> {
                        val qi = path.removePrefix("/media/").toIntOrNull() ?: -1
                        serveMedia(client, qi)
                    }

                    path.startsWith("/seg/") -> {
                        val segId = path.removePrefix("/seg/")
                        val sep = segId.indexOf("_")
                        if (sep >= 0) {
                            serveSegment(client,
                                segId.substring(0, sep).toIntOrNull() ?: -1,
                                segId.substring(sep + 1).toIntOrNull() ?: -1)
                        } else {
                            serveSegment(client, -1, segId.toIntOrNull() ?: -1)
                        }
                    }

                    else -> writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                        "Not found".toByteArray())
                }
            } catch (_: java.net.SocketException) {}
            catch (_: Exception) {} finally { try { client.close() } catch (_: Exception) {} }
        }

        private fun serveMedia(client: java.net.Socket, qi: Int) {
            val mediaUrl = parseNthUrl(cachedM3U8, qi, isSegment = false)
            if (mediaUrl == null) {
                writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                    "Media playlist not found".toByteArray())
                return
            }
            val content = fetchTextViaWV(mediaUrl)
            if (content == null || !content.contains("#EXTM3U")) {
                writeResp(client, 502, mapOf("Content-Type" to "text/plain"),
                    "Failed to fetch media playlist".toByteArray())
                return
            }
            val body = rewriteMedia(content, qi).toByteArray(Charsets.UTF_8)
            writeResp(client, 200, mapOf(
                "Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-cache"), body)
        }

        private fun serveSegment(client: java.net.Socket, qi: Int, si: Int) {
            for (attempt in 0 until 2) {
                val segUrl = if (qi >= 0 && isMaster) {
                    val mediaUrl = parseNthUrl(cachedM3U8, qi, isSegment = false) ?: continue
                    val mc = fetchTextViaWV(mediaUrl)
                    if (mc != null && mc.contains("#EXTM3U")) parseNthUrl(mc, si, isSegment = true)
                    else null
                } else {
                    parseNthUrl(cachedM3U8, si, isSegment = true)
                }

                if (segUrl == null) {
                    writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                        "Segment not found".toByteArray())
                    return
                }

                // ★ Fetch segment via same-origin WebView XHR
                val data = fetchBinaryViaWV(segUrl)
                if (data != null && data.size > 100) {
                    writeResp(client, 200, mapOf(
                        "Content-Type" to "video/mp2t",
                        "Content-Length" to data.size.toString(),
                        "Connection" to "close"), data)
                    return
                }

                // Retry: refresh M3U8 for fresh tokens
                if (attempt == 0) {
                    val m3u8Base = parseNthUrl(cachedM3U8, 0, isSegment = true) ?: ""
                    if (m3u8Base.isNotEmpty()) {
                        // Extract M3U8 base URL from segment URL
                        val m3u8UrlFromSeg = Regex("""(https?://[^/]+/playlist/[^?#]+)""")
                            .find(m3u8Base)?.groupValues?.get(1)
                        if (m3u8UrlFromSeg != null) refreshM3U8(m3u8UrlFromSeg)
                    }
                    try { Thread.sleep(500) } catch (_: Exception) {}
                }
            }
            writeResp(client, 502, mapOf("Content-Type" to "text/plain"),
                "Segment fetch failed".toByteArray())
        }

        private fun writeResp(client: java.net.Socket, code: Int, headers: Map<String, String>, body: ByteArray) {
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
    // loadLinks
    // ================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        cleanupSession()
        val epUrl = data.substringBefore("|")

        // 1. Find iframe URL from episode page
        val epPageHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://stream\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._-]+""")
            .find(epPageHtml)?.value?.replace("&amp;", "&")

        if (iframeUrl == null) return true  // No player found

        // 2. Get cookies
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val ajaxCookie = try {
            app.post("$mainUrl/ajax/player", headers = mapOf(
                "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to epUrl, "Origin" to mainUrl
            ), data = mapOf("episodeId" to epId, "backup" to "1"))
                .cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (_: Exception) { "" }

        // 3. Get player JS
        val avsJs = cachedAvsJs ?: fetchJs(
            "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9", ajaxCookie
        )?.also { cachedAvsJs = it } ?: return true

        // 4. ★ TWO-PHASE: Phase 1 (find M3U8) → Phase 2 (navigate to player for same-origin)
        val (m3u8Result, cookies, webView) = initTwoPhase(epUrl, iframeUrl, ajaxCookie, avsJs)
        if (webView == null) { cleanupSession(); return true }
        val bridge = activeBridge
        if (bridge == null) { cleanupSession(); return true }

        // 5. Resolve M3U8 content
        val m3u8Content: String
        val m3u8BaseUrl: String?

        if (m3u8Result == null) { cleanupSession(); return true }

        if (m3u8Result.startsWith("BLOB::")) {
            // M3U8 content từ blob (Phase 1)
            m3u8BaseUrl = bridge.m3u8Url  // Phase 2 URL (nếu có)
            m3u8Content = m3u8Result.removePrefix("BLOB::")
            if (!m3u8Content.contains("#EXTM3U")) { cleanupSession(); return true }
        } else {
            // M3U8 URL
            m3u8BaseUrl = m3u8Result
            // ★ Fetch M3U8 content qua WebView XHR (same-origin!)
            m3u8Content = try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val esc = m3u8BaseUrl.replace("\\", "\\\\").replace("'", "\\'")
                        webView.evaluateJavascript("""
(function(){try{var x=new XMLHttpRequest();x.open('GET','$esc',false);
x.withCredentials=true;try{Android.onM3U8(x.responseText);}catch(e){}
}catch(e){}})();
""".trimIndent(), null)
                    } catch (_: Exception) {}
                }
                Thread.sleep(1500)
                bridge.m3u8Content ?: ""
            } catch (_: Exception) { "" }
            if (!m3u8Content.contains("#EXTM3U")) { cleanupSession(); return true }
        }

        // 6. Create proxy
        val server = StreamProxy(webView, bridge, m3u8Content)
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
