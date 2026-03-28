package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.EnumSet

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.you"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,*/*",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$mainUrl/${url.removePrefix("/")}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl${request.data}" else "$mainUrl${request.data}/page/$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.select("li.item").mapNotNull {
            val title = it.selectFirst("p.name")?.text() ?: it.selectFirst("a")?.attr("title") ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrl(it.selectFirst("img")?.attr("src") ?: "")
            val status = it.selectFirst("span.label")?.text() ?: ""
            
            val isDub = status.contains("Lồng Tiếng", true) || status.contains("Thuyết Minh", true)
            
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD
                this.dubStatus = if (isDub) EnumSet.of(DubStatus.Subbed, DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = headers).document
        return doc.select("li.item").mapNotNull {
            val title = it.selectFirst("p.name")?.text() ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrl(it.selectFirst("img")?.attr("src") ?: "")
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: ""
        val poster = fixUrl(doc.selectFirst("div.image img")?.attr("src") ?: "")
        
        // --- Lấy dữ liệu thô ---
        val originalName = doc.selectFirst("span.real-name")?.text()?.trim()
        val status = doc.selectFirst("span.status")?.text()?.trim()
        val quality = doc.selectFirst("span.quality")?.text()?.trim()
        val yearText = doc.selectFirst("a[href*='/nam-phat-hanh/']")?.text()?.trim()
        val country = doc.selectFirst("a[href*='/quoc-gia/']")?.text()?.trim()
        val categories = doc.select("a[href*='/the-loai/']").map { it.text() }.joinToString(", ")
        
        var director = ""
        var casts = ""
        doc.select("ul.entry-meta li").forEach { li ->
            val text = li.text()
            if (text.contains("Đạo diễn", true)) director = text.substringAfter(":").trim()
            if (text.contains("Diễn viên", true)) casts = text.substringAfter(":").trim()
        }

        // Làm sạch Plot: Loại bỏ tiêu đề bị lặp lại trong nội dung
        var plotText = doc.selectFirst("div#film-content")?.text() ?: ""
        if (plotText.startsWith(title)) {
            plotText = plotText.removePrefix(title).trim().removePrefix(":").trim()
        }

        val episodes = doc.select("ul#list_episodes li a").map {
            val epHref = fixUrl(it.attr("href"))
            val epName = it.text().trim()
            newEpisode(epHref) {
                this.name = if (epName.contains("Tập")) epName else "Tập $epName"
                this.episode = epName.replace(Regex("\\D"), "").toIntOrNull()
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = buildBeautifulDescription(
                originalName, status, quality, yearText, country, categories, director, casts, plotText
            )
            this.year = yearText?.toIntOrNull()
            this.tags = categories.split(", ").filter { it.isNotBlank() }
        }
    }

    private fun buildBeautifulDescription(
        originalName: String?,
        status: String?,
        quality: String?,
        year: String?,
        country: String?,
        categories: String?,
        director: String?,
        casts: String?,
        plotText: String?
    ): String {
        return buildString {
            // Tên gốc - In nghiêng mờ
            if (!originalName.isNullOrBlank()) {
                append("<font color='#AAAAAA'><i>$originalName</i></font><br><br>")
            }

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank() && value != "Đang cập nhật") {
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
                }
            }

            // Status Color (Xanh lá nếu Hoàn tất, Xanh dương nếu đang chiếu)
            val statusColor = if (status?.contains("Full", true) == true || status?.contains("Hoàn", true) == true) "#4CAF50" else "#2196F3"

            addInfo("📺", "Trạng thái", status, statusColor)
            addInfo("🎬", "Chất lượng", quality, "#E91E63")
            addInfo("🌍", "Quốc gia", country)
            addInfo("📅", "Năm", year)
            addInfo("🎥", "Đạo diễn", director)
            addInfo("🎭", "Diễn viên", casts)
            addInfo("🏷", "Thể loại", categories)

            // Phần nội dung - Cách biệt hẳn ra bằng gạch ngang
            if (!plotText.isNullOrBlank()) {
                append("<br><br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(plotText.trim())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, headers = headers).document
            val script = doc.select("script").find { it.data().contains("player.set") }?.data() ?: return false
            
            val vietsubKey = Regex("\"key\":\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)
            val tmKey = Regex("\"key_presub\":\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)

            if (!vietsubKey.isNullOrEmpty()) {
                callback(newExtractorLink("Vietsub - PMHLS", "Vietsub - PMHLS", "https://sotrim.topphimmoi.org/mpeg/$vietsubKey/index.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                })
            }

            if (!tmKey.isNullOrEmpty() && tmKey != vietsubKey) {
                callback(newExtractorLink("Thuyết Minh - PMHLS", "Thuyết Minh - PMHLS", "https://sotrim.topphimmoi.org/mpeg/$tmKey/index.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                })
            }

            return !vietsubKey.isNullOrEmpty() || !tmKey.isNullOrEmpty()
        } catch (e: Exception) { return false }
    }
}
