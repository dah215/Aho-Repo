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

    // Chuyển URL trang chi tiết → trang xem phim
    private fun detailToWatch(detailUrl: String): String =
        detailUrl.replace("/thong-tin-phim/", "/xem-phim/")

    // ===== MAIN PAGE =====
    // animevui.social phân trang dạng ?page=N
    override val mainPage = mainPageOf(
        "$mainUrl/"                    to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/" to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/"  to "Anime Bộ",
        "$mainUrl/the-loai/anime-le/"  to "Anime Lẻ",
        "$mainUrl/the-loai/hanh-dong/" to "Action",
        "$mainUrl/the-loai/phep-thuat/" to "Fantasy"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url  = if (page == 1) base else "${base.removeSuffix("/")}/?page=$page"
        val doc  = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document

        // animevui dùng thẻ <a> bọc card, chỉ lấy những link có ảnh
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
            interceptor = cf,
            headers     = pageH
        ).document
        return doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    // ===== LOAD (chi tiết phim + danh sách tập) =====
    override suspend fun load(url: String): LoadResponse {
        val detailUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")

        // 1. Trang chi tiết → thông tin phim
        val detailDoc = app.get(detailUrl, interceptor = cf, headers = pageH).document

        val title = (
            detailDoc.selectFirst("h1")?.text()
                ?: detailDoc.title().substringBefore(" - ")
        ).trim().ifBlank { "Anime" }

        val poster = detailDoc.selectFirst(
            ".img-film img, figure img, .thumb img, img[itemprop=image], .poster img"
        )?.let { el ->
            fix(el.attr("data-src").ifBlank { el.attr("src") })
        }

        val plot = detailDoc.selectFirst(
            ".description, .desc, [itemprop=description], .film-content, .content-film"
        )?.text()?.trim()

        // 2. Trang xem phim → danh sách tập
        // animevui.social: /thong-tin-phim/{slug} → /xem-phim/{slug}
        val watchUrl = detailToWatch(detailUrl)
        val watchDoc = app.get(watchUrl, interceptor = cf, headers = pageH).document

        // Danh sách tập: tất cả link /xem-phim/{slug}/tap-{N}
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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        val foundUrls = mutableSetOf<String>()

        try {
            val doc     = app.get(epUrl, interceptor = cf, headers = pageH).document
            val rawHtml = doc.html()

            // === Bước 1: Tìm URL m3u8 / mp4 trực tiếp trong HTML ===
            Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|/hls/)[^\s"'<>]*""")
                .findAll(rawHtml)
                .map { it.value }
                .filter { v ->
                    !v.contains("googleads", ignoreCase = true) &&
                    !v.contains("/ads/",      ignoreCase = true)
                }
                .forEach { foundUrls.add(it) }

            // === Bước 2: Xử lý iframe (dùng loadExtractor) ===
            doc.select("iframe[src]").forEach { iframe ->
                val src = fix(iframe.attr("src")) ?: return@forEach
                if (!src.contains("googleads") && !src.contains("/ads/")) {
                    try {
                        loadExtractor(src, epUrl, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }

            // === Bước 3: Tìm data-src / data-video / data-url trên player div ===
            doc.select("[data-src],[data-video],[data-url],[data-file]").forEach { el ->
                val raw = listOf(
                    el.attr("data-src"),
                    el.attr("data-video"),
                    el.attr("data-url"),
                    el.attr("data-file")
                ).firstOrNull { it.isNotBlank() } ?: return@forEach

                val fixed = fix(raw) ?: return@forEach
                when {
                    fixed.contains(".m3u8") || fixed.contains(".mp4") ->
                        foundUrls.add(fixed)
                    fixed.startsWith("http") -> {
                        try {
                            loadExtractor(fixed, epUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            }

            // === Bước 4: Tìm URL trong các thẻ script ===
            doc.select("script:not([src])").forEach { script ->
                val txt = script.html()
                Regex("""["'](https?://[^"']+?\.(?:m3u8|mp4)[^"']*)["']""")
                    .findAll(txt)
                    .forEach { mr ->
                        val v = mr.groupValues[1]
                        if (!v.contains("googleads") && !v.contains("/ads/"))
                            foundUrls.add(v)
                    }
            }
        } catch (_: Exception) {}

        // === Emit tất cả URL tìm được ===
        for (videoUrl in foundUrls) {
            val isHls = videoUrl.contains(".m3u8", ignoreCase = true) ||
                        videoUrl.contains("/hls/", ignoreCase = true)
            callback(newExtractorLink(name, name, videoUrl) {
                referer = epUrl
                quality = Qualities.Unknown.value
                type    = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                headers = mapOf("User-Agent" to ua, "Referer" to epUrl)
            })
        }

        return foundUrls.isNotEmpty()
    }
}
