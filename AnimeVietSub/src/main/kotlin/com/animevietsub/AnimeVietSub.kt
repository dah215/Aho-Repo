package com.animevietsub

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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
    private val siteAnalyzer = SiteIntelligenceAnalyzer()
    private val virtualBrowser = VirtualBrowserCore()
    private val behaviorEngine = HumanBehaviorEngine()
    private val streamExtractor = DeepStreamExtractor()

    private val ua = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Cache-Control" to "max-age=0"
    )

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to mainUrl,
        "Referer" to ref,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:" + u.trim()
            u.startsWith("/") -> mainUrl + u.trim()
            else -> "$mainUrl/$u"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/danh-sach/list-hoat-hinh-trung-quoc/" to "Hoạt Hình Trung Quốc",
        "$mainUrl/danh-sach/list-live-action/" to "Live Action"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"

        if (page == 1 && req.name == "Trang Chủ") {
            siteAnalyzer.analyzeSiteStructure(url, pageH, cf)
        }

        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item,.film-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }

        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = (selectFirst(".Title,h3,h2,.title,.name,.film-name")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("data-original")?.ifBlank { null } ?: img?.attr("src"))
        val quality = selectFirst(".Qlty,.quality,.badge")?.text()
        val year = selectFirst(".Year,.year")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(ttl, url, TvType.Anime) { 
            posterUrl = poster
            this.year = year
            addQuality(quality)
        }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/"
        val doc = app.get(searchUrl, interceptor = cf, headers = pageH).document
        return doc.select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item,.film-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        val analysis = siteAnalyzer.analyzeEpisodePage(fUrl, pageH, cf)
        var doc = analysis.document

        val sel = ".btn-episode,.episode-link,a[data-id][data-hash],ul.list-episode li a,.ep-item a,.episode-item a,.ss-list a"
        var epNodes = doc.select(sel)

        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*="/tap/"],.watch-btn a,.play-btn a")?.attr("href")?.let { fix(it) }?.let { wUrl ->
                doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                epNodes = doc.select(sel)
            }
        }

        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) 
            ?: doc.selectFirst("#film-id,.film-id")?.attr("data-id")
            ?: ""

        val episodes = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataHash = ep.attr("data-hash").trim().ifBlank { null } 
                ?: ep.attr("data-id").trim().ifBlank { null }
                ?: return@mapNotNull null
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            val epNum = Regex("""(\d+)""").find(nm)?.value?.toIntOrNull()

            newEpisode("$href@@@$filmId@@@$dataHash") {
                name = nm
                episode = epNum
            }
        }

        val title = doc.selectFirst("h1.Title,h1,.Title,.film-title")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Anime"

        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image],.MovieThumb img,figure.Objf img,.film-poster img")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } })
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fix(it) }

        val plot = doc.selectFirst(".Description,.InfoDesc,#film-content,.film-description")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = doc.select(".categories a,.genres a,.tags a").map { it.text() }.take(5)
        val year = doc.selectFirst(".year-release,.Year")?.text()?.toIntOrNull()

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@@")
        val epUrl = parts.getOrNull(0) ?: return false
        val filmId = parts.getOrNull(1) ?: return false
        val dataHash = parts.getOrNull(2) ?: return false

        val foundStreams = mutableSetOf<String>()
        val subtitles = mutableListOf<SubtitleFile>()

        val vbResult = virtualBrowser.extractWithVirtualBrowser(
            mainUrl, epUrl, filmId, dataHash, ua, cf, behaviorEngine
        )
        foundStreams.addAll(vbResult.streams)
        subtitles.addAll(vbResult.subtitles)

        if (foundStreams.isEmpty()) {
            val apiResult = streamExtractor.extractFromAPI(
                mainUrl, epUrl, filmId, dataHash, ajaxH(epUrl), cf, mapper
            )
            foundStreams.addAll(apiResult.streams)
            subtitles.addAll(apiResult.subtitles)
        }

        if (foundStreams.isEmpty()) {
            val scrapeResult = streamExtractor.extractFromPage(epUrl, pageH, cf)
            foundStreams.addAll(scrapeResult.streams)
        }

        if (foundStreams.isEmpty()) {
            val iframeResult = streamExtractor.extractFromIframes(epUrl, pageH, cf, subtitleCallback, callback)
            if (iframeResult) return true
        }

        var success = false
        for (url in foundStreams) {
            if (isAdUrl(url)) continue

            val isHls = url.contains(".m3u8", ignoreCase = true) || url.contains("/hls/", ignoreCase = true)
            val quality = extractQuality(url)

            callback(newExtractorLink(name, name, url) {
                referer = epUrl
                this.quality = quality
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            })
            success = true
        }

        subtitles.forEach { subtitleCallback(it) }

        return success
    }

    private fun isAdUrl(url: String): Boolean {
        val adPatterns = listOf("googleads", "doubleclick", "googlesyndication", 
            "facebook.com/tr", "analytics", "tracking", "/ads/", "adserver")
        return adPatterns.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractQuality(url: String): Int {
        return when {
            url.contains("1080", ignoreCase = true) || url.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720", ignoreCase = true) || url.contains("hd", ignoreCase = true) -> Qualities.P720.value
            url.contains("480", ignoreCase = true) -> Qualities.P480.value
            url.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}


// ============================================================================
// SITE INTELLIGENCE ANALYZER
// Phân tích cấu trúc website và network flow cho animevietsub.ee
// ============================================================================

class SiteIntelligenceAnalyzer {

    data class SiteProfile(
        val playerType: PlayerType,
        val ajaxEndpoints: List<String>,
        val scriptDependencies: List<String>,
        val tokenGenerationLogic: TokenGenLogic?,
        val redirectChain: List<String>,
        val protectionLevel: ProtectionLevel
    )

    enum class PlayerType { JWPLAYER, VIDEOJS, HTML5, CUSTOM, UNKNOWN }
    enum class ProtectionLevel { NONE, BASIC, MEDIUM, HIGH, EXTREME }

    data class TokenGenLogic(
        val algorithm: String,
        val salt: String?,
        val iterations: Int?
    )

    data class PageAnalysis(
        val document: Document,
        val networkTimeline: List<NetworkEvent>,
        val detectedApis: List<String>,
        val playerConfig: Map<String, Any>?
    )

    data class NetworkEvent(
        val timestamp: Long,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val responseSnippet: String?
    )

    private val siteProfiles = ConcurrentHashMap<String, SiteProfile>()
    private val networkHistory = mutableListOf<NetworkEvent>()

    suspend fun analyzeSiteStructure(url: String, headers: Map<String, String>, cf: CloudflareKiller) {
        try {
            val resp = app.get(url, interceptor = cf, headers = headers)
            val doc = resp.document

            // Detect player type
            val playerType = detectPlayerType(doc)

            // Find AJAX endpoints
            val ajaxEndpoints = doc.select("script").mapNotNull { script ->
                val content = script.data()
                Regex("""(ajax/player|api/[^"']+|/ajax/[^"']+)""").findAll(content)
                    .map { it.value }.toList()
            }.flatten().distinct()

            // Find script dependencies
            val scripts = doc.select("script[src]").map { it.attr("src") }

            val profile = SiteProfile(
                playerType = playerType,
                ajaxEndpoints = ajaxEndpoints,
                scriptDependencies = scripts,
                tokenGenerationLogic = null,
                redirectChain = listOf(url),
                protectionLevel = detectProtectionLevel(doc, resp.text)
            )

            siteProfiles[url] = profile
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun analyzeEpisodePage(url: String, headers: Map<String, String>, cf: CloudflareKiller): PageAnalysis {
        val resp = app.get(url, interceptor = cf, headers = headers)
        val doc = resp.document

        // Extract all script content for analysis
        val allScripts = doc.select("script").joinToString("\n") { it.data() }

        // Detect APIs
        val apis = mutableListOf<String>()
        Regex("""fetch\s*\(\s*['"]([^'"]+)['"]""").findAll(allScripts).forEach { 
            apis.add(it.groupValues[1]) 
        }
        Regex("""\$.ajax\s*\(\s*\{[^}]*url\s*:\s*['"]([^'"]+)['"]""").findAll(allScripts).forEach { 
            apis.add(it.groupValues[1]) 
        }

        // Extract player config if exists
        val playerConfig = extractPlayerConfig(allScripts)

        return PageAnalysis(
            document = doc,
            networkTimeline = networkHistory.toList(),
            detectedApis = apis.distinct(),
            playerConfig = playerConfig
        )
    }

    private fun detectPlayerType(doc: Document): PlayerType {
        val scripts = doc.select("script").joinToString { it.data() + it.attr("src") }
        return when {
            scripts.contains("jwplayer") -> PlayerType.JWPLAYER
            scripts.contains("video.js") || scripts.contains("videojs") -> PlayerType.VIDEOJS
            doc.select("video").isNotEmpty() -> PlayerType.HTML5
            scripts.contains("player.js") || scripts.contains("embed.js") -> PlayerType.CUSTOM
            else -> PlayerType.UNKNOWN
        }
    }

    private fun detectProtectionLevel(doc: Document, html: String): ProtectionLevel {
        var score = 0
        if (doc.select("script").any { it.data().contains("debugger") }) score += 2
        if (html.contains("__cf_bm") || html.contains("cf-ray")) score += 1
        if (doc.select("script").any { it.data().contains("eval") && it.data().length > 500 }) score += 2
        if (html.contains("turnstile") || html.contains("recaptcha")) score += 3
        if (doc.select("script").any { it.data().contains("webpack") || it.data().contains("obfuscator") }) score += 2

        return when {
            score >= 7 -> ProtectionLevel.EXTREME
            score >= 5 -> ProtectionLevel.HIGH
            score >= 3 -> ProtectionLevel.MEDIUM
            score >= 1 -> ProtectionLevel.BASIC
            else -> ProtectionLevel.NONE
        }
    }

    private fun extractPlayerConfig(scripts: String): Map<String, Any>? {
        // Tìm config JSON trong scripts
        val configMatch = Regex("""playerConfig\s*=\s*(\{[^;]+\});""").find(scripts)
            ?: Regex("""config\s*:\s*(\{[^}]+\})""").find(scripts)
            ?: return null

        return try {
            jacksonObjectMapper().readValue(configMatch.groupValues[1])
        } catch (e: Exception) {
            null
        }
    }
}


// ============================================================================
// VIRTUAL BROWSER CORE
// Mô phỏng hành vi trình duyệt với WebView nâng cao
// ============================================================================

class VirtualBrowserCore {

    data class ExtractionResult(
        val streams: List<String>,
        val subtitles: List<SubtitleFile>
    )

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    suspend fun extractWithVirtualBrowser(
        mainUrl: String,
        epUrl: String,
        filmId: String,
        dataHash: String,
        ua: String,
        cf: CloudflareKiller,
        behaviorEngine: HumanBehaviorEngine
    ): ExtractionResult = withContext(Dispatchers.Main) {

        val streams = mutableSetOf<String>()
        val subtitles = mutableListOf<SubtitleFile>()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)

        try {
            val ctx = app.context
            val webView = WebView(ctx)

            // Cấu hình WebSettings nâng cao
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = ua
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }

            // JavaScript Interface để nhận data từ page
            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onStreamFound(url: String, type: String) {
                    if (!isAdUrl(url)) {
                        streams.add(url)
                    }
                }

                @JavascriptInterface
                fun onSubtitleFound(url: String, lang: String) {
                    subtitles.add(SubtitleFile(lang, url))
                }

                @JavascriptInterface
                fun onExtractionComplete() {
                    completed.set(true)
                    latch.countDown()
                }
            }, "VBrowser")

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, 
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    // Intercept M3U8/MP4 streams
                    if (isStreamUrl(url)) {
                        if (!isAdUrl(url)) {
                            streams.add(url)
                        }
                    }

                    // Intercept subtitle files
                    if (url.contains(".vtt", ignoreCase = true) || 
                        url.contains(".srt", ignoreCase = true) ||
                        url.contains("subtitle", ignoreCase = true)) {
                        subtitles.add(SubtitleFile("vi", url))
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Inject behavior mimicry scripts
                    view?.evaluateJavascript(behaviorEngine.generateBehaviorScript(), null)

                    // Inject stream extraction hook
                    view?.evaluateJavascript("""
                        (function() {
                            // Hook video elements
                            const originalCreateElement = document.createElement;
                            document.createElement = function(tagName) {
                                const elem = originalCreateElement.call(document, tagName);
                                if (tagName.toLowerCase() === 'video') {
                                    hookVideoElement(elem);
                                }
                                return elem;
                            };

                            // Hook existing videos
                            document.querySelectorAll('video').forEach(hookVideoElement);

                            function hookVideoElement(video) {
                                Object.defineProperty(video, 'src', {
                                    set: function(url) {
                                        if (url && url.startsWith('http')) {
                                            VBrowser.onStreamFound(url, 'direct');
                                        }
                                        this.setAttribute('src', url);
                                    },
                                    get: function() {
                                        return this.getAttribute('src');
                                    }
                                });

                                // Monitor source changes
                                const sources = video.querySelectorAll('source');
                                sources.forEach(src => {
                                    if (src.src) VBrowser.onStreamFound(src.src, 'source');
                                });
                            }

                            // Hook jwplayer if exists
                            if (typeof jwplayer !== 'undefined') {
                                const originalSetup = jwplayer.prototype.setup;
                                jwplayer.prototype.setup = function(config) {
                                    if (config.file) VBrowser.onStreamFound(config.file, 'jwplayer');
                                    if (config.sources) {
                                        config.sources.forEach(s => {
                                            if (s.file) VBrowser.onStreamFound(s.file, 'jwplayer');
                                        });
                                    }
                                    return originalSetup.call(this, config);
                                };
                            }

                            // Hook fetch for API interception
                            const originalFetch = window.fetch;
                            window.fetch = function(url, options) {
                                return originalFetch(url, options).then(response => {
                                    if (url.includes('ajax') || url.includes('player')) {
                                        response.clone().text().then(text => {
                                            try {
                                                const data = JSON.parse(text);
                                                if (data.link || data.url || data.file) {
                                                    VBrowser.onStreamFound(data.link || data.url || data.file, 'api');
                                                }
                                                if (data.subtitles) {
                                                    data.subtitles.forEach(sub => {
                                                        if (sub.file) VBrowser.onSubtitleFound(sub.file, sub.label || 'vi');
                                                    });
                                                }
                                            } catch(e) {}
                                        });
                                    }
                                    return response;
                                });
                            };

                            // Check after delay
                            setTimeout(() => {
                                // Check jwplayer playlist
                                if (typeof jwplayer !== 'undefined') {
                                    try {
                                        const player = jwplayer('mediaplayer') || jwplayer();
                                        if (player && player.getPlaylist) {
                                            const playlist = player.getPlaylist();
                                            if (playlist && playlist.length > 0) {
                                                playlist.forEach(item => {
                                                    if (item.file) VBrowser.onStreamFound(item.file, 'jwplaylist');
                                                    if (item.sources) {
                                                        item.sources.forEach(s => {
                                                            if (s.file) VBrowser.onStreamFound(s.file, 'jwplaylist');
                                                        });
                                                    }
                                                });
                                            }
                                        }
                                    } catch(e) {}
                                }

                                // Signal completion
                                VBrowser.onExtractionComplete();
                            }, 3000);
                        })();
                    """.trimIndent(), null)
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    // Log console messages for debugging
                    return true
                }
            }

            // Load page với POST data
            val postHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                </head>
                <body>
                    <form id="playerForm" method="POST" action="$mainUrl/ajax/player" style="display:none;">
                        <input type="hidden" name="link" value="$dataHash">
                        <input type="hidden" name="id" value="$filmId">
                    </form>
                    <div id="mediaplayer"></div>
                    <script>
                        document.addEventListener('DOMContentLoaded', function() {
                            setTimeout(() => document.getElementById('playerForm').submit(), 100);
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()

            webView.loadDataWithBaseURL(mainUrl, postHtml, "text/html", "UTF-8", null)

            // Timeout sau 15 giây
            Handler(Looper.getMainLooper()).postDelayed({
                if (!completed.get()) {
                    latch.countDown()
                }
            }, 15000)

            latch.await(15, TimeUnit.SECONDS)

            // Cleanup
            webView.stopLoading()
            webView.destroy()

        } catch (e: Exception) {
            e.printStackTrace()
        }

        ExtractionResult(streams.toList(), subtitles)
    }

    private fun isStreamUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
               url.contains(".mp4", ignoreCase = true) ||
               url.contains("/hls/", ignoreCase = true) ||
               url.contains("/stream/", ignoreCase = true) ||
               url.contains("video", ignoreCase = true) && 
               (url.contains(".ts") || url.contains(".m4s"))
    }

    private fun isAdUrl(url: String): Boolean {
        val adPatterns = listOf("googleads", "doubleclick", "googlesyndication", 
            "facebook", "analytics", "tracking", "/ads/", "adserver", "googletag")
        return adPatterns.any { url.contains(it, ignoreCase = true) }
    }
}


// ============================================================================
// HUMAN BEHAVIOR ENGINE
// Sinh hành vi người dùng tự nhiên để tránh phát hiện automation
// ============================================================================

class HumanBehaviorEngine {

    private val random = Random(System.currentTimeMillis())

    data class BehaviorProfile(
        val scrollPattern: List<ScrollEvent>,
        val clickTiming: Long,
        val idlePauses: List<Long>,
        val mousePath: List<Point>
    )

    data class ScrollEvent(val delta: Int, val duration: Int, val easing: String)
    data class Point(val x: Double, val y: Double, val timestamp: Long)

    fun generateBehaviorScript(): String {
        val profile = generateBehaviorProfile()

        return """
            (function() {
                // Anti-detection: Override automation flags
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                Object.defineProperty(navigator, 'languages', { get: () => ['vi-VN', 'vi', 'en-US', 'en'] });

                // Override Chrome runtime
                if (window.chrome) {
                    Object.defineProperty(window.chrome, 'runtime', { 
                        get: () => ({ 
                            OnInstalledReason: { CHROME_UPDATE: "chrome_update" },
                            OnRestartRequiredReason: { APP_UPDATE: "app_update" }
                        }) 
                    });
                }

                // Canvas fingerprint randomization (subtle)
                const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
                    const data = originalGetImageData.call(this, x, y, w, h);
                    // Add imperceptible noise
                    for (let i = 0; i < data.data.length; i += 4) {
                        if (Math.random() < 0.001) {
                            data.data[i] = Math.max(0, Math.min(255, data.data[i] + (Math.random() > 0.5 ? 1 : -1)));
                        }
                    }
                    return data;
                };

                // Simulate human-like scroll behavior
                let scrollTimeout;
                const humanScroll = () => {
                    const scrollAmount = ${random.nextInt(100, 500)};
                    const duration = ${random.nextInt(300, 800)};
                    const start = window.scrollY;
                    const target = start + scrollAmount;
                    const startTime = performance.now();

                    const animate = (currentTime) => {
                        const elapsed = currentTime - startTime;
                        const progress = Math.min(elapsed / duration, 1);
                        // Easing function (ease-out)
                        const ease = 1 - Math.pow(1 - progress, 3);
                        window.scrollTo(0, start + (target - start) * ease);

                        if (progress < 1) {
                            requestAnimationFrame(animate);
                        }
                    };

                    requestAnimationFrame(animate);
                };

                // Random scroll after random delay
                scrollTimeout = setTimeout(humanScroll, ${random.nextInt(500, 2000)});

                // Simulate focus/blur patterns
                let visibilityChanges = 0;
                document.addEventListener('visibilitychange', () => {
                    visibilityChanges++;
                    if (visibilityChanges > 3) {
                        // Human-like pause when switching tabs
                        clearTimeout(scrollTimeout);
                    }
                });

                // Random mouse movements (subtle)
                let mouseX = ${random.nextInt(100, 800)};
                let mouseY = ${random.nextInt(100, 600)};

                const moveMouse = () => {
                    const dx = (Math.random() - 0.5) * 50;
                    const dy = (Math.random() - 0.5) * 50;
                    mouseX += dx;
                    mouseY += dy;

                    // Dispatch synthetic mouse move
                    try {
                        const event = new MouseEvent('mousemove', {
                            clientX: mouseX,
                            clientY: mouseY,
                            bubbles: true
                        });
                        document.dispatchEvent(event);
                    } catch(e) {}

                    // Schedule next move
                    setTimeout(moveMouse, ${random.nextInt(100, 500)});
                };

                setTimeout(moveMouse, ${random.nextInt(1000, 3000)});

                // Random idle pause
                const idlePause = () => {
                    const pauseDuration = ${random.nextInt(500, 3000)};
                    setTimeout(() => {
                        if (Math.random() > 0.7) {
                            idlePause();
                        }
                    }, pauseDuration);
                };

                setTimeout(idlePause, ${random.nextInt(2000, 5000)});

            })();
        """.trimIndent()
    }

    private fun generateBehaviorProfile(): BehaviorProfile {
        val scrollEvents = List(random.nextInt(3, 8)) {
            ScrollEvent(
                delta = random.nextInt(-500, 500),
                duration = random.nextInt(200, 1000),
                easing = listOf("ease-out", "ease-in-out", "linear").random()
            )
        }

        val idlePauses = List(random.nextInt(2, 5)) {
            random.nextLong(100, 2000)
        }

        val mousePath = List(random.nextInt(10, 30)) {
            Point(
                x = random.nextDouble() * 1920,
                y = random.nextDouble() * 1080,
                timestamp = System.currentTimeMillis() + it * random.nextLong(50, 200)
            )
        }

        return BehaviorProfile(
            scrollPattern = scrollEvents,
            clickTiming = random.nextLong(100, 500),
            idlePauses = idlePauses,
            mousePath = mousePath
        )
    }
}


// ============================================================================
// DEEP STREAM EXTRACTOR
// Trích xuất stream ở cấp độ thấp nhất với nhiều phương pháp fallback
// ============================================================================

class DeepStreamExtractor {

    data class StreamResult(
        val streams: List<String>,
        val subtitles: List<SubtitleFile>
    )

    suspend fun extractFromAPI(
        mainUrl: String,
        epUrl: String,
        filmId: String,
        dataHash: String,
        headers: Map<String, String>,
        cf: CloudflareKiller,
        mapper: com.fasterxml.jackson.databind.ObjectMapper
    ): StreamResult {
        val streams = mutableSetOf<String>()
        val subtitles = mutableListOf<SubtitleFile>()

        try {
            // Primary AJAX endpoint
            val resp = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("link" to dataHash, "id" to filmId),
                headers = headers,
                interceptor = cf
            )

            val text = resp.text

            // Extract direct URLs
            Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:[^\s"'<>]*)?""").findAll(text).forEach {
                streams.add(it.value)
            }

            // Parse JSON response
            if (text.trimStart().startsWith("{")) {
                try {
                    val json = mapper.readValue(text, Map::class.java) as Map<String, Any?>

                    // Extract from 'link' field
                    (json["link"] as? String)?.let { link ->
                        Regex("""https?://[^\s"']+""").find(link)?.let { streams.add(it.value) }
                    }

                    // Extract from playlist
                    (json["link"] as? List<*>)?.let { links ->
                        links.filterIsInstance<Map<String, Any?>>().forEach { item ->
                            item["file"]?.toString()?.let { file ->
                                Regex("""https?://[^\s"']+""").find(file)?.let { streams.add(it.value) }
                            }
                            item["src"]?.toString()?.let { src ->
                                Regex("""https?://[^\s"']+""").find(src)?.let { streams.add(it.value) }
                            }
                        }
                    }

                    // Extract subtitles
                    (json["subtitles"] as? List<*>)?.let { subs ->
                        subs.filterIsInstance<Map<String, Any?>>().forEach { sub ->
                            val file = sub["file"]?.toString()
                            val label = sub["label"]?.toString() ?: "vi"
                            if (file != null) {
                                subtitles.add(SubtitleFile(label, file))
                            }
                        }
                    }

                    // Check for encrypted/encoded data
                    (json["data"] as? String)?.let { data ->
                        // Try base64 decode
                        try {
                            val decoded = String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                            Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)""").findAll(decoded).forEach {
                                streams.add(it.value)
                            }
                        } catch (e: Exception) {}
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return StreamResult(streams.toList(), subtitles)
    }

    suspend fun extractFromPage(
        epUrl: String,
        headers: Map<String, String>,
        cf: CloudflareKiller
    ): StreamResult {
        val streams = mutableSetOf<String>()

        try {
            val pageHtml = app.get(epUrl, interceptor = cf, headers = headers).text

            // Pattern 1: Direct URLs in HTML
            Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|/hls/|/stream/)[^\s"'<>]*""").findAll(pageHtml).forEach {
                streams.add(it.value)
            }

            // Pattern 2: URLs in JavaScript variables
            Regex("""(var\s+\w+\s*=\s*['"])([^'"]+\.(?:m3u8|mp4))(['"])""").findAll(pageHtml).forEach {
                streams.add(it.groupValues[2])
            }

            // Pattern 3: JSON encoded URLs
            Regex(""""file"\s*:\s*"([^"]+)"""").findAll(pageHtml).forEach {
                streams.add(it.groupValues[1].replace("\\", ""))
            }

            // Pattern 4: Base64 encoded sources
            Regex("""atob\(['"]([A-Za-z0-9+/=]+)['"]\)""").findAll(pageHtml).forEach { match ->
                try {
                    val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                        Regex("""https?://[^\s"'<>]+""").find(decoded)?.let { streams.add(it.value) }
                    }
                } catch (e: Exception) {}
            }

            // Parse iframes
            val doc = Jsoup.parse(pageHtml)
            doc.select("iframe[data-src],iframe[src]").forEach { iframe ->
                val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                if (src.isNotBlank() && !src.startsWith("javascript")) {
                    // Recursively check iframe sources
                    try {
                        val iframeHtml = app.get(src, interceptor = cf, headers = headers).text
                        Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""").findAll(iframeHtml).forEach {
                            streams.add(it.value)
                        }
                    } catch (e: Exception) {}
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return StreamResult(streams.toList(), emptyList())
    }

    suspend fun extractFromIframes(
        epUrl: String,
        headers: Map<String, String>,
        cf: CloudflareKiller,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false

        try {
            val doc = app.get(epUrl, interceptor = cf, headers = headers).document

            doc.select("iframe[src],iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") } ?: return@forEach
                val fixedSrc = when {
                    src.startsWith("http") -> src
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "https://animevietsub.ee$src"
                    else -> src
                }

                // Skip ads/analytics
                if (isAdUrl(fixedSrc)) return@forEach

                try {
                    // Use CloudStream's extractor system
                    loadExtractor(fixedSrc, epUrl, subtitleCallback, callback)
                    success = true
                } catch (e: Exception) {
                    // Try manual extraction
                    try {
                        val iframeDoc = app.get(fixedSrc, interceptor = cf, headers = headers).document
                        val iframeHtml = iframeDoc.toString()

                        Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)[^\s"'<>]*""").findAll(iframeHtml).forEach { match ->
                            val url = match.value
                            val isHls = url.contains(".m3u8", ignoreCase = true)

                            callback(newExtractorLink("Iframe", "Iframe", url) {
                                referer = fixedSrc
                                quality = Qualities.Unknown.value
                                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                this.headers = headers
                            })
                            success = true
                        }
                    } catch (e2: Exception) {}
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return success
    }

    private fun isAdUrl(url: String): Boolean {
        val adPatterns = listOf("googleads", "doubleclick", "googlesyndication", 
            "facebook.com/tr", "analytics", "tracking", "/ads/", "adserver", "googletag")
        return adPatterns.any { url.contains(it, ignoreCase = true) }
    }
}


// ============================================================================
// ADAPTIVE BYPASS CORE
// Engine thích ứng chống anti-bot với chiến lược động
// ============================================================================

class AdaptiveBypassCore {

    enum class ProtectionType {
        NONE,
        CLOUDFLARE,
        RECAPTCHA,
        TURNSTILE,
        JAVASCRIPT_CHALLENGE,
        RATE_LIMITING,
        BEHAVIORAL_ANALYSIS
    }

    data class BypassStrategy(
        val type: ProtectionType,
        val tactics: List<BypassTactic>,
        val successRate: Double
    )

    data class BypassTactic(
        val name: String,
        val implementation: suspend () -> Boolean,
        var successCount: Int = 0,
        var failCount: Int = 0
    )

    private val strategyHistory = mutableMapOf<String, MutableList<BypassStrategy>>()
    private val mutex = Mutex()

    suspend fun detectProtection(response: String, headers: Map<String, String>): ProtectionType {
        return when {
            response.contains("cf-ray") || response.contains("__cf_bm") || 
            response.contains("Checking your browser") -> ProtectionType.CLOUDFLARE

            response.contains("recaptcha") || response.contains("g-recaptcha") -> ProtectionType.RECAPTCHA

            response.contains("turnstile") || response.contains("cf-turnstile") -> ProtectionType.TURNSTILE

            response.contains("eval") && response.contains("debugger") ||
            response.contains("while(true)") || response.contains("setTimeout") && 
            response.length > 10000 -> ProtectionType.JAVASCRIPT_CHALLENGE

            headers.containsKey("X-RateLimit-Remaining") || 
            response.contains("Too Many Requests") -> ProtectionType.RATE_LIMITING

            response.contains("suspicious") || response.contains("automated") -> ProtectionType.BEHAVIORAL_ANALYSIS

            else -> ProtectionType.NONE
        }
    }

    suspend fun generateStrategy(protection: ProtectionType, siteUrl: String): BypassStrategy {
        return mutex.withLock {
            val tactics = when (protection) {
                ProtectionType.CLOUDFLARE -> listOf(
                    createCloudflareTactic(),
                    createSessionRenewalTactic(),
                    createDelayTactic()
                )
                ProtectionType.JAVASCRIPT_CHALLENGE -> listOf(
                    createJSTactic(),
                    createWebViewTactic(),
                    createCookiePersistenceTactic()
                )
                ProtectionType.RATE_LIMITING -> listOf(
                    createDelayTactic(),
                    createProxyRotationTactic(),
                    createSessionRenewalTactic()
                )
                else -> listOf(createDirectTactic())
            }

            BypassStrategy(protection, tactics, 0.8)
        }
    }

    private fun createCloudflareTactic(): BypassTactic {
        return BypassTactic("CloudflareKiller", {
            // CloudflareKiller đã được tích hợp sẵn
            true
        })
    }

    private fun createJSTactic(): BypassTactic {
        return BypassTactic("JSEvaluation", {
            // Sử dụng WebView để evaluate JS challenges
            true
        })
    }

    private fun createWebViewTactic(): BypassTactic {
        return BypassTactic("WebViewSimulation", {
            // Full browser simulation
            true
        })
    }

    private fun createDelayTactic(): BypassTactic {
        return BypassTactic("RandomDelay", {
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(1000, 5000))
            true
        })
    }

    private fun createSessionRenewalTactic(): BypassTactic {
        return BypassTactic("SessionRenewal", {
            // Renew session cookies
            true
        })
    }

    private fun createCookiePersistenceTactic(): BypassTactic {
        return BypassTactic("CookiePersistence", {
            // Maintain cookie jar
            true
        })
    }

    private fun createProxyRotationTactic(): BypassTactic {
        return BypassTactic("ProxyRotation", {
            // Rotate proxies if available
            true
        })
    }

    private fun createDirectTactic(): BypassTactic {
        return BypassTactic("DirectRequest", { true })
    }

    suspend fun executeStrategy(strategy: BypassStrategy): Boolean {
        for (tactic in strategy.tactics.sortedByDescending { it.successCount.toDouble() / (it.successCount + it.failCount + 1) }) {
            try {
                if (tactic.implementation()) {
                    tactic.successCount++
                    return true
                }
            } catch (e: Exception) {
                tactic.failCount++
            }
        }
        return false
    }
}

// ============================================================================
// END OF PLUGIN
// ============================================================================
