package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jsoup.Jsoup

@CloudstreamPlugin
class HeoVLPlugin : Plugin() {
    override fun load() { registerMainAPI(HeoVLProvider()) }
}

class HeoVLProvider : MainAPI() {
    override var mainUrl = "https://heovl.moe"
    override var name = "HeoVL"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ", "/categories/moi/" to "Mới",
        "/categories/viet-nam/" to "Việt Nam", "/categories/han-quoc/" to "Hàn Quốc",
        "/categories/nhat-ban/" to "Nhật Bản", "/categories/trung-quoc/" to "Trung Quốc"
    )

    private val UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}?page=$page".replace("//?", "/?")
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("abs:src")
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|trivonix|spexliu|p1\.spexliu)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: Jsoup.parse(html).selectFirst("iframe")?.attr("src") ?: return false

        val fullIframe = fixUrl(iframeUrl)
        val iframeHtml = app.get(fullIframe, headers = mapOf("User-Agent" to UA, "Referer" to data)).text

        // Direct extract từ link bạn đưa
        val m3u8 = Regex("""(https?://p1\.spexliu\.top[^"'\s]+?master\.m3u8[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+?master\.m3u8[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)

        if (m3u8 != null) {
            callback(newExtractorLink("HeoVL VIP", "Server VIP", m3u8, ExtractorLinkType.M3U8) {
                referer = fullIframe
                quality = Qualities.P1080.value
            })
            return true
        }

        // Fallback sniff hacker
        val sniffed = sniffVideoOnly(fullIframe)
        if (sniffed != null) {
            callback(newExtractorLink("HeoVL VIP", "Server VIP", sniffed, ExtractorLinkType.M3U8) {
                referer = fullIframe
                quality = Qualities.P1080.value
            })
        }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoOnly(iframeUrl: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null
            val ctx = AcraApplication.context ?: return@withContext null

            val wv = WebView(ctx)
            wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = UA }

            wv.addJavascriptInterface(object {
                @JavascriptInterface fun send(url: String) {
                    if (videoUrl == null && url.contains("master.m3u8")) { videoUrl = url; latch.countDown() }
                }
            }, "Android")

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (url.contains("master.m3u8") && !url.contains("vast")) {
                        if (videoUrl == null) { videoUrl = url; latch.countDown() }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            wv.loadUrl(iframeUrl, mapOf("Referer" to mainUrl))

            wv.evaluateJavascript("""
                (function(){
                    const h = u => { if(u && u.includes('master.m3u8')) Android.send(u); };
                    window.fetch = new Proxy(window.fetch, {apply:(t,a,args)=>{if(args[0])h(args[0]);return t.apply(this,args)}});
                    const o = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){h(u);return o.apply(this,arguments)};
                    setTimeout(()=>{document.querySelectorAll('video').forEach(v=>{if(v.src)h(v.src);v.play();});},3000);
                })();
            """.trimIndent(), null)

            withContext(Dispatchers.IO) { try { latch.await(40, TimeUnit.SECONDS) } catch (e: Exception) {} }
            wv.post { wv.destroy() }
            videoUrl
        }
    }
}
