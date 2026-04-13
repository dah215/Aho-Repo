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
    override var mainUrl = "https://phimmoichill.men"
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

        val labelElement = el.selectFirst(".label, .badge, .status, .film-status")
        val label = labelElement?.text()?.trim() ?: ""

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

            val epNum = Regex("""\d+""").find(label)?.value?.toIntOrNull()
            if (epNum != null) {
                addSub(epNum)
            }

            this.quality = when {
                has4k -> SearchQuality.UHD
                label.contains("CAM", true) -> SearchQuality.Cam
                else -> SearchQuality.HD
            }
            this.dubStatus = dubs
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

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

        // ===== THÔNG TIN CƠ BẢN =====
        val title    = doc.selectFirst("h1")?.text()?.trim() ?: "Phim"
        val altTitle = doc.selectFirst("h2")?.text()?.trim()
        val poster   = imgUrl(doc.selectFirst(".film-info img[itemprop=image]"))
        val plotOriginal = getPlot(doc)

        // ===== EXTRACT TỪ entry-meta (Cấu trúc đúng của phimmoichill.you) =====
        fun metaValue(label: String): String {
            val items = doc.select("ul.entry-meta li, ul.block-film li")
            for (item in items) {
                val lbl = item.selectFirst("label")
                if (lbl != null && lbl.text().contains(label, ignoreCase = true)) {
                    return item.text().substringAfter(lbl.text()).trim()
                }
            }
            return ""
        }

        // Đang phát: "HD - Vietsub" hoặc "HD - Thuyết Minh" hoặc "Trailer"
        val dangPhat = metaValue("Đang phát")
        val quality = Regex("""(HD|FHD|4K|UHD|CAM|FULL HD|SD|Trailer)""").find(dangPhat)?.value ?: ""
        val status  = Regex("""(Hoàn Tất|Tập\s*\d+/\d+|Tập\s*\d+|Vietsub|Thuyết Minh|Trailer)""").find(dangPhat)?.value
            ?: if (quality.isNotBlank()) "$quality - Vietsub" else null

        // Năm
        val year = metaValue("Năm Phát Hành")
                      ?.let { Regex("""(19\d{2}|20\d{2})""").find(it)?.value?.toIntOrNull() }

        // Quốc gia
        val country = doc.selectFirst("ul.entry-meta a[href*='/country/']")?.text()?.trim()
                          ?: metaValue("Quốc gia")

        // Thể loại
        val tags = doc.select("ul.entry-meta a[href*='/genre/']")
                       .map { it.text().trim() }.filter { it.isNotBlank() }

        // Đạo diễn
        val director = doc.select("ul.entry-meta a[href*='/dao-dien/']")
                           .joinToString(", ") { it.text().trim() }.ifBlank { metaValue("Đạo diễn") }

        // Diễn viên
        val cast = doc.select("ul.entry-meta a[href*='/dien-vien/']")
                      .take(5).joinToString(", ") { it.text().trim() }.ifBlank { metaValue("Diễn viên") }

        // Thời lượng
        val duration = metaValue("Thời lượng")

        // IMDb score
        val imdbScore = doc.selectFirst("ul.entry-meta .imdb, ul.entry-meta span.imdb")
                           ?.text()?.trim()

        // Số tập: tìm từ status
        val totalEpisodes = Regex("""Tập\s*(\d+)\s*/\s*\d+""").find(dangPhat)?.groupValues?.get(1)?.toIntOrNull()

        val theLoai    = tags.joinToString(", ")
        val namPhatHanh = year?.toString() ?: ""

        // ===== TẠO MÔ TẢ HTML (giống y NguonC) =====
        val description = buildBeautifulDescription(
            altTitle, status, totalEpisodes, duration, quality, country,
            namPhatHanh, director, cast, theLoai, plotOriginal
        )

        val watchUrl = doc.selectFirst("a.btn-see[href*='/xem/']")?.attr("href")?.let { fixUrl(it) }
        val hasEps   = doc.select("div.latest-episode a[data-id], ul.episodes li a").isNotEmpty()

        if (!hasEps && watchUrl == null) return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster; this.plot = description; this.year = year; this.tags = tags
        }

        val eps = (watchUrl?.let { wu ->
            try {
                val watchDoc = app.get(wu, headers = headers).document
                watchDoc.select("ul.episodes li a, div.list-episode a").mapNotNull { a ->
                    val href  = fixUrl(a.attr("href"))
                    val name  = a.text().trim()
                    val epNum = Regex("""Tập\s*(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                                ?: Regex("""(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    newEpisode(href) { this.name = name; this.episode = epNum }
                }
            } catch (_: Exception) { emptyList() }
        } ?: emptyList()).ifEmpty {
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                newEpisode(fixUrl(a.attr("href"))) {
                    this.name    = a.text()
                    this.episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                }
            }
        }

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            eps.ifEmpty { watchUrl?.let { listOf(newEpisode(it) { name = "Tập 1" }) } ?: emptyList() }
        ) {
            this.posterUrl = poster; this.plot = description; this.year = year; this.tags = tags
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  TẠO MÔ TẢ HTML ĐẸP — Giống y hệt NguonC style
    // ════════════════════════════════════════════════════════════════
    private fun buildBeautifulDescription(
        altTitle: String?,
        currentEpisode: String?,
        totalEpisodes: Int?,
        time: String?,
        quality: String?,
        country: String?,
        year: String?,
        director: String?,
        cast: String?,
        genre: String?,
        description: String?
    ): String {
        return buildString {
            altTitle?.takeIf { it.isNotBlank() }?.let {
                append("<font color='#AAAAAA'><i>$it</i></font><br><br>")
            }

            fun addInfo(icon: String, label: String, value: String?, color: String = "#FFFFFF") {
                if (!value.isNullOrBlank()) {
                    append("$icon <b>$label:</b> <font color='$color'>$value</font><br>")
                }
            }

            val statusColor = if (currentEpisode?.contains("hoàn tất", true) == true ||
                                 currentEpisode?.contains("Hoàn Tất", true) == true) "#4CAF50" else "#2196F3"

            addInfo("📺", "Trạng thái", currentEpisode, statusColor)
            if (totalEpisodes != null && totalEpisodes != 0)
                addInfo("🎞", "Số tập", "$totalEpisodes tập")

            addInfo("⏱", "Thời lượng", time)
            addInfo("🎬", "Chất lượng", quality?.ifBlank { null }, "#E91E63")
            addInfo("🌍", "Quốc gia", country)
            addInfo("📅", "Năm", year?.ifBlank { null })
            addInfo("🎥", "Đạo diễn", director)
            addInfo("🎭", "Diễn viên", cast)
            addInfo("🏷", "Thể loại", genre)

            description?.takeIf { it.isNotBlank() }?.let {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<hr color='#333333' size='1'><br>")
                append(it.trim())
            }
        }
    }

    private fun getPlot(doc: org.jsoup.nodes.Document): String? {

        doc.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.let {
            if (it.isNotEmpty()) return it
        }

        val filmContent = doc.selectFirst("#film-content")
        if (filmContent != null) {

            val clone = filmContent.clone()

            clone.selectFirst("a")?.remove()

            clone.select(".hidden").remove()
            val text = clone.text()?.trim()
            if (!text.isNullOrEmpty()) return text
        }

        try {
            doc.select("script[type='application/ld+json']").forEach { script ->
                val json = script.data()
                val descMatch = Regex(""""description"\s*:\s*"([^"]+)"""").find(json)
                descMatch?.groupValues?.get(1)?.trim()?.let {
                    if (it.isNotEmpty()) return it
                }
            }
        } catch (_: Exception) {}

        return null
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
