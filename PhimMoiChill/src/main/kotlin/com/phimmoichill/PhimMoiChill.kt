package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    // ... (Các hàm getMainPage, search giữ nguyên cấu trúc chuẩn)

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc = Jsoup.parse(html)
        // Fix lỗi "Unknown" bằng cách lấy tiêu đề chuẩn hơn
        val title = doc.selectFirst("h1.entry-title, .halim-movie-title, h1.title")?.text()?.trim() ?: "PhimMoi"
        val poster = doc.selectFirst(".halim-movie-poster img, .film-poster img")?.attr("src")
        
        val episodes = doc.select("a[href*='/xem/']").mapNotNull {
            val href = it.attr("href")
            val name = it.text().trim()
            if (href.isNullOrBlank()) null else newEpisode(if (href.startsWith("http")) href else "$mainUrl$href") {
                this.name = name
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = if (poster?.startsWith("http") == true) poster else "$mainUrl$poster"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var hasLinks = false
        val episodeId = Regex("""pm(\d+)""").find(data)?.groupValues?.get(1)

        // 1. CHIẾN THUẬT: Truy quét file cấu hình XML (da88.xml) mà bạn tìm thấy
        try {
            val xmlData = app.get("$mainUrl/da88.xml", headers = defaultHeaders.plus("Referer" to data)).text
            // Tìm các link m3u8 ẩn trong playlist XML
            Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""").findAll(xmlData).forEach { match ->
                M3u8Helper.generateM3u8(name, match.value, data).forEach { 
                    callback(it)
                    hasLinks = true 
                }
            }
        } catch (e: Exception) {}

        // 2. Tấn công vào chillsplayer.php với ID từ Initiator
        if (episodeId != null && !hasLinks) {
            try {
                val response = app.post(
                    "$mainUrl/chillsplayer.php",
                    data = mapOf("qcao" to episodeId),
                    headers = defaultHeaders.plus("Referer" to data)
                ).text

                // Quét link m3u8 sạch (loại bỏ link quảng cáo)
                val m3u8Regex = Regex("""https?[:\\]+[^"'<>\s]+?\.m3u8[^"'<>\s]*""")
                m3u8Regex.findAll(response.replace("\\/", "/")).forEach { match ->
                    val link = match.value
                    if (!link.contains("ads") && !link.contains("pre-roll")) {
                        M3u8Helper.generateM3u8(name, link, data).forEach {
                            callback(it)
                            hasLinks = true
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        // 3. Fallback: Nếu vẫn văng, thử quét toàn bộ script tìm link từ sotrim.js logic
        if (!hasLinks) {
            val html = app.get(data, headers = defaultHeaders).text
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(html).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                if (!link.contains("ads")) {
                    M3u8Helper.generateM3u8(name, link, data).forEach { callback(it); hasLinks = true }
                }
            }
        }

        return hasLinks
    }
}
