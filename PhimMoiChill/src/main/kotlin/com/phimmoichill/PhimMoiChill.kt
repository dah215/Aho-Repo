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

    // ── Headers cho request lấy trang HTML ──────────────────────────────────
    // KHÔNG có X-Requested-With — tránh server trả JSON thay vì HTML
    private val pageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
    )

    // ── Headers cho AJAX call (chillsplayer.php) ────────────────────────────
    private val ajaxHeaders = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le"  to "Phim Lẻ",
        "list/phim-bo"  to "Phim Bộ"
    )

    // ════════════════════════════════════════════════════════════════════════
    // FIX LỖI 1 — fixUrl: chuyển path tương đối thành URL tuyệt đối
    //
    // Đây là nguyên nhân gốc rễ:
    //   load() lưu episodes với href="/xem/ten-phim/tap-1/" (tương đối)
    //   → loadLinks(data="/xem/...") được gọi
    //   → app.get("/xem/...") thất bại (không phải URL hợp lệ)
    //   → html rỗng → episodeId = null → return false → "Không tìm thấy liên kết"
    // ════════════════════════════════════════════════════════════════════════
    private fun fixUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> "$mainUrl$url"
            else                   -> "$mainUrl/$url"
        }
    }

    // getImageUrl — đã fix ở lần trước, giữ nguyên
    private fun getImageUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-original", "data-lazy-src", "src")
        for (attr in attrs) {
            val url = el.attr(attr).trim()
            if (url.isNotEmpty() && !url.startsWith("data:image") && url.length > 10)
                return fixUrl(url).takeIf { it.isNotEmpty() }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIX LỖI 3 — Mở rộng mapping quality text
    // Dùng `in` (contains) thay vì `==` để bắt mọi biến thể
    // Thêm "VIETSUB" / "THUYẾT MINH" để hiện badge khi chỉ có nhãn phụ đề
    // ════════════════════════════════════════════════════════════════════════
    private fun getSearchQuality(quality: String?): SearchQuality? {
        val q = quality?.uppercase()?.trim() ?: return null
        return when {
            "4K"       in q || "2160"    in q || "UHD"     in q -> SearchQuality.UHD
            "FHD"      in q || "1080"    in q || "FULL HD" in q -> SearchQuality.HD
            "HD"       in q || "720"     in q                   -> SearchQuality.HD
            "SD"       in q || "480"     in q                   -> SearchQuality.SD
            "CAM"      in q || "HDCAM"   in q                   -> SearchQuality.CAM
            "VIETSUB"  in q || "PHỤ ĐỀ" in q                   -> SearchQuality.HD
            "THUYẾT"   in q || "LỒNG"   in q                   -> SearchQuality.HD
            else -> null
        }
    }

    // FIX LỖI 3 — Selector mở rộng để bắt badge quality trên nhiều loại HTML
    private fun getQualityText(el: org.jsoup.nodes.Element): String? {
        return el.select(
            ".quality, .hd, .sd, .cam, .label, .badge, " +
            ".film-type, .type-label, .status-label, .video-quality, " +
            "span[class*=quality], span[class*=label], span[class*=type], " +
            "div[class*=quality], div[class*=label]"
        ).map { it.text().trim() }
         .firstOrNull { it.isNotEmpty() && it.length <= 20 }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url  = if (page <= 1) "$mainUrl/${request.data}"
                   else           "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = pageHeaders).text
        val doc  = Jsoup.parse(html)

        val items = doc.select(".list-film .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            // FIX LỖI 1: fixUrl() trên MỌI href
            val href = fixUrl(a.attr("href"))
            if (href.isEmpty()) return@mapNotNull null

            val title = el.selectFirst("p, .title, h3, .film-name")
                ?.text()?.trim()
                ?.ifEmpty { a.attr("title") }
                ?: a.attr("title")
            if (title.isEmpty()) return@mapNotNull null

            val poster      = getImageUrl(el.selectFirst("img"))
            val qualityText = getQualityText(el) // FIX LỖI 3

            // FIX LỖI 3: Phim Bộ dùng TvSeriesSearchResponse để hiện số tập
            val isSeries = request.data.contains("phim-bo") ||
                el.selectFirst(".episode, .ep, .num-episode, [class*=episode]") != null

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.quality   = getSearchQuality(qualityText)
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.quality   = getSearchQuality(qualityText)
                }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url  = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}"
        val html = app.get(url, headers = pageHeaders).text
        return Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a    = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href")) // FIX LỖI 1
            if (href.isEmpty()) return@mapNotNull null

            val title = el.selectFirst("p, .title, h3")?.text()?.trim()
                ?: a.attr("title")
            val poster      = getImageUrl(el.selectFirst("img"))
            val qualityText = getQualityText(el)

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality   = getSearchQuality(qualityText)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = pageHeaders).text
        val doc  = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title, h1, .caption, .film-name")
            ?.text()?.trim() ?: "Phim"
        val poster = getImageUrl(doc.selectFirst(".film-poster img, .poster img, .thumb img"))
            ?: getImageUrl(doc.selectFirst("img.poster, img[itemprop=image]"))
        val description = doc.selectFirst(".film-content, .description, .entry-content")
            ?.text()?.trim()
        val year = doc.selectFirst(".year, [itemprop=dateCreated]")
            ?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val genres = doc.select(".genre a, .categories a, [itemprop=genre]").map { it.text() }

        val tags = genres.toMutableList()
        if (html.contains("vietsub",      ignoreCase = true) ||
            html.contains("phụ đề",      ignoreCase = true)) tags.add("Phụ đề")
        if (html.contains("thuyết minh", ignoreCase = true) ||
            html.contains("lồng tiếng",  ignoreCase = true)) tags.add("Thuyết minh")

        // ════════════════════════════════════════════════════════════════════
        // FIX LỖI 1: fixUrl() bắt buộc cho TẤT CẢ href của tập phim
        // ════════════════════════════════════════════════════════════════════
        val episodes = doc.select("ul.list-episode li a, .episode-list a, a[href*='/xem/']")
            .mapNotNull { el ->
                val href = fixUrl(el.attr("href"))  // ← FIX CHÍNH
                if (href.isEmpty() || href == mainUrl || href == "$mainUrl/") return@mapNotNull null
                newEpisode(href) {
                    this.name = el.text().trim().ifEmpty { null }
                }
            }
            .distinctBy { it.data }

        return if (episodes.size <= 1) {
            // Phim lẻ: dùng MovieLoadResponse
            val watchUrl = episodes.firstOrNull()?.data
                ?: doc.select("a[href*='/xem/']")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { it.length > mainUrl.length + 5 }
                ?: url

            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        } else {
            // Phim bộ: dùng TvSeriesLoadResponse
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data luôn là URL đầy đủ (đã fixUrl ở load()) → app.get() thành công
        val pageResponse = app.get(data, headers = pageHeaders)
        val html         = pageResponse.text
        val cookies      = pageResponse.cookies

        // Tìm episodeID với nhiều pattern hơn
        val episodeId =
            Regex("""episodeID["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""episode_id["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-(?:episode-)?id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""var\s+episodeID\s*=\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&](?:id|ep|episode)=(\d+)""").find(data)?.groupValues?.get(1)
            ?: return false

        return try {
            val responseText = app.post(
                "$mainUrl/chillsplayer.php",
                data    = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = ajaxHeaders + mapOf("Referer" to data),
                cookies = cookies
            ).text

            // ════════════════════════════════════════════════════════════════
            // FIX LỖI 4: Trích xuất key an toàn — KHÔNG dùng substringAfterLast
            //
            // BUG GỐC:
            //   substringAfterLast("iniPlayers(\"") trả về NGUYÊN responseText
            //   khi "iniPlayers(\"" không tìm thấy (string operation không bao giờ null)
            //   → key = hàng nghìn ký tự → URL m3u8 invalid → MANIFEST_MALFORMED
            //
            // FIX: Chỉ dùng regex. Nếu không match → null → return false (sạch hơn)
            // Cho phép ký tự base64 (+/=) trong key pattern
            // ════════════════════════════════════════════════════════════════
            val key: String? =
                // Pattern chính: iniPlayers("KEY") hoặc iniPlayers('KEY')
                Regex("""iniPlayers\s*\(\s*["']([A-Za-z0-9+/=_\-]{8,500})["']""")
                    .find(responseText)?.groupValues?.get(1)
                // Pattern phụ: "key":"VALUE"
                ?: Regex(""""key"\s*:\s*"([A-Za-z0-9+/=_\-]{8,500})"""")
                    .find(responseText)?.groupValues?.get(1)
                // Pattern fallback: chuỗi dài trong ngoặc kép/đơn (token/base64)
                ?: Regex("""["']([A-Za-z0-9+/=_\-]{32,500})["']""")
                    .findAll(responseText)
                    .firstOrNull { it.groupValues[1].length in 32..500 }
                    ?.groupValues?.get(1)

            if (key.isNullOrEmpty()) return false

            // ════════════════════════════════════════════════════════════════
            // FIX LỖI 4: Bỏ "sotrim/manifest/" — server này hay trả nội dung
            // không hợp lệ → gây MANIFEST_MALFORMED (3002) dù stream vẫn chạy
            // ════════════════════════════════════════════════════════════════
            val serverList = listOf(
                "https://sotrim.topphimmoi.org/raw/$key/index.m3u8" to "Sotrim",
                "https://dash.megacdn.xyz/raw/$key/index.m3u8"      to "Mega-HLS",
                "https://dash.megacdn.xyz/dast/$key/index.m3u8"     to "Mega-BK"
            )

            var found = false
            serverList.forEach { (link, serverName) ->
                callback(
                    newExtractorLink(
                        source = serverName,
                        name   = serverName,
                        url    = link,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }

            if (!found) {
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .findAll(responseText)
                    .forEach { match ->
                        M3u8Helper.generateM3u8(name, match.value, data)
                            .forEach { m3u8 -> callback(m3u8); found = true }
                    }
            }

            found
        } catch (e: Exception) {
            false
        }
    }
}
