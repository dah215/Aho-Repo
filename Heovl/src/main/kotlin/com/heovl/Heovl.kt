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
            """src=["'](https?://[^"']*spexliu[^"']*)["']""",
            """src=["'](https?://[^"']*trivonix[^"']*)["']"""
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

        // WebView sniffing với auto skip ads
        val sniffedM3u8 = sniffM3u8WithSkipAds(iframeUrl, data)
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
    private suspend fun sniffM3u8WithSkipAds(iframeUrl: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            val latch = CountDownLatch(1)
            var videoUrl: String? = null
            var isAdPlaying = false

            val ctx = AcraApplication.context ?: return@withContext null

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
                        // Chỉ lấy m3u8 KHÔNG phải quảng cáo
                        if (videoUrl == null && url.contains("master.m3u8")) {
                            val lower = url.lowercase()
                            // Bỏ qua quảng cáo
                            if (!lower.contains("vast") && 
                                !lower.contains("/ad/") && 
                                !lower.contains("adtag") &&
                                !lower.contains("adservice") &&
                                !lower.contains("advertisement")) {
                                videoUrl = url
                                latch.countDown()
                            }
                        }
                    }
                    
                    @JavascriptInterface
                    fun log(msg: String) {
                        android.util.Log.d("HeoVL", msg)
                    }
                }, "Android")

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        request?.url?.toString()?.let { url ->
                            if (url.contains("master.m3u8")) {
                                val lower = url.lowercase()
                                if (!lower.contains("vast") && 
                                    !lower.contains("/ad/") && 
                                    !lower.contains("adtag") &&
                                    !lower.contains("adservice") &&
                                    videoUrl == null) {
                                    videoUrl = url
                                    latch.countDown()
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // JavaScript: Click Play → Đợi 5s → Click Skip → Lấy m3u8
                        wv.evaluateJavascript("""
                            (function() {
                                var h = function(u) {
                                    if(u && u.includes('master.m3u8')) {
                                        Android.send(u);
                                    }
                                };
                                
                                // Intercept requests
                                if(window.fetch) {
                                    var _f = window.fetch;
                                    window.fetch = function() {
                                        if(arguments[0]) h(arguments[0]);
                                        return _f.apply(this, arguments);
                                    };
                                }
                                
                                var _o = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function(m, u) {
                                    h(u);
                                    return _o.apply(this, arguments);
                                };
                                
                                // Bước 1: Click Play
                                function clickPlay() {
                                    Android.log('Clicking play...');
                                    
                                    // Click vào body/video
                                    document.body.click();
                                    
                                    // Click tất cả các nút có thể là play
                                    var playSelectors = [
                                        'button',
                                        '[class*="play"]',
                                        '[class*="Play"]', 
                                        '[onclick*="play"]',
                                        '.jw-icon-playback',
                                        '.vjs-big-play-button',
                                        'video'
                                    ];
                                    
                                    playSelectors.forEach(function(sel) {
                                        var els = document.querySelectorAll(sel);
                                        els.forEach(function(el) {
                                            try {
                                                el.click();
                                            } catch(e) {}
                                        });
                                    });
                                    
                                    // Play video trực tiếp
                                    document.querySelectorAll('video').forEach(function(v) {
                                        try {
                                            v.muted = true;
                                            v.play();
                                        } catch(e) {}
                                    });
                                }
                                
                                // Bước 2: Click Skip Ads (sau 5-6 giây)
                                function clickSkip() {
                                    Android.log('Clicking skip...');
                                    
                                    var skipSelectors = [
                                        '[class*="skip"]',
                                        '[class*="Skip"]',
                                        '[onclick*="skip"]',
                                        '.jw-skip',
                                        '.skip-ad',
                                        '.skipBtn',
                                        '.ads_skip',
                                        'button[class*="close"]',
                                        '[aria-label*="skip"]',
                                        '[title*="skip"]'
                                    ];
                                    
                                    skipSelectors.forEach(function(sel) {
                                        var els = document.querySelectorAll(sel);
                                        els.forEach(function(el) {
                                            try {
                                                el.click();
                                                Android.log('Clicked: ' + sel);
                                            } catch(e) {}
                                        });
                                    });
                                    
                                    // Click vào video để đảm bảo play
                                    document.querySelectorAll('video').forEach(function(v) {
                                        try {
                                            v.click();
                                            v.play();
                                            if(v.src) h(v.src);
                                            if(v.currentSrc) h(v.currentSrc);
                                        } catch(e) {}
                                    });
                                }
                                
                                // Thực hiện: Click Play ngay
                                setTimeout(clickPlay, 500);
                                setTimeout(clickPlay, 1000);
                                
                                // Click Skip sau 5s (khi ads chạy xong)
                                setTimeout(clickSkip, 5500);
                                setTimeout(clickSkip, 6500);
                                setTimeout(clickSkip, 8000);
                                
                                // Check video src định kỳ
                                setInterval(function() {
                                    document.querySelectorAll('video').forEach(function(v) {
                                        if(v.src) h(v.src);
                                        if(v.currentSrc) h(v.currentSrc);
                                    });
                                }, 500);
                            })();
                        """.trimIndent(), null)
                    }
                }

                wv.loadUrl(iframeUrl, mapOf("Referer" to referer))

                // Đợi lâu hơn để kịp skip ads và lấy video
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
