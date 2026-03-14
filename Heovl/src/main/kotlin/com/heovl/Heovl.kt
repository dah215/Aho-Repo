package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HeoVL Plugin for CloudStream
 * Provider: https://heovl.moe
 * Language: Vietnamese
 * Type: NSFW/Adult Content
 * 
 * Flow: heovl.moe → iframe (p1.spexliu.top) → m3u8
 * m3u8 format: https://p1.spexliu.top/videos/{id}/master.m3u8?e={timestamp}&s={signature}
 */
@CloudstreamPlugin
class HeoVLPlugin : Plugin() {
    override fun load() {
        registerMainAPI(HeoVLProvider())
    }
}

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.moe"
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // User Agent
    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    // Main page categories
    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/categories/moi/" to "Mới Cập Nhật",
        "/categories/viet-nam/" to "Việt Nam",
        "/categories/han-quoc/" to "Hàn Quốc",
        "/categories/nhat-ban/" to "Nhật Bản",
        "/categories/trung-quoc/" to "Trung Quốc",
        "/categories/au-my/" to "Âu Mỹ",
        "/categories/jav/" to "JAV"
    )

    /**
     * Fix and normalize URLs
     */
    private fun fixUrl(url: String, base: String = mainUrl): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val baseUrl = base.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$baseUrl$cleanUrl" else "$baseUrl/$cleanUrl"
    }

    /**
     * Build headers
     */
    private fun buildHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        referer?.let { headers["Referer"] = it }
        return headers
    }

    /**
     * Main page - list videos by category
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page == 1) {
                "$mainUrl${request.data}"
            } else {
                "$mainUrl${request.data}?page=$page".replace("//?", "/?")
            }

            val doc = app.get(url, headers = buildHeaders()).document

            val items = doc.select("div.video-box").mapNotNull { el ->
                val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
                val href = fixUrl(linkEl.attr("href"))
                val title = linkEl.attr("title")
                    .ifBlank { el.selectFirst("h3")?.text() }
                    ?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("data-src") }
                }

                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }

            val hasNext = items.isNotEmpty() && 
                doc.select("a.pagination__link--next, a.next, li.next a").isNotEmpty()
            newHomePageResponse(request.name, items, hasNext)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    /**
     * Search functionality
     */
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/${query.encodeUri()}/"
            val doc = app.get(url, headers = buildHeaders()).document

            doc.select("div.video-box").mapNotNull { el ->
                val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
                val href = fixUrl(linkEl.attr("href"))
                val title = linkEl.attr("title")
                    .ifBlank { el.selectFirst("h3")?.text() }
                    ?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("abs:src")

                newMovieSearchResponse(title, href, TvType.NSFW) {
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Load video details
     */
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = buildHeaders()).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster")

        val description = doc.selectFirst("div.video-info__description, div.description")?.text()?.trim()
        val tags = doc.select("div.video-info__tags a, div.tags a").mapNotNull { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    /**
     * Extract video links
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(data, headers = buildHeaders()).text
            var foundLinks = false

            // Step 1: Find iframe URLs
            val iframeUrls = extractIframeUrls(html)

            for (iframeUrl in iframeUrls) {
                val fullIframe = fixUrl(iframeUrl)
                
                // Step 2: Extract m3u8 from iframe
                val links = extractFromIframe(fullIframe, data)
                if (links.isNotEmpty()) {
                    links.forEach { callback(it) }
                    foundLinks = true
                }
            }

            // Fallback: WebView sniffing
            if (!foundLinks && iframeUrls.isNotEmpty()) {
                val sniffed = sniffVideoUrl(fixUrl(iframeUrls.first()), data)
                if (sniffed != null) {
                    callback(
                        newExtractorLink(
                            "HeoVL",
                            "Server VIP",
                            sniffed,
                            ExtractorLinkType.M3U8
                        ) {
                            referer = "https://p1.spexliu.top/"
                            quality = Qualities.P1080.value
                        }
                    )
                    foundLinks = true
                }
            }

            foundLinks
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract iframe URLs from HTML
     */
    private fun extractIframeUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        // Patterns for iframe src
        val patterns = listOf(
            """src=["'](https?://p1\.spexliu\.top[^"']*)["']""",
            """src=["'](https?://[^"']*(?:spexliu|streamqq|trivonix)[^"']*)["']""",
            """<iframe[^>]+src=["']([^"']+)["']"""
        )

        for (pattern in patterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                if (url.isNotBlank() && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }

        // Fallback: Jsoup
        if (urls.isEmpty()) {
            Jsoup.parse(html).select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !urls.contains(src)) {
                    urls.add(src)
                }
            }
        }

        return urls.distinct()
    }

    /**
     * Extract m3u8 from iframe page
     * Main pattern: https://p1.spexliu.top/videos/{id}/master.m3u8?e={}&s={}
     */
    private suspend fun extractFromIframe(iframeUrl: String, referer: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        try {
            val headers = buildHeaders(referer = referer)
            val html = app.get(iframeUrl, headers = headers).text

            // Primary pattern for p1.spexliu.top m3u8
            val m3u8Patterns = listOf(
                // Pattern 1: Full URL with query params (most common)
                """(https?://p1\.spexliu\.top/videos/[a-zA-Z0-9]+/master\.m3u8\?[^"'\s]*)""",
                // Pattern 2: Full URL without query params
                """(https?://p1\.spexliu\.top/videos/[a-zA-Z0-9]+/master\.m3u8)""",
                // Pattern 3: Relative path with query
                """["'](/videos/[a-zA-Z0-9]+/master\.m3u8\?[^"']*)["']""",
                // Pattern 4: Relative path without query
                """["'](/videos/[a-zA-Z0-9]+/master\.m3u8)["']""",
                // Pattern 5: Generic m3u8
                """(https?://[^"'\s]+?/videos/[a-zA-Z0-9]+/master\.m3u8[^"'\s]*)""",
                // Pattern 6: Any m3u8 URL
                """(https?://[^"'\s]+?\.m3u8[^"'\s]*)"""
            )

            for (pattern in m3u8Patterns) {
                val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
                if (match != null) {
                    var m3u8Url = match.groupValues[1].replace("\\/", "/")
                    
                    // If relative URL, make it absolute
                    if (m3u8Url.startsWith("/videos/")) {
                        m3u8Url = "https://p1.spexliu.top$m3u8Url"
                    }

                    links.add(
                        newExtractorLink(
                            "HeoVL",
                            "Server VIP",
                            m3u8Url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://p1.spexliu.top/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return links
                }
            }

            // Try extracting from JavaScript variables
            val jsPatterns = listOf(
                """(?:var|let|const)\s+\w*[Uu]rl\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
                """(?:var|let|const)\s+\w*[Ss]ource\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
                """file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
                """src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""
            )

            for (pattern in jsPatterns) {
                val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
                if (match != null) {
                    var m3u8Url = match.groupValues[1].replace("\\/", "/")
                    
                    if (m3u8Url.startsWith("/videos/")) {
                        m3u8Url = "https://p1.spexliu.top$m3u8Url"
                    }

                    if (m3u8Url.contains("master.m3u8") || m3u8Url.contains(".m3u8")) {
                        links.add(
                            newExtractorLink(
                                "HeoVL",
                                "Server VIP",
                                m3u8Url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://p1.spexliu.top/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return links
                    }
                }
            }

        } catch (e: Exception) {
            // Will fallback to WebView
        }

        return links
    }

    /**
     * WebView sniffing for m3u8 URL
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoUrl(iframeUrl: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null

            val ctx = AcraApplication.context
            if (ctx == null) {
                return@withContext null
            }

            val wv = WebView(ctx)
            try {
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    blockNetworkImage = true
                }

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun send(url: String) {
                        if (videoUrl == null && isValidVideoUrl(url)) {
                            videoUrl = url
                            latch.countDown()
                        }
                    }
                }, "Android")

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request?.url?.toString()?.let { url ->
                            if (isValidVideoUrl(url) && videoUrl == null) {
                                videoUrl = url
                                latch.countDown()
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        
                        // Inject JavaScript to capture m3u8 URLs
                        wv.evaluateJavascript(
                            """
                            (function(){
                                const h = u => { 
                                    if(u && u.includes('master.m3u8')) {
                                        Android.send(u); 
                                    }
                                };
                                
                                // Intercept fetch
                                if(window.fetch) {
                                    const _f = window.fetch;
                                    window.fetch = function() {
                                        if(arguments[0]) h(arguments[0]);
                                        return _f.apply(this, arguments);
                                    };
                                }
                                
                                // Intercept XMLHttpRequest
                                const _o = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function(m, u) {
                                    h(u);
                                    return _o.apply(this, arguments);
                                };
                                
                                // Check video elements
                                setTimeout(() => {
                                    document.querySelectorAll('video').forEach(v => {
                                        if(v.src && v.src.includes('m3u8')) h(v.src);
                                        if(v.currentSrc && v.currentSrc.includes('m3u8')) h(v.currentSrc);
                                        try { v.play && v.play(); } catch(e){}
                                    });
                                }, 2000);
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                wv.loadUrl(iframeUrl, mapOf("Referer" to referer))

                withContext(Dispatchers.IO) {
                    try {
                        latch.await(45, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        // Timeout
                    }
                }

                videoUrl
            } catch (e: Exception) {
                null
            } finally {
                wv.post { wv.destroy() }
            }
        }
    }

    /**
     * Check if URL is valid m3u8 video URL
     */
    private fun isValidVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("master.m3u8") 
            && !lower.contains("vast")
            && !lower.contains("/ad")
    }
}

private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")
