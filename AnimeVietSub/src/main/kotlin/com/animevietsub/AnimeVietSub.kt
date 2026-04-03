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
 * AnimeVietSub Plugin v6 — Fix Error 2004 (ROOT CAUSE FOUND)
 *
 * ROOT CAUSE (from network capture analysis):
 *   1. stream.googleapiscdn.com KHÔNG có CORS headers → XHR cross-origin bị block
 *   2. WebView trên animevietsub.id → XHR tới stream.googleapiscdn.com = cross-origin → FAIL
 *   3. xhr.withCredentials = true thiếu → cf_clearance không được gửi
 *
 * v6 fix:
 *   - WebView PHẢI navigate trực tiếp tới stream.googleapiscdn.com/player/{token}
 *   - Tất cả XHR giờ là same-origin → không cần CORS
 *   - xhr.withCredentials = true cho mọi request
 *   - M3U8 re-fetch cũng qua WebView XHR (không dùng app.get() cho googleapiscdn.com)
 *   - Không inject player JS khi ở trên stream.googleapiscdn.com
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

    /**
     * ★ Network interceptor — intercept fetch() và XHR để bắt M3U8 URL
     */
    private val networkInterceptor = """
;(function(){
function rp(url){
    if(url && (url.indexOf('.m3u8')!==-1 || url.indexOf('mpegurl')!==-1 ||
       url.indexOf('googleapiscdn.com/playlist')!==-1 || url.indexOf('googleapiscdn.com/video')!==-1)){
        try{Android.onM3U8Url(url);}catch(x){}
    }
}
var _fetch=window.fetch;
if(_fetch){
    window.fetch=function(){
        if(arguments.length>0 && typeof arguments[0]==='string') rp(arguments[0]);
        else if(arguments[0] && arguments[0].url) rp(arguments[0].url);
        return _fetch.apply(this,arguments);
    };
}
var _xo=XMLHttpRequest.prototype.open;
if(_xo){
    XMLHttpRequest.prototype.open=function(m,u){
        if(typeof u==='string') rp(u);
        return _xo.apply(this,arguments);
    };
}
})();
""".trimIndent()

    /** Blob interceptor — catch M3U8 content from URL.createObjectURL */
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
    // JsBridge — JS↔Kotlin bridge cho text + binary transfer
    // ================================================================
    inner class JsBridge {
        @Volatile var m3u8Content: String? = null
        @Volatile var m3u8Url: String? = null
        private val pending = ConcurrentHashMap<String, PendingReq>()

        inner class PendingReq {
            val latch = CountDownLatch(1)
            @Volatile var data: ByteArray? = null       // for binary
            @Volatile var text: String? = null          // for text
            @Volatile var error: String? = null
            @Volatile var done = false
            private val chunks = ConcurrentHashMap<Int, String>()
            @Volatile var totalChunks: Int = -1

            @Synchronized fun addChunk(idx: Int, chunk: String) {
                chunks[idx] = chunk
                if (totalChunks > 0 && chunks.size >= totalChunks) finishWithBinary()
            }

            @Synchronized fun setTotalChunks(n: Int) {
                totalChunks = n
                if (n > 0 && chunks.size >= n) finishWithBinary()
            }

            @Synchronized fun finishWithBinary() {
                if (!done) {
                    done = true
                    if (data == null) {
                        val keys = chunks.keys.sorted()
                        val full = keys.joinToString("") { chunks[it] ?: "" }
                        data = try { android.util.Base64.decode(full, android.util.Base64.DEFAULT) }
                        catch (e: Exception) { null }
                    }
                    latch.countDown()
                }
            }

            @Synchronized fun finishWithText(t: String) {
                if (!done) { done = true; text = t; latch.countDown() }
            }

            @Synchronized fun finishWithError(msg: String) {
                if (!done) { done = true; error = msg; latch.countDown() }
            }
        }

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) m3u8Content = content
        }

        @JavascriptInterface
        fun onM3U8Url(url: String) {
            if (url.contains("m3u8") || url.contains("googleapiscdn")) m3u8Url = url
        }

        @JavascriptInterface
        fun onReqStart(id: String, total: Int) { pending[id]?.setTotalChunks(total) }

        @JavascriptInterface
        fun onReqChunk(id: String, index: Int, b64: String) { pending[id]?.addChunk(index, b64) }

        @JavascriptInterface
        fun onReqDone(id: String) { pending[id]?.finishWithBinary() }

        @JavascriptInterface
        fun onReqError(id: String, msg: String) { pending[id]?.finishWithError(msg) }

        @JavascriptInterface
        fun onTextDone(id: String, txt: String) { pending[id]?.finishWithText(txt) }

        fun prepare(id: String): PendingReq {
            val p = PendingReq()
            pending[id] = p
            return p
        }

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
    // ★ v6: initWebView — Navigate trực tiếp tới stream.googleapiscdn.com
    // KHÔNG load trên animevietsub.id → tránh cross-origin XHR
    // ================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initWebViewOnStream(playerUrl: String, cookie: String): Pair<String?, WebView?> {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(35_000L) {
                suspendCancellableCoroutine<Pair<String?, WebView?>> { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) {
                        cont.resume(null as String? to null as WebView?)
                        return@suspendCancellableCoroutine
                    }

                    val bridge = JsBridge()

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

                    // ★ Inject networkInterceptor + blobInterceptor vào player page
                    val interceptorJs = (networkInterceptor + "\n" + blobInterceptor)
                        .toByteArray(Charsets.UTF_8)
                    val fakeAdsBytes = fakeAds.toByteArray(Charsets.UTF_8)

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url.toString()
                            return when {
                                // ★ Inject interceptor vào player JS (nếu có)
                                url.contains("player.") && url.endsWith(".js") ->
                                    WebResourceResponse("application/javascript", "utf-8",
                                        ByteArrayInputStream(interceptorJs))
                                // ★ Bắt M3U8 URL qua network layer
                                url.contains("googleapiscdn.com") &&
                                    (url.contains(".m3u8") || url.contains("playlist")) -> {
                                    bridge.m3u8Url = url; null
                                }
                                url.contains("adsbygoogle") || url.contains("googlesyndication") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(fakeAdsBytes))
                                url.contains("google-analytics") || url.contains("doubleclick") ||
                                url.contains("googletagmanager") || url.contains("facebook.com") ->
                                    WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream("".toByteArray()))
                                url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf") ||
                                url.endsWith(".eot") || url.endsWith(".css") ->
                                    WebResourceResponse("text/css", "utf-8", ByteArrayInputStream("".toByteArray()))
                                url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                                url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".svg") ->
                                    WebResourceResponse("image/png", "utf-8", ByteArrayInputStream("".toByteArray()))
                                else -> null
                            }
                        }
                    }

                    // ★ Navigate trực tiếp tới player URL trên stream.googleapiscdn.com
                    wv.loadUrl(playerUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "Referer" to "$mainUrl/"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            when {
                                bridge.m3u8Url != null || bridge.m3u8Content != null -> {
                                    // Delay để cookies được set + JS hoàn thành
                                    handler.postDelayed({
                                        try {
                                            val c1 = CookieManager.getInstance().getCookie("https://stream.googleapiscdn.com") ?: ""
                                            val c2 = CookieManager.getInstance().getCookie("http://stream.googleapiscdn.com") ?: ""
                                            lastStreamCookies = listOf(c1, c2, cookie)
                                                .filter { it.isNotBlank() }.distinct().joinToString("; ")
                                        } catch (_: Exception) {}

                                        wv.stopLoading()
                                        activeWebView = wv
                                        activeBridge = bridge

                                        val res = bridge.m3u8Url?.let { "DIRECT_URL::$it" }
                                            ?: bridge.m3u8Content
                                        if (cont.isActive) cont.resume(res to (wv as WebView?))
                                    }, 1500)
                                }
                                elapsed >= 30_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null as String? to null as WebView?)
                                }
                                else -> { elapsed += 200; handler.postDelayed(this, 200) }
                            }
                        }
                    }
                    handler.postDelayed(checker, 500)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker); wv.stopLoading()
                    }
                }
            } ?: Pair<String?, WebView?>(null, null)
        }
    }

    // ================================================================
    // v6: StreamProxy — ALL requests qua WebView XHR (same-origin!)
    // ================================================================
    inner class StreamProxy(
        private val webView: WebView,
        private val bridge: JsBridge,
        private val m3u8BaseUrl: String?,      // URL để re-fetch M3U8
        private val initialM3U8: String
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
        // ★ Re-fetch M3U8 qua WebView XHR (same-origin → no CORS!)
        // ----------------------------------------------------------
        private fun refreshM3U8ViaWV(): String? {
            val url = m3u8BaseUrl ?: return cachedM3U8
            val text = fetchTextViaWebView(url) ?: return cachedM3U8
            if (text.contains("#EXTM3U") && text.contains("http")) {
                synchronized(m3u8Lock) { cachedM3U8 = text }
                return cachedM3U8
            }
            return cachedM3U8
        }

        /** ★ Fetch TEXT content qua WebView XHR (cho M3U8) */
        private fun fetchTextViaWebView(targetUrl: String): String? {
            val reqId = "t${System.currentTimeMillis()}"
            val pending = bridge.prepare(reqId)
            val esc = targetUrl.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "")

            val js = """
(function(){
try{
var id='$reqId';
var url='$esc';
var xhr=new XMLHttpRequest();
xhr.open('GET',url,true);
xhr.withCredentials=true;
xhr.timeout=15000;
xhr.onload=function(){
    if(xhr.status>=200&&xhr.status<300){
        Android.onTextDone(id,xhr.responseText);
    }else{
        Android.onReqError(id,'HTTP_'+xhr.status);
    }
};
xhr.onerror=function(){Android.onReqError(id,'xhr_error');};
xhr.ontimeout=function(){Android.onReqError(id,'timeout');};
xhr.send();
}catch(e){Android.onReqError('$reqId','js_'+e.message);}
})();
""".trimIndent()

            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
                val ok = pending.latch.await(20, TimeUnit.SECONDS)
                bridge.cleanup(reqId)
                if (!ok || pending.error != null) return null
                return pending.text
            } catch (_: Exception) { return null }
            finally { bridge.cleanup(reqId) }
        }

        /** ★ Fetch BINARY content qua WebView XHR (cho segments) */
        private fun fetchBinaryViaWebView(targetUrl: String): ByteArray? {
            val reqId = "b${System.currentTimeMillis()}"
            val pending = bridge.prepare(reqId)
            val esc = targetUrl.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "")

            val js = """
(function(){
try{
var id='$reqId';
var url='$esc';
var xhr=new XMLHttpRequest();
xhr.open('GET',url,true);
xhr.responseType='arraybuffer';
xhr.withCredentials=true;
xhr.timeout=40000;
xhr.onload=function(){
    if(xhr.status<200||xhr.status>=300){
        Android.onReqError(id,'HTTP_'+xhr.status);return;
    }
    var bytes=new Uint8Array(xhr.response);
    var total=Math.ceil(bytes.length/32768);
    Android.onReqStart(id,total);
    for(var i=0;i<total;i++){
        var s=i*32768;
        var e=Math.min(s+32768,bytes.length);
        var slice=bytes.subarray(s,e);
        var bin='';
        for(var j=0;j<slice.length;j++) bin+=String.fromCharCode(slice[j]);
        Android.onReqChunk(id,i,btoa(bin));
    }
    Android.onReqDone(id);
};
xhr.onerror=function(){Android.onReqError(id,'xhr_error');};
xhr.onabort=function(){Android.onReqError(id,'xhr_abort');};
xhr.ontimeout=function(){Android.onReqError(id,'timeout');};
xhr.send();
}catch(e){Android.onReqError('$reqId','js_'+e.message);}
})();
""".trimIndent()

            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
                }
                val ok = pending.latch.await(50, TimeUnit.SECONDS)
                bridge.cleanup(reqId)
                if (!ok || pending.error != null) return null
                return pending.data
            } catch (_: Exception) { return null }
            finally { bridge.cleanup(reqId) }
        }

        // Parse N-th URL từ M3U8
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

        // Rewrite M3U8 (master)
        private fun rewriteMaster(content: String): String {
            val sb = StringBuilder()
            var idx = 0
            var urlType = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) urlType = 1
                else if (t.startsWith("#EXTINF:")) urlType = 2
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    if (urlType == 1) {
                        sb.append("http://127.0.0.1:$port/media/$idx")
                        idx++; urlType = 0
                    } else if (urlType == 2) {
                        sb.append("http://127.0.0.1:$port/seg/$idx")
                        idx++; urlType = 0
                    } else {
                        sb.append(t)
                    }
                } else {
                    sb.append(line)
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        // Rewrite media playlist
        private fun rewriteMedia(content: String, qIdx: Int): String {
            val sb = StringBuilder()
            var idx = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    sb.append("http://127.0.0.1:$port/seg/${qIdx}_$idx")
                    idx++
                } else {
                    sb.append(line)
                }
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
                        val fresh = refreshM3U8ViaWV() ?: cachedM3U8
                        val rewritten = rewriteMaster(fresh)
                        val body = rewritten.toByteArray(Charsets.UTF_8)
                        writeResp(client, 200, mapOf(
                            "Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
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
                            val qi = segId.substring(0, sep).toIntOrNull() ?: -1
                            val si = segId.substring(sep + 1).toIntOrNull() ?: -1
                            serveSegment(client, qi, si)
                        } else {
                            val si = segId.toIntOrNull() ?: -1
                            serveSegment(client, -1, si)
                        }
                    }

                    else -> writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                        "Not found".toByteArray())
                }
            } catch (_: java.net.SocketException) {}
            catch (_: Exception) {} finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        // ----------------------------------------------------------
        // Serve media playlist
        // ----------------------------------------------------------
        private fun serveMediaPlaylist(client: java.net.Socket, qualityIndex: Int) {
            val fresh = refreshM3U8ViaWV() ?: cachedM3U8
            val mediaUrl = parseNthUrl(fresh, qualityIndex, isSegment = false)
            if (mediaUrl == null) {
                writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                    "Media playlist not found".toByteArray())
                return
            }

            // ★ Fetch media playlist qua WebView XHR (same-origin!)
            val content = fetchTextViaWebView(mediaUrl)
            if (content == null || !content.contains("#EXTM3U")) {
                writeResp(client, 502, mapOf("Content-Type" to "text/plain"),
                    "Failed to fetch media playlist".toByteArray())
                return
            }

            val rewritten = rewriteMedia(content, qualityIndex)
            val body = rewritten.toByteArray(Charsets.UTF_8)
            writeResp(client, 200, mapOf(
                "Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-cache"), body)
        }

        // ----------------------------------------------------------
        // ★ Serve segment — retry + WebView XHR
        // ----------------------------------------------------------
        private fun serveSegment(client: java.net.Socket, qualityIndex: Int, segIndex: Int) {
            for (attempt in 0 until 2) {
                val segUrl = findSegmentUrl(qualityIndex, segIndex)
                if (segUrl == null) {
                    writeResp(client, 404, mapOf("Content-Type" to "text/plain"),
                        "Segment URL not found".toByteArray())
                    return
                }

                // ★ PRIMARY: WebView XHR (same-origin, with credentials)
                val data = fetchBinaryViaWebView(segUrl)
                if (data != null && data.size > 100) {
                    writeResp(client, 200, mapOf(
                        "Content-Type" to "video/mp2t",
                        "Content-Length" to data.size.toString(),
                        "Accept-Ranges" to "bytes",
                        "Connection" to "close"), data)
                    return
                }

                // Retry: refresh M3U8 để lấy token mới
                if (attempt == 0) {
                    refreshM3U8ViaWV()
                    try { Thread.sleep(300) } catch (_: Exception) {}
                }
            }

            writeResp(client, 502, mapOf("Content-Type" to "text/plain"),
                "Segment fetch failed".toByteArray())
        }

        private fun findSegmentUrl(qualityIndex: Int, segIndex: Int): String? {
            return if (qualityIndex >= 0 && isMaster) {
                val fresh = refreshM3U8ViaWV() ?: cachedM3U8
                val mediaUrl = parseNthUrl(fresh, qualityIndex, isSegment = false) ?: return null
                val mediaContent = fetchTextViaWebView(mediaUrl) ?: return null
                if (mediaContent.contains("#EXTM3U")) parseNthUrl(mediaContent, segIndex, isSegment = true)
                else null
            } else {
                val fresh = refreshM3U8ViaWV() ?: cachedM3U8
                parseNthUrl(fresh, segIndex, isSegment = true)
            }
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

        // 1. ★ Tìm iframe URL — BẮT BUỘC phải có
        val epPageHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://stream\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._-]+""")
            .find(epPageHtml)?.value?.replace("&amp;", "&")

        if (iframeUrl == null) {
            // Fallback: thử tìm trong page source bằng regex khác
            val altMatch = Regex("""https://stream\.googleapiscdn\.com/player/\w+""")
                .find(epPageHtml)?.value
            if (altMatch != null) {
                return initAndPlay(altMatch, callback)
            }
            return true
        }

        return initAndPlay(iframeUrl, callback)
    }

    private suspend fun initAndPlay(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        // 2. Get cookies from animevietsub.id (cho cf_clearance ban đầu)
        val ajaxCookie = try {
            val epId = Regex("""-(\d+)\.html""").find(playerUrl)?.groupValues?.get(1)
            if (epId != null) {
                app.post("$mainUrl/ajax/player", headers = mapOf(
                    "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Referer" to "$mainUrl/", "Origin" to mainUrl
                ), data = mapOf("episodeId" to epId, "backup" to "1"))
                    .cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else ""
        } catch (_: Exception) { "" }

        // 3. ★ Navigate WebView trực tiếp tới stream.googleapiscdn.com
        val (m3u8Result, webView) = initWebViewOnStream(playerUrl, ajaxCookie)
        if (m3u8Result == null || webView == null) { cleanupSession(); return true }
        val bridge = activeBridge
        if (bridge == null) { cleanupSession(); return true }

        // 4. Lấy M3U8 content — ưu tiên URL để re-fetch
        val m3u8Content: String
        val m3u8BaseUrl: String?

        if (m3u8Result.startsWith("DIRECT_URL::")) {
            m3u8BaseUrl = m3u8Result.removePrefix("DIRECT_URL::")
            // ★ Fetch M3U8 qua WebView XHR (same-origin, no CORS!)
            m3u8Content = try {
                // Dùng evaluateJavascript để fetch text
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val esc = m3u8BaseUrl.replace("\\", "\\\\").replace("'", "\\'")
                        webView.evaluateJavascript("""
(function(){
var xhr=new XMLHttpRequest();
xhr.open('GET','$esc',false);
xhr.withCredentials=true;
try{Android.onM3U8(xhr.responseText);}catch(x){}
})();
""".trimIndent(), null)
                    } catch (_: Exception) {}
                }
                Thread.sleep(1000)
                bridge.m3u8Content ?: ""
            } catch (_: Exception) { "" }
        } else {
            m3u8BaseUrl = bridge.m3u8Url
            m3u8Content = m3u8Result
        }

        if (!m3u8Content.contains("#EXTM3U")) { cleanupSession(); return true }

        // 5. Tạo proxy
        val server = StreamProxy(
            webView = webView,
            bridge = bridge,
            m3u8BaseUrl = m3u8BaseUrl,
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
