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
import kotlinx.coroutines.*
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
        GlobalScope.launch { provider.prefetchAvsJs() }
    }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl = "https://animevietsub.mx"
    override var name = "AnimeVietSub"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    private val baseHeaders = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
    private var cachedAvsJs: String? = null

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/" to "Anime Mới Nhất",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/hoat-hinh-trung-quoc/" to "Hoạt Hình TQ"
    )

    override suspend fun load(url: String): LoadResponse {
        val base = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = baseHeaders).document
        
        val title = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster = watchDoc.selectFirst("div.Image figure img")?.attr("src")
        val rawPlot = watchDoc.selectFirst("div.Description")?.text()?.trim() ?: ""
        
        val infoLists = watchDoc.select("ul.InfoList li")
        fun getInfo(label: String): String = infoLists.find { it.text().contains(label) }
            ?.text()?.replace(label, "")?.replace(":", "")?.trim() ?: "Đang cập nhật"

        val castList = watchDoc.select("#MvTb-Cast .ListCast li figcaption").map { it.text() }
        val castString = if (castList.isNotEmpty()) "\n\n### 👥 Nhân vật\n" + castList.joinToString(", ") else ""

        val formattedPlot = """
$rawPlot

---
### 📋 Thông tin chi tiết
* **Trạng thái:** ${getInfo("Trạng thái")}
* **Thời lượng:** ${getInfo("Thời lượng")}
* **Đạo diễn:** ${getInfo("Đạo diễn")}
* **Studio:** ${getInfo("Studio")}
* **Season:** ${getInfo("Season")}
* **Rating:** ${getInfo("Rating")}
$castString
        """.trimIndent()

        val episodes = watchDoc.select("#list-server .list-episode a.episode-link").mapNotNull { a ->
            val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val num = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
            newEpisode(href) {
                this.name = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
                this.episode = num
            }
        }.sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, url, TvType.Anime, true) {
            this.posterUrl = poster
            this.plot = formattedPlot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private val blobInterceptor = ";(function(){var _oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=_oc.apply(this,arguments);try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){var r=new FileReader();r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};r.readAsText(b);}}catch(x){}return u;};})();"
    private val fakeAds = "window.adsbygoogle=window.adsbygoogle||[];window.adsbygoogle.loaded=true;window.adsbygoogle.push=function(){};"

    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @JavascriptInterface
        fun onM3U8(content: String) { if (content.contains("#EXTM3U")) result = content }
    }

    suspend fun prefetchAvsJs() {
        if (cachedAvsJs != null) return
        try {
            val js = app.get("$mainUrl/statics/default/js/avs.watch.js?v=6.1.6", headers = baseHeaders).text
            if (js.length > 500) cachedAvsJs = js
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(epUrl: String, cookie: String, avsJs: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = AcraApplication.context ?: return@suspendCancellableCoroutine cont.resume(null)
                    val bridge = M3U8Bridge()
                    val wv = WebView(ctx)
                    wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = UA }
                    wv.addJavascriptInterface(bridge, "Android")
                    val patchedAvsJs = blobInterceptor + "\n" + avsJs
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            return when {
                                url.contains("avs.watch.js") -> WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(patchedAvsJs.toByteArray()))
                                url.contains("adsbygoogle") || url.contains("googlesyndication") -> WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(fakeAds.toByteArray()))
                                else -> null
                            }
                        }
                    }
                    wv.loadUrl(epUrl)
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val checker = object : Runnable {
                        override fun run() {
                            if (bridge.result != null) { wv.stopLoading(); wv.destroy(); if (cont.isActive) cont.resume(bridge.result) }
                            else handler.postDelayed(this, 500)
                        }
                    }
                    handler.postDelayed(checker, 1000)
                }
            }
        }
    }

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
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().write(body)
                            client.close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }.start()
        }
        fun stop() { try { serverSocket?.close() } catch (_: Exception) {} }
    }

    private var localServer: LocalM3U8Server? = null

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val epUrl = data.substringBefore("|")
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return true
        val playerResp = app.post("$mainUrl/ajax/player", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("episodeId" to epId, "backup" to "1"))
        val cookie = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val avsJs = cachedAvsJs ?: return true
        val m3u8 = getM3U8(epUrl, cookie, avsJs) ?: return true
        
        localServer?.stop()
        val server = LocalM3U8Server(m3u8)
        server.start()
        localServer = server
        
        callback(newExtractorLink(name, "$name - Stream", "http://127.0.0.1:${server.port}/stream.m3u8", ExtractorLinkType.M3U8))
        return true
    }
}
