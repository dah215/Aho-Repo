package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVui()) }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevui.social"
    override var name = "AnimeVui"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }

    private fun detailToWatch(detailUrl: String): String =
        detailUrl.replace("/thong-tin-phim/", "/xem-phim/")

    // ===== MAIN PAGE =====
    override val mainPage = mainPageOf(
        "$mainUrl/"                              to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/"           to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/"            to "Anime Bộ",
        "$mainUrl/the-loai/anime-le/"            to "Anime Lẻ",
        "$mainUrl/the-loai/hanh-dong/"           to "Action",
        "$mainUrl/the-loai/phep-thuat/"          to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/?page=$page"
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document

        val items = doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }

        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fix(a.attr("href")) ?: return null
        if (!href.contains("/thong-tin-phim/")) return null

        val ttl = (
            a.selectFirst("h2,h3,.title,.name,[class*='title']")?.text()
            ?: a.attr("title")
        ).trim().ifBlank { return null }

        val img = a.selectFirst("img")
        val poster = fix(
            img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }
            ?: img?.attr("src")
        )

        return newAnimeSearchResponse(ttl, href, TvType.Anime) { posterUrl = poster }
    }

    // ===== SEARCH =====
    override suspend fun search(q: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(q, "utf-8")
        val doc = app.get(
            "$mainUrl/tim-kiem/$encoded/",
            interceptor = cf,
            headers = pageH
        ).document

        return doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    // ===== LOAD =====
    override suspend fun load(url: String): LoadResponse {
        val detailUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")

        val detailDoc = app.get(detailUrl, interceptor = cf, headers = pageH).document

        val title = (
            detailDoc.selectFirst("h1")?.text()
            ?: "Anime"
        ).trim()

        val poster = detailDoc.selectFirst(
            ".img-film img, figure img, .thumb img, img[itemprop=image], .poster img"
        )?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }

        val plot = detailDoc.selectFirst(
            ".description, .desc, [itemprop=description], .film-content, .content-film"
        )?.text()?.trim()

        // Lấy danh sách tập từ trang xem phim
        val watchUrl = detailToWatch(detailUrl)
        val watchDoc = app.get(watchUrl, interceptor = cf, headers = pageH).document

        val epLinks = watchDoc.select("a[href*='/xem-phim/'][href*='/tap-']")
            .distinctBy { it.attr("href") }

        val episodes = epLinks.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val label = ep.text().trim().ifBlank { ep.attr("title").trim() }.ifBlank { href }
            val epNum = Regex("""tap-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)""").find(label)?.value?.toIntOrNull()
            newEpisode(href) {
                name    = label
                episode = epNum
            }
        }

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl      = poster
            this.plot      = plot
            this.episodes  = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ===== LOAD LINKS =====
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        val foundUrls = mutableSetOf<String>()

        // ===== PHƯƠNG ÁN 1: WebView Interception =====
        try {
            val latch       = CountDownLatch(1)
            val videoUrlRef = AtomicReference<String?>(null)

            runOnMainThread {
                try {
                    val ctx = app.context
                    val webView = WebView(ctx).apply {
                        settings.javaScriptEnabled                = true
                        settings.domStorageEnabled                = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString                  = ua

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url.toString()
                                if ((reqUrl.contains(".m3u8", ignoreCase = true) ||
                                            reqUrl.contains("/hls/", ignoreCase = true) ||
                                            reqUrl.contains(".mp4", ignoreCase = true)) &&
                                    !reqUrl.contains("googleads") &&
                                    !reqUrl.contains("tracking") &&
                                    !reqUrl.contains("/ads/")
                                ) {
                                    videoUrlRef.set(reqUrl)
                                    latch.countDown()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                evaluateJavascript("""
                                    (function() {
                                        try {
                                            if (typeof jwplayer !== 'undefined') {
                                                var p = jwplayer();
                                                if (p && p.getPlaylist) {
                                                    var pl = p.getPlaylist();
                                                    if (pl && pl.length > 0) {
                                                        for (var i = 0; i < pl.length; i++) {
                                                            if (pl[i].file) return pl[i].file;
                                                            if (pl[i].sources && pl[i].sources.length > 0)
                                                                return pl[i].sources[0].file;
                                                        }
                                                    }
                                                }
                                            }
                                            var videos = document.querySelectorAll('video');
                                            for (var i = 0; i < videos.length; i++) {
                                                if (videos[i].src && !videos[i].src.startsWith('blob:'))
                                                    return videos[i].src;
                                                var sources = videos[i].querySelectorAll('source');
                                                for (var j = 0; j < sources.length; j++) {
                                                    if (sources[j].src) return sources[j].src;
                                                }
                                            }
                                        } catch(e) {}
                                        return null;
                                    })();
                                """) { result ->
                                    val found = result?.trim('"')
                                        ?.takeIf { it.startsWith("http") }
                                    if (found != null) {
                                        videoUrlRef.set(found)
                                        latch.countDown()
                                    }
                                }
                            }
                        }

                        loadUrl(epUrl, pageH)
                    }

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { latch.countDown() } catch (_: Exception) {}
                    }, 15000)
                } catch (_: Exception) {}
            }

            latch.await(15, TimeUnit.SECONDS)
            videoUrlRef.get()?.let { foundUrls.add(it) }
        } catch (_: Exception) {}

        // ===== PHƯƠNG ÁN 2: Scrape HTML + iframe =====
        if (foundUrls.isEmpty()) {
            try {
                val doc     = app.get(epUrl, interceptor = cf, headers = pageH).document
                val rawHtml = doc.html()

                // Tìm URL m3u8/mp4 trực tiếp trong HTML
                Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|/hls/)[^\s"'<>]*""")
                    .findAll(rawHtml)
                    .forEach { foundUrls.add(it.value) }

                // Xử lý iframe
                doc.select("iframe[src]").forEach { iframe ->
                    val src = fix(iframe.attr("src")) ?: return@forEach
                    if (!src.contains("googleads")) {
                        try { loadExtractor(src, epUrl, subtitleCallback, callback) }
                        catch (_: Exception) {}
                    }
                }

                // Xử lý data-src / data-video / data-url
                doc.select("[data-src],[data-video],[data-url]").forEach { el ->
                    val raw = el.attr("data-src").ifBlank {
                        el.attr("data-video").ifBlank { el.attr("data-url") }
                    }
                    fix(raw)?.let { fixedUrl ->
                        if (fixedUrl.contains(".m3u8") || fixedUrl.contains(".mp4")) {
                            foundUrls.add(fixedUrl)
                        } else {
                            try { loadExtractor(fixedUrl, epUrl, subtitleCallback, callback) }
                            catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // ===== EMIT =====
        for (videoUrl in foundUrls) {
            if (videoUrl.contains("googleads", ignoreCase = true)) continue
            if (videoUrl.contains("/ads/", ignoreCase = true)) continue

            val isHls = videoUrl.contains(".m3u8", ignoreCase = true) ||
                        videoUrl.contains("/hls/", ignoreCase = true)

            callback(newExtractorLink(name, name, videoUrl) {
                referer = epUrl
                quality = Qualities.Unknown.value
                type    = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf("User-Agent" to ua, "Referer" to epUrl)
            })
        }

        return foundUrls.isNotEmpty()
    }

    private suspend fun runOnMainThread(block: () -> Unit) =
        suspendCoroutine<Unit> { cont ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { block() } catch (_: Exception) {}
                cont.resume(Unit)
            }
        }
}
