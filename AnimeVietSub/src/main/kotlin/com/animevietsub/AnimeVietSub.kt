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
    override var mainUrl  = "https://animevui.social"
    override var name     = "AnimeVui"
    override var lang     = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    private val hdrs = mapOf(
        "User-Agent"      to ua,
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Accept"          to "text/html,application/xhtml+xml,*/*"
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/" to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/" to "Anime Bộ",
        "$mainUrl/the-loai/hanh-dong/" to "Action"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url  = if (page == 1) req.data else "${req.data.removeSuffix("/")}/?page=$page"
        val doc  = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = hdrs).document
        val items = doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun imgOf(el: Element?): String? {
        el ?: return null
        return listOf("data-src", "data-original", "data-lazy", "src")
            .map { el.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fix(it) }
    }

    private fun Element.toSR(): SearchResponse? {
        val a    = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fix(a.attr("href")) ?: return null
        if (!href.contains("/thong-tin-phim/")) return null
        val ttl  = (a.attr("title").ifBlank {
            a.selectFirst("h2,h3,.title,.name,p")?.text()
        } ?: "").trim().ifBlank { return null }
        val poster = imgOf(a.selectFirst("img"))
        return newAnimeSearchResponse(ttl, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/",
            interceptor = cf, headers = hdrs).document
        return doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        val doc = app.get(detailUrl, interceptor = cf, headers = hdrs).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim().ifBlank { "Anime" }

        // Poster: thử nhiều selector
        val poster = listOf(
            ".film-poster img", ".poster img", ".thumb img",
            "figure img", ".info img", "img[itemprop=image]",
            ".img-film img", ".film-info img", "img.poster"
        ).firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.let { imgOf(it) } }
            ?: doc.selectFirst("img[src*='.jpg'], img[src*='.webp'], img[src*='.png']")
                ?.let { imgOf(it) }

        val plot = doc.selectFirst(
            "[itemprop=description], .description, .desc, .film-content, .content-film"
        )?.text()?.trim()

        val tags = doc.select("a[href*='/the-loai/'], a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val year = doc.select("a[href*='phim-nam-'], a[href*='year=']")
            .firstNotNullOfOrNull { Regex("""\b(20\d{2})\b""").find(it.text())?.value?.toIntOrNull() }

        // Lấy episode list từ watch page
        val watchUrl = detailUrl.replace("/thong-tin-phim/", "/xem-phim/")
        val watchDoc = try {
            app.get(watchUrl, interceptor = cf, headers = hdrs).document
        } catch (_: Exception) { doc }

        val episodes = watchDoc
            .select("a[href*='/tap-']")
            .distinctBy { it.attr("href") }
            .mapNotNull { ep ->
                val href = fix(ep.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/xem-phim/")) return@mapNotNull null
                val raw   = ep.text().trim().ifBlank { ep.attr("title").trim() }
                val epNum = Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""tap-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                val label = raw.ifBlank { "Tập $epNum" }
                newEpisode(href) { name = label; episode = epNum }
            }
            .sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // Tìm var streamUrl từ HTML hoặc iframe
    private suspend fun findStreamUrl(html: String, referer: String): String? {
        val patterns = listOf(
            Regex("""var\s+streamUrl\s*=\s*["']([^"']+)["']"""),
            Regex("""streamUrl\s*=\s*["']([^"']+)["']"""),
            Regex("""["'](https://video\.twimg\.com/amplify_video/[^"'\s]+\.m3u8)["']""")
        )

        // Tìm trong HTML chính
        for (pat in patterns) {
            val m = pat.find(html) ?: continue
            val found = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            if (found.startsWith("http")) return found
        }

        // Tìm trong iframe
        val doc = org.jsoup.Jsoup.parse(html)
        val iframeSrc = doc.select("iframe[src]")
            .map { it.attr("src") }
            .firstOrNull { it.contains("streamfree") || it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else "https:$it" }
            ?: return null

        return try {
            val iframeHtml = app.get(iframeSrc, interceptor = cf, headers = mapOf(
                "User-Agent"      to ua,
                "Referer"         to referer,
                "Accept"          to "text/html,application/xhtml+xml,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9"
            )).text

            for (pat in patterns) {
                val m = pat.find(iframeHtml) ?: continue
                val found = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
                if (found.startsWith("http")) return found
            }
            null
        } catch (_: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        val html  = try {
            app.get(epUrl, interceptor = cf, headers = hdrs).text
        } catch (_: Exception) { return false }

        val masterUrl = findStreamUrl(html, epUrl) ?: return false

        // Emit master URL trực tiếp - để ExoPlayer tự xử lý quality
        // Không pre-parse để tránh lỗi URL construction
        callback(newExtractorLink(name, name, masterUrl) {
            this.quality = Qualities.P720.value
            this.referer = ""
            type = ExtractorLinkType.M3U8
        })

        // Thử parse master để thêm các quality riêng
        try {
            val masterText = app.get(masterUrl, headers = mapOf("User-Agent" to ua)).text
            if (masterText.contains("#EXTM3U")) {
                Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+)x(\d+).*?\n(https?://[^\s]+)""")
                    .findAll(masterText).forEach { match ->
                        val width    = match.groupValues[1].toIntOrNull() ?: 0
                        val height   = match.groupValues[2].toIntOrNull() ?: 0
                        val streamUrl = match.groupValues[3].trim()
                        val quality  = when {
                            height >= 720 || width >= 1280 -> Qualities.P720.value
                            height >= 480 || width >= 640  -> Qualities.P480.value
                            else                           -> Qualities.P360.value
                        }
                        callback(newExtractorLink(name, "$name ${height}p", streamUrl) {
                            this.quality = quality
                            this.referer = ""
                            type = ExtractorLinkType.M3U8
                        })
                    }
            }
        } catch (_: Exception) {}

        return true
    }
}
