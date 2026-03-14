// HeoVL Plugin - Copy file này vào thư mục src/main/java/com/heovl/ trong project CloudStream-Plugins

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

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

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

    private fun fixUrl(url: String, base: String = mainUrl): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val baseUrl = base.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$baseUrl$cleanUrl" else "$baseUrl/$cleanUrl"
    }

    private fun buildHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to UA, "Accept" to "*/*")
        referer?.let { headers["Referer"] = it }
        return headers
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page == 1) "$mainUrl${request.data}" 
                      else "$mainUrl${request.data}?page=$page".replace("//?", "/?")
            val doc = app.get(url, headers = buildHeaders()).document

            val items = doc.select("div.video-box").mapNotNull { el ->
                val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
                val href = fixUrl(linkEl.attr("href"))
                val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() }?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("abs:src").ifBlank { el.selectFirst("img")?.attr("data-src") }
                newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
            }
            val hasNext = items.isNotEmpty() && doc.select("a.pagination__link--next, a.next, li.next a").isNotEmpty()
            newHomePageResponse(request.name, items, hasNext)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/"
            val doc = app.get(url, headers = buildHeaders()).document
            doc.select("div.video-box").mapNotNull { el ->
                val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
                val href = fixUrl(linkEl.attr("href"))
                val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() }?.trim() ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.attr("abs:src")
                newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = buildHeaders()).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(data, headers = buildHeaders()).text
            var foundLinks = false

            // Extract iframe URLs
            val iframeUrls = mutableListOf<String>()
            val patterns = listOf(
                """src=["'](https?://p1\.spexliu\.top[^"']*)["']""",
                """src=["'](https?://[^"']*(?:spexliu|streamqq|trivonix)[^"']*)["']""",
                """<iframe[^>]+src=["']([^"']+)["']"""
            )
            for (pattern in patterns) {
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(html).forEach {
                    val url = it.groupValues[1].replace("\\/", "/")
                    if (url.isNotBlank() && !iframeUrls.contains(url)) iframeUrls.add(url)
                }
            }
            if (iframeUrls.isEmpty()) {
                Jsoup.parse(html).select("iframe").forEach {
                    val src = it.attr("src").ifBlank { it.attr("data-src") }
                    if (src.isNotBlank() && !iframeUrls.contains(src)) iframeUrls.add(src)
                }
            }

            for (iframeUrl in iframeUrls) {
                val fullIframe = fixUrl(iframeUrl)
                val iframeHtml = app.get(fullIframe, headers = buildHeaders(data)).text

                // M3U8 patterns
                val m3u8Patterns = listOf(
                    """(https?://p1\.spexliu\.top/videos/[a-zA-Z0-9]+/master\.m3u8\?[^"'\s]*)""",
                    """(https?://p1\.spexliu\.top/videos/[a-zA-Z0-9]+/master\.m3u8)""",
                    """["'](/videos/[a-zA-Z0-9]+/master\.m3u8\?[^"']*)["']""",
                    """["'](/videos/[a-zA-Z0-9]+/master\.m3u8)["']""",
                    """(https?://[^"'\s]+?/videos/[a-zA-Z0-9]+/master\.m3u8[^"'\s]*)""",
                    """(https?://[^"'\s]+?\.m3u8[^"'\s]*)"""
                )

                for (pattern in m3u8Patterns) {
                    val match = Regex(pattern, RegexOption.IGNORE_CASE).find(iframeHtml)
                    if (match != null) {
                        var m3u8Url = match.groupValues[1].replace("\\/", "/")
                        if (m3u8Url.startsWith("/videos/")) {
                            m3u8Url = "https://p1.spexliu.top$m3u8Url"
                        }
                        callback(
                            newExtractorLink("HeoVL", "Server VIP", m3u8Url, ExtractorLinkType.M3U8) {
                                referer = "https://p1.spexliu.top/"
                                quality = Qualities.P1080.value
                            }
                        )
                        foundLinks = true
                        break
                    }
                }
                if (foundLinks) break
            }

            // Fallback: WebView sniffing
            if (!foundLinks && iframeUrls.isNotEmpty()) {
                val sniffed = sniffVideoUrl(fixUrl(iframeUrls.first()), data)
                if (sniffed != null) {
                    callback(
                        newExtractorLink("HeoVL", "Server WebView", sniffed, ExtractorLinkType.M3U8) {
                            referer = "https://p1.spexliu.top/"
                            quality = Qualities.P1080.value
                        }
                    )
                    foundLinks = true
                }
            }

            foundLinks
        } catch (e: Exception) { false }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoUrl(iframeUrl: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null
            val ctx = AcraApplication.context ?: return@withContext null

            val wv = WebView(ctx)
            try {
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun send(url: String) {
                        if (videoUrl == null && url.contains("master.m3u8") && !url.contains("vast")) {
                            videoUrl = url
                            latch.countDown()
                        }
                    }
                }, "Android")

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        request?.url?.toString()?.let { url ->
                            if (url.contains("master.m3u8") && !url.contains("vast") && videoUrl == null) {
                                videoUrl = url
                                latch.countDown()
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        wv.evaluateJavascript("""
                            (function(){
                                const h=u=>{if(u&&u.includes('master.m3u8'))Android.send(u)};
                                if(window.fetch){const _f=window.fetch;window.fetch=function(){if(arguments[0])h(arguments[0]);return _f.apply(this,arguments)}}
                                const _o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){h(u);return _o.apply(this,arguments)};
                                setTimeout(()=>{document.querySelectorAll('video').forEach(v=>{if(v.src)h(v.src);try{v.play()}catch(e){}})},2000);
                            })();
                        """.trimIndent(), null)
                    }
                }

                wv.loadUrl(iframeUrl, mapOf("Referer" to referer))
                withContext(Dispatchers.IO) { latch.await(45, TimeUnit.SECONDS) }
                videoUrl
            } catch (e: Exception) { null }
            finally { wv.post { wv.destroy() } }
        }
    }
}
