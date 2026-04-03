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
import kotlin.coroutines.resume

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
    /** Cookies for stream.googleapiscdn.com extracted from WebView */
    @Volatile private var lastStreamCookies: String = ""

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
    // WebView M3U8 extraction
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

    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @Volatile var m3u8Url: String? = null
        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(epUrl: String, cookie: String, avsJs: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context } catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val bridge = M3U8Bridge()
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
                                url.contains("googleapiscdn.com/playlist/") && url.contains(".m3u8") -> {
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
                                bridge.m3u8Url != null || bridge.result != null -> {
                                    handler.postDelayed({
                                        // Extract ALL cookies from both domains
                                        try {
                                            val c1 = CookieManager.getInstance().getCookie("https://stream.googleapiscdn.com") ?: ""
                                            val c2 = CookieManager.getInstance().getCookie("http://stream.googleapiscdn.com") ?: ""
                                            val c3 = CookieManager.getInstance().getCookie("https://animevietsub.id") ?: ""
                                            val merged = listOf(c1, c2, c3, cookie)
                                                .filter { it.isNotBlank() }.distinct().joinToString("; ")
                                            lastStreamCookies = merged
                                        } catch (_: Exception) {}
                                        wv.stopLoading(); wv.destroy()
                                        val res = bridge.m3u8Url?.let { "DIRECT_URL::$it" } ?: bridge.result
                                        if (cont.isActive) cont.resume(res)
                                    }, 1500)
                                }
                                elapsed >= 25_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> { elapsed += 200; handler.postDelayed(this, 200) }
                            }
                        }
                    }
                    handler.postDelayed(checker, 800)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker); wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    // ================================================================
    // ★ FIX v3: Local proxy using CloudStream's app HTTP client
    // ================================================================
    // The core insight: app.get() already successfully fetches the M3U8.
    // We use the SAME HTTP client to fetch segments via runBlocking().
    // This avoids HttpURLConnection HTTPS issues entirely.
    // ================================================================

    private var localServer: LocalProxyServer? = null

    /**
     * Fetch a URL using CloudStream's app HTTP client (works in any thread).
     * Returns Pair<statusCode, bodyBytes> or null on error.
     */
    private fun fetchBytes(url: String, headers: Map<String, String>): Pair<Int, ByteArray>? {
        return try {
            runBlocking {
                try {
                    val resp = app.get(url, headers = headers)
                    val text = resp.text
                    // If it's an error JSON, return as-is with error code
                    if (text.contains("\"error\"") || text.contains("expired") || text.contains("invalid")) {
                        Pair(403, text.toByteArray(Charsets.UTF_8))
                    } else {
                        // For binary data, .text corrupts it via charset decoding.
                        // Try to get raw bytes via reflection on the response object.
                        val rawBytes = extractRawBytes(resp)
                        if (rawBytes != null && rawBytes.size > 10) {
                            Pair(200, rawBytes)
                        } else {
                            // Fallback: use text with ISO-8859-1 (preserves bytes 0-255)
                            Pair(200, text.toByteArray(Charsets.ISO_8859_1))
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (_: Exception) { null }
    }

    /** Try to extract raw bytes from CloudStream's HttpResponse via reflection */
    private fun extractRawBytes(resp: Any): ByteArray? {
        // Try common patterns to find OkHttp ResponseBody
        for (field in resp.javaClass.declaredFields) {
            try {
                field.isAccessible = true
                val obj = field.get(resp) ?: continue
                // Try bytes() method
                obj.javaClass.methods.firstOrNull {
                    it.name == "bytes" && it.parameterCount == 0 && it.returnType == ByteArray::class.java
                }?.let { return it.invoke(obj) as? ByteArray }
                // Try byteStream() method
                obj.javaClass.methods.firstOrNull {
                    it.name == "byteStream" && it.parameterCount == 0
                }?.let { m ->
                    val stream = m.invoke(obj) as? java.io.InputStream ?: return null
                    val baos = java.io.ByteArrayOutputStream()
                    stream.copyTo(baos); stream.close()
                    return baos.toByteArray()
                }
            } catch (_: Exception) { continue }
        }
        // Try methods on the response object itself
        try {
            resp.javaClass.methods.firstOrNull {
                it.name == "bytes" && it.parameterCount == 0 && it.returnType == ByteArray::class.java
            }?.let { return it.invoke(resp) as? ByteArray }
        } catch (_: Exception) {}
        return null
    }

    inner class LocalProxyServer(
        private val m3u8Content: String,
        private val segHeaders: Map<String, String>
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private val segMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        private var rewrittenM3U8: String = ""
        val port: Int get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = java.net.ServerSocket(0, 50)
            rewrittenM3U8 = rewriteM3U8()
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

        private fun rewriteM3U8(): String {
            val sb = StringBuilder()
            var idx = 0
            for (line in m3u8Content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    val id = idx.toString()
                    segMap[id] = trimmed
                    sb.append("http://127.0.0.1:$port/s/$id")
                    idx++
                } else {
                    sb.append(line)
                }
                sb.append("\n")
            }
            return sb.toString()
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 30_000
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
                            "Content-Length" to "0"), emptyByteArray)
                    path == "/stream.m3u8" || path.endsWith(".m3u8") -> {
                        val body = rewrittenM3U8.toByteArray(Charsets.UTF_8)
                        writeResponse(client, 200,
                            mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=utf-8",
                                "Content-Length" to body.size.toString(),
                                "Cache-Control" to "no-cache"), body)
                    }
                    path.startsWith("/s/") -> {
                        val id = path.removePrefix("/s/")
                        val origUrl = segMap[id]
                        if (origUrl != null) proxySegment(client, origUrl)
                        else writeResponse(client, 404,
                            mapOf("Content-Type" to "text/plain",
                                "Content-Length" to "17"), "Segment not found".toByteArray())
                    }
                    else -> writeResponse(client, 404,
                        mapOf("Content-Type" to "text/plain"), "Not found".toByteArray())
                }
            } catch (_: java.net.SocketException) {} // client disconnected
            catch (_: Exception) {} finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        private fun proxySegment(client: java.net.Socket, url: String) {
            // ★ PRIMARY: Use CloudStream's app.get() via runBlocking
            val result = fetchBytes(url, segHeaders)
            if (result != null) {
                val (code, data) = result
                if (code in 200..299 && data.isNotEmpty()) {
                    writeResponse(client, 200,
                        mapOf("Content-Type" to "video/mp2t",
                            "Content-Length" to data.size.toString(),
                            "Accept-Ranges" to "bytes"), data)
                    return
                }
                // Got an error from upstream (e.g., session expired)
                if (data.isNotEmpty()) {
                    // Log error by writing it to the response
                    writeResponse(client, code,
                        mapOf("Content-Type" to "application/json",
                            "Content-Length" to data.size.toString()), data)
                    return
                }
            }

            // ★ FALLBACK: Try HttpURLConnection with SSL bypass
            proxySegmentFallback(client, url)
        }

        private fun proxySegmentFallback(client: java.net.Socket, url: String) {
            var conn: HttpURLConnection? = null
            try {
                conn = java.net.URL(url).openConnection() as HttpURLConnection
                // SSL bypass for HTTPS
                if (conn is javax.net.ssl.HttpsURLConnection) {
                    try {
                        val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                        sslCtx.init(null, arrayOf<javax.net.ssl.TrustManager>(
                            object : javax.net.ssl.X509TrustManager {
                                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                            }
                        ), java.security.SecureRandom())
                        conn.sslSocketFactory = sslCtx.socketFactory
                        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    } catch (_: Exception) {}
                }
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                segHeaders.forEach { (k, v) -> try { conn.setRequestProperty(k, v) } catch (_: Exception) {} }

                val code = conn.responseCode
                if (code in 200..299) {
                    val ct = conn.contentType ?: "video/mp2t"
                    val len = conn.contentLength
                    val hdrs = mutableMapOf(
                        "Content-Type" to ct,
                        "Accept-Ranges" to "bytes"
                    )
                    if (len > 0) hdrs["Content-Length"] = len.toString()

                    val os = client.getOutputStream()
                    os.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    hdrs.forEach { (k, v) -> os.write("$k: $v\r\n".toByteArray()) }
                    os.write("\r\n".toByteArray())

                    val buf = ByteArray(32768)
                    var read: Int
                    conn.inputStream.use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            os.write(buf, 0, read)
                        }
                    }
                    os.flush()
                } else {
                    // Read error body
                    val errBody = try { conn.errorStream?.readBytes() } catch (_: Exception) { null }
                    writeResponse(client, code,
                        mapOf("Content-Type" to "text/plain"), errBody ?: "Upstream error $code".toByteArray())
                }
            } catch (_: java.net.SocketException) {} // client disconnected
            catch (_: Exception) {
                try { writeResponse(client, 502, mapOf("Content-Type" to "text/plain"),
                    "Proxy fallback error".toByteArray()) } catch (_: Exception) {}
            } finally { conn?.disconnect() }
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

        fun stop() { try { serverSocket?.close() } catch (_: Exception) {} }
    }

    private val emptyByteArray = ByteArray(0)

    // ================================================================
    // loadLinks
    // ================================================================

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        // 1. Find iframe URL
        val epPageHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://stream\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._-]+""")
            .find(epPageHtml)?.value?.replace("&amp;", "&")

        // 2. Get ajax cookies
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

        // 4. Get M3U8 from WebView
        val targetUrl = iframeUrl ?: epUrl
        val m3u8Result = getM3U8(targetUrl, ajaxCookie, avsJs) ?: return true

        // 5. Collect cookies
        val streamCookies = lastStreamCookies
        val allCookies = listOf(streamCookies, ajaxCookie)
            .filter { it.isNotBlank() }.distinct().joinToString("; ")

        // 6. Get M3U8 content
        val m3u8Content: String
        val directM3u8Url: String?

        if (m3u8Result.startsWith("DIRECT_URL::")) {
            directM3u8Url = m3u8Result.removePrefix("DIRECT_URL::")
            val fetchHdrs = mutableMapOf(
                "User-Agent" to UA, "Referer" to "https://stream.googleapiscdn.com/", "Accept" to "*/*"
            )
            if (allCookies.isNotBlank()) fetchHdrs["Cookie"] = allCookies
            m3u8Content = try { app.get(directM3u8Url, headers = fetchHdrs).text }
                catch (_: Exception) { "" }
            if (!m3u8Content.contains("#EXTM3U")) return true
        } else {
            directM3u8Url = null
            m3u8Content = m3u8Result
            if (!m3u8Content.contains("#EXTM3U")) return true
        }

        // 7. Build segment headers
        val segHeaders = mutableMapOf(
            "User-Agent" to UA, "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Referer" to "https://stream.googleapiscdn.com/player/",
            "Origin" to "https://stream.googleapiscdn.com"
        )
        if (allCookies.isNotBlank()) segHeaders["Cookie"] = allCookies

        // 8. ★ TEST: Verify a segment works before deciding strategy
        val firstSegUrl = m3u8Content.lines().firstOrNull {
            it.trim().startsWith("http")
        }?.trim()

        if (firstSegUrl != null) {
            try {
                val testResp = app.get(firstSegUrl, headers = segHeaders)
                val testBody = testResp.text
                // Check if segment actually returns video data
                if (!testBody.contains("\"error\"") && !testBody.contains("expired") && !testBody.contains("invalid")) {
                    // ★ Segment works with app.get() → tokens are self-sufficient or cookies work
                    // Strategy: use proxy with app.get() (most reliable)

                    localServer?.stop()
                    val server = LocalProxyServer(m3u8Content, segHeaders)
                    server.start()
                    localServer = server

                    callback(newExtractorLink(
                        source = name, name = "$name - Proxy",
                        url = "http://127.0.0.1:${server.port}/stream.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
                    })
                    return true
                }
            } catch (_: Exception) {}
        }

        // 9. If segment test failed, try without cookies (token might be self-sufficient)
        val segHeadersNoCookie = mutableMapOf(
            "User-Agent" to UA, "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Referer" to "https://stream.googleapiscdn.com/player/",
            "Origin" to "https://stream.googleapiscdn.com"
        )

        if (firstSegUrl != null) {
            try {
                val testResp = app.get(firstSegUrl, headers = segHeadersNoCookie)
                val testBody = testResp.text
                if (!testBody.contains("\"error\"") && !testBody.contains("expired")) {
                    // Works without cookies!
                    localServer?.stop()
                    val server = LocalProxyServer(m3u8Content, segHeadersNoCookie)
                    server.start()
                    localServer = server

                    callback(newExtractorLink(
                        source = name, name = "$name - Proxy",
                        url = "http://127.0.0.1:${server.port}/stream.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
                    })
                    return true
                }
            } catch (_: Exception) {}
        }

        // 10. Last resort: try direct URL + cookie in ExtractorLink headers
        // (CloudStream might pass headers to ExoPlayer segments)
        if (directM3u8Url != null) {
            val directHdrs = mutableMapOf(
                "User-Agent" to UA,
                "Referer" to "https://stream.googleapiscdn.com/player/",
                "Origin" to "https://stream.googleapiscdn.com"
            )
            if (allCookies.isNotBlank()) directHdrs["Cookie"] = allCookies

            callback(newExtractorLink(
                source = name, name = "$name - Direct",
                url = directM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.headers = directHdrs
            })
        }

        // Also provide proxy as fallback
        localServer?.stop()
        val server = LocalProxyServer(m3u8Content, segHeaders)
        server.start()
        localServer = server

        callback(newExtractorLink(
            source = name, name = "$name - Proxy",
            url = "http://127.0.0.1:${server.port}/stream.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
        })

        return true
    }
}
