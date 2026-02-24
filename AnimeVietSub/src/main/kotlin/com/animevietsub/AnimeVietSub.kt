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

    // ── JavaScript bridge để nhận M3U8 từ WebView ──────────────────────────
    inner class M3U8Bridge {
        var m3u8Content: String? = null

        @JavascriptInterface
        fun onM3U8(content: String) {
            m3u8Content = content
        }
    }

    private val interceptJs = """
(function() {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function(blob) {
        var url = origCreate.apply(this, arguments);
        if (blob && blob.type && blob.type.indexOf('mpegurl') !== -1) {
            var reader = new FileReader();
            reader.onload = function(e) {
                if (window.Android) window.Android.onM3U8(e.target.result);
            };
            reader.readAsText(blob);
        }
        return url;
    };
})();
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3U8ViaWebView(epUrl: String): String? {
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { cont ->
                val bridge  = M3U8Bridge()
                val context = try { AcraApplication.context ?: return@suspendCancellableCoroutine }
                              catch (_: Exception) { return@suspendCancellableCoroutine }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val wv = WebView(context)
                    wv.settings.apply {
                        javaScriptEnabled               = true
                        domStorageEnabled               = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString                 = UA
                    }
                    wv.addJavascriptInterface(bridge, "Android")
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(interceptJs, null)
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

    // ── Ghi M3U8 vào cache file ─────────────────────────────────────────────
    private fun writeM3U8ToCache(m3u8Text: String): String? {
        return try {
            val cacheDir = AcraApplication.context?.cacheDir
                ?: java.io.File("/data/local/tmp")
            cacheDir.mkdirs()
            val file = java.io.File(cacheDir, "avs_${System.currentTimeMillis()}.m3u8")
            file.writeText(m3u8Text, Charsets.UTF_8)
            file.absolutePath
        } catch (_: Exception) { null }
    }

    // ── Resolve videoN.html -> lh3.googleusercontent.com và ghi M3U8 mới ──
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

        val lines = m3u8Text.lines()

        // Resolve tất cả segment URLs song song
        val newLines = coroutineScope {
            lines.map { line ->
                async {
                    if (line.startsWith("https://storage.googleapiscdn.com") ||
                        line.startsWith("https://storage.googleapis.com")) {
                        try {
                            val resp = app.get(
                                line.trim(),
                                headers        = segHdr,
                                allowRedirects = false
                            )
                            resp.headers["location"]?.trim() ?: line
                        } catch (_: Exception) { line }
                    } else line
                }
            }.awaitAll()
        }

        val sep = "\n"
        val newM3u8   = newLines.joinToString(sep)
        val cachePath = writeM3U8ToCache(newM3u8) ?: return

        callback(newExtractorLink(
            source = name,
            name   = "$name - DU",
            url    = "file://$cachePath",
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })
    }

    // ── loadLinks ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("|")

        val m3u8Text = getM3U8ViaWebView(epUrl)
        if (!m3u8Text.isNullOrBlank()) {
            parseM3U8AndCallback(m3u8Text, epUrl, callback)
            return true
        }

        // Fallback: POST /ajax/player → HDX embed → abysscdn
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
            val playerResp = app.post(
                "$mainUrl/ajax/player",
                headers = ajaxHdr,
                data    = mapOf("episodeId" to extractEpisodeId(epUrl), "backup" to "1")
            )
            val html = try { playerResp.parsed<PlayerResponse>().html }
                       catch (_: Exception) { null } ?: return@safeApiCall

            Jsoup.parseBodyFragment(html)
                .select("a.btn3dsv[data-play=embed]")
                .firstOrNull()
                ?.let { btn ->
                    val href = btn.attr("data-href").trim()
                    val id   = btn.attr("data-id").trim()
                    if (href.isBlank()) return@let

                    val embedResp = app.post(
                        "$mainUrl/ajax/player",
                        headers = ajaxHdr,
                        data    = mapOf(
                            "link"        to href,
                            "play"        to "embed",
                            "id"          to id.ifBlank { "3" },
                            "backuplinks" to "1"
                        )
                    ).parsed<EmbedResponse>()

                    val shortUrl = embedResp.link ?: return@let
                    val abyssUrl = app.get(
                        shortUrl,
                        headers        = mapOf("User-Agent" to UA, "Referer" to epUrl),
                        allowRedirects = true
                    ).url

                    if (abyssUrl.contains("abysscdn.com") || abyssUrl.contains("abyss.to")) {
                        val hdxM3u8 = getM3U8ViaWebView(abyssUrl)
                        if (!hdxM3u8.isNullOrBlank()) {
                            parseM3U8AndCallback(hdxM3u8, epUrl, callback)
                        }
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
