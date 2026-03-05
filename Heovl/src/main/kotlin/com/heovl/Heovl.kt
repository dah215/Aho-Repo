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
import java.util.concurrent.ConcurrentHashMap

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

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- PHẦN GIAO DIỆN (GIỮ NGUYÊN) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else if (request.data == "/") "$mainUrl/?page=$page" else "$mainUrl${request.data}?page=$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3.video-box__heading")?.text() }?.trim() ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "HeoVL Video"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    // --- PHẦN XỬ LÝ VIDEO: FILTERED SNIFFER ---

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoOnly(iframeUrl: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null
            val ctx = try { AcraApplication.context } catch (e: Exception) { null } ?: return@withContext null

            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                userAgentString = UA
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    
                    // BỘ LỌC DỨT KHOÁT:
                    // 1. Phải chứa elifros hoặc m3u8
                    // 2. KHÔNG ĐƯỢC chứa .jpg, .png, .gif (Loại bỏ ảnh)
                    // 3. KHÔNG ĐƯỢC chứa 'ads', 'vast' (Loại bỏ quảng cáo)
                    if ((url.contains("elifros") || url.contains(".m3u8")) && 
                        !url.contains(Regex("\\.(jpg|png|gif|jpeg)$")) && 
                        !url.contains("ads") && !url.contains("vast")) {
                        
                        if (videoUrl == null) {
                            videoUrl = url
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            wv.loadUrl(iframeUrl, mapOf("Referer" to "$mainUrl/"))
            withContext(Dispatchers.IO) { try { latch.await(20, TimeUnit.SECONDS) } catch (e: Exception) {} }
            wv.post { wv.destroy() }
            videoUrl
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
        val iframeUrl = Regex("""src=["'](https?://[^"']*(?:streamqq|spexliu|flimora)[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: org.jsoup.Jsoup.parse(html).select("iframe").attr("src")

        if (!iframeUrl.isNullOrBlank()) {
            val link = sniffVideoOnly(fixUrl(iframeUrl))
            if (link != null) {
                callback(newExtractorLink("HeoVL VIP", "Server VIP (Clean)", link, ExtractorLinkType.M3U8) {
                    this.referer = fixUrl(iframeUrl)
                    this.quality = Qualities.P1080.value
                })
            }
        }
        return true
    }
    
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }
}
