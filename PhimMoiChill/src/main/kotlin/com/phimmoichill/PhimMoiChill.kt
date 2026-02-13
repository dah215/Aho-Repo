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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "list/phim-moi" to "Phim Mới",
        "list/phim-le" to "Phim Lẻ",
        "list/phim-bo" to "Phim Bộ"
    )

    // ══════════════════════════════════════════════
    // FIX 1: fixUrl — chuyển URL tương đối → tuyệt đối
    // Đây là nguyên nhân gốc rễ của lỗi phim lẻ không phát được
    // ══════════════════════════════════════════════
    private fun fixUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//")   -> "https:$url"
            url.startsWith("/")    -> "$mainUrl$url"
            else                   -> "$mainUrl/$url"
        }
    }

    // ══════════════════════════════════════════════
    // FIX 2: getImageUrl — hỗ trợ thêm data-lazy-src + lọc URL rỗng/base64
    // ══════════════════════════════════════════════
    private fun getImageUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null
        val attrs = listOf("data-src", "data-original", "data-lazy-src", "data-srcset", "src")
        for (attr in attrs) {
            val url = el.attr(attr).trim()
            if (url.isNotEmpty() && !url.startsWith("data:image") && url.length > 10) {
                return fixUrl(url).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun getSearchQuality(quality: String?): SearchQuality? {
        return when (quality?.uppercase()?.trim()) {
            "4K", "2160P", "UHD"          -> SearchQuality.UHD
            "HD", "720P", "FULL HD",
            "FHD", "1080P"                -> SearchQuality.HD
            "SD", "480P"                  -> SearchQuality.SD
            else                          -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}"
                  else           "$mainUrl/${request.data}?page=$page"
        val html = app.get(url, headers = defaultHeaders).text
        val doc  = Jsoup.parse(html)

        val items = doc.select(".list-film .item").mapNotNull { el ->
            val a     = el.selectFirst("a") ?: return@mapNotNull null
            val href  = fixUrl(a.attr("href"))   // FIX 1: đảm bảo URL tuyệt đối
            if (href.isEmpty()) return@mapNotNull null

            val title = el.selectFirst("p, .title, h3")?.text()?.trim()
                ?: a.attr("title").trim()
            if (title.isEmpty()) return@mapNotNull null

            val poster       = getImageUrl(el.selectFirst("img"))  // FIX 2
            val qualityText  = el.selectFirst(".quality, .hd, .resolution, .label-quality")?.text()?.trim()

            // ══════════════════════════════════════════════
            // FIX 3: Nhận diện đúng Movie vs TvSeries để hiện nhãn tập
            // ══════════════════════════════════════════════
            val isSeries = request.data.contains("phim-bo")
                || el.selectFirst(".episode, .ep, .label-episode, .num-episode") != null

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
        val html = app.get(url, headers = defaultHeaders).text
        return Jsoup.parse(html).select(".list-film .item").mapNotNull { el ->
            val a    = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))  // FIX 1
            if (href.isEmpty()) return@mapNotNull null

            val title  = el.selectFirst("p, .title, h3")?.text()?.trim()
                ?: a.attr("title").trim()
            val poster = getImageUrl(el.selectFirst("img"))  // FIX 2
            val qualityText = el.selectFirst(".quality, .hd, .resolution, .label-quality")?.text()?.trim()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality   = getSearchQuality(qualityText)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url, headers = defaultHeaders).text
        val doc  = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title, h1, .caption, .film-name")
            ?.text()?.trim() ?: "Phim"

        // FIX 2: thử nhiều selector poster hơn
        val poster = getImageUrl(doc.selectFirst(".film-poster img, .poster img, .thumb img"))
            ?: getImageUrl(doc.selectFirst("img.poster, img[itemprop=image], .film-thumbnail img"))

        val description = doc.selectFirst(
            ".film-content, .description, .film-info-content, .entry-content"
        )?.text()?.trim()

        val year = doc.selectFirst(".year, [itemprop=dateCreated]")
            ?.text()?.trim()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val genres = doc.select(".genre a, .categories a, [itemprop=genre]").map { it.text() }

        val tags = genres.toMutableList()
        if (html.contains("vietsub",     ignoreCase = true) ||
            html.contains("phụ đề",     ignoreCase = true)) tags.add("Phụ đề")
        if (html.contains("thuyết minh", ignoreCase = true) ||
            html.contains("lồng tiếng", ignoreCase = true)) tags.add("Thuyết minh")

        // ══════════════════════════════════════════════
        // FIX 1 QUAN TRỌNG: Dùng fixUrl() cho mọi href episode
        // Đây là lý do phim lẻ báo "Không tìm thấy liên kết"
        // ══════════════════════════════════════════════
        val episodes = doc.select("ul.list-episode li a, .episode-list a, a[href*='/xem/']")
            .mapNotNull {
                val href = fixUrl(it.attr("href"))
                if (href.isEmpty() || href == mainUrl || href == "$mainUrl/") return@mapNotNull null
                newEpisode(href) {
                    this.name = it.text().trim().ifEmpty { null }
                }
            }
            .distinctBy { it.data }

        // ══════════════════════════════════════════════
        // FIX 3: Trả về MovieLoadResponse nếu là phim lẻ (0–1 tập)
        // tránh CloudStream hiện "TvSeries 0 tập" không phát được
        // ══════════════════════════════════════════════
        return if (episodes.size <= 1) {
            val watchData = episodes.firstOrNull()?.data
                ?: doc.selectFirst("a[href*='/xem/'], .btn-play a")
                    ?.attr("href")?.let { fixUrl(it) }
                ?: url

            newMovieLoadResponse(title, url, TvType.Movie, watchData) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        } else {
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
        // FIX 1: data giờ luôn là URL tuyệt đối nên app.get() hoạt động đúng
        val pageResponse = app.get(data, headers = defaultHeaders)
        val html         = pageResponse.text
        val cookies      = pageResponse.cookies

        val episodeId =
            Regex("""episodeID["']?\s*:\s*["']?(\d+)""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]id=(\d+)""").find(data)?.groupValues?.get(1)
            ?: return false

        return try {
            val responseText = app.post(
                "$mainUrl/chillsplayer.php",
                data    = mapOf("qcao" to episodeId, "sv" to "0"),
                headers = defaultHeaders.plus("Referer" to data),
                cookies = cookies
            ).text

            // ══════════════════════════════════════════════
            // FIX 4: Trích xuất key an toàn — tránh lấy toàn bộ response làm key
            // Bug cũ: substringAfterLast trả về nguyên chuỗi khi không tìm thấy,
            // dẫn đến key = hàng nghìn ký tự → m3u8 URL sai → MANIFEST_MALFORMED
            // ══════════════════════════════════════════════
            val key: String? = Regex("""iniPlayers\(["']([A-Za-z0-9_\-]{5,200})["']""")
                .find(responseText)?.groupValues?.get(1)
                ?: run {
                    val marker = "iniPlayers(\""
                    val idx    = responseText.lastIndexOf(marker)
                    if (idx >= 0) {
                        val after = responseText.substring(idx + marker.length)
                        val candidate = after.substringBefore("\"")
                        // Chỉ dùng nếu trông như key hợp lệ
                        if (candidate.length in 5..200 && !candidate.contains(" ")) candidate
                        else null
                    } else null
                }

            if (key.isNullOrEmpty()) return false

            val serverList = listOf(
                "https://sotrim.topphimmoi.org/manifest/$key/index.m3u8" to "Chill-VIP",
                "https://sotrim.topphimmoi.org/raw/$key/index.m3u8"      to "Sotrim-Raw",
                "https://dash.megacdn.xyz/raw/$key/index.m3u8"           to "Mega-HLS",
                "https://dash.megacdn.xyz/dast/$key/index.m3u8"          to "Mega-BK"
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

            // Fallback: tìm m3u8 trực tiếp trong response
            if (!found) {
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .findAll(responseText)
                    .forEach { match ->
                        M3u8Helper.generateM3u8(name, match.value, data)
                            .forEach { m3u8 ->
                                callback(m3u8)
                                found = true
                            }
                    }
            }

            found
        } catch (e: Exception) {
            false
        }
    }
}
