package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf("User-Agent" to ua, "Accept-Language" to "vi-VN,vi;q=0.9")

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:${u.trim()}"
            u.startsWith("/") -> "$mainUrl${u.trim()}"
            else -> "$mainUrl/$u"
        }
    }

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
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = (selectFirst(".Title,h3,h2,.title,.name")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> =
        app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/", interceptor = cf, headers = pageH).document
            .select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageH).document

        val sel = ".btn-episode,.episode-link,a[data-id][data-hash],ul.list-episode li a"
        var epNodes = doc.select(sel)

        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*='/tap-']")?.attr("href")?.let { fix(it) }?.let { wUrl ->
                doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                epNodes = doc.select(sel)
            }
        }

        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""

        val episodes = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataHash = ep.attr("data-hash").trim().ifBlank { null } ?: return@mapNotNull null
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            newEpisode("$href@@$filmId@@$dataHash") {
                name = nm
                episode = Regex("\\d+").find(nm)?.value?.toIntOrNull()
            }
        }

        val title = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image],.MovieThumb img,figure.Objf img")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts.getOrNull(0) ?: return false
        val filmId = parts.getOrNull(1) ?: return false
        val dataHash = parts.getOrNull(2) ?: return false

        val foundUrls = mutableSetOf<String>()

        // ===== PHƯƠNG ÁN 1: WebView Interception =====
        try {
            val latch = CountDownLatch(1)
            val videoUrlRef = AtomicReference<String?>(null)

            runOnMainThread {
                try {
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = ua

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val url = request?.url.toString()

                                if (url.contains(".m3u8", ignoreCase = true) ||
                                    url.contains("/hls/", ignoreCase = true) ||
                                    url.contains("m3u8.animevietsub", ignoreCase = true) ||
                                    url.contains(".mp4", ignoreCase = true)) {

                                    if (!url.contains("googleads") && !url.contains("ads") && !url.contains("tracking")) {
                                        videoUrlRef.set(url)
                                        latch.countDown()
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                evaluateJavascript("""
                                    (function() {
                                        try {
                                            if (typeof jwplayer !== 'undefined') {
                                                var p = jwplayer('mediaplayer') || jwplayer();
                                                if (p && p.getPlaylist) {
                                                    var pl = p.getPlaylist();
                                                    if (pl && pl.length > 0) {
                                                        for (var i = 0; i < pl.length; i++) {
                                                            if (pl[i].file) return pl[i].file;
                                                            if (pl[i].sources && pl[i].sources.length > 0) {
                                                                return pl[i].sources[0].file;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            var videos = document.querySelectorAll('video');
                                            for (var i = 0; i < videos.length; i++) {
                                                if (videos[i].src && !videos[i].src.startsWith('blob:')) {
                                                    return videos[i].src;
                                                }
                                                var sources = videos[i].querySelectorAll('source');
                                                for (var j = 0; j < sources.length; j++) {
                                                    if (sources[j].src) return sources[j].src;
                                                }
                                            }
                                        } catch(e) {}
                                        return null;
                                    })();
                                """) { result ->
                                    val foundUrl = result?.trim('"')?.takeIf { it.startsWith("http") }
                                    if (foundUrl != null) {
                                        videoUrlRef.set(foundUrl)
                                        latch.countDown()
                                    }
                                }
                            }
                        }

                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head><meta charset="UTF-8"></head>
                            <body>
                            <form id="f" method="POST" action="$mainUrl/ajax/player">
                                <input type="hidden" name="link" value="$dataHash">
                                <input type="hidden" name="id" value="$filmId">
                            </form>
                            <script>document.getElementById('f').submit();</script>
                            </body>
                            </html>
                        """
                        loadDataWithBaseURL(mainUrl, html, "text/html", "UTF-8", null)
                    }

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { latch.countDown() } catch (_: Exception) {}
                    }, 15000)
                } catch (_: Exception) {}
            }

            latch.await(15, TimeUnit.SECONDS)
            videoUrlRef.get()?.let { foundUrls.add(it) }
        } catch (_: Exception) {}

        // ===== PHƯƠNG ÁN 2: Direct POST =====
        if (foundUrls.isEmpty()) {
            try {
                val resp = app.post("$mainUrl/ajax/player",
                    data = mapOf("link" to dataHash, "id" to filmId),
                    headers = ajaxH(epUrl),
                    interceptor = cf
                ).text

                Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:[^\s"'<>]*)?""")
                    .findAll(resp ?: "")
                    .forEach { foundUrls.add(it.value) }

                if (resp?.trimStart()?.startsWith("{") == true) {
                    @Suppress("UNCHECKED_CAST")
                    val json = mapper.readValue(resp, Map::class.java) as Map<String, Any?>
                    (json["link"] as? List<*>)?.let { links ->
                        for (item in links.filterIsInstance<Map<String, Any?>>()) {
                            item["file"]?.toString()?.let { file ->
                                Regex("""https?://[^\s"']+""")
                                    .find(file)
                                    ?.let { foundUrls.add(it.value) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // ===== PHƯƠNG ÁN 3: Scrape episode page =====
        if (foundUrls.isEmpty()) {
            try {
                val pageHtml = app.get(epUrl, interceptor = cf, headers = pageH).text

                Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|hls)[^\s"'<>]*""")
                    .findAll(pageHtml ?: "")
                    .forEach { foundUrls.add(it.value) }

                Jsoup.parse(pageHtml).select("iframe[src]").forEach { iframe ->
                    val src = fix(iframe.attr("src")) ?: return@forEach
                    try {
                        loadExtractor(src, epUrl, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // ===== EMIT URLS =====
        for (url in foundUrls) {
            if (url.contains("googleads", ignoreCase = true)) continue
            if (url.contains("/ads/", ignoreCase = true)) continue

            val isHls = url.contains(".m3u8", ignoreCase = true) || url.contains("/hls/", ignoreCase = true)

            callback(newExtractorLink(name, name, url) {
                referer = epUrl
                quality = Qualities.Unknown.value
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf("User-Agent" to ua)
            })
        }

        return foundUrls.isNotEmpty()
    }

    private suspend fun runOnMainThread(block: () -> Unit) = suspendCoroutine<Unit> { cont ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block() } catch (_: Exception) {}
            cont.resume(Unit)
        }
    }
}
