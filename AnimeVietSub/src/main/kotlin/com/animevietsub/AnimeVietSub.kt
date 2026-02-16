package com.animevietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevui.social"
    override var name = "AnimeVui"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent"      to ua,
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )

    // ===== URL helpers =====
    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }

    private fun detailToWatch(detailUrl: String): String =
        detailUrl.replace("/thong-tin-phim/", "/xem-phim/")

    // ===== MAIN PAGE =====
    override val mainPage = mainPageOf(
        "$mainUrl/"                     to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/"  to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/"   to "Anime Bộ",
        "$mainUrl/the-loai/anime-le/"   to "Anime Lẻ",
        "$mainUrl/the-loai/hanh-dong/"  to "Action",
        "$mainUrl/the-loai/phep-thuat/" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url  = if (page == 1) base else "${base.removeSuffix("/")}/?page=$page"
        val doc  = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document

        val items = doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }

        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a    = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fix(a.attr("href")) ?: return null
        if (!href.contains("/thong-tin-phim/")) return null

        val ttl = (
            a.selectFirst("h2,h3,.title,.name,[class*='title']")?.text()
                ?: a.attr("title")
        ).trim().ifBlank { return null }

        val img    = a.selectFirst("img")
        val poster = fix(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-lazy-src")?.ifBlank { null }
                ?: img?.attr("src")
        )
        return newAnimeSearchResponse(ttl, href, TvType.Anime) { posterUrl = poster }
    }

    // ===== SEARCH =====
    override suspend fun search(q: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(q, "utf-8")
        val doc = app.get(
            "$mainUrl/tim-kiem/$encoded/",
            interceptor = cf, headers = pageH
        ).document
        return doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    // ===== LOAD =====
    override suspend fun load(url: String): LoadResponse {
        val detailUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")

        val detailDoc = app.get(detailUrl, interceptor = cf, headers = pageH).document

        val title = (
            detailDoc.selectFirst("h1")?.text()
                ?: detailDoc.title().substringBefore(" - ")
        ).trim().ifBlank { "Anime" }

        val poster = detailDoc.selectFirst(
            ".img-film img, figure img, .thumb img, img[itemprop=image], .poster img"
        )?.let { el -> fix(el.attr("data-src").ifBlank { el.attr("src") }) }

        val plot = detailDoc.selectFirst(
            ".description, .desc, [itemprop=description], .film-content, .content-film"
        )?.text()?.trim()

        // Lấy danh sách tập từ trang xem phim
        val watchUrl = detailToWatch(detailUrl)
        val watchDoc = app.get(watchUrl, interceptor = cf, headers = pageH).document

        val episodes = watchDoc
            .select("a[href*='/xem-phim/'][href*='/tap-']")
            .distinctBy { it.attr("href") }
            .mapNotNull { ep ->
                val href  = fix(ep.attr("href")) ?: return@mapNotNull null
                val label = ep.text().trim()
                    .ifBlank { ep.attr("title").trim() }
                    .ifBlank { href }
                val epNum = Regex("""tap-(\d+)""")
                    .find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+""").find(label)?.value?.toIntOrNull()
                newEpisode(href) {
                    name    = label
                    episode = epNum
                }
            }

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl     = poster
            this.plot     = plot
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ===== LOAD LINKS =====
    // Flow: Episode page → iframe streamfree.casa → m3u8 trên video.twimg.com
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        val foundUrls = mutableSetOf<String>()

        // ===== BƯỚC 1: Fetch trang tập, lấy iframe src =====
        val epDoc = try {
            app.get(epUrl, interceptor = cf, headers = pageH).document
        } catch (_: Exception) { return false }

        // Tìm iframe streamfree.casa (hoặc iframe player khác)
        val iframeSrc = epDoc.selectFirst(
            "iframe#player, iframe.player-iframe, iframe[title=player], iframe[src*='streamfree'], iframe[src*='player']"
        )?.attr("src")?.let { fix(it) }

        if (iframeSrc != null) {
            // ===== BƯỚC 2: Fetch trang streamfree.casa =====
            val streamHeaders = mapOf(
                "User-Agent" to ua,
                "Referer"    to epUrl,
                "Origin"     to mainUrl,
                "Accept-Language" to "vi-VN,vi;q=0.9"
            )

            val streamHtml = try {
                app.get(iframeSrc, headers = streamHeaders).text
            } catch (_: Exception) { "" }

            if (streamHtml.isNotBlank()) {
                // Tìm m3u8 từ video.twimg.com hoặc bất kỳ CDN nào
                Regex("""["'`](https://video\.twimg\.com/[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""")
                    .findAll(streamHtml)
                    .forEach { foundUrls.add(it.groupValues[1]) }

                // Tìm m3u8 chung
                Regex("""["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""")
                    .findAll(streamHtml)
                    .forEach { foundUrls.add(it.groupValues[1]) }

                // Tìm mp4 từ video.twimg.com
                Regex("""["'`](https://video\.twimg\.com/[^"'`\s]+\.mp4[^"'`\s]*)["'`]""")
                    .findAll(streamHtml)
                    .forEach { foundUrls.add(it.groupValues[1]) }

                // Tìm trong JSON (file:"...", src:"..., source:"...")
                Regex("""(?:file|src|source|url)\s*[=:]\s*["'`](https?://[^"'`\s]+\.(?:m3u8|mp4)[^"'`\s]*)["'`]""")
                    .findAll(streamHtml)
                    .forEach { foundUrls.add(it.groupValues[1]) }

                // Nếu vẫn không có → thử loadExtractor với iframe src
                if (foundUrls.isEmpty()) {
                    try { loadExtractor(iframeSrc, epUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }
            }
        }

        // ===== BƯỚC 3: Fallback – quét thẳng HTML trang tập =====
        if (foundUrls.isEmpty()) {
            val rawHtml = epDoc.html()

            Regex("""["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""")
                .findAll(rawHtml)
                .forEach { foundUrls.add(it.groupValues[1]) }

            Regex("""["'`](https?://[^"'`\s]+\.mp4[^"'`\s]*)["'`]""")
                .findAll(rawHtml)
                .forEach { foundUrls.add(it.groupValues[1]) }

            // Thử tất cả iframe còn lại
            epDoc.select("iframe[src]").forEach { iframe ->
                val src = fix(iframe.attr("src")) ?: return@forEach
                if (!src.contains("googleads") && !src.contains("/ads/")) {
                    try { loadExtractor(src, epUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }
            }
        }

        // ===== EMIT =====
        for (videoUrl in foundUrls) {
            if (videoUrl.contains("googleads", ignoreCase = true)) continue
            if (videoUrl.contains("/ads/",      ignoreCase = true)) continue

            val isHls = videoUrl.contains(".m3u8", ignoreCase = true)

            callback(newExtractorLink(name, name, videoUrl) {
                referer = iframeSrc ?: epUrl
                quality = Qualities.Unknown.value
                type    = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to (iframeSrc ?: epUrl)
                )
            })
        }

        return foundUrls.isNotEmpty()
    }
}
