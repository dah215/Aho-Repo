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

    private val pageHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept"          to "text/html,application/xhtml+xml,*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.5"
    )
    private val ajaxHeaders = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept"           to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi"           to "Phim Mới",
        "list/phim-le"            to "Phim Lẻ",
        "list/phim-bo"            to "Phim Bộ",
        "genre/phim-hanh-dong"    to "Hành Động",
        "country/phim-han-quoc"   to "Phim Hàn",
        "country/phim-trung-quoc" to "Phim Trung",
        "country/phim-au-my"      to "Phim Mỹ"
    )

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> "$mainUrl$url"
            else                   -> "$mainUrl/$url"
        }
    }

    private fun imgUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        for (attr in listOf("data-src", "data-original", "src")) {
            val v = el.attr(attr)
            if (!v.isNullOrEmpty() && !v.startsWith("data:image") && v.length > 5)
                return fixUrl(v)
        }
        return null
    }

    private fun episodeIdFromUrl(url: String): String? =
        Regex("""[/-]pm(\d+)(?:[^0-9]|$)""")
            .find(url.substringBefore("?"))
            ?.groupValues?.get(1)

    /**
     * Parse span.label text để lấy chất lượng và loại phim.
     *
     * Các dạng text thực tế:
     *   "HD - Vietsub"          → Movie, HD
     *   "4K - Vietsub"          → Movie, UHD
     *   "CAM - Vietsub"         → Movie, Cam
     *   "Tập 3/12 - Vietsub"    → TvSeries, HD
     *   "Tập 18/34"             → TvSeries, HD
     *   "Hoàn Tất (12/12)"      → TvSeries, HD
     *   "Hoàn Tất(13/13)"       → TvSeries, HD
     */
    data class LabelInfo(val quality: SearchQuality?, val isSeries: Boolean, val subInfo: String?)

    private fun parseLabelText(el: org.jsoup.nodes.Element): LabelInfo {
        // Lấy text từ span.label (kể cả nested div.status)
        val labelEl = el.selectFirst("span.label") ?: return LabelInfo(null, false, null)
        val text = labelEl.text().trim()

        if (text.isBlank()) return LabelInfo(null, false, null)
        val upper = text.uppercase()

        // Detect series: có "Tập", "/", "Hoàn Tất", "Đang chiếu", "Tập full"
        val isSeries = upper.contains("TẬP") || 
                       (upper.contains("/") && !upper.startsWith("HD") && !upper.startsWith("4K")) ||
                       upper.contains("HOÀN TẤT") || 
                       upper.contains("HOÀN TẤT") ||
                       upper.contains("FULL (")

        // Detect quality
        val quality = when {
            upper.contains("4K") || upper.contains("UHD")       -> SearchQuality.UHD
            upper.contains("FULL HD") || upper.contains("1080") -> SearchQuality.HD
            upper.contains("HD")                                -> SearchQuality.HD
            upper.contains("CAM")                               -> SearchQuality.Cam
            upper.contains("SD") || upper.contains("480")       -> SearchQuality.SD
            // Nếu là series (Tập X/Y), mặc định hiện HD
            isSeries                                             -> SearchQuality.HD
            else                                                 -> null
        }

        // Kiểm tra badge-4k riêng (badge thêm bên ngoài label)
        val has4k = el.selectFirst("span.film-badge.badge-4k") != null
        val finalQuality = if (has4k) SearchQuality.UHD else quality

        // Sub/dub info từ label text
        val subInfo = when {
            upper.contains("VIETSUB")       -> "Vietsub"
            upper.contains("THUYẾT MINH") || 
            upper.contains("THUYẾT MINH")   -> "Thuyết Minh"
            else -> null
        }

        return LabelInfo(finalQuality, isSeries, subInfo)
    }

    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a      = el.selectFirst("a[href*='/info/']") ?: return null
        val href   = fixUrl(a.attr("href"))
        val title  = a.attr("title").trim().ifEmpty {
            el.selectFirst("h3, p")?.text()?.trim() ?: return null
        }
        val poster = imgUrl(el.selectFirst("img"))
        val info   = parseLabelText(el)

        return if (info.isSeries) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality   = info.quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality   = info.quality
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // MAIN PAGE & SEARCH
    // ─────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = if (page <= 1) "$mainUrl/${request.data}"
                   else           "$mainUrl/${request.data}?page=$page"
        val doc  = app.get(url, headers = pageHeaders).document
        val items = doc.select("li.item").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "utf-8")
        val doc = app.get("$mainUrl/tim-kiem/$encoded", headers = pageHeaders).document
        return doc.select("li.item").mapNotNull { parseCard(it) }
    }

    // ─────────────────────────────────────────────────────────────────
    // LOAD (info page → episode list)
    // ─────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc  = app.get(url, headers = pageHeaders).document
        val html = doc.html()

        val title = doc.selectFirst("h1[itemprop=name], .film-info h1, h1")
            ?.text()?.trim() ?: "Phim"
        val poster = imgUrl(
            doc.selectFirst("img[itemprop=image], img[itemprop=thumbnailUrl], .image img, .film-poster img")
        )
        val description = doc.selectFirst("[itemprop=description], #film-content, .film-content")
            ?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        // Tags: Vietsub / Thuyết Minh
        val htmlLow = html.lowercase()
        val tags = genres.toMutableList().apply {
            if (htmlLow.contains("vietsub"))                           add("Vietsub")
            if (htmlLow.contains("thuyết minh") || 
                htmlLow.contains("lồng tiếng"))                       add("Thuyết Minh")
        }

        // Link xem đầu tiên (nút "Xem phim")
        val firstWatchUrl = doc.selectFirst(
            "a.btn-see[href*='/xem/'], a[href*='/xem/'].btn-danger, a[href*='/xem/'].btn-primary"
        )?.attr("href")?.let { fixUrl(it) }

        // Detect series: có link tập trong div.latest-episode
        val hasEpisodes = doc.select("div.latest-episode a[data-id], .episode-manager a[data-id]").isNotEmpty()

        // ── PHIM LẺ ──────────────────────────────────────────────────
        if (!hasEpisodes) {
            val watchUrl = firstWatchUrl ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        }

        // ── PHIM BỘ: load watch page lấy danh sách tập ──────────────
        val episodes = if (firstWatchUrl != null) {
            try {
                val watchDoc = app.get(firstWatchUrl, headers = pageHeaders).document
                // Selector chính xác: ul.episodes#list_episodes li a[data-id]
                watchDoc.select("ul.episodes li a[data-id]").mapNotNull { a ->
                    val epUrl  = fixUrl(a.attr("href"))
                    val epName = a.text().trim()
                    if (epUrl.isBlank()) null
                    else newEpisode(epUrl) {
                        this.name    = epName
                        this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                    }
                }.distinctBy { it.data }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        // Fallback nếu watch page không lấy được
        val finalEpisodes = episodes.ifEmpty {
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                val epUrl  = fixUrl(a.attr("href"))
                val epName = a.text().trim()
                if (epUrl.isBlank()) null
                else newEpisode(epUrl) {
                    this.name    = epName
                    this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                }
            }.distinctBy { it.data }.ifEmpty {
                firstWatchUrl?.let { listOf(newEpisode(it) { this.name = "Tập 1"; this.episode = 1 }) }
                    ?: emptyList()
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LOAD LINKS (watch page → video URL)
    //
    // Cơ chế:
    //   POST /chillsplayer.php  {qcao: episodeID, sv: serverIndex}
    //   Response HTML chứa: iniPlayers("KEY")
    //   CDN: sotrim.topphimmoi.org / dash.megacdn.xyz
    //
    // Chỉ dùng /raw/ và /dast/ (bỏ /manifest/ để tránh lỗi)
    // ─────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data, headers = pageHeaders)
        val html     = pageResp.text
        val cookies  = pageResp.cookies

        // Lấy episodeID
        val episodeId =
            Regex("""filmInfo\.episodeID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""chillplay\s*\(\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: episodeIdFromUrl(data)
                ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
                ?: return false

        val postHeaders = ajaxHeaders + mapOf(
            "Referer" to data,
            "Origin"  to mainUrl
        )

        // Lấy KEY từ server đầu tiên (sv=0), rồi dùng cho tất cả server
        var key: String? = null
        for (sv in 0..3) {
            try {
                val resp = app.post(
                    "$mainUrl/chillsplayer.php",
                    data    = mapOf("qcao" to episodeId, "sv" to sv.toString()),
                    headers = postHeaders,
                    cookies = cookies
                ).text

                if (resp.isBlank()) continue

                key = Regex("""iniPlayers\s*\(\s*["']([A-Za-z0-9+/=_\-]{8,})["']""")
                    .find(resp)?.groupValues?.get(1)

                if (!key.isNullOrEmpty()) break

                // Fallback: m3u8 trực tiếp trong response
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(resp).forEach { m ->
                    callback(newExtractorLink(
                        source = "Server ${sv + 1}",
                        name   = "Server ${sv + 1}",
                        url    = m.value,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    })
                }
            } catch (_: Exception) { continue }
        }

        if (key.isNullOrEmpty()) return false

        // 4 server với URL /raw/ hoặc /dast/ (bỏ /manifest/ gây lỗi)
        // sv=0 PMFAST: sotrim /raw/ (dùng raw thay manifest để tránh lỗi)
        // sv=1 PMHLS:  sotrim /raw/
        // sv=2 PMPRO:  megacdn /raw/
        // sv=3 PMBK:   megacdn /dast/
        val serverUrls = listOf(
            "PMHLS"  to "https://sotrim.topphimmoi.org/raw/$key/index.m3u8",
            "PMPRO"  to "https://dash.megacdn.xyz/raw/$key/index.m3u8",
            "PMBK"   to "https://dash.megacdn.xyz/dast/$key/index.m3u8"
        )

        var added = false
        for ((serverName, m3u8Url) in serverUrls) {
            callback(newExtractorLink(
                source = serverName,
                name   = serverName,
                url    = m3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            })
            added = true
        }

        return added
    }
}
