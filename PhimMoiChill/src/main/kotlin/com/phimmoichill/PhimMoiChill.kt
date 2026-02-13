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

    // ── Headers ─────────────────────────────────────────────────────
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

    // ── Danh sách trang chính ────────────────────────────────────────
    override val mainPage = mainPageOf(
        "list/phim-moi"          to "Phim Mới",
        "list/phim-le"           to "Phim Lẻ",
        "list/phim-bo"           to "Phim Bộ",
        "genre/phim-hanh-dong"   to "Hành Động",
        "country/phim-han-quoc"  to "Phim Hàn",
        "country/phim-trung-quoc" to "Phim Trung"
    )

    // ── Helpers ──────────────────────────────────────────────────────

    /** Lấy URL ảnh, hỗ trợ lazy-load (data-src) */
    private fun imgUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        for (attr in listOf("data-src", "data-original", "src")) {
            val v = el.attr(attr)
            if (!v.isNullOrEmpty() && !v.startsWith("data:image") && v.length > 5)
                return fixUrl(v)
        }
        return null
    }

    /** Chuẩn hóa URL tương đối → tuyệt đối */
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> "$mainUrl$url"
            else                   -> "$mainUrl/$url"
        }
    }

    /**
     * Trích xuất episodeID từ URL xem phim.
     * Ví dụ: /xem/film-tap-1-pm126172 → "126172"
     */
    private fun episodeIdFromUrl(url: String): String? =
        Regex("""[/-]pm(\d+)(?:[^0-9]|$)""")
            .find(url.substringBefore("?"))
            ?.groupValues?.get(1)

    /**
     * Lấy SearchQuality từ nội dung span.label.
     *
     * HTML phim lẻ : <span class="label"><div class="status">HD - Vietsub</div></span>
     * HTML phim bộ : <span class="label">Tập 5/20 - Vietsub</span>
     */
    private fun qualityFromLabel(el: org.jsoup.nodes.Element?): SearchQuality? {
        val t = el?.selectFirst("span.label")?.text()?.uppercase() ?: return null
        return when {
            t.contains("4K") || t.contains("UHD")          -> SearchQuality.UHD
            t.contains("FULL HD") || t.contains("1080")    -> SearchQuality.HD
            t.contains("HD")                               -> SearchQuality.HD
            t.contains("CAM")                              -> SearchQuality.Cam
            t.contains("SD") || t.contains("480")         -> SearchQuality.SD
            else                                           -> null
        }
    }

    /** Parse 1 card item → SearchResponse */
    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a = el.selectFirst("a[href*='/info/']") ?: return null
        val href   = fixUrl(a.attr("href"))
        val title  = a.attr("title").trim().ifEmpty {
            el.selectFirst("h3, p")?.text()?.trim() ?: return null
        }
        val poster = imgUrl(el.selectFirst("img"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.quality   = qualityFromLabel(el)
        }
    }

    // ── Trang chính ──────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = if (page <= 1) "$mainUrl/${request.data}"
                   else           "$mainUrl/${request.data}?page=$page"
        val doc  = app.get(url, headers = pageHeaders).document
        val items = doc.select("li.item").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Tìm kiếm ─────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "utf-8")
        val doc = app.get("$mainUrl/tim-kiem/$encoded", headers = pageHeaders).document
        return doc.select("li.item").mapNotNull { parseCard(it) }
    }

    // ── Tải thông tin phim (info page) ───────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc  = app.get(url, headers = pageHeaders).document
        val html = doc.html()

        val title = doc.selectFirst("h1[itemprop=name], .film-info h1, h1")
            ?.text()?.trim() ?: "Phim"
        val poster = imgUrl(doc.selectFirst("img[itemprop=image], .image img, .film-poster img"))
        val description = doc.selectFirst("#film-content, .film-content")?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }.distinct()

        // Tags: Phụ đề / Thuyết minh
        val htmlLow = html.lowercase()
        val tags = genres.toMutableList().apply {
            if (htmlLow.contains("vietsub") || htmlLow.contains("phụ đề")) add("Phụ đề")
            if (htmlLow.contains("thuyết minh") || htmlLow.contains("lồng tiếng")) add("Thuyết minh")
        }

        // Link xem đầu tiên từ nút "Xem phim"
        val firstWatchUrl = doc.selectFirst("a.btn-see[href*='/xem/'], a[href*='/xem/'].btn-danger")
            ?.attr("href")?.let { fixUrl(it) }

        // Kiểm tra phim có nhiều tập không (div.latest-episode chứa link)
        val hasEpisodes = doc.select("div.latest-episode a[data-id]").isNotEmpty()

        // ── PHIM LẺ (không có danh sách tập) ─────────────────────────
        if (!hasEpisodes) {
            val watchUrl = firstWatchUrl ?: url
            return newTvSeriesLoadResponse(title, url, TvType.Movie, listOf(
                newEpisode(watchUrl) { this.name = "Xem phim"; this.episode = 1 }
            )) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.type      = TvType.Movie
            }
        }

        // ── PHIM BỘ: load watch page để lấy toàn bộ danh sách tập ────
        val episodes = if (firstWatchUrl != null) {
            try {
                val watchDoc = app.get(firstWatchUrl, headers = pageHeaders).document
                // FIX: Selector đúng theo HTML thực: ul.episodes#list_episodes li a[data-id]
                watchDoc.select("ul.episodes li a[data-id]")
                    .mapNotNull { a ->
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

        // Fallback nếu watch page không có danh sách
        val finalEpisodes = if (episodes.isEmpty()) {
            doc.select("div.latest-episode a[data-id]").mapNotNull { a ->
                val epUrl = fixUrl(a.attr("href"))
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
        } else episodes

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    // ── Tải link video (watch page) ──────────────────────────────────
    //
    // Cơ chế thực tế (xác nhận từ phimchill.public.js):
    //   chillplay(episodeID)   →  POST /chillsplayer.php  {qcao: episodeID}
    //   ChangePlayer(id, sv)   →  POST /chillsplayer.php  {qcao: episodeID, sv: serverIndex}
    //
    // Response: HTML chứa iniPlayers("KEY") → KEY dùng để ghép m3u8
    // CDN servers: PMFAST=0, PMHLS=1, PMPRO=2, PMBK=3
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResp = app.get(data, headers = pageHeaders)
        val html     = pageResp.text
        val cookies  = pageResp.cookies

        // Lấy episodeID — ưu tiên từ filmInfo.episodeID = parseInt('...')
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

        // Danh sách server: name, sv index, CDN URL pattern
        // sv=0 → PMFAST, sv=1 → PMHLS, sv=2 → PMPRO, sv=3 → PMBK
        val servers = listOf(
            Triple("PMFAST", "0",  { key: String -> "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" }),
            Triple("PMHLS",  "1",  { key: String -> "https://sotrim.topphimmoi.org/raw/$key/index.m3u8" }),
            Triple("PMPRO",  "2",  { key: String -> "https://dash.megacdn.xyz/raw/$key/index.m3u8" }),
            Triple("PMBK",   "3",  { key: String -> "https://dash.megacdn.xyz/dast/$key/index.m3u8" })
        )

        var found = false

        for ((serverName, sv, urlBuilder) in servers) {
            try {
                val resp = app.post(
                    "$mainUrl/chillsplayer.php",
                    data    = mapOf("qcao" to episodeId, "sv" to sv),
                    headers = postHeaders,
                    cookies = cookies
                ).text

                if (resp.isBlank()) continue

                // Tìm KEY từ iniPlayers("KEY") trong response HTML
                val key = Regex("""iniPlayers\s*\(\s*["']([A-Za-z0-9+/=_\-]{8,})["']""")
                    .find(resp)?.groupValues?.get(1)

                if (!key.isNullOrEmpty()) {
                    val m3u8Url = urlBuilder(key)
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name   = serverName,
                            url    = m3u8Url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                    // Chỉ cần 1 server trả KEY thành công là đủ để ghép tất cả
                    // nhưng vẫn thử tiếp để lấy thêm lựa chọn
                } else {
                    // Fallback: tìm m3u8 URL trực tiếp trong response
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                        .findAll(resp).forEach { m ->
                            M3u8Helper.generateM3u8(name, m.value, data).forEach { link ->
                                callback(link)
                                found = true
                            }
                        }
                }

                // Nếu đã có KEY từ server đầu tiên, ghép URL cho tất cả server còn lại
                if (found && key != null) {
                    servers.drop(servers.indexOfFirst { it.first == serverName } + 1)
                        .forEach { (sn, _, ub) ->
                            callback(
                                newExtractorLink(
                                    source = sn,
                                    name   = sn,
                                    url    = ub(key),
                                    type   = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                        }
                    break  // Không cần POST thêm, đã có KEY
                }

            } catch (_: Exception) { continue }
        }

        return found
    }
}
