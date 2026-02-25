package com.animevietsub

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
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

    // Intercept videoN.html requests → collect redirect lh3 URLs → build M3U8
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun collectSegmentsViaWebView(epUrl: String): Map<Int, String>? {
        return withContext(Dispatchers.Main) {
            // Collect: segmentIndex -> lh3.googleusercontent.com URL
            val segments = ConcurrentHashMap<Int, String>()

            withTimeoutOrNull(35_000L) {
                suspendCancellableCoroutine { cont ->
                    val ctx = try { AcraApplication.context }
                              catch (_: Exception) { null }
                    if (ctx == null) { cont.resume(null); return@suspendCancellableCoroutine }

                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
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

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()

                            // Intercept videoN.html requests
                            // Pattern: .../chunks/.../original/.../videoN.html
                            if (url.contains("storage.googleapiscdn.com") &&
                                url.contains("/video") && url.endsWith(".html")) {
                                // Extract segment index from videoN.html
                                val idx = Regex("""/video(\d+)\.html""")
                                    .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                                // Fetch redirect URL via background thread
                                Thread {
                                    try {
                                        val conn = java.net.URL(url).openConnection()
                                            as java.net.HttpURLConnection
                                        conn.instanceFollowRedirects = false
                                        conn.setRequestProperty("User-Agent", UA)
                                        conn.setRequestProperty("Referer", "$mainUrl/")
                                        conn.connect()
                                        val location = conn.getHeaderField("Location")
                                        conn.disconnect()
                                        if (!location.isNullOrBlank()) {
                                            segments[idx] = location.trim()
                                        }
                                    } catch (_: Exception) {}
                                }.start()

                                // Return empty response - trang sẽ xử lý redirect tự nhiên
                                return null
                            }

                            // Block ads để player hoạt động
                            return when {
                                url.contains("adsbygoogle") ||
                                url.contains("googlesyndication") -> {
                                    WebResourceResponse(
                                        "application/javascript", "utf-8",
                                        java.io.ByteArrayInputStream(
                                            "window.adsbygoogle=window.adsbygoogle||[];".toByteArray()
                                        )
                                    )
                                }
                                url.contains("google-analytics") ||
                                url.contains("doubleclick") -> {
                                    WebResourceResponse(
                                        "application/javascript", "utf-8",
                                        java.io.ByteArrayInputStream("".toByteArray())
                                    )
                                }
                                else -> null
                            }
                        }
                    }

                    wv.loadUrl(epUrl, mapOf(
                        "Accept-Language" to "vi-VN,vi;q=0.9",
                        "Referer"         to "$mainUrl/"
                    ))

                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    var elapsed    = 0
                    var lastCount  = 0
                    var stableTime = 0

                    val checker = object : Runnable {
                        override fun run() {
                            val count = segments.size
                            when {
                                // Đủ segments và stable 3s → done
                                count > 0 && count == lastCount -> {
                                    stableTime += 500
                                    if (stableTime >= 3000) {
                                        wv.stopLoading(); wv.destroy()
                                        if (cont.isActive) cont.resume(segments)
                                        return
                                    }
                                }
                                else -> {
                                    lastCount  = count
                                    stableTime = 0
                                }
                            }
                            elapsed += 500
                            if (elapsed >= 33_000) {
                                wv.stopLoading(); wv.destroy()
                                if (cont.isActive) cont.resume(
                                    if (segments.isNotEmpty()) segments else null
                                )
                                return
                            }
                            handler.postDelayed(this, 500)
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

    override suspend fun loadLinks(
        data:             String,
        isCasting:        Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback:         (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl    = data.substringBefore("|")
        val segments = collectSegmentsViaWebView(epUrl)

        if (segments.isNullOrEmpty()) return true

        // Build M3U8 từ segments đã collect
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:10\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")

        val maxIdx = segments.keys.maxOrNull() ?: 0
        for (i in 0..maxIdx) {
            val url = segments[i] ?: continue
            sb.append("#EXTINF:10.0,\n")
            sb.append("$url\n")
        }
        sb.append("#EXT-X-ENDLIST\n")

        val m3u8     = sb.toString()
        val cacheDir = AcraApplication.context?.cacheDir ?: return true
        cacheDir.mkdirs()
        val file = java.io.File(cacheDir, "avs_${System.currentTimeMillis()}.m3u8")
        file.writeText(m3u8, Charsets.UTF_8)

        callback(newExtractorLink(
            source = name, name = "$name - DU",
            url    = "file://${file.absolutePath}",
            type   = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
            this.headers = mapOf("User-Agent" to UA)
        })

        return true
    }
}
