package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    private val headers = mapOf(
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
        val doc   = app.get(pageUrl(request.data, page), headers = headers).document
        val items = doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/",
            headers = headers
        ).document
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val base     = url.trimEnd('/')
        val watchDoc = app.get("$base/xem-phim.html", headers = headers).document
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

    // ── Step 1: Lấy hash và filmId từ API ───────────────────────────────────
    data class EpisodeInfo(
        val fileHash: String,
        val filmId:   String,
        val cookie:   String
    )

    private suspend fun getEpisodeInfo(epUrl: String): EpisodeInfo? {
        val pageHtml  = app.get(epUrl, headers = headers).text
        val filmId    = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageHtml)?.groupValues?.get(1) ?: return null
        val episodeId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                        .find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1)
                        ?: return null

        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to epUrl,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // POST /ajax/player để lấy cookie
        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to episodeId, "backup" to "1")
        )
        val cookie = playerResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (cookie.isBlank()) return null

        // GET /ajax/get_episode với cookie → RSS XML chứa <file>
        val rssResp = app.get(
            "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
            headers = ajaxHdr + mapOf("Cookie" to cookie) - "Content-Type"
        )
        val fileHash = Regex("""<file>\s*([A-Za-z0-9_\-+/=]+)\s*</file>""")
                       .find(rssResp.text)?.groupValues?.get(1)?.trim()
                       ?: return null

        return EpisodeInfo(fileHash, filmId, cookie)
    }

    // ── Step 2: WebView chạy custom HTML với avs.watch.js + hash ────────────
    // Không dùng trang gốc (có ad detector) mà tạo HTML riêng inject avs.watch.js
    inner class M3U8Bridge {
        @Volatile var m3u8Content: String? = null

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) m3u8Content = content
        }
    }

    private fun buildCustomHtml(fileHash: String, filmId: String, cookie: String): String {
        // HTML tự chứa script bắt blob + gọi AnimeVsub trực tiếp
        // loadDataWithBaseURL với base = mainUrl để CORS pass
        return """<!DOCTYPE html>
<html>
<head>
<script>
// Bắt blob M3U8 TRƯỚC khi load avs.watch.js
(function(){
var _orig = URL.createObjectURL;
URL.createObjectURL = function(blob) {
  var url = _orig.apply(this, arguments);
  try {
    if (blob && blob.type && blob.type.indexOf('mpegurl') !== -1) {
      var r = new FileReader();
      r.onload = function(e) {
        try { if (window.Android) window.Android.onM3U8(e.target.result); } catch(x) {}
      };
      r.readAsText(blob);
    }
  } catch(x) {}
  return url;
};
// Fake jQuery minimal để avs.watch.js có thể chạy $.ajax
window.$ = window.jQuery = function(x) { return window.$; };
window.$.ajax = function(opts) {
  var url = opts.url || '';
  var data = opts.data || {};
  var method = (opts.type || opts.method || 'GET').toUpperCase();
  var xhr = new XMLHttpRequest();
  xhr.open(method, url, true);
  xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
  xhr.setRequestHeader('Cookie', '${cookie.replace("'", "\\'")}');
  xhr.onload = function() {
    if (xhr.status === 200 && opts.success) opts.success(xhr.responseText);
  };
  xhr.onerror = function() { if (opts.error) opts.error(); };
  if (method === 'POST' && data) {
    var params = Object.keys(data).map(function(k){ return encodeURIComponent(k)+'='+encodeURIComponent(data[k]); }).join('&');
    xhr.send(params);
  } else {
    xhr.send();
  }
  return { done: function(f) { return this; }, fail: function(f) { return this; } };
};
window.$.fn = {};
window.$.extend = function(a,b) { return Object.assign(a,b); };
// filmInfo needed by AnimeVsub
window.filmInfo = { filmID: $filmId };
})();
</script>
<script src="$mainUrl/statics/default/js/avs.watch.js?v=6.1.6"></script>
</head>
<body>
<script>
// Gọi AnimeVsub với hash từ API
try { AnimeVsub('$fileHash', $filmId); } catch(e) { console.error(e); }
</script>
</body>
</html>"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runAnimeVsubInWebView(
        fileHash: String,
        filmId:   String,
        cookie:   String
    ): String? {
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { cont ->
                val bridge  = M3U8Bridge()
                val context = try { AcraApplication.context }
                              catch (_: Exception) { null }
                              ?: return@suspendCancellableCoroutine

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    // QUAN TRỌNG: Sync cookie vào WebView cookie store
                    // Nếu không, GET /ajax/get_episode sẽ fail vì thiếu cookie
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(
                        android.webkit.WebView(context), true
                    )
                    cookie.split(";").forEach { pair ->
                        val kv = pair.trim()
                        if (kv.isNotBlank()) {
                            cookieManager.setCookie(mainUrl, kv)
                        }
                    }
                    cookieManager.flush()

                    val wv = WebView(context)
                    wv.settings.apply {
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString                  = UA
                        mixedContentMode                 = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    cookieManager.setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")
                    wv.webViewClient = WebViewClient()

                    // Load custom HTML với base URL = mainUrl (CORS pass + cookie domain match)
                    val html = buildCustomHtml(fileHash, filmId, cookie)
                    wv.loadDataWithBaseURL(
                        "$mainUrl/", html, "text/html", "utf-8", null
                    )

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed = 0
                    val checker = object : Runnable {
                        override fun run() {
                            val m3u8 = bridge.m3u8Content
                            when {
                                m3u8 != null && m3u8.contains("#EXTM3U") -> {
                                    wv.destroy()
                                    if (cont.isActive) cont.resume(m3u8)
                                }
                                elapsed >= 28_000 -> {
                                    wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> {
                                    elapsed += 500
                                    handler.postDelayed(this, 500)
                                }
                            }
                        }
                    }
                    handler.postDelayed(checker, 2_000)
                    cont.invokeOnCancellation { wv.destroy() }
                }
            }
        }
    }

    // ── Step 3: Resolve videoN.html → lh3.googleusercontent.com ────────────
    private suspend fun parseM3U8AndCallback(
        m3u8Text: String,
        epUrl:    String,
        callback: (ExtractorLink) -> Unit
    ) {
        val segHdr   = mapOf("Referer" to "$mainUrl/", "User-Agent" to UA)
        val lines    = m3u8Text.lines()
        val newLines = coroutineScope {
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

        val sep       = "\n"
        val newM3u8   = newLines.joinToString(sep)
        val cacheDir  = AcraApplication.context?.cacheDir ?: return
        cacheDir.mkdirs()
        val cacheFile = java.io.File(cacheDir, "avs_${System.currentTimeMillis()}.m3u8")
        cacheFile.writeText(newM3u8, Charsets.UTF_8)

        callback(newExtractorLink(
            source = name,
            name   = "$name - DU",
            url    = "file://${cacheFile.absolutePath}",
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })
    }

    // ── loadLinks ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        // Bước 1: lấy hash + filmId từ API
        val info = getEpisodeInfo(epUrl) ?: return true

        // Bước 2: chạy AnimeVsub trong WebView riêng (không có ad detector)
        val m3u8Text = runAnimeVsubInWebView(info.fileHash, info.filmId, info.cookie)
            ?: return true

        // Bước 3: resolve segments và callback
        parseM3U8AndCallback(m3u8Text, epUrl, callback)
        return true
    }

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )
}
