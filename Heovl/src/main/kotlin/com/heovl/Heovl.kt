package com.heovl

import android.annotation.SuppressLint
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
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

    private val headers = mapOf(
        "User-Agent" to UA,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Trang Chủ",
        "/categories/moi/" to "Mới Cập Nhật",
        "/categories/viet-nam/" to "Việt Nam",
        "/categories/han-quoc/" to "Hàn Quốc",
        "/categories/nhat-ban/" to "Nhật Bản",
        "/categories/trung-quoc/" to "Trung Quốc"
    )

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        var cleanUrl = url.trim().replace("\\/", "/")
        if (cleanUrl.startsWith("http")) return cleanUrl
        if (cleanUrl.startsWith("//")) return "https:$cleanUrl"
        val base = mainUrl.removeSuffix("/")
        return if (cleanUrl.startsWith("/")) "$base$cleanUrl" else "$base/$cleanUrl"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}${if (page > 1) "?page=$page" else ""}"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            val href = fixUrl(linkEl.attr("href"))
            val title = linkEl.attr("title").ifBlank { el.selectFirst("h3")?.text() ?: "" }
            newMovieSearchResponse(title.trim(), href, TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${java.net.URLEncoder.encode(query, "UTF-8")}/"
        val doc = app.get(url, headers = headers).document
        return doc.select("div.video-box").mapNotNull { el ->
            val linkEl = el.selectFirst("a.video-box__thumbnail__link") ?: return@mapNotNull null
            newMovieSearchResponse(linkEl.attr("title"), fixUrl(linkEl.attr("href")), TvType.NSFW) {
                this.posterUrl = el.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text

        // Tìm iframe URL
        var iframeUrl: String? = null
        val iframePatterns = listOf(
            """src=["'](https?://e\.streamqq\.com[^"']*)["']""",
            """src=["'](https?://[^"']*streamqq[^"']*)["']""",
            """src=["'](https?://p1\.spexliu\.top[^"']*)["']""",
            """src=["'](https?://[^"']*spexliu[^"']*)["']"""
        )

        for (pattern in iframePatterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(html)
            if (match != null) {
                iframeUrl = match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                break
            }
        }

        if (iframeUrl == null) return false

        // Extract video ID
        val videoIdMatch = Regex("""/videos/([a-zA-Z0-9]+)/play""").find(iframeUrl)
        val videoId = videoIdMatch?.groupValues?.get(1)

        // WebView sniffing với selector chính xác
        val sniffedM3u8 = sniffM3u8(iframeUrl, data)
        if (sniffedM3u8 != null) {
            callback(
                newExtractorLink(name, "Server VIP", sniffedM3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = "https://p1.spexliu.top/"
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffM3u8(iframeUrl: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null
            var adVideoUrl: String? = null  // Lưu URL quảng cáo để loại bỏ

            val ctx = AcraApplication.context ?: return@withContext null

            val wv = WebView(ctx)
            try {
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun send(url: String, isAd: Boolean) {
                        if (isAd) {
                            // Đây là quảng cáo, lưu lại để bỏ qua
                            if (adVideoUrl == null) adVideoUrl = url
                        } else if (videoUrl == null && url.contains("master.m3u8")) {
                            // Lấy URL video chính (khác với quảng cáo)
                            if (adVideoUrl == null || url != adVideoUrl) {
                                videoUrl = url
                                latch.countDown()
                            }
                        }
                    }
                }, "Android")

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        request?.url?.toString()?.let { url ->
                            // Bỏ qua vast/ad
                            if (url.contains("vast") || url.contains("adtag")) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            
                            // Capture m3u8
                            if (url.contains("master.m3u8") && videoUrl == null) {
                                if (adVideoUrl == null) {
                                    // Đây có thể là quảng cáo (m3u8 đầu tiên)
                                    adVideoUrl = url
                                } else if (url != adVideoUrl) {
                                    // Đây là video chính
                                    videoUrl = url
                                    latch.countDown()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        wv.evaluateJavascript("""
                            (function() {
                                var capturedUrls = [];
                                var adCaptured = false;
                                
                                var h = function(u, isAd) {
                                    if(u && u.includes('master.m3u8')) {
                                        Android.send(u, isAd || false);
                                    }
                                };
                                
                                // Intercept fetch
                                if(window.fetch) {
                                    var _f = window.fetch;
                                    window.fetch = function() {
                                        var url = arguments[0];
                                        if(url && url.includes('master.m3u8')) {
                                            h(url, !adCaptured);
                                            if(!adCaptured) adCaptured = true;
                                        }
                                        return _f.apply(this, arguments);
                                    };
                                }
                                
                                // Intercept XHR
                                var _o = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function(m, u) {
                                    if(u && u.includes('master.m3u8')) {
                                        h(u, !adCaptured);
                                        if(!adCaptured) adCaptured = true;
                                    }
                                    return _o.apply(this, arguments);
                                };
                                
                                // Click Play button
                                function clickPlay() {
                                    var playBtn = document.querySelector('#play-btn, .play-button');
                                    if(playBtn) {
                                        playBtn.click();
                                    }
                                    
                                    // Also click video element
                                    var videos = document.querySelectorAll('.jw-video, video');
                                    videos.forEach(function(v) {
                                        try {
                                            v.muted = true;
                                            v.click();
                                            v.play();
                                        } catch(e) {}
                                    });
                                }
                                
                                // Skip ads
                                function skipAds() {
                                    // Click vào JW skip button
                                    var skipBtns = document.querySelectorAll('.jw-skiptext, .jw-icon-inline.jw-skip, [class*="skip"]');
                                    skipBtns.forEach(function(btn) {
                                        try { 
                                            btn.click();
                                            // Click parent too
                                            if(btn.parentElement) btn.parentElement.click();
                                        } catch(e) {}
                                    });
                                    
                                    // Click any close buttons
                                    var closeBtns = document.querySelectorAll('[class*="close"], [aria-label*="close"], [title*="close"]');
                                    closeBtns.forEach(function(btn) {
                                        try { btn.click(); } catch(e) {}
                                    });
                                    
                                    // Play main video after skip
                                    var videos = document.querySelectorAll('.jw-video, video');
                                    videos.forEach(function(v) {
                                        try {
                                            v.muted = true;
                                            v.click();
                                            v.play();
                                        } catch(e) {}
                                    });
                                }
                                
                                // Execute sequence
                                setTimeout(clickPlay, 500);      // Click play
                                setTimeout(clickPlay, 1500);
                                setTimeout(skipAds, 5500);       // Skip after 5s
                                setTimeout(skipAds, 6500);
                                setTimeout(skipAds, 8000);
                                setTimeout(clickPlay, 8500);     // Play main video
                                
                                // Monitor video src
                                setInterval(function() {
                                    var videos = document.querySelectorAll('.jw-video, video');
                                    videos.forEach(function(v) {
                                        if(v.src && v.src.includes('blob:')) {
                                            // Blob URL - cần sniff qua network
                                        }
                                    });
                                }, 1000);
                            })();
                        """.trimIndent(), null)
                    }
                }

                wv.loadUrl(iframeUrl, mapOf("Referer" to referer))

                withContext(Dispatchers.IO) {
                    latch.await(90, TimeUnit.SECONDS)
                }

                videoUrl
            } catch (e: Exception) {
                null
            } finally {
                wv.post { wv.destroy() }
            }
        }
    }
}
