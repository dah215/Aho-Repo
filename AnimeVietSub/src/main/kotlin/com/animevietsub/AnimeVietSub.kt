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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    override fun load() { registerMainAPI(AnimeVietSubProvider()) }
}

class AnimeVietSubProvider : MainAPI() {
    override var mainUrl     = "https://animevietsub.be"
    override var name        = "AnimeVietSub"
    override val hasMainPage = true
    override var lang        = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent"      to UA,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Referer"         to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/"                 to "Anime Mới Nhất",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình TQ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/the-loai/hanh-dong/"        to "Action",
        "$mainUrl/the-loai/tinh-cam/"         to "Romance",
        "$mainUrl/the-loai/phep-thuat/"       to "Fantasy",
        "$mainUrl/the-loai/kinh-di/"          to "Horror",
        "$mainUrl/the-loai/hai-huoc/"         to "Comedy",
        "$mainUrl/the-loai/shounen/"          to "Shounen"
    )

    private fun pageUrl(base: String, page: Int) =
        if (page == 1) "${base.trimEnd('/')}/"
        else "${base.trimEnd('/')}/trang-$page.html"

    private fun parseCard(el: Element): SearchResponse? {
        val article = el.selectFirst("article.TPost") ?: return null
        val a       = article.selectFirst("a[href]") ?: return null
        val href    = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val title   = article.selectFirst("h2.Title")?.text()?.trim()
                      ?.takeIf { it.isNotBlank() } ?: return null
        val poster  = article.selectFirst("div.Image img, figure img")?.attr("src")
                      ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val epiNum  = article.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.quality   = SearchQuality.HD
            this.dubStatus = EnumSet.of(DubStatus.Subbed)
            if (epiNum != null) this.episodes = mutableMapOf(DubStatus.Subbed to epiNum)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc   = app.get(pageUrl(request.data, page), headers = baseHeaders).document
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
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = baseHeaders).document
        val title    = watchDoc.selectFirst("h1.Title")?.text()?.trim() ?: watchDoc.title()
        val poster   = watchDoc.selectFirst("div.Image figure img")?.attr("src")
                       ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        val plot     = watchDoc.selectFirst("div.Description")?.text()?.trim()
        val year     = watchDoc.selectFirst("p.Info .Date a, p.Info .Date")
                       ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags     = watchDoc.select("p.Genre a").map { it.text().trim() }.filter { it.isNotBlank() }

        val seen     = mutableSetOf<String>()
        val episodes = watchDoc.select("#list-server .list-episode a.episode-link")
            .mapNotNull { a ->
                val href = a.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }
                if (href.isBlank() || !seen.add(href)) return@mapNotNull null
                val num  = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                newEpisode(href) {
                    this.name    = a.attr("title").ifBlank { "Tập ${a.text().trim()}" }
                    this.episode = num
                }
            }.sortedBy { it.episode ?: 0 }

        return if (episodes.size <= 1) {
            newMovieLoadResponse(
                title, url, TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: "$base/xem-phim.html"
            ) { this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, true) {
                this.posterUrl = poster; this.plot = plot; this.tags = tags; this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // Script inject blob interceptor - chạy TRƯỚC avs.watch.js
    private val interceptScript = """
window.__blobIntercepted = false;
var __origOBU = URL.createObjectURL;
URL.createObjectURL = function(b) {
  var u = __origOBU.apply(this, arguments);
  try {
    if (!window.__blobIntercepted && b && b.type && b.type.indexOf('mpegurl') !== -1) {
      var r = new FileReader();
      r.onload = function(e) {
        try { Android.onM3U8(e.target.result); window.__blobIntercepted = true; } catch(x) {}
      };
      r.readAsText(b);
    }
  } catch(x) {}
  return u;
};
""".trimIndent()

    // Fake adsbygoogle JS - trick ad detector vào nghĩ quảng cáo đã load
    private val fakeAdScript = """
window.adsbygoogle = window.adsbygoogle || [];
window.adsbygoogle.push = function() {};
Object.defineProperty(window, 'adsbygoogle', {
  get: function() { return {loaded: true, push: function(){}}; },
  configurable: true
});
""".trimIndent()

    inner class M3U8Bridge {
        @Volatile var result: String? = null
        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }
    }

    // Fake JS response để bypass ad detector
    private fun fakeJs(): WebResourceResponse = WebResourceResponse(
        "application/javascript", "utf-8",
        ByteArrayInputStream(fakeAdScript.toByteArray(Charsets.UTF_8))
    )

    // Empty response cho analytics và trackers không cần thiết
    private fun emptyJs(): WebResourceResponse = WebResourceResponse(
        "application/javascript", "utf-8",
        ByteArrayInputStream("".toByteArray())
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(epUrl: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context }
                              catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val bridge = M3U8Bridge()

                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString                  = UA
                        mixedContentMode                 = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(wv, true)
                    }
                    wv.addJavascriptInterface(bridge, "Android")

                    wv.webViewClient = object : WebViewClient() {

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            return when {
                                // Fake adsbygoogle → bypass ad detector
                                url.contains("adsbygoogle") -> fakeJs()
                                url.contains("googlesyndication") -> fakeJs()
                                // Block trackers + popups không cần thiết
                                url.contains("google-analytics") -> emptyJs()
                                url.contains("doubleclick") -> emptyJs()
                                url.contains("googletagmanager") -> emptyJs()
                                else -> null
                            }
                        }

                        override fun onPageStarted(view: WebView, url: String, f: android.graphics.Bitmap?) {
                            // Inject sớm nhất
                            view.evaluateJavascript(interceptScript, null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // Inject lại sau khi page load xong
                            view.evaluateJavascript(interceptScript, null)
                        }
                    }

                    wv.loadUrl(epUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "Referer"         to "$mainUrl/"
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
                                elapsed >= 28_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> {
                                    elapsed += 300
                                    // Re-inject interceptor mỗi 3s phòng bị ghi đè
                                    if (elapsed % 3000 == 0)
                                        view.evaluateJavascript(interceptScript, null)
                                    handler.postDelayed(this, 300)
                                }
                            }
                        }
                    }
                    handler.postDelayed(checker, 3_000)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker)
                        wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    // Resolve videoN.html → lh3.googleusercontent.com
    private suspend fun resolveAndCallback(m3u8: String, callback: (ExtractorLink) -> Unit) {
        val segHdr   = mapOf("Referer" to "$mainUrl/", "User-Agent" to UA)
        val lines    = m3u8.lines()
        val resolved = coroutineScope {
            lines.map { line ->
                async {
                    if (line.startsWith("https://storage.googleapiscdn.com") ||
                        line.startsWith("https://storage.googleapis.com")) {
                        try {
                            app.get(line.trim(), headers = segHdr, allowRedirects = false)
                               .headers["location"]?.trim() ?: line
                        } catch (_: Exception) { line }
                    } else line
                }
            }.awaitAll()
        }

        val sep      = "\n"
        val newM3u8  = resolved.joinToString(sep)
        val cacheDir = AcraApplication.context?.cacheDir ?: return
        cacheDir.mkdirs()
        val file = java.io.File(cacheDir, "avs_${System.currentTimeMillis()}.m3u8")
        file.writeText(newM3u8, Charsets.UTF_8)

        callback(newExtractorLink(
            source = name, name = "$name - DU",
            url    = "file://${file.absolutePath}",
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })
    }

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")
        val m3u8  = getM3U8(epUrl) ?: return true
        resolveAndCallback(m3u8, callback)
        return true
    }
}
