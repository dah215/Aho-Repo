package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
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

    // Cache avs.watch.js để không fetch mỗi lần
    private var avsJsCache: String? = null

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

    // ── Lấy avs.watch.js qua OkHttp (có thể bypass Cloudflare vì có cookie) ─
    private suspend fun fetchAvsJs(epUrl: String, cookie: String): String? {
        if (avsJsCache != null) return avsJsCache
        return try {
            val js = app.get(
                "$mainUrl/statics/default/js/avs.watch.js?v=6.1.6",
                headers = mapOf(
                    "User-Agent"      to UA,
                    "Referer"         to epUrl,
                    "Accept"          to "*/*",
                    "Accept-Language" to "vi-VN,vi;q=0.9",
                    "Cookie"          to cookie
                )
            ).text
            if (js.contains("AnimeVsub") || js.length > 1000) {
                avsJsCache = js
                js
            } else null
        } catch (_: Exception) { null }
    }

    // ── API: lấy fileHash + filmId + cookie ──────────────────────────────────
    data class EpInfo(
        val fileHash:  String,
        val filmId:    String,
        val epId:      String,
        val cookieStr: String
    )

    private suspend fun fetchEpInfo(epUrl: String): EpInfo? {
        val pageText = app.get(epUrl, headers = baseHeaders).text
        val filmId   = Regex("""filmInfo\.filmID\s*=\s*parseInt\('(\d+)'\)""")
                       .find(pageText)?.groupValues?.get(1) ?: return null
        val epId     = Regex("""filmInfo\.episodeID\s*=\s*parseInt\('(\d+)'\)""")
                       .find(pageText)?.groupValues?.get(1)
                       ?: Regex("""-(\d+)\.html""").find(epUrl)?.groupValues?.get(1)
                       ?: return null

        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer"          to epUrl,
            "Origin"           to mainUrl
        )

        val playerResp = app.post(
            "$mainUrl/ajax/player",
            headers = ajaxHdr,
            data    = mapOf("episodeId" to epId, "backup" to "1")
        )
        val cookieStr = playerResp.cookies.entries
                        .joinToString("; ") { "${it.key}=${it.value}" }
        if (cookieStr.isBlank()) return null

        val rss = app.get(
            "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$epId",
            headers = ajaxHdr + mapOf("Cookie" to cookieStr) - "Content-Type"
        ).text
        val fileHash = Regex("""<file>\s*([A-Za-z0-9_\-+/=]+)\s*</file>""")
                       .find(rss)?.groupValues?.get(1)?.trim() ?: return null

        return EpInfo(fileHash, filmId, epId, cookieStr)
    }

    // ── WebView bridge ────────────────────────────────────────────────────────
    inner class M3U8Bridge {
        @Volatile var result: String? = null

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) result = content
        }
    }

    // HTML với avs.watch.js được INLINE (không load external)
    private fun buildHtml(fileHash: String, filmId: String, avsJs: String): String {
        // Escape backticks trong avsJs nếu có
        val safeJs = avsJs.replace("</script>", "<\\/script>")
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<script>
// Bắt blob TRƯỚC khi avs.watch.js chạy
(function(){
var _oc=URL.createObjectURL;
URL.createObjectURL=function(b){
  var u=_oc.apply(this,arguments);
  try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){
    var r=new FileReader();
    r.onload=function(e){try{Android.onM3U8(e.target.result);}catch(x){}};
    r.readAsText(b);
  }}catch(x){}
  return u;
};
// jQuery stub
function xhrAjax(o){
  var x=new XMLHttpRequest(),m=(o.type||o.method||'GET').toUpperCase();
  x.open(m,o.url,true);
  x.setRequestHeader('X-Requested-With','XMLHttpRequest');
  x.onload=function(){if(x.status<400&&o.success)o.success(x.responseText);else if(o.error)o.error(x.status,x.responseText);};
  x.onerror=function(){if(o.error)o.error(0,'');};
  var body=null;
  if(o.data){body=typeof o.data==='string'?o.data:Object.keys(o.data).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(o.data[k]);}).join('&');
    if(m==='POST')x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');}
  x.send(m==='POST'?body:null);
  var p={done:function(f){return p;},fail:function(f){return p;}};return p;
}
var jq=function(s){
  var arr=[];
  try{if(typeof s==='string')arr=Array.prototype.slice.call(document.querySelectorAll(s));}catch(e){}
  var o={
    ready:function(f){if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',f);else f();return o;},
    html:function(v){return o;},val:function(){return '';},
    on:function(){return o;},each:function(){return o;},
    find:function(){return o;},length:arr.length,
    addClass:function(){return o;},removeClass:function(){return o;}
  };
  return o;
};
jq.ajax=xhrAjax;
jq.fn={};jq.extend=function(a,b){return Object.assign(a||{},b||{});};
jq.parseXML=function(s){return(new DOMParser()).parseFromString(s,'text/xml');};
jq.isFunction=function(f){return typeof f==='function';};
jq.noop=function(){};
window.$=window.jQuery=jq;
window.filmInfo={filmID:$filmId};
})();
</script>
<script>
// avs.watch.js inlined
$safeJs
</script>
</head><body>
<script>
setTimeout(function(){
  try{AnimeVsub('$fileHash',$filmId);}catch(e){}
},800);
</script>
</body></html>"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8(info: EpInfo, avsJs: String): String? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(25_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context }
                              catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    val bridge = M3U8Bridge()

                    // Sync cookie vào WebView
                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        info.cookieStr.split(";").forEach { kv ->
                            val t = kv.trim()
                            if (t.isNotBlank()) setCookie(mainUrl, t)
                        }
                        flush()
                    }

                    val wv = WebView(ctx)
                    wv.settings.apply {
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString                  = UA
                        mixedContentMode                 = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    android.webkit.CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(wv, true)
                    wv.addJavascriptInterface(bridge, "Android")
                    wv.webViewClient = WebViewClient()

                    wv.loadDataWithBaseURL(
                        "$mainUrl/",
                        buildHtml(info.fileHash, info.filmId, avsJs),
                        "text/html", "utf-8", null
                    )

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
                                elapsed >= 24_000 -> {
                                    wv.stopLoading(); wv.destroy()
                                    if (cont.isActive) cont.resume(null)
                                }
                                else -> {
                                    elapsed += 300
                                    handler.postDelayed(this, 300)
                                }
                            }
                        }
                    }
                    handler.postDelayed(checker, 2_000)
                    cont.invokeOnCancellation {
                        handler.removeCallbacks(checker)
                        wv.stopLoading(); wv.destroy()
                    }
                }
            }
        }
    }

    // ── Resolve videoN.html → lh3.googleusercontent.com ─────────────────────
    private suspend fun resolveAndCallback(
        m3u8:     String,
        callback: (ExtractorLink) -> Unit
    ) {
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

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        // 1. Lấy thông tin episode từ API
        val info = fetchEpInfo(epUrl) ?: return true

        // 2. Fetch avs.watch.js qua OkHttp (inline vào HTML, bypass Cloudflare block trên WebView)
        val avsJs = fetchAvsJs(epUrl, info.cookieStr) ?: return true

        // 3. Chạy AnimeVsub trong WebView sạch, bắt blob M3U8
        val m3u8 = getM3U8(info, avsJs) ?: return true

        // 4. Resolve segments và callback
        resolveAndCallback(m3u8, callback)
        return true
    }
}
