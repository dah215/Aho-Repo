package com.animevietsub

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.ServerSocket
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

    // ... (Giữ nguyên các hàm mainPage, parseCard, getMainPage, search, load như cũ)

    // --- SỬA LỖI WEBVIEW VÀ M3U8 ---
    private val blobInterceptor = ";(function(){var _oc=URL.createObjectURL;URL.createObjectURL=function(b){var u=_oc.apply(this,arguments);try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){var r=new FileReader();r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};r.readAsText(b);}}catch(x){}return u;};})();"
    
    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @JavascriptInterface
        fun onM3U8(content: String) { if (content.contains("#EXTM3U")) result = content }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(epUrl: String, cookie: String, avsJs: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val ctx = AcraApplication.context ?: return@suspendCancellableCoroutine cont.resume(null)
                val bridge = M3U8Bridge()
                val wv = WebView(ctx)
                
                wv.settings.apply { 
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = UA 
                }
                
                wv.addJavascriptInterface(bridge, "Android")
                
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.toString().contains("avs.watch.js")) {
                            return WebResourceResponse("application/javascript", "utf-8", 
                                ByteArrayInputStream((blobInterceptor + "\n" + avsJs).toByteArray()))
                        }
                        return null
                    }
                }

                // Set cookie trước khi load
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookie.split(";").forEach { cookieManager.setCookie(mainUrl, it.trim()) }

                wv.loadUrl(epUrl)

                val handler = Handler(Looper.getMainLooper())
                val runnable = object : Runnable {
                    var count = 0
                    override fun run() {
                        if (bridge.result != null) {
                            wv.destroy()
                            if (cont.isActive) cont.resume(bridge.result)
                        } else if (count++ > 60) { // Timeout sau 30s
                            wv.destroy()
                            if (cont.isActive) cont.resume(null)
                        } else {
                            handler.postDelayed(this, 500)
                        }
                    }
                }
                handler.postDelayed(runnable, 500)
                
                cont.invokeOnCancellation { handler.removeCallbacks(runnable); wv.destroy() }
            }
        }
    }

    // --- SỬA LỖI LOCAL SERVER ---
    inner class LocalM3U8Server(private val m3u8Content: String) {
        private var serverSocket: ServerSocket? = null
        val port: Int get() = serverSocket?.localPort ?: 0

        fun start() {
            serverSocket = ServerSocket(0)
            Thread {
                try {
                    val client = serverSocket?.accept() ?: return@Thread
                    client.getInputStream().bufferedReader().readLine()
                    val body = m3u8Content.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray())
                    client.getOutputStream().write(body)
                    client.close()
                    serverSocket?.close()
                } catch (_: Exception) {}
            }.start()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val epUrl = data.substringBefore("|")
        val epId = Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1) ?: return false
        
        val playerResp = app.post("$mainUrl/ajax/player", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), data = mapOf("episodeId" to epId, "backup" to "1"))
        val cookie = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val avsJs = cachedAvsJs ?: return false
        
        val m3u8 = getM3U8(epUrl, cookie, avsJs) ?: return false
        
        val server = LocalM3U8Server(m3u8)
        server.start()
        
        callback(newExtractorLink(name, "$name - Stream", "http://127.0.0.1:${server.port}/stream.m3u8", ExtractorLinkType.M3U8))
        return true
    }
}
