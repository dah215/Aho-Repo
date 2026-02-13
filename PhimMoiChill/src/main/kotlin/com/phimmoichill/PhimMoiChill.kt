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
    override var mainUrl = "https://phimmoichill.now"
    override var name = "PhimMoiChill"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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
        "list/phim-bo"            to "Phim Bộ"
    )

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
     * Parse card → AnimeSearchResponse (duy nhất loại hỗ trợ cả quality badge + dub/sub badge)
     *
     * HTML label thực tế từ site:
     *   "HD - Vietsub"        → Movie, HD badge + Sub badge
     *   "4K - Vietsub"        → Movie, 4K badge + Sub badge
     *   "CAM - Vietsub"       → Movie, CAM badge + Sub badge
     *   "Tập 3/12 - Vietsub"  → Series, HD badge + Sub badge
     *   "Hoàn Tất (12/12)"    → Series, HD badge (check TM badge cho Dub)
     *   badge-tm trên card    → Cũng có Thuyết Minh → thêm Dub badge
     *   badge-4k trên card    → UHD/4K badge
     */
    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a     = el.selectFirst("a[href*='/info/']") ?: return null
        val href  = fixUrl(a.attr("href"))
        val title = a.attr("title").trim().ifEmpty {
            el.selectFirst("h3, p")?.text()?.trim() ?: return null
        }
        val poster = imgUrl(el.selectFirst("img"))

        // Lấy text label + alt text
        val labelText  = el.selectFirst("span.label")?.text()?.trim() ?: ""
        val labelUpper = labelText.uppercase()
        val altText    = el.selectFirst("img")?.attr("alt")?.uppercase() ?: ""

        // Detect series: label có "Tập", "/" với số, "Hoàn Tất"
        val isSeries = labelUpper.run {
            contains("TẬP") || contains("HOÀN TẤT") || contains("FULL (") ||
            (Regex("""\d+/\d+""").containsMatchIn(this))
        }

        // Quality: ưu tiên badge-4k > label text > alt text
        val has4kBadge = el.selectFirst("span.film-badge.badge-4k") != null
        val quality: SearchQuality? = when {
            has4kBadge                                              -> SearchQuality.UHD
            labelUpper.contains("4K") || labelUpper.contains("UHD")  -> SearchQuality.UHD
            altText.contains("4K") || altText.contains("UHD")       -> SearchQuality.UHD
            labelUpper.contains("FULL HD")                          -> SearchQuality.HD
            labelUpper.contains("HD") || altText.contains(" HD ")   -> SearchQuality.HD
            labelUpper.contains("CAM")                              -> SearchQuality.Cam
            labelUpper.contains("SD") || labelUpper.contains("480") -> SearchQuality.SD
            else                                                    -> SearchQuality.HD // default
        }

        // DubStatus: Subbed = Vietsub, Dubbed = Thuyết Minh
        // badge-tm = có ít nhất 1 tập thuyết minh
        val hasTMBadge    = el.selectFirst("span.film-badge.badge-tm") != null
        val hasVietsub    = labelUpper.contains("VIETSUB") || altText.contains("VIETSUB")
        val hasThuyetMinh = labelUpper.contains("THUYẾT MINH") || 
                            labelUpper.contains("LỒNG TIẾNG") || hasTMBadge

        val dubs = EnumSet<DubStatus>().apply {
            // Thêm Subbed nếu có Vietsub hoặc không rõ (mặc định site này là Vietsub)
            if (hasVietsub || (!hasThuyetMinh && !labelUpper.contains("RAW"))) {
                add(DubStatus.Subbed)
            }
            if (hasThuyetMinh) add(DubStatus.Dubbed)
        }

        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        // Dùng newAnimeSearchResponse để hiện ĐỒNG THỜI quality badge + Sub/Dub badge
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.quality   = quality
            this.dubStatus = dubs
        }
    }

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

    override suspend fun load(url: String): LoadResponse {
        val doc  = app.get(url, headers = pageHeaders).document
        val html = doc.html()

        val title = doc.selectFirst("h1[itemprop=name], .film-info h1, h1")
            ?.text()?.trim() ?: "Phim"
        val poster = imgUrl(
            doc.selectFirst("img[itemprop=image], img[itemprop=thumbnailUrl], .image img, .film-poster img")
        )
        val description = doc.selectFirst(
            "[itemprop=description], #film-content, .film-content, .film-info p"
        )?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        val htmlLow = html.lowercase()
        val tags = genres.toMutableList().apply {
            if (htmlLow.contains("vietsub"))                          add("Vietsub")
            if (htmlLow.contains("thuyết minh") || 
                htmlLow.contains("lồng tiếng"))                      add("Thuyết Minh")
        }

        val firstWatchUrl = doc.selectFirst(
            "a.btn-see[href*='/xem/'], a[href*='/xem/'].btn-danger, a[href*='/xem/'].btn-primary"
        )?.attr("href")?.let { fixUrl(it) }

        val hasEpisodes = doc.select("div.latest-episode a[data-id]").isNotEmpty()

        if (!hasEpisodes) {
            val watchUrl = firstWatchUrl ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        }

        val episodes = if (firstWatchUrl != null) {
            try {
                val watchDoc = app.get(firstWatchUrl, headers = pageHeaders).document
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data, headers = pageHeaders)
        val html     = pageResp.text
        val cookies  = pageResp.cookies

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

        listOf(
            "PMHLS" to "https://sotrim.topphimmoi.org/raw/$key/index.m3u8",
            "PMPRO" to "https://dash.megacdn.xyz/raw/$key/index.m3u8",
            "PMBK"  to "https://dash.megacdn.xyz/dast/$key/index.m3u8"
        ).forEach { (serverName, m3u8Url) ->
            callback(newExtractorLink(
                source = serverName,
                name   = serverName,
                url    = m3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            })
        }

        return true
    }
}
