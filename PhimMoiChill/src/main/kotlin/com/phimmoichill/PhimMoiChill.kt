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
        "Accept" to "text/html,application/xhtml+xml,*/*"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
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
        val label = el.selectFirst("span.label")?.text()?.uppercase() ?: ""
        val isSeries = label.contains("TẬP") || label.contains("HOÀN TẤT") || Regex("""\d+/\d+""").containsMatchIn(label)
        val has4k = el.selectFirst("span.film-badge.badge-4k") != null
        val hasTM = el.selectFirst("span.film-badge.badge-tm") != null
        val quality = when {
            has4k || label.contains("4K") -> SearchQuality.UHD
            label.contains("CAM") -> SearchQuality.Cam
            else -> SearchQuality.HD
        }
        val dubs = java.util.EnumSet.noneOf(DubStatus::class.java).apply {
            if (!label.contains("RAW")) add(DubStatus.Subbed)
            if (label.contains("THUYẾT MINH") || hasTM) add(DubStatus.Dubbed)
        }
        return newAnimeSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            this.quality = quality
            this.dubStatus = dubs
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
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
        val hasEps = doc.select("div.latest-episode a[data-id]").isNotEmpty()

        if (!hasEps) return newMovieLoadResponse(title, url, TvType.Movie, watchUrl ?: url) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = genres
        }

        val eps = (watchUrl?.let { wu ->
            try { app.get(wu, headers = headers).document.select("ul.episodes li a[data-id]").mapNotNull { a ->
                newEpisode(fixUrl(a.attr("href"))) { name = a.text(); episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull() }
            }} catch (_: Exception) { emptyList() }
        } ?: emptyList()).ifEmpty {
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                newEpisode(fixUrl(a.attr("href"))) { name = a.text(); episode = Regex("""\d+""").find(a.text())?.value?.toIntOrNull() }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.ifEmpty { watchUrl?.let { listOf(newEpisode(it) { name = "Tập 1" }) } ?: emptyList() }) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = genres
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(data, headers = headers)
        val html = res.text
        val cookies = res.cookies

        val epId = Regex("""[/-]pm(\d+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)

        if (epId == null) return false

        val postHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to data,
            "Origin" to mainUrl
        )

        var foundLinks = false

        // Lấy Vietsub (không có quality_index)
        val vietsubKey = fetchKey(epId, null, cookies, postHeaders)
        if (!vietsubKey.isNullOrEmpty()) {
            addLinks("Vietsub", vietsubKey, callback)
            foundLinks = true
        }

        // Lấy Thuyết Minh (quality_index=1)
        val tmKey = fetchKey(epId, "1", cookies, postHeaders)
        if (!tmKey.isNullOrEmpty() && tmKey != vietsubKey) {
            addLinks("Thuyết Minh", tmKey, callback)
            foundLinks = true
        }

        return foundLinks
    }

    private suspend fun fetchKey(epId: String, qualityIndex: String?, cookies: Map<String, String>, postHeaders: Map<String, String>): String? {
        for (sv in 0..3) {
            try {
                val payload = mutableMapOf("qcao" to epId, "sv" to sv.toString())
                if (qualityIndex != null) payload["quality_index"] = qualityIndex

                val resp = app.post("$mainUrl/chillsplayer.php", data = payload, headers = postHeaders, cookies = cookies).text
                val key = Regex("""iniPlayers\s*\(\s*["']([a-fA-F0-9]+)["']""").find(resp)?.groupValues?.get(1)
                if (!key.isNullOrEmpty() && key.length >= 20) return key
            } catch (_: Exception) { }
        }
        return null
    }

    private fun addLinks(serverName: String, key: String, callback: (ExtractorLink) -> Unit) {
        listOf(
            "$serverName - PMHLS" to "https://sotrim.topphimmoi.org/mpeg/$key/index.m3u8",
            "$serverName - PMPRO" to "https://dash.megacdn.xyz/mpeg/$key/index.m3u8"
        ).forEach { (name, m3u8Url) ->
            callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            })
        }
    }
}
