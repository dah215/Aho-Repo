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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSubProvider())
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

    @Volatile private var activeWebView: WebView? = null
    @Volatile private var activeBridge: JBridge? = null
    @Volatile private var activeProxy: VProxy? = null

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

    private val scanScript = """
(function(){
try{
    var els=document.querySelectorAll('[class*="art"]');
    for(var i=0;i<els.length;i++){
        try{if(els[i].__art__&&els[i].__art__.option&&els[i].__art__.option.url)
            Android.onM3U8Url(els[i].__art__.option.url);}catch(e){}
    }
    try{if(window.art&&window.art.option&&window.art.option.url)Android.onM3U8Url(window.art.option.url);}catch(e){}
    try{if(window.artplayer&&window.artplayer.url)Android.onM3U8Url(window.artplayer.url);}catch(e){}
    try{if(window.player&&window.player.url)Android.onM3U8Url(window.player.url);}catch(e){}
}catch(e){}
try{
    var scripts=document.querySelectorAll('script');
    for(var i=0;i<scripts.length;i++){
        var text=scripts[i].textContent||'';
        var m=text.match(/https?:\/\/[^\s'"<>\\]+\.m3u8[^\s'"<>\\]*/);
        if(m)Android.onM3U8Url(m[0]);
        var m2=text.match(/['"]?(?:url|src|source|video_url|file)['"]?\s*[:=]\s*['"](https?:\/\/[^'"]+)['"]/gi);
        if(m2)for(var j=0;j<m2.length;j++){
            var u=m2[j].match(/https?:\/\/[^'"]+/);if(u&&u[0].indexOf('googleapiscdn')!==-1)Android.onM3U8Url(u[0]);
        }
    }
}catch(e){}
try{
    var all=document.querySelectorAll('[data-url],[data-src],[data-video],[data-source],[data-player]');
    for(var i=0;i<all.length;i++){
        ['data-url','data-src','data-video','data-source','data-player'].forEach(function(attr){
            var v=all[i].getAttribute(attr);
            if(v&&(v.indexOf('.m3u8')!==-1||v.indexOf('googleapiscdn')!==-1))Android.onM3U8Url(v);
        });
    }
}catch(e){}
try{
    var metas=document.querySelectorAll('meta[property*="video"],meta[property*="player"]');
    for(var i=0;i<metas.length;i++){
        var c=metas[i].getAttribute('content');if(c)Android.onM3U8Url(c);
    }
}catch(e){}
Android.onPageScanned();
})();
""".trimIndent()

    private val hookScript = """
(function(){
var hooked=false;
if(hooked)return;hooked=true;
if(window.fetch){
    var _f=window.fetch;
    window.fetch=function(){
        var url='';
        if(arguments.length>0)url=typeof arguments[0]==='string'?arguments[0]:(arguments[0]&&arguments[0].url?arguments[0].url:'');
        if(url&&(url.indexOf('.m3u8')!==-1||url.indexOf('googleapiscdn')!==-1||
            url.indexOf('/playlist')!==-1||url.indexOf('/master')!==-1)){
            try{Android.onM3U8Url(url);}catch(e){}
        }
        return _f.apply(this,arguments).then(function(resp){
            try{
                var ct=resp.headers.get('content-type')||'';
                if(ct.indexOf('mpegurl')!==-1||url.indexOf('.m3u8')!==-1){
                    var cl=resp.clone();
                    cl.text().then(function(t){if(t.indexOf('#EXTM3U')!==-1)Android.onM3U8Content(url,t);}).catch(function(){});
                }
            }catch(e){}
            return resp;
        }).catch(function(err){return Promise.reject(err);});
    };
}
if(XMLHttpRequest&&XMLHttpRequest.prototype){
    var _o=XMLHttpRequest.prototype.open;
    var _s=XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open=function(m,u){
        this._avUrl=typeof u==='string'?u:'';
        return _o.apply(this,arguments);
    };
    XMLHttpRequest.prototype.send=function(){
        var self=this;var url=this._avUrl||'';
        this.addEventListener('load',function(){
            try{
                var ct=self.getResponseHeader('content-type')||'';
                if((ct.indexOf('mpegurl')!==-1||url.indexOf('.m3u8')!==-1)&&self.responseText&&self.responseText.indexOf('#EXTM3U')!==-1){
                    Android.onM3U8Content(url,self.responseText);
                }
            }catch(e){}
        });
        return _s.apply(this,arguments);
    };
}
if(URL&&URL.createObjectURL){
    var _c=URL.createObjectURL;
    URL.createObjectURL=function(blob){
        try{
            if(blob&&blob.type){
                if(blob.type.indexOf('mpegurl')!==-1||blob.type.indexOf('mp2t')!==-1){
                    var r=new FileReader();r.onload=function(e){
                        try{var t=e.target.result;if(t.indexOf('#EXTM3U')!==-1)Android.onM3U8Content('blob',t);}catch(ex){}
                    };r.readAsText(blob);
                }
            }
        }catch(e){}
        return _c.apply(this,arguments);
    };
}
Android.onHooksReady();
})();
""".trimIndent()

    class JBridge {
        @Volatile var m3u8Content: String? = null
        @Volatile var m3u8Url: String? = null
        @Volatile var hooksReady: Boolean = false
        @Volatile var pageScanned: Boolean = false
        @Volatile var pageLoaded: Boolean = false
        private val pending = ConcurrentHashMap<String, PReq>()

        class PReq {
            val latch = CountDownLatch(1)
            @Volatile var data: ByteArray? = null
            @Volatile var text: String? = null
            @Volatile var error: String? = null
            @Volatile var done = false
            private val chunks = ConcurrentHashMap<Int, String>()
            @Volatile private var chunkTotal: Int = -1

            @Synchronized fun addChunk(i: Int, c: String) {
                chunks[i] = c
                if (chunkTotal > 0 && chunks.size >= chunkTotal) complete()
            }
            @Synchronized fun setTotal(n: Int) { chunkTotal = n; if (n > 0 && chunks.size >= n) complete() }
            @Synchronized fun finishBinary() { if (!done) { done = true; data = assemble(); latch.countDown() } }
            @Synchronized fun finishText(t: String) { if (!done) { done = true; text = t; latch.countDown() } }
            @Synchronized fun finishError(m: String) { if (!done) { done = true; error = m; latch.countDown() } }
            private fun complete() { if (!done) { done = true; data = assemble(); latch.countDown() } }
            private fun assemble(): ByteArray? {
                val full = chunks.keys.sorted().joinToString("") { chunks[it] ?: "" }
                return try { android.util.Base64.decode(full, android.util.Base64.DEFAULT) } catch (e: Exception) { null }
            }
        }

        @JavascriptInterface fun onM3U8Url(url: String) {
            if (url.isNotBlank()) {
                val clean = url.replace("&amp;", "&")
                if (clean.contains(".m3u8") || clean.contains("googleapiscdn")) {
                    m3u8Url = clean
                }
            }
        }
        @JavascriptInterface fun onM3U8Content(url: String, content: String) {
            if (content.contains("#EXTM3U")) {
                m3u8Content = content
                if (url.isNotBlank() && url != "blob") m3u8Url = url.replace("&amp;", "&")
            }
        }
        @JavascriptInterface fun onHooksReady() { hooksReady = true }
        @JavascriptInterface fun onPageScanned() { pageScanned = true }
        @JavascriptInterface fun onReqStart(id: String, n: Int) { pending[id]?.setTotal(n) }
        @JavascriptInterface fun onReqChunk(id: String, i: Int, b: String) { pending[id]?.addChunk(i, b) }
        @JavascriptInterface fun onReqDone(id: String) { pending[id]?.finishBinary() }
        @JavascriptInterface fun onReqError(id: String, m: String) { pending[id]?.finishError(m) }
        @JavascriptInterface fun onTextDone(id: String, t: String) { pending[id]?.finishText(t) }

        fun prep(id: String): PReq { val p = PReq(); pending[id] = p; return p }
        fun clean(id: String) { pending.remove(id) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadPlayer(
        playerUrl: String,
        cookie: String
    ): Triple<String?, String?, WebView?> {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(45_000L) {
                suspendCancellableCoroutine<Triple<String?, String?, WebView?>> { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) {
                        cont.resume(Triple(null, null, null))
                        return@suspendCancellableCoroutine
                    }
                    val bridge = JBridge()
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        cookie.split(";").forEach { kv ->
                            val t = kv.trim(); if (t.isNotBlank()) {
                                setCookie("https://animevietsub.id", t)
                                setCookie("https://storage.googleapiscdn.com", t)
                                setCookie("https://stream.googleapiscdn.com", t)
                            }
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
                        blockNetworkImage = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView, req: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = req.url.toString()
                            if (url.contains(".m3u8") ||
                                (url.contains("googleapiscdn.com") &&
                                    (url.contains("/playlist") || url.contains("/master") ||
                                     url.contains("/index") || url.contains("/hls")))) {
                                bridge.m3u8Url = url
                            }
                            if (url.contains("adsbygoogle") || url.contains("googlesyndication") ||
                                url.contains("doubleclick") || url.contains("google-analytics") ||
                                url.contains("googletagmanager") || url.contains("facebook.com") ||
                                url.contains("facebook.net")) {
                                return WebResourceResponse("text/plain", "utf-8",
                                    ByteArrayInputStream("".toByteArray()))
                            }
                            if (url.endsWith(".woff") || url.endsWith(".woff2") ||
                                url.endsWith(".ttf") || url.endsWith(".eot") ||
                                url.endsWith(".css")) {
                                return WebResourceResponse("text/plain", "utf-8",
                                    ByteArrayInputStream("".toByteArray()))
                            }
                            return null
                        }
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (url != null && url.contains("googleapiscdn.com")) {
                                bridge.pageLoaded = false; bridge.m3u8Content = null
                                bridge.m3u8Url = null; bridge.hooksReady = false; bridge.pageScanned = false
                            }
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url == null || !url.contains("googleapiscdn.com")) return
                            bridge.pageLoaded = true
                            view.evaluateJavascript(scanScript, null)
                            view.evaluateJavascript(hookScript, null)
                        }
                    }
                    wv.loadUrl(playerUrl, mapOf("Referer" to "$mainUrl/"))
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            val hasContent = bridge.m3u8Content != null && bridge.m3u8Content!!.contains("#EXTM3U")
                            val hasUrl = bridge.m3u8Url != null
                            when {
                                hasContent || hasUrl -> handler.postDelayed({
                                    wv.stopLoading(); activeWebView = wv; activeBridge = bridge
                                    if (cont.isActive) cont.resume(Triple(bridge.m3u8Content, bridge.m3u8Url, wv as WebView?))
                                }, 1500)
                                elapsed >= 40_000 -> {
                                    try { wv.evaluateJavascript(scanScript, null) } catch (_: Exception) {}
                                    handler.postDelayed({
                                        wv.stopLoading()
                                        if (bridge.m3u8Url != null || bridge.m3u8Content != null) {
                                            activeWebView = wv; activeBridge = bridge
                                            if (cont.isActive) cont.resume(Triple(bridge.m3u8Content, bridge.m3u8Url, wv as WebView?))
                                        } else { wv.destroy(); if (cont.isActive) cont.resume(Triple(null, null, null)) }
                                    }, 3000)
                                }
                                else -> { elapsed += 300; handler.postDelayed(this, 300) }
                            }
                        }
                    }
                    handler.postDelayed(checker, 1500)
                    cont.invokeOnCancellation { handler.removeCallbacks(checker); try { wv.stopLoading() } catch (_: Exception) {} }
                }
            } ?: Triple(null, null, null)
        }
    }

    class VProxy(
        private val wv: WebView,
        private val bridge: JBridge,
        private val initM3U8: String,
        private val initM3U8Url: String?
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private val lock = Any()
        @Volatile private var m3u8Data: String = initM3U8
        @Volatile private var m3u8UrlData: String? = initM3U8Url
        val port: Int get() = serverSocket?.localPort ?: 0
        private val isMaster = initM3U8.contains("#EXT-X-STREAM-INF")

        fun start() {
            serverSocket = java.net.ServerSocket(0, 50)
            Thread {
                try { val ss = serverSocket ?: return@Thread
                    while (!ss.isClosed) {
                        try { val c = ss.accept(); Thread { handleClient(c) }.also { it.isDaemon = true }.start() }
                        catch (_: java.net.SocketException) { break }
                        catch (_: Exception) { break }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
        fun stop() { try { serverSocket?.close() } catch (_: Exception) {} }

        private fun resolveUrl(base: String, relative: String): String {
            if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
            return java.net.URI(base).resolve(relative).toString()
        }

        private fun wvFetchText(url: String): String? {
            val id = "t${System.currentTimeMillis()}"; val p = bridge.prep(id)
            val esc = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { wv.evaluateJavascript("(function(){try{var x=new XMLHttpRequest();x.open('GET','$esc',false);x.withCredentials=true;try{Android.onTextDone('$id',x.responseText);}catch(er){}}catch(ex){Android.onReqError('$id',''+ex);}})();", null) }
                    catch (_: Exception) {}
                }
                p.latch.await(15, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            finally { bridge.clean(id) }
            return if (p.error != null) null else p.text
        }

        private fun wvFetchBinary(url: String): ByteArray? {
            val id = "b${System.currentTimeMillis()}"; val p = bridge.prep(id)
            val esc = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { wv.evaluateJavascript("""(function(){try{var id='$id',u='$esc',x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='arraybuffer';x.withCredentials=true;x.timeout=30000;x.onload=function(){if(x.status<200||x.status>=300){Android.onReqError(id,'H'+x.status);return;}var b=new Uint8Array(x.response),n=Math.ceil(b.length/32768);Android.onReqStart(id,n);for(var i=0;i<n;i++){var s=i*32768,e=Math.min(s+32768,b.length),sl=b.subarray(s,e),bin='';for(var j=0;j<sl.length;j++)bin+=String.fromCharCode(sl[j]);Android.onReqChunk(id,i,btoa(bin));}Android.onReqDone(id);};x.onerror=function(){Android.onReqError(id,'err');};x.ontimeout=function(){Android.onReqError(id,'to');};x.send();}catch(ex){Android.onReqError('$id',''+ex);}})();""".trimIndent(), null) }
                    catch (_: Exception) {}
                }
                p.latch.await(45, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            finally { bridge.clean(id) }
            return if (p.error != null) null else p.data
        }

        private fun parseStreamUrl(content: String, idx: Int): String? {
            var ui = -1
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) ui++
                if (ui == idx && (t.startsWith("http://") || t.startsWith("https://"))) return t
            }
            return null
        }

        private fun parseSegUrl(content: String, idx: Int): String? {
            var ui = -1
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXTINF") || t.startsWith("#EXT-X-BYTERANGE")) ui++
                if (ui == idx && (t.startsWith("http://") || t.startsWith("https://"))) return t
                if (ui == idx && !t.startsWith("#") && t.isNotBlank()) {
                    val base = m3u8UrlData ?: ""
                    if (base.isNotBlank()) return resolveUrl(base, t)
                    return t
                }
            }
            return null
        }

        private fun rewriteMaster(content: String): String {
            val sb = StringBuilder(); var i = 0; var mode = 0
            for (line in content.lines()) {
                val t = line.trim()
                if (t.startsWith("#EXT-X-STREAM-INF")) mode = 1
                else if (t.startsWith("#EXTINF:")) mode = 2
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    if (mode == 1) { sb.append("http://127.0.0.1:$port/media/$i"); i++; mode = 0 }
                    else if (mode == 2) { sb.append("http://127.0.0.1:$port/seg/0_$i"); i++; mode = 0 }
                    else sb.append(t)
                } else sb.append(t)
                sb.append("\n")
            }
            return sb.toString()
        }

        private fun rewriteMedia(content: String, qualityIdx: Int): String {
            val sb = StringBuilder(); var segIdx = 0
            for (line in content.lines()) {
                val t = line.trim()
                if ((t.startsWith("http://") || t.startsWith("https://")) ||
                    (!t.startsWith("#") && t.isNotBlank() && !t.startsWith("http"))) {
                    sb.append("http://127.0.0.1:$port/seg/${qualityIdx}_$segIdx"); segIdx++
                } else sb.append(t)
                sb.append("\n")
            }
            return sb.toString()
        }

        private fun refreshM3U8(): String {
            val url = m3u8UrlData ?: return synchronized(lock) { m3u8Data }
            val cleanUrl = url.substringBefore("?")
            val fresh = wvFetchText("$cleanUrl?_r=${System.currentTimeMillis()}")
            if (fresh != null && fresh.contains("#EXTM3U") && fresh.contains("http")) {
                synchronized(lock) { m3u8Data = fresh }; return m3u8Data
            }
            return synchronized(lock) { m3u8Data }
        }

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
                    parts[0] == "OPTIONS" -> writeResponse(client, 200, mapOf(
                        "Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Content-Length" to "0"), ByteArray(0))
                    path == "/stream.m3u8" -> {
                        val body = rewriteMaster(m3u8Data).toByteArray(Charsets.UTF_8)
                        writeResponse(client, 200, mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                            "Content-Length" to body.size.toString(), "Cache-Control" to "no-cache",
                            "Access-Control-Allow-Origin" to "*"), body)
                    }
                    path.startsWith("/media/") -> {
                        val qi = path.removePrefix("/media/").toIntOrNull() ?: -1
                        serveMedia(client, qi)
                    }
                    path.startsWith("/seg/") -> {
                        val segPart = path.removePrefix("/seg/")
                        val sep = segPart.indexOf("_")
                        if (sep >= 0) serveSegment(client, segPart.substring(0, sep).toIntOrNull() ?: -1, segPart.substring(sep + 1).toIntOrNull() ?: -1)
                        else serveSegment(client, -1, segPart.toIntOrNull() ?: -1)
                    }
                    else -> writeResponse(client, 404, mapOf("Content-Type" to "text/plain"), "Not found".toByteArray())
                }
            } catch (_: java.net.SocketException) {} catch (_: Exception) {}
            finally { try { client.close() } catch (_: Exception) {} }
        }

        private fun serveMedia(client: java.net.Socket, qualityIdx: Int) {
            val streamUrl = parseStreamUrl(m3u8Data, qualityIdx)
            if (streamUrl == null) { writeResponse(client, 404, mapOf("Content-Type" to "text/plain"), "Not found".toByteArray()); return }
            val resolvedUrl = if (streamUrl.startsWith("http")) streamUrl else resolveUrl(m3u8UrlData ?: "", streamUrl)
            val mediaContent = wvFetchText(resolvedUrl)
            if (mediaContent == null || !mediaContent.contains("#EXTM3U")) {
                writeResponse(client, 502, mapOf("Content-Type" to "text/plain"), "Fail".toByteArray()); return
            }
            m3u8UrlData = resolvedUrl
            val body = rewriteMedia(mediaContent, qualityIdx).toByteArray(Charsets.UTF_8)
            writeResponse(client, 200, mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                "Content-Length" to body.size.toString(), "Cache-Control" to "no-cache",
                "Access-Control-Allow-Origin" to "*"), body)
        }

        private fun serveSegment(client: java.net.Socket, qualityIdx: Int, segIdx: Int) {
            for (attempt in 0 until 3) {
                var segUrl: String? = null
                if (isMaster && qualityIdx >= 0) {
                    val streamUrl = parseStreamUrl(m3u8Data, qualityIdx)
                    if (streamUrl != null) {
                        val resolvedStreamUrl = if (streamUrl.startsWith("http")) streamUrl else resolveUrl(m3u8UrlData ?: "", streamUrl)
                        val mediaContent = wvFetchText(resolvedStreamUrl)
                        if (mediaContent != null && mediaContent.contains("#EXTM3U")) { m3u8UrlData = resolvedStreamUrl; segUrl = parseSegUrl(mediaContent, segIdx) }
                    }
                } else segUrl = parseSegUrl(m3u8Data, segIdx)
                if (segUrl == null) { writeResponse(client, 404, mapOf("Content-Type" to "text/plain"), "Not found".toByteArray()); return }
                val resolvedSegUrl = if (segUrl.startsWith("http")) segUrl else resolveUrl(m3u8UrlData ?: "", segUrl)
                val data = wvFetchBinary(resolvedSegUrl)
                if (data != null && data.size > 100) {
                    writeResponse(client, 200, mapOf("Content-Type" to "video/mp2t", "Content-Length" to data.size.toString(),
                        "Connection" to "close", "Access-Control-Allow-Origin" to "*"), data); return
                }
                if (attempt < 2) { refreshM3U8(); try { Thread.sleep(800) } catch (_: Exception) {} }
            }
            writeResponse(client, 502, mapOf("Content-Type" to "text/plain"), "Fail".toByteArray())
        }

        private fun writeResponse(client: java.net.Socket, code: Int, headers: Map<String, String>, body: ByteArray) {
            try {
                val out = client.getOutputStream()
                out.write("HTTP/1.1 $code\r\n".toByteArray())
                headers.forEach { (k, v) -> out.write("$k: $v\r\n".toByteArray()) }
                out.write("\r\n".toByteArray())
                if (body.isNotEmpty()) out.write(body); out.flush()
            } catch (_: Exception) {}
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        cleanup()
        val epUrl = data.substringBefore("|")
        val epHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://(?:storage|stream)\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._:/-]+""")
            .find(epHtml)?.value?.replace("&amp;", "&")
        if (iframeUrl == null) return true
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val cookie = try {
            app.post("$mainUrl/ajax/player", headers = mapOf(
                "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to epUrl, "Origin" to mainUrl
            ), data = mapOf("episodeId" to epId, "backup" to "1"))
                .cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (_: Exception) { "" }
        val (m3u8Content, m3u8Url, webView) = loadPlayer(iframeUrl, cookie)
        if (webView == null) { cleanup(); return true }
        val bridge = activeBridge ?: run { cleanup(); return true }
        var finalM3U8Content: String? = m3u8Content
        var finalM3U8Url: String? = m3u8Url
        if (finalM3U8Content == null && finalM3U8Url != null) {
            try {
                val fetched = wvFetchTextViaBridge(webView, bridge, finalM3U8Url!!)
                if (fetched != null && fetched.contains("#EXTM3U")) finalM3U8Content = fetched
            } catch (_: Exception) {}
        }
        if (finalM3U8Content == null) {
            try {
                Thread.sleep(2000)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { webView.evaluateJavascript(scanScript, null) } catch (_: Exception) {}
                }
                Thread.sleep(2000)
                if (bridge.m3u8Url != null && bridge.m3u8Content == null) {
                    val fetched = wvFetchTextViaBridge(webView, bridge, bridge.m3u8Url!!)
                    if (fetched != null && fetched.contains("#EXTM3U")) { finalM3U8Content = fetched; finalM3U8Url = bridge.m3u8Url }
                }
                if (bridge.m3u8Content != null) { finalM3U8Content = bridge.m3u8Content; finalM3U8Url = bridge.m3u8Url ?: finalM3U8Url }
            } catch (_: Exception) {}
        }
        if (finalM3U8Content == null || !finalM3U8Content!!.contains("#EXTM3U")) { cleanup(); return true }
        val proxy = VProxy(webView, bridge, finalM3U8Content!!, finalM3U8Url)
        proxy.start(); activeProxy = proxy
        callback(newExtractorLink(source = name, name = "$name",
            url = "http://127.0.0.1:${proxy.port}/stream.m3u8", type = ExtractorLinkType.M3U8) {
            this.quality = Qualities.P1080.value; this.headers = mapOf("User-Agent" to UA)
        })
        return true
    }

    private fun wvFetchTextViaBridge(wv: WebView, bridge: JBridge, url: String): String? {
        val id = "ft${System.currentTimeMillis()}"; val p = bridge.prep(id)
        val esc = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { wv.evaluateJavascript("(function(){try{var x=new XMLHttpRequest();x.open('GET','$esc',false);x.withCredentials=true;try{Android.onTextDone('$id',x.responseText);}catch(er){}}catch(ex){Android.onReqError('$id',''+ex);}})();", null) }
                catch (_: Exception) {}
            }
            p.latch.await(15, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        finally { bridge.clean(id) }
        return if (p.error != null) null else p.text
    }

    private fun cleanup() {
        activeProxy?.stop(); activeProxy = null
        val w = activeWebView; activeWebView = null; activeBridge = null
        if (w != null) try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { w.stopLoading() } catch (_: Exception) {}
                try { w.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
