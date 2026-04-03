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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.EnumSet
import kotlin.coroutines.resume

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        val provider = AnimeVietSubProvider()
        registerMainAPI(provider)
        kotlinx.coroutines.GlobalScope.launch {
            provider.prefetchAvsJs()
        }
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
    /** Cookies extracted from WebView (cf_clearance etc.) for stream.googleapiscdn.com */
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

        val infoDoc = try {
            app.get("$base/", headers = baseHeaders).document
        } catch (_: Exception) { null }

        val watchDoc = app.get("$base/xem-phim.html", headers = baseHeaders).document

        val title = watchDoc.selectFirst("h1.Title")?.text()?.trim()
            ?: infoDoc?.selectFirst("h1.Title")?.text()?.trim()
            ?: watchDoc.title()
        val altTitle = watchDoc.selectFirst("h2.SubTitle")?.text()?.trim()
            ?: infoDoc?.selectFirst("h2.SubTitle")?.text()?.trim()
        val poster = watchDoc.selectFirst("div.Image figure img")?.attr("src")
            ?: infoDoc?.selectFirst("div.Image figure img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plotOriginal = watchDoc.selectFirst("div.Description")?.text()?.trim()
            ?: infoDoc?.selectFirst("div.Description")?.text()?.trim()

        fun metaValue(doc: org.jsoup.nodes.Document?, label: String): String? {
            if (doc == null) return null
            for (li in doc.select("li")) {
                val lbl = li.selectFirst("label")
                if (lbl != null && lbl.text().contains(label, ignoreCase = true)) {
                    return li.text().substringAfter(lbl.text()).trim().ifBlank { null }
                }
            }
            val found = doc.selectFirst("li:contains($label)")
            if (found != null) {
                return found.text().replace(label, "").trim().ifBlank { null }
            }
            return null
        }

        val views = watchDoc.selectFirst("span.View")?.text()?.trim()
            ?.replace("Lượt Xem", "lượt xem")

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

        val followers = metaValue(infoDoc, "Theo dõi")
            ?: metaValue(infoDoc, "Số người theo dõi")
            ?: metaValue(watchDoc, "Theo dõi")
            ?: metaValue(watchDoc, "Số người theo dõi")

        val tags = (infoDoc?.select("p.Genre a, li:contains(Thể loại:) a")
            ?: watchDoc.select("p.Genre a, li:contains(Thể loại:) a")).map {
            it.text().trim()
        }.filter { it.isNotBlank() }.distinct()

        val latestEps = (infoDoc?.select("li.latest_eps a")
            ?: watchDoc.select("li.latest_eps a")).map { it.text().trim() }
            .take(3).joinToString(", ")

        val description = buildBeautifulDescription(
            altTitle, status, duration, quality, country,
            year?.toString(), studio, followers, views,
            latestEps.ifBlank { null }, tags.joinToString(", "), plotOriginal
        )

        val seen = mutableSetOf<String>()
        val episodes = watchDoc.select("#list-server .list-episode a.episode-link, " +
                ".listing.items a[href*=/tap-], " +
                "a[href*=-tap-]")
            .mapNotNull { a ->
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                if (href.isBlank() || !seen.add(href)) return@mapNotNull null

                val epNum = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                val epTitle = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }

                newEpisode(href) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.distinctBy { it.episode ?: it.data }
            .sortedBy { it.episode ?: 0 }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(
                title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html"
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    private fun buildBeautifulDescription(
        altTitle: String?,
        status: String?,
        duration: String?,
        quality: String?,
        country: String?,
        year: String?,
        studio: String?,
        followers: String?,
        views: String?,
        latestEps: String?,
        genre: String?,
        description: String?
    ): String {
        return buildString {
            altTitle?.takeIf { it.isNotBlank() }?.let {
                append("<font color='#AAAAAA'><i>$it</i></font><br><br>")
            }

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank()) {
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
                }
            }

            val statusColor = when {
                status?.contains("đang chiếu", ignoreCase = true) == true -> "#4CAF50"
                status?.contains("hoàn thành", ignoreCase = true) == true -> "#2196F3"
                status?.contains("sắp chiếu", ignoreCase = true) == true -> "#FF9800"
                else -> "#2196F3"
            }

            addInfo("📺", "Trạng thái", status, statusColor)
            addInfo("⏱", "Thời lượng", duration)
            addInfo("🎬", "Chất lượng", quality?.ifBlank { null }, "#E91E63")
            addInfo("🌍", "Quốc gia", country)
            addInfo("📅", "Năm", year?.ifBlank { null })
            addInfo("🎥", "Studio", studio)
            addInfo("👥", "Theo dõi", followers?.ifBlank { null })
            addInfo("👁", "Lượt xem", views)
            addInfo("🎞", "Tập mới", latestEps)
            addInfo("🏷", "Thể loại", genre?.ifBlank { null })

            description?.takeIf { it.isNotBlank() }?.let {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(it.trim())
            }
        }
    }

    private val blobInterceptor = """
;(function(){
var _oc=URL.createObjectURL;
URL.createObjectURL=function(b){
var u=_oc.apply(this,arguments);
try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){
var r=new FileReader();
r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};
r.readAsText(b);}}catch(x){}
return u;};
});
""".trimIndent()

    private val fakeAds = """
window.adsbygoogle=window.adsbygoogle||[];
window.adsbygoogle.loaded=true;
window.adsbygoogle.push=function(){};
""".trimIndent()

    inner class M3U8Bridge {
        @Volatile var result: String? = null     // blob M3U8 content
        @Volatile var m3u8Url: String? = null    // direct playlist URL
        @Volatile var streamCookies: String? = null // cf_clearance cookies from WebView

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }
    }

    suspend fun prefetchAvsJs() {
        if (cachedAvsJs != null) return
        try {
            // Auto-detect player JS from page HTML first
            val pageHtml = try { app.get("$mainUrl/", headers = baseHeaders).text } catch(_: Exception) { "" }
            val detectedPath = Regex("""statics/default/js/((?:pl\.watchbk\d+|avs\.watch)\.js\?v=[0-9.]+)""")
                .find(pageHtml)?.groupValues?.get(1)
            val jsUrl = if (!detectedPath.isNullOrBlank())
                "$mainUrl/statics/default/js/$detectedPath"
            else
                "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9"
            val js = app.get(
                jsUrl,
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*")
            ).text
            if (js.length > 500) cachedAvsJs = js
        } catch (_: Exception) {}
    }

    private suspend fun fetchJs(url: String, cookie: String): String? {
        // Try provided URL first
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/",
                "Accept" to "*/*", "Cookie" to cookie
            ))
            if (resp.text.length > 500) return resp.text
        } catch (_: Exception) {}
        // Auto-detect from page HTML
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
                    val ctx = try { AcraApplication.context }
                    catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val bridge = M3U8Bridge()

                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        cookie.split(";").forEach { kv ->
                            val t = kv.trim()
                            if (t.isNotBlank()) setCookie(mainUrl, t)
                        }
                        flush()
                    }

                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = UA
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    android.webkit.CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")

                    val patchedAvsJs = blobInterceptor + "\n" + avsJs
                    val avsJsBytes = patchedAvsJs.toByteArray(Charsets.UTF_8)
                    val fakeAdsBytes = fakeAds.toByteArray(Charsets.UTF_8)

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            return when {
                                url.contains("watchbk") || url.contains("avs.watch") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream(avsJsBytes)
                                )
                                // Capture direct M3U8 URL from player
                                url.contains("googleapiscdn.com/playlist/") && url.contains(".m3u8") -> {
                                    bridge.m3u8Url = url
                                    null // let the request proceed normally
                                }
                                url.contains("adsbygoogle") ||
                                        url.contains("googlesyndication") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream(fakeAdsBytes)
                                )
                                url.contains("google-analytics") ||
                                        url.contains("doubleclick") ||
                                        url.contains("googletagmanager") ||
                                        url.contains("facebook.com") ||
                                        url.contains("hotjar") ||
                                        url.contains("disqus") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                url.endsWith(".woff") || url.endsWith(".woff2") ||
                                        url.endsWith(".ttf") || url.endsWith(".eot") ||
                                        (url.endsWith(".css") && !url.contains(mainUrl)) -> WebResourceResponse(
                                    "text/css", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                url.endsWith(".png") || url.endsWith(".jpg") ||
                                        url.endsWith(".jpeg") || url.endsWith(".gif") ||
                                        url.endsWith(".webp") || url.endsWith(".svg") -> WebResourceResponse(
                                    "image/png", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                                else -> null
                            }
                        }
                    }

                    wv.loadUrl(epUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "Referer" to "$mainUrl/"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            val directUrl = bridge.m3u8Url
                            val blobContent = bridge.result
                            when {
                                directUrl != null -> {
                                    // ★ FIX: Extract cf_clearance cookies BEFORE destroying WebView
                                    try {
                                        val cookies = CookieManager.getInstance()
                                            .getCookie("https://stream.googleapiscdn.com") ?: ""
                                        bridge.streamCookies = cookies
                                        lastStreamCookies = cookies
                                    } catch (_: Exception) {}
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume("DIRECT_URL::$directUrl")
                                }
                                blobContent != null -> {
                                    // ★ FIX: Also extract cookies for blob path
                                    try {
                                        val cookies = CookieManager.getInstance()
                                            .getCookie("https://stream.googleapiscdn.com") ?: ""
                                        bridge.streamCookies = cookies
                                        lastStreamCookies = cookies
                                    } catch (_: Exception) {}
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(blobContent)
                                }
                                elapsed >= 25_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> {
                                    elapsed += 200
                                    handler.postDelayed(this, 200)
                                }
                            }
                        }
                    }
                    handler.postDelayed(checker, 800)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker)
                        wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    // =========================================================================
    // ★ FIX: StreamingProxyServer — Local proxy that handles BOTH M3U8 & segments
    // =========================================================================
    // Problem: ExoPlayer doesn't have cf_clearance cookie for segment requests,
    // causing Cloudflare to return 404 on segments.
    // Solution: Rewrite M3U8 to point segments to local proxy, proxy forwards
    // segment requests with proper auth headers & cookies.
    // =========================================================================

    private var localServer: StreamingProxyServer? = null

    inner class StreamingProxyServer(
        private val m3u8Content: String,
        private val segmentHeaders: Map<String, String>
    ) {
        private var serverSocket: java.net.ServerSocket? = null
        private val segMap = java.util.concurrent.ConcurrentHashMap<String, String>()
        private var rewrittenM3U8: String = ""

        val port: Int get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = java.net.ServerSocket(0)
            rewrittenM3U8 = rewriteM3U8()

            Thread {
                try {
                    val ss = serverSocket ?: return@Thread
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            Thread { handleClient(client) }
                                .also { it.isDaemon = true }
                                .start()
                        } catch (_: java.net.SocketException) { break }
                        catch (_: Exception) { break }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }

        /** Rewrite all segment URLs in M3U8 to go through local proxy */
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
                client.soTimeout = 20_000
                val reader = java.io.BufferedReader(
                    java.io.InputStreamReader(client.getInputStream())
                )
                val requestLine = reader.readLine() ?: return
                // Consume remaining headers
                while (reader.readLine()?.isNotBlank() == true) {}

                val parts = requestLine.split(" ")
                if (parts.size < 2) { client.close(); return }
                val method = parts[0]
                val path = parts[1].substringBefore("?")

                when {
                    method == "OPTIONS" -> {
                        val os = client.getOutputStream()
                        os.write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: *\r\n" +
                            "Access-Control-Max-Age: 86400\r\n" +
                            "Content-Length: 0\r\n" +
                            "\r\n"
                        ).toByteArray())
                        os.flush()
                    }
                    path == "/stream.m3u8" || path.endsWith(".m3u8") -> {
                        val body = rewrittenM3U8.toByteArray(Charsets.UTF_8)
                        val os = client.getOutputStream()
                        os.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        os.write("Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n".toByteArray())
                        os.write("Content-Length: ${body.size}\r\n".toByteArray())
                        os.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        os.write("Connection: close\r\n".toByteArray())
                        os.write("\r\n".toByteArray())
                        os.write(body)
                        os.flush()
                    }
                    path.startsWith("/s/") -> {
                        val id = path.removePrefix("/s/")
                        val origUrl = segMap[id]
                        if (origUrl != null) {
                            proxySegment(client, origUrl)
                        } else {
                            sendError(client, 404, "Segment not found")
                        }
                    }
                    else -> sendError(client, 404, "Not found")
                }
            } catch (_: Exception) {} finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

        /** Proxy a segment request to the actual server with proper auth headers */
        private fun proxySegment(client: java.net.Socket, url: String) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    segmentHeaders.forEach { (k, v) ->
                        try { setRequestProperty(k, v) } catch (_: Exception) {}
                    }
                }

                val code = conn.responseCode
                when {
                    code == 200 || code == 206 -> {
                        val ct = conn.contentType ?: "video/mp2t"
                        val len = conn.contentLength
                        val os = client.getOutputStream()
                        os.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        os.write("Content-Type: $ct\r\n".toByteArray())
                        if (len > 0) os.write("Content-Length: $len\r\n".toByteArray())
                        os.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        os.write("Connection: close\r\n".toByteArray())
                        os.write("\r\n".toByteArray())

                        val buf = ByteArray(16384)
                        var read: Int
                        conn.inputStream.use { inp ->
                            while (inp.read(buf).also { read = it } != -1) {
                                os.write(buf, 0, read)
                            }
                        }
                        os.flush()
                    }
                    code in 300..399 -> {
                        // Handle redirect — some CDNs redirect segments
                        val loc = conn.getHeaderField("Location") ?: ""
                        val os = client.getOutputStream()
                        os.write("HTTP/1.1 302 Found\r\n".toByteArray())
                        os.write("Location: $loc\r\n".toByteArray())
                        os.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        os.write("Connection: close\r\n".toByteArray())
                        os.write("\r\n".toByteArray())
                        os.flush()
                    }
                    else -> sendError(client, code, "Upstream error: $code")
                }
            } catch (_: java.net.SocketException) {
                // Client disconnected
            } catch (_: Exception) {
                try { sendError(client, 502, "Proxy error") } catch (_: Exception) {}
            } finally {
                conn?.disconnect()
            }
        }

        private fun sendError(client: java.net.Socket, code: Int, msg: String = "") {
            try {
                val body = msg.toByteArray(Charsets.UTF_8)
                val os = client.getOutputStream()
                os.write("HTTP/1.1 $code\r\n".toByteArray())
                os.write("Content-Type: text/plain\r\n".toByteArray())
                os.write("Content-Length: ${body.size}\r\n".toByteArray())
                os.write("Connection: close\r\n".toByteArray())
                os.write("\r\n".toByteArray())
                os.write(body)
                os.flush()
            } catch (_: Exception) {}
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        // Fetch episode page → find iframe URL (stream.googleapiscdn.com/player/...)
        val epPageHtml = try { app.get(epUrl, headers = baseHeaders).text } catch (_: Exception) { "" }
        val iframeUrl = Regex("""https://stream\.googleapiscdn\.com/player/[a-fA-F0-9?=&%+._-]+""")
            .find(epPageHtml)?.value?.replace("&amp;", "&")

        // Get cookie
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val ajaxHdr = mapOf(
            "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to epUrl, "Origin" to mainUrl
        )
        val cookie = try {
            app.post("$mainUrl/ajax/player", headers = ajaxHdr,
                data = mapOf("episodeId" to epId, "backup" to "1"))
                .cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } catch (_: Exception) { "" }

        // Get player JS
        val avsJs = cachedAvsJs ?: fetchJs(
            "$mainUrl/statics/default/js/pl.watchbk2.js?v=6.1.9", cookie
        )?.also { cachedAvsJs = it } ?: return true

        // Load iframe URL directly — blob M3U8 is created in iframe context
        val targetUrl = iframeUrl ?: epUrl
        val m3u8Result = getM3U8(targetUrl, cookie, avsJs) ?: return true

        // ★ FIX: Build headers with cf_clearance cookie for segment proxying
        val streamCookies = lastStreamCookies
        val segHeaders = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "https://stream.googleapiscdn.com/player/",
            "Origin" to "https://stream.googleapiscdn.com"
        )
        // Add cf_clearance cookie if available
        if (streamCookies.isNotBlank()) {
            segHeaders["Cookie"] = streamCookies
        }
        // Also add any cookies from ajax/player request
        if (cookie.isNotBlank() && streamCookies.isBlank()) {
            segHeaders["Cookie"] = cookie
        }

        val m3u8Content: String

        if (m3u8Result.startsWith("DIRECT_URL::")) {
            // ★ FIX: Fetch M3U8 content ourselves with proper auth, then proxy
            val directM3u8Url = m3u8Result.removePrefix("DIRECT_URL::")
            val fetchHeaders = mutableMapOf(
                "User-Agent" to UA,
                "Referer" to "https://stream.googleapiscdn.com/",
                "Accept" to "*/*"
            )
            if (streamCookies.isNotBlank()) fetchHeaders["Cookie"] = streamCookies
            else if (cookie.isNotBlank()) fetchHeaders["Cookie"] = cookie

            m3u8Content = try {
                app.get(directM3u8Url, headers = fetchHeaders).text
            } catch (_: Exception) { "" }

            if (!m3u8Content.contains("#EXTM3U")) return true
        } else {
            // Blob M3U8 content — still needs proxy for segments
            m3u8Content = m3u8Result
            if (!m3u8Content.contains("#EXTM3U")) return true
        }

        // ★ FIX: Always serve through StreamingProxyServer (handles both M3U8 + segments)
        localServer?.stop()
        val server = StreamingProxyServer(m3u8Content, segHeaders)
        server.start()
        localServer = server

        callback(newExtractorLink(
            source = name, name = "$name - Proxy",
            url = "http://127.0.0.1:${server.port}/stream.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/"
            )
        })
        return true
    }
}
