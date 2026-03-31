package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
    override var mainUrl = "https://animevietsub.mx"
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
})();
""".trimIndent()

    private val fakeAds = """
window.adsbygoogle=window.adsbygoogle||[];
window.adsbygoogle.loaded=true;
window.adsbygoogle.push=function(){};
""".trimIndent()

    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }
    }

    suspend fun prefetchAvsJs() {
        if (cachedAvsJs != null) return
        try {
            val js = app.get(
                "$mainUrl/statics/default/js/avs.watch.js?v=6.1.6",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/", "Accept" to "*/*")
            ).text
            if (js.length > 500) cachedAvsJs = js
        } catch (_: Exception) {}
    }

    private suspend fun fetchJs(url: String, cookie: String): String? {
        return try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Accept" to "*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9",
                "Cookie" to cookie
            ))
            if (resp.text.length > 500) resp.text else null
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
                                url.contains("avs.watch.js") -> WebResourceResponse(
                                    "application/javascript", "utf-8",
                                    ByteArrayInputStream(avsJsBytes)
                                )
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
                            val m = bridge.result
                            when {
                                m != null -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(m)
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

    private var localServer: LocalM3U8Server? = null

    inner class LocalM3U8Server(private val m3u8Content: String) {
        private var serverSocket: java.net.ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = java.net.ServerSocket(0)
            Thread {
                try {
                    val ss = serverSocket ?: return@Thread
                    repeat(10) {
                        try {
                            val client = ss.accept()
                            client.getInputStream().bufferedReader().readLine()
                            val body = m3u8Content.toByteArray(Charsets.UTF_8)
                            val crlf = "\r\n"
                            val response = "HTTP/1.1 200 OK${crlf}" +
                                    "Content-Type: application/vnd.apple.mpegurl${crlf}" +
                                    "Content-Length: ${body.size}${crlf}" +
                                    "Access-Control-Allow-Origin: *${crlf}" +
                                    crlf
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().write(body)
                            client.getOutputStream().flush()
                            client.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun serveM3U8AndCallback(m3u8: String, callback: (ExtractorLink) -> Unit) {
        localServer?.stop()
        val server = LocalM3U8Server(m3u8)
        server.start()
        localServer = server

        callback(newExtractorLink(
            source = name, name = "$name - DU",
            url = "http://127.0.0.1:${server.port}/stream.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
        })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val ajaxHdr = mapOf(
            "User-Agent" to UA,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to epUrl,
            "Origin" to mainUrl
        )
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data = mapOf("episodeId" to epId, "backup" to "1")
        )
        val cookie = playerResp.cookies.entries
            .joinToString("; ") { "${it.key}=${it.value}" }

        val avsJs = cachedAvsJs ?: fetchJs(
            "$mainUrl/statics/default/js/avs.watch.js?v=6.1.6", cookie
        )?.also { cachedAvsJs = it } ?: return true

        val m3u8 = getM3U8(epUrl, cookie, avsJs) ?: return true

        serveM3U8AndCallback(m3u8, callback)

        return true
    }
}
