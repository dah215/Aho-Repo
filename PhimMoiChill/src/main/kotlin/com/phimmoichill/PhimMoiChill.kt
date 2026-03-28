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
        val altTitle = doc.selectFirst(".film-original-name, h2.subtitle, .sub-name")?.text()?.trim()
        val poster   = imgUrl(doc.selectFirst(".film-poster img, img[itemprop=image]"))
        val plotOriginal = getPlot(doc)

        // ===== METADATA =====
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()
                      ?.let { Regex("""(20\d{2})""").find(it)?.value?.toIntOrNull() }
        // Only get genres from the film info section, not from navigation/sidebar
        val tags = (doc.selectFirst(".film-info, .info-film, #film-info, .film-detail, ul.list-info")
                       ?: doc.selectFirst(".film-content, .content-film"))
                   ?.select("a[href*='/genre/'], a[href*='/the-loai/']")
                   ?.map { it.text().trim() }?.filter { it.isNotBlank() }
                   ?: doc.select("a[href*='/genre/']")
                        .filter { it.parents().any { p -> p.className().contains("info") || p.className().contains("detail") || p.className().contains("film") } }
                        .map { it.text().trim() }.filter { it.isNotBlank() }

        // Rating / IMDb
        val imdbScore = doc.selectFirst("[class*=imdb], .imdb-score, [data-imdb]")
                           ?.text()?.replace(Regex("[^0-9.]"), "")?.trim()
        val rtScore   = doc.selectFirst("[class*=tomato], [class*=rotten], .rotten-score")
                           ?.text()?.trim()

        // Thông tin từ bảng info
        fun infoRow(label: String): String {
            val allText = doc.select("ul.list-info li, .film-info p, table.film-info td, p")
            for (el in allText) {
                val txt = el.text()
                if (txt.contains(label, ignoreCase = true)) {
                    val child = el.selectFirst("span, b, strong, a")?.text()?.trim()
                    return child?.ifBlank { txt.substringAfter(":").trim() }
                        ?: txt.substringAfter(":").trim()
                }
            }
            return ""
        }

        // Quality: only exact badge text, never pick year or long text
        val quality = doc.select("span, .badge, .label")
                         .firstOrNull { el ->
                             val t = el.text().trim().uppercase()
                             t == "HD" || t == "FHD" || t == "4K" || t == "UHD" ||
                             t == "CAM" || t == "FULL HD" || t == "SD"
                         }?.text()?.trim() ?: ""

        val status   = doc.selectFirst(".current-episode, .episode-status, [class*=status]")
                          ?.text()?.trim()?.let {
                              if (it.contains("/") || it.any { c -> c.isDigit() }) it else null
                          }
        val views    = doc.selectFirst("[class*=view], .view-count")?.text()?.trim()
        
        val duration = infoRow("Thời lượng").ifBlank { infoRow("Duration") }
        val director = doc.select("a[href*='/director/']").joinToString(", ") { it.text() }
                          .ifBlank { infoRow("Đạo diễn") }
        val cast     = doc.select("a[href*='/actor/'], a[href*='/dien-vien/']")
                          .take(5).joinToString(", ") { it.text() }
                          .ifBlank { infoRow("Diễn viên") }

        // ===== TẠO MÔ TẢ HTML ĐẸP (y hệt AnimeVietSub style) =====
        val description = buildString {
            if (!altTitle.isNullOrBlank() && altTitle != title)
                append("<font color='#AAAAAA'><i>$altTitle</i></font><br><br>")

            // IMDb / RT
            if (!imdbScore.isNullOrBlank())
                append("<font color='#F5C518'>⭐ <b>IMDb: $imdbScore</b></font>  ")
            if (!rtScore.isNullOrBlank())
                append("<font color='#FA320A'>🍅 <b>RT: $rtScore</b></font>")
            if (!imdbScore.isNullOrBlank() || !rtScore.isNullOrBlank())
                append("<br><br>")

            // Thông tin - mỗi dòng cách nhau rõ ràng
            if (!views.isNullOrBlank())
                append("👁 <b>Lượt xem:</b> $views<br>")
            if (!status.isNullOrBlank()) {
                val sc = when {
                    status.contains("Tập", ignoreCase = true) -> "#4CAF50"
                    status.contains("Hoàn", ignoreCase = true) -> "#2196F3"
                    else -> "#FFFFFF"
                }
                append("📺 <b>Trạng thái:</b> <font color='$sc'>$status</font><br>")
            }
            if (quality.isNotBlank())
                append("🎬 <b>Chất lượng:</b> <font color='#E91E63'>$quality</font><br>")
            
            if (duration.isNotBlank())
                append("⏱ <b>Thời lượng:</b> $duration<br>")
            if (director.isNotBlank())
                append("🎥 <b>Đạo diễn:</b> $director<br>")
            if (cast.isNotBlank())
                append("🎭 <b>Diễn viên:</b> $cast<br>")
            if (tags.isNotEmpty())
                append("🏷 <b>Thể loại:</b> ${tags.take(5).joinToString(" · ")}<br>")

            if (!plotOriginal.isNullOrBlank()) {
                append("<br><b><font color='#FFEB3B'>✦ NỘI DUNG PHIM</font></b><br>")
                append("<br>")
                append(plotOriginal)
            }

            append("<br><br>")
            append("<font color='#666666' size='2'><i>Nguồn: PhimMoiChill")
            if (year != null) append("<br>Năm phát hành: $year")
            append("</i></font>")
        }

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
