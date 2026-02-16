package com.animevietsub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@CloudstreamPlugin
class AnimeVietSubV2Plugin : Plugin() {
    override fun load() { 
        registerMainAPI(AnimeVietSubV2()) 
    }
}

class AnimeVietSubV2 : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub-V2"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private val ua = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )


    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"

        val items = extractViaWebView(url)
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return extractViaWebView(searchUrl)
    }


    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        return extractLoadResponseViaWebView(fixedUrl) 
            ?: throw ErrorLoadingException("Failed to load")
    }


    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@@")
        val epUrl = parts.getOrNull(0) ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val dataHash = parts.getOrNull(2) ?: ""

        val streams = mutableSetOf<String>()
        val latch = CountDownLatch(1)

        withContext(Dispatchers.Main) {
            val ctx = getContext() ?: return@withContext
            val webView = createWebView(ctx)

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onStream(url: String) {
                    if (!isAdUrl(url)) streams.add(url)
                }
            }, "StreamHook")

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("/hls/")) {
                        if (!isAdUrl(url)) streams.add(url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(getPlayerHookJs()) {}
                    Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 12000)
                }
            }

            val html = getPlayerHtml(filmId, dataHash)
            webView.loadDataWithBaseURL(mainUrl, html, "text/html", "UTF-8", null)

            Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 15000)
            latch.await(15, TimeUnit.SECONDS)
            webView.destroy()
        }

        var success = false
        streams.forEach { streamUrl ->
            if (isAdUrl(streamUrl) || streamUrl == "done") return@forEach

            val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
            callback(newExtractorLink(name, name, streamUrl) {
                referer = epUrl
                quality = Qualities.Unknown.value
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf("User-Agent" to ua)
            })
            success = true
        }

        return success
    }


    private fun getPlayerHookJs(): String {
        return """
            (function() {
                var checkPlayers = setInterval(function() {
                    if (typeof jwplayer !== 'undefined') {
                        var p = jwplayer();
                        if (p && p.getPlaylist) {
                            p.getPlaylist().forEach(function(item) {
                                if (item.file) StreamHook.onStream(item.file);
                                (item.sources || []).forEach(function(s) {
                                    if (s.file) StreamHook.onStream(s.file);
                                });
                            });
                        }
                    }
                    document.querySelectorAll('video').forEach(function(v) {
                        if (v.src) StreamHook.onStream(v.src);
                        v.querySelectorAll('source').forEach(function(s) {
                            if (s.src) StreamHook.onStream(s.src);
                        });
                    });
                }, 1000);
                setTimeout(function() { clearInterval(checkPlayers); }, 10000);
            })();
        """.trimIndent()
    }

    private fun getPlayerHtml(filmId: String, dataHash: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <body>
                <form id="f" method="POST" action="$mainUrl/ajax/player">
                    <input type="hidden" name="link" value="$dataHash">
                    <input type="hidden" name="id" value="$filmId">
                </form>
                <script>document.getElementById('f').submit();</script>
            </body>
            </html>
        """.trimIndent()
    }


    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractViaWebView(url: String): List<SearchResponse> = withContext(Dispatchers.Main) {
        val results = mutableListOf<SearchResponse>()
        val latch = CountDownLatch(1)

        try {
            val ctx = getContext() ?: return@withContext emptyList()
            val webView = createWebView(ctx)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    view?.evaluateJavascript("""
                        (function() {
                            var items = [];
                            document.querySelectorAll('article, .TPost, .TPostMv, .item, .anime-item, .movie-item').forEach(function(el) {
                                var a = el.querySelector('a');
                                if (a && a.href) {
                                    items.push({
                                        title: el.querySelector('.Title, .title, h3')?.textContent?.trim() || a.title || a.textContent?.trim(),
                                        url: a.href,
                                        poster: el.querySelector('img')?.dataset?.src || el.querySelector('img')?.src
                                    });
                                }
                            });
                            return JSON.stringify(items);
                        })();
                    """) { result ->
                        parseResults(result, results)
                        latch.countDown()
                    }
                }
            }

            webView.loadUrl(fixUrl(url) ?: mainUrl)
            Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 10000)
            latch.await(10, TimeUnit.SECONDS)
            webView.destroy()
        } catch (e: Exception) {}

        results
    }

    private fun parseResults(result: String, results: MutableList<SearchResponse>) {
        try {
            val cleanJson = result.trim('"').replace("\"", """)
            val json = mapper.readValue(cleanJson, List::class.java) as List<Map<String, String>>
            json.forEach { item ->
                val title = item["title"] ?: return@forEach
                val url = item["url"] ?: return@forEach
                val poster = item["poster"]

                if (title.isNotBlank() && url.isNotBlank()) {
                    fixUrl(url)?.let { fixed ->
                        results.add(newAnimeSearchResponse(title, fixed, TvType.Anime) {
                            posterUrl = fixUrl(poster)
                        })
                    }
                }
            }
        } catch (e: Exception) {}
    }


    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractLoadResponseViaWebView(url: String): LoadResponse? = withContext(Dispatchers.Main) {
        var result: LoadResponse? = null
        val latch = CountDownLatch(1)

        try {
            val ctx = getContext() ?: return@withContext null
            val webView = createWebView(ctx)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    view?.evaluateJavascript("""
                        (function() {
                            var data = {
                                title: document.querySelector('h1.Title, h1, .film-title')?.textContent?.trim(),
                                poster: document.querySelector('.Image img, .film-poster img')?.src,
                                plot: document.querySelector('.Description, #film-content')?.textContent?.trim(),
                                episodes: []
                            };
                            document.querySelectorAll('.btn-episode, .episode-link, a[data-hash]').forEach(function(ep) {
                                data.episodes.push({
                                    name: ep.textContent?.trim(),
                                    href: ep.href,
                                    hash: ep.dataset?.hash || ep.dataset?.id
                                });
                            });
                            return JSON.stringify(data);
                        })();
                    """) { jsonResult ->
                        try {
                            val cleanJson = jsonResult.trim('"').replace("\"", """)
                            val data = mapper.readValue(cleanJson, Map::class.java)
                            val title = data["title"] as? String
                            if (!title.isNullOrBlank()) {
                                val episodes = (data["episodes"] as? List<Map<String, String>>)?.mapNotNull { ep ->
                                    val name = ep["name"] ?: return@mapNotNull null
                                    val href = ep["href"] ?: return@mapNotNull null
                                    val hash = ep["hash"] ?: return@mapNotNull null
                                    val fixed = fixUrl(href) ?: return@mapNotNull null

                                    newEpisode("$fixed@@@$hash") {
                                        this.name = name
                                    }
                                } ?: emptyList()

                                result = newAnimeLoadResponse(title, url, TvType.Anime) {
                                    posterUrl = fixUrl(data["poster"] as? String)
                                    plot = data["plot"] as? String
                                    this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                                }
                            }
                        } catch (e: Exception) {}
                        latch.countDown()
                    }
                }
            }

            webView.loadUrl(url)
            Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 12000)
            latch.await(12, TimeUnit.SECONDS)
            webView.destroy()
        } catch (e: Exception) {}

        result
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context): WebView {
        return WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = ua
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun getContext(): Context? {
        return try {
            Class.forName("com.lagradost.cloudstream3.MainActivity")
                .getMethod("getContext")
                .invoke(null) as? Context
        } catch (e: Exception) { null }
    }

    private fun fixUrl(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:" + u.trim()
            u.startsWith("/") -> mainUrl + u.trim()
            else -> "$mainUrl/$u"
        }
    }

    private fun isAdUrl(url: String): Boolean {
        return listOf("googleads", "doubleclick", "googlesyndication", 
            "facebook", "analytics", "tracking", "/ads/", "adserver")
            .any { url.contains(it, ignoreCase = true) }
    }
}
