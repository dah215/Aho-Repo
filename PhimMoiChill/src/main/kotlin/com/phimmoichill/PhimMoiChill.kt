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
            val title = it.selectFirst("p.name")?.text() ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = fixUrl(it.selectFirst("img")?.attr("src") ?: "")
            val status = it.selectFirst("span.label")?.text() ?: ""
            val isDub = status.contains("Lồng Tiếng", true) || status.contains("Thuyết Minh", true)
            
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
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
        
        // Metadata thô
        val originalName = doc.selectFirst("span.real-name")?.text()?.trim()
        val status = doc.selectFirst("span.status")?.text()?.trim()
        val quality = doc.selectFirst("span.quality")?.text()?.trim()
        val year = doc.selectFirst("a[href*='/nam-phat-hanh/']")?.text()?.trim()
        val country = doc.selectFirst("a[href*='/quoc-gia/']")?.text()?.trim()
        val categories = doc.select("a[href*='/the-loai/']").map { it.text() }.joinToString(", ")
        
        // Plot sạch
        val plotText = doc.selectFirst("div#film-content")?.text()
            ?.replace(title, "") // Xóa tên phim trùng lặp
            ?.replace(originalName ?: "", "") // Xóa tên gốc trùng lặp
            ?.trim()

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
            this.plot = buildBeautifulDescription(originalName, status, quality, year, country, categories, plotText)
            this.year = year?.toIntOrNull()
            this.tags = categories.split(", ").filter { it.isNotBlank() }
        }
    }

    private fun buildBeautifulDescription(
        originalName: String?, status: String?, quality: String?, 
        year: String?, country: String?, categories: String?, plotText: String?
    ): String {
        return buildString {
            // Dòng đầu tiên: Tên gốc (nếu có)
            if (!originalName.isNullOrBlank()) append("<i><font color='#AAAAAA'>$originalName</font></i><br>")
            
            // Dòng 2: Trạng thái & Chất lượng
            val sColor = if (status?.contains("Full", true) == true) "#4CAF50" else "#2196F3"
            append("📺 <font color='$sColor'>$status</font>  •  🎬 <font color='#E91E63'>$quality</font><br>")
            
            // Dòng 3: Năm & Quốc gia
            append("📅 $year  •  🌍 $country<br>")
            
            // Dòng 4: Thể loại
            append("🏷️ <font color='#BBBBBB'>$categories</font><br>")

            // Ngắt quãng cực mạnh bằng HR và tiêu đề màu vàng
            if (!plotText.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b>")
                append("<hr color='#333333' size='1'>")
                append(plotText)
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        val script = doc.select("script").find { it.data().contains("player.set") }?.data() ?: return false
        val vietsubKey = Regex("\"key\":\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)
        val tmKey = Regex("\"key_presub\":\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1)

        if (!vietsubKey.isNullOrEmpty()) {
            callback(newExtractorLink("Vietsub", "Vietsub", "https://sotrim.topphimmoi.org/mpeg/$vietsubKey/index.m3u8", ExtractorLinkType.M3U8) { this.referer = "$mainUrl/" })
        }
        if (!tmKey.isNullOrEmpty() && tmKey != vietsubKey) {
            callback(newExtractorLink("Thuyết Minh", "Thuyết Minh", "https://sotrim.topphimmoi.org/mpeg/$tmKey/index.m3u8", ExtractorLinkType.M3U8) { this.referer = "$mainUrl/" })
        }
        return true
    }
}
