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

    private val pageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.5"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    private fun fixUrl(url: String): String = when {
        url.isBlank() -> url
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
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

    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a = el.selectFirst("a[href*='/info/']") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").trim().ifEmpty { el.selectFirst("h3, p")?.text()?.trim() ?: return null }
        val poster = imgUrl(el.selectFirst("img"))
        val labelText = el.selectFirst("span.label")?.text()?.trim()?.uppercase() ?: ""

        val isSeries = labelText.contains("TẬP") || labelText.contains("HOÀN TẤT") || Regex("""\d+/\d+""").containsMatchIn(labelText)
        val has4kBadge = el.selectFirst("span.film-badge.badge-4k") != null
        val quality = when {
            has4kBadge || labelText.contains("4K") || labelText.contains("UHD") -> SearchQuality.UHD
            labelText.contains("HD") -> SearchQuality.HD
            labelText.contains("CAM") -> SearchQuality.Cam
            else -> SearchQuality.HD
        }

        val hasTMBadge = el.selectFirst("span.film-badge.badge-tm") != null
        val dubs = java.util.EnumSet.noneOf(DubStatus::class.java).apply {
            if (labelText.contains("VIETSUB") || !labelText.contains("RAW")) add(DubStatus.Subbed)
            if (labelText.contains("THUYẾT MINH") || hasTMBadge) add(DubStatus.Dubbed)
        }

        return newAnimeSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            this.quality = quality
            this.dubStatus = dubs
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        val doc = app.get(url, headers = pageHeaders).document
        val items = doc.select("li.item").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}", headers = pageHeaders).document
        return doc.select("li.item").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = pageHeaders).document
        val title = doc.selectFirst("h1[itemprop=name], .film-info h1, h1")?.text()?.trim() ?: "Phim"
        val poster = imgUrl(doc.selectFirst("img[itemprop=image], .film-poster img"))
        val description = doc.selectFirst("[itemprop=description], #film-content, .film-content")?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        val firstWatchUrl = doc.selectFirst("a.btn-see[href*='/xem/'], a[href*='/xem/'].btn-danger")?.attr("href")?.let { fixUrl(it) }
        val hasEpisodes = doc.select("div.latest-episode a[data-id]").isNotEmpty()

        if (!hasEpisodes) {
            return newMovieLoadResponse(title, url, TvType.Movie, firstWatchUrl ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }

        val episodes = if (firstWatchUrl != null) {
            try {
                val watchDoc = app.get(firstWatchUrl, headers = pageHeaders).document
                watchDoc.select("ul.episodes li a[data-id]").mapNotNull { a ->
                    val epUrl = fixUrl(a.attr("href"))
                    val epName = a.text().trim()
                    if (epUrl.isBlank()) null
                    else newEpisode(epUrl) {
                        this.name = epName
                        this.episode = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                    }
                }.distinctBy { it.data }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val finalEpisodes = episodes.ifEmpty {
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                newEpisode(fixUrl(a.attr("href"))) {
                    this.name = a.text().trim()
                    this.episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull()
                }
            }.ifEmpty { firstWatchUrl?.let { listOf(newEpisode(it) { name = "Tập 1"; episode = 1 }) } ?: emptyList() }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data, headers = pageHeaders)
        val html = pageResp.text
        val cookies = pageResp.cookies

        // Extract episode ID từ URL: /xem/...-pm126234 → 126234
        val episodeId = Regex("""[/-]pm(\d+)""").find(data.substringBefore("?"))?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)

        if (episodeId == null) return false

        val postHeaders = ajaxHeaders + mapOf("Referer" to data, "Origin" to mainUrl)

        // Thử tất cả server sv=0,1,2,3
        for (sv in 0..3) {
            try {
                val resp = app.post(
                    "$mainUrl/chillsplayer.php",
                    data = mapOf("qcao" to episodeId, "sv" to sv.toString()),
                    headers = postHeaders,
                    cookies = cookies
                ).text

                // FIX: Regex mới match iniPlayers("key",2,) hoặc iniPlayers("key",2)
                val key = Regex("""iniPlayers\s*\(\s*["']([a-fA-F0-9]+)["']\s*,?\s*\d*[,)]""")
                    .find(resp)?.groupValues?.get(1)

                if (!key.isNullOrEmpty() && key.length >= 20) {
                    // Tạo các link server
                    listOf(
                        "PMHLS" to "https://sotrim.topphimmoi.org/raw/$key/index.m3u8",
                        "PMPRO" to "https://dash.megacdn.xyz/raw/$key/index.m3u8",
                        "PMBK" to "https://dash.megacdn.xyz/dast/$key/index.m3u8"
                    ).forEach { (name, m3u8Url) ->
                        callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        })
                    }
                    return true
                }

                // Fallback: tìm direct m3u8 trong response
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(resp).forEach { m ->
                    callback(newExtractorLink("Server $sv", "Server $sv", m.value, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    })
                }
            } catch (_: Exception) { }
        }

        return false
    }
}
