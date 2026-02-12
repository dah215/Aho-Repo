package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now" 
    override var name    = "PhimMoiChill"
    override val hasMainPage        = true
    override var lang               = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl
    )

    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$mainUrl/${url.removePrefix("/")}"
        }
    }

    // ... (Các hàm getMainPage, search, load giữ nguyên)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = defaultHeaders.plus("Referer" to mainUrl))
        val html = res.text
        val document = org.jsoup.Jsoup.parse(html)
        var hasLinks = false

        // 1. Lấy slug hoặc ID tập phim từ URL hoặc mã nguồn
        // Ví dụ URL: .../xem/dau-si-thanh-algiers-tap-full-pm126172
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1) 
            ?: Regex("""id\s*[:=]\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)

        if (episodeId != null) {
            // 2. Gửi yêu cầu đến endpoint AJAX bạn vừa tìm thấy
            val ajaxUrl = "$mainUrl/ajax/get_episode_links"
            val ajaxRes = app.post(
                ajaxUrl,
                data = mapOf(
                    "episode_id" to episodeId,
                    "type" to "full" // Hoặc lấy từ logic trang
                ),
                headers = defaultHeaders.plus("Referer" to data)
            ).text

            // 3. Phân tích phản hồi từ AJAX (thường chứa link iframe hoặc m3u8 trực tiếp)
            val linkInAjax = Regex("""["'](https?://[^\s"'<>]+?\.(?:m3u8|mp4|html)[^\s"'<>]*?)["']""").findAll(ajaxRes)
            linkInAjax.forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach {
                        hasLinks = true
                        callback(it)
                    }
                } else if (link.contains("http")) {
                    loadExtractor(link, data, subtitleCallback) {
                        hasLinks = true
                        callback(it)
                    }
                }
            }
        }

        // 4. Fallback: Nếu AJAX thất bại, quét toàn bộ script tìm link ẩn
        if (!hasLinks) {
            val videoRegex = Regex("""https?[:\\]+[^"'<>]+?\.(?:m3u8|mp4)[^"'<>]*""")
            videoRegex.findAll(html).forEach { match ->
                val rawUrl = match.value.replace("\\/", "/")
                if (rawUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, rawUrl, data).forEach {
                        hasLinks = true
                        callback(it)
                    }
                } else {
                    callback(
                        newExtractorLink(name, "$name Player", rawUrl, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                    hasLinks = true
                }
            }
        }

        return hasLinks
    }
}
