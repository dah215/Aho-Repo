package com.phimmoichill

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

@CloudstreamPlugin
class PhimMoiChillPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PhimMoiChillProvider())
    }
}

class PhimMoiChillProvider : MainAPI() {
    override var mainUrl = "https://phimmoichill.my"
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
        "list/phim-nam-2026" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    private fun fixUrl(url: String) = when {
        url.isBlank() -> url
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$mainUrl/$url"
    }

    private fun imgUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        listOf("data-src", "data-original", "src").forEach { attr ->
            el.attr(attr)?.takeIf { it.isNotEmpty() && !it.startsWith("data:image") }?.let { return fixUrl(it) }
        }
        return null
    }

    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a = el.selectFirst("a[href*='/info/']") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").trim().ifEmpty { el.selectFirst("h3, p")?.text() ?: return null }
        val poster = imgUrl(el.selectFirst("img"))
        
        // FIX 1: Lấy tất cả các nhãn (Label/Badge/Status) để tìm số tập
        // Web thường dùng .label hoặc .badge hoặc .status
        val labelElement = el.selectFirst(".label, .badge, .status, .film-status")
        val label = labelElement?.text()?.trim() ?: ""
        
        // Kiểm tra xem có phải phim bộ không dựa trên Title hoặc Label
        val isSeries = label.contains("Tập", true) || 
                       label.contains("Hoàn Tất", true) || 
                       title.contains("Phần", true) || 
                       title.contains("Season", true) ||
                       Regex("""\d+/\d+""").containsMatchIn(label)

        val has4k = el.selectFirst("span.film-badge.badge-4k") != null || label.contains("4K", true)
        val hasTM = el.selectFirst("span.film-badge.badge-tm") != null || label.contains("Thuyết Minh", true)
        
        val dubs = java.util.EnumSet.noneOf(DubStatus::class.java).apply {
            if (!label.contains("RAW", true)) add(DubStatus.Subbed)
            if (hasTM) add(DubStatus.Dubbed)
        }

        return newAnimeSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            // Hiển thị số tập hoặc trạng thái lên Card
            this.addSub = label
            this.quality = when {
                has4k -> SearchQuality.UHD
                label.contains("CAM", true) -> SearchQuality.Cam
                else -> SearchQuality.HD
            }
            this.dubStatus = dubs
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FIX 2: Cấu trúc phân trang chuẩn theo link bạn gửi: .../page-2/
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}/page-$page"
        }
        
        val items = app.get(url, headers = headers).document.select("li.item").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}", headers = headers).document
        return doc.select("li.item").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Phim"
        val poster = imgUrl(doc.selectFirst(".film-poster img, img[itemprop=image]"))
        val plot = doc.selectFirst("#film-content, [itemprop=description]")?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()?.let { Regex("""\b(20\d{2})\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
        val watchUrl = doc.selectFirst("a.btn-see[href*='/xem/']")?.attr("href")?.let { fixUrl(it) }
        
        // Kiểm tra danh sách tập phim
        val hasEps = doc.select("div.latest-episode a[data-id], ul.episodes li a").isNotEmpty()

        if (!hasEps && watchUrl == null) return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = genres
        }

        // Lấy danh sách tập
        val eps = (watchUrl?.let { wu ->
            try { 
                val watchDoc = app.get(wu, headers = headers).document
                // Lấy tập từ trang xem (dựa trên HTML bạn cung cấp: a[data-id])
                watchDoc.select("ul.episodes li a, div.list-episode a").mapNotNull { a ->
                    val href = fixUrl(a.attr("href"))
                    val name = a.text().trim()
                    val epNum = Regex("""Tập\s*(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() 
                                ?: Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    newEpisode(href) { 
                        this.name = name
                        this.episode = epNum
                    }
                }
            } catch (_: Exception) { emptyList() }
        } ?: emptyList()).ifEmpty {
            // Fallback nếu không lấy được từ trang xem, thử lấy ở trang info (nếu có)
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                newEpisode(fixUrl(a.attr("href"))) { 
                    this.name = a.text()
                    this.episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull() 
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.ifEmpty { watchUrl?.let { listOf(newEpisode(it) { name = "Tập 1" }) } ?: emptyList() }) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(data, headers = headers)
            val html = res.text
            val cookies = res.cookies

            // Extract episode ID
            val epId = Regex("""[/-]pm(\d+)""").find(data)?.groupValues?.get(1)
                ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
                ?: return false

            val postHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to data,
                "Origin" to mainUrl
            )

            // ============================================
            // BƯỚC 1: LẤY KEY VIETSUB (không có quality_index)
            // ============================================
            var vietsubKey: String? = null
            
            for (sv in 0..3) {
                try {
                    val response = app.post(
                        "$mainUrl/chillsplayer.php",
                        data = mapOf("qcao" to epId, "sv" to sv.toString()),
                        headers = postHeaders,
                        cookies = cookies
                    ).text

                    val match = Regex("""iniPlayers\s*\(\s*["']([a-fA-F0-9]{20,})["']""").find(response)
                    if (match != null) {
                        vietsubKey = match.groupValues[1]
                        break
                    }
                } catch (e: Exception) { continue }
            }

            // ============================================
            // BƯỚC 2: LẤY KEY THUYẾT MINH (có quality_index=1)
            // ============================================
            var tmKey: String? = null
            
            for (sv in 0..3) {
                try {
                    val response = app.post(
                        "$mainUrl/chillsplayer.php",
                        data = mapOf("qcao" to epId, "sv" to sv.toString(), "quality_index" to "1"),
                        headers = postHeaders,
                        cookies = cookies
                    ).text

                    val match = Regex("""iniPlayers\s*\(\s*["']([a-fA-F0-9]{20,})["']""").find(response)
                    if (match != null) {
                        tmKey = match.groupValues[1]
                        break
                    }
                } catch (e: Exception) { continue }
            }

            // ============================================
            // BƯỚC 3: THÊM LINKS
            // ============================================
            
            // Thêm Vietsub
            if (!vietsubKey.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        "Vietsub - PMHLS",
                        "Vietsub - PMHLS",
                        "https://sotrim.topphimmoi.org/mpeg/$vietsubKey/index.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                callback(
                    newExtractorLink(
                        "Vietsub - PMPRO",
                        "Vietsub - PMPRO",
                        "https://dash.megacdn.xyz/mpeg/$vietsubKey/index.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            // Thêm Thuyết Minh (chỉ nếu key khác với Vietsub)
            if (!tmKey.isNullOrEmpty() && tmKey != vietsubKey) {
                callback(
                    newExtractorLink(
                        "Thuyết Minh - PMHLS",
                        "Thuyết Minh - PMHLS",
                        "https://sotrim.topphimmoi.org/mpeg/$tmKey/index.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                callback(
                    newExtractorLink(
                        "Thuyết Minh - PMPRO",
                        "Thuyết Minh - PMPRO",
                        "https://dash.megacdn.xyz/mpeg/$tmKey/index.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            return !vietsubKey.isNullOrEmpty() || !tmKey.isNullOrEmpty()

        } catch (e: Exception) {
            return false
        }
    }
}
