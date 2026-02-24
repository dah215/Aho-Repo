package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    // Script inject vào <head> để bắt blob TRƯỚC khi page scripts chạy
    private val headScript = """<script>
(function(){
var _orig=URL.createObjectURL;
URL.createObjectURL=function(b){
var u=_orig.apply(this,arguments);
try{if(b&&b.type&&b.type.indexOf('mpegurl')!==-1){
var r=new FileReader();
r.onload=function(e){try{if(window.Android)window.Android.onM3U8(e.target.result);}catch(x){}};
r.readAsText(b);}}catch(x){}
return u;};})();
</script>"""

    inner class M3U8Bridge {
        @Volatile var m3u8Content: String? = null

        @JavascriptInterface
        fun onM3U8(content: String) {
            if (content.contains("#EXTM3U")) m3u8Content = content
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8ViaWebView(epUrl: String): String? {
        return withTimeoutOrNull(35_000L) {
            suspendCancellableCoroutine { cont ->
                val bridge  = M3U8Bridge()
                val context = try { AcraApplication.context }
                              catch (_: Exception) { null }
                              ?: return@suspendCancellableCoroutine

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val wv = WebView(context)
                    wv.settings.apply {
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString                  = UA
                        mixedContentMode                 = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    wv.addJavascriptInterface(bridge, "Android")

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            // Intercept HTML của episode page, inject script vào <head>
                            if (url == epUrl || (url.contains(mainUrl) && url.endsWith(".html"))) {
                                try {
                                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                    conn.setRequestProperty("User-Agent", UA)
                                    conn.setRequestProperty("Referer", "$mainUrl/")
                                    conn.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9")
                                    conn.connect()
                                    val html = conn.inputStream.bufferedReader().readText()
                                    conn.disconnect()
                                    // Inject script vào đầu <head>
                                    val modified = html.replace("<head>", "<head>$headScript", ignoreCase = true)
                                    return WebResourceResponse(
                                        "text/html", "utf-8",
                                        ByteArrayInputStream(modified.toByteArray(Charsets.UTF_8))
                                    )
                                } catch (_: Exception) {}
                            }
                            return null
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
                            val m3u8 = bridge.m3u8Content
                            when {
                                m3u8 != null && m3u8.contains("#EXTM3U") -> {
                                    wv.destroy()
                                    if (cont.isActive) cont.resume(m3u8)
                                }
                                elapsed >= 33_000 -> {
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
                    handler.postDelayed(checker, 3_000)
                    cont.invokeOnCancellation { wv.destroy() }
                }
            }
        }
    }

    // Resolve videoN.html -> lh3.googleusercontent.com
    private suspend fun parseM3U8AndCallback(
        m3u8Text: String,
        epUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val segHdr = mapOf(
            "Referer"    to "$mainUrl/",
            "User-Agent" to UA,
            "Origin"     to mainUrl
        )

        val lines    = m3u8Text.lines()
        val newLines = coroutineScope {
            lines.map { line ->
                async {
                    if (line.startsWith("https://storage.googleapiscdn.com") ||
                        line.startsWith("https://storage.googleapis.com")) {
                        try {
                            val resp = app.get(
                                line.trim(), headers = segHdr, allowRedirects = false
                            )
                            resp.headers["location"]?.trim() ?: line
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl    = data.substringBefore("|")
        val m3u8Text = getM3U8ViaWebView(epUrl)

        if (!m3u8Text.isNullOrBlank()) {
            parseM3U8AndCallback(m3u8Text, epUrl, callback)
            return true
        }

        // Fallback HDX embed
        val ajaxHdr = mapOf(
            "User-Agent"       to UA,
            "Accept-Language"  to "vi-VN,vi;q=0.9",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin"           to mainUrl,
            "Referer"          to epUrl,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )
        safeApiCall {
            val html = try {
                app.post(
                    "$mainUrl/ajax/player",
                    headers = ajaxHdr,
                    data    = mapOf("episodeId" to extractEpisodeId(epUrl), "backup" to "1")
                ).parsed<PlayerResponse>().html
            } catch (_: Exception) { null } ?: return@safeApiCall

            Jsoup.parseBodyFragment(html)
                .select("a.btn3dsv[data-play=embed]").firstOrNull()?.let { btn ->
                    val href = btn.attr("data-href").trim().takeIf { it.isNotBlank() } ?: return@let
                    val id   = btn.attr("data-id").trim()
                    val resp = app.post(
                        "$mainUrl/ajax/player", headers = ajaxHdr,
                        data = mapOf("link" to href, "play" to "embed",
                                     "id" to id.ifBlank { "3" }, "backuplinks" to "1")
                    ).parsed<EmbedResponse>()
                    val abyssUrl = app.get(
                        resp.link ?: return@let,
                        headers = mapOf("User-Agent" to UA, "Referer" to epUrl),
                        allowRedirects = true
                    ).url
                    if (abyssUrl.contains("abysscdn.com") || abyssUrl.contains("abyss.to")) {
                        getM3U8ViaWebView(abyssUrl)?.let { parseM3U8AndCallback(it, epUrl, callback) }
                    }
                }
        }
        return true
    }

    private fun extractEpisodeId(epUrl: String): String =
        Regex("""-(\d+)\.html$""").find(epUrl)?.groupValues?.get(1) ?: ""

    data class PlayerResponse(
        @JsonProperty("success") val success: Int  = 0,
        @JsonProperty("html")    val html: String? = null
    )
    data class EmbedResponse(
        @JsonProperty("success")   val success: Int      = 0,
        @JsonProperty("link")      val link: String?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("_fxStatus") val fxStatus: Int     = 0
    )
}
