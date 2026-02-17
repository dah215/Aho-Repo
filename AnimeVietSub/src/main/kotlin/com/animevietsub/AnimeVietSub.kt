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

    private fun imgOf(el: Element?): String? {
        el ?: return null
        return listOf("data-src", "data-original", "data-lazy", "src")
            .map { el.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fix(it) }
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

    private fun Element.toSR(): SearchResponse? {
        val a    = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fix(a.attr("href")) ?: return null
        if (!href.contains("/thong-tin-phim/")) return null
        val ttl  = (a.attr("title").ifBlank {
            a.selectFirst("h2,h3,.title,.name,p")?.text()
        } ?: "").trim().ifBlank { return null }
        val poster = imgOf(a.selectFirst("img"))
        // Lấy label chất lượng nếu có
        val label = a.selectFirst("span.label, .quality, .badge")?.text()?.uppercase() ?: ""
        val quality = when {
            label.contains("4K") || label.contains("UHD") -> SearchQuality.UHD
            label.contains("FHD") || label.contains("1080") -> SearchQuality.HD
            label.contains("CAM") -> SearchQuality.Cam
            else -> SearchQuality.HD
        }
        return newAnimeSearchResponse(ttl, href, TvType.Anime) {
            posterUrl = poster
            this.quality = quality
        }
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

        // Poster
        val poster = listOf(
            ".film-poster img", ".poster img", ".thumb img", ".film-thumbnail img",
            "figure img", ".info img", "img[itemprop=image]", ".img-film img",
            ".film-info img", ".cover img", ".movie-thumbnail img"
        ).firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.let { imgOf(it) } }
            ?: doc.select("img").firstNotNullOfOrNull { el ->
                val src = imgOf(el) ?: return@firstNotNullOfOrNull null
                if (src.contains(".jpg") || src.contains(".webp") || src.contains(".png")) src else null
            }

        val plot = doc.selectFirst(
            "[itemprop=description], .description, .desc, .film-content, .content-film, .synopsis, .film-overview"
        )?.text()?.trim()

        val tags = doc.select("a[href*='/the-loai/'], a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val year = doc.select("a[href*='phim-nam-'], a[href*='year='], span, td")
            .firstNotNullOfOrNull { Regex("""\b(20\d{2})\b""").find(it.text())?.value?.toIntOrNull() }

        // Tổng số tập & trạng thái
        val statusText = doc.select("td, li, span, p")
            .firstOrNull { it.text().contains("Tập") && Regex("""\d+""").containsMatchIn(it.text()) }
            ?.text()?.trim()
        val showStatus = when {
            statusText?.contains("Hoàn", ignoreCase = true) == true -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        // Lấy episodes từ watch page
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
                val epNum = Regex("""tap-0*(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    name    = raw.ifBlank { "Tập $epNum" }
                    episode = epNum
                }
            }
            .sortedBy { it.episode ?: 0 }

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl  = poster
            this.plot  = plot
            this.tags  = tags
            this.year  = year
            this.showStatus = showStatus
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // Tìm var streamUrl từ HTML trang episode hoặc iframe
    private suspend fun findStreamUrl(html: String, referer: String): String? {
        val urlPatterns = listOf(
            Regex("""var\s+streamUrl\s*=\s*["']([^"']+)["']"""),
            Regex("""streamUrl\s*=\s*["']([^"']+)["']"""),
            Regex("""["'](https://video\.twimg\.com/amplify_video/[^"'\s]+\.m3u8)["']""")
        )

        // Tìm trong HTML chính trước
        for (pat in urlPatterns) {
            val found = pat.find(html)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http") } ?: continue
            return found
        }

        // Lấy iframe src
        val doc = org.jsoup.Jsoup.parse(html)
        val iframeSrc = doc.select("iframe[src]")
            .map { it.attr("src") }
            .firstOrNull { it.isNotBlank() && !it.contains("googleads") }
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?: return null

        // Thử fetch iframe KHÔNG dùng CloudflareKiller trước (nhanh hơn)
        val iframeHtml = try {
            app.get(iframeSrc, headers = mapOf(
                "User-Agent"      to ua,
                "Referer"         to referer,
                "Accept"          to "text/html,application/xhtml+xml,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9"
            )).text
        } catch (_: Exception) {
            // Fallback: dùng CloudflareKiller
            try {
                app.get(iframeSrc, interceptor = cf, headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to referer,
                    "Accept"     to "text/html,application/xhtml+xml,*/*"
                )).text
            } catch (_: Exception) { return null }
        }

        for (pat in urlPatterns) {
            val found = pat.find(iframeHtml)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http") } ?: continue
            return found
        }
        return null
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

        // Fetch master playlist và parse quality variants
        return try {
            val masterText = app.get(masterUrl, headers = mapOf("User-Agent" to ua)).text
            
            if (masterText.contains("#EXTM3U")) {
                // Parse từng quality từ master playlist
                val variants = Regex(
                    """#EXT-X-STREAM-INF:.*?BANDWIDTH=(\d+).*?RESOLUTION=(\d+)x(\d+).*?\n(https?://[^\s]+)""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(masterText).toList()

                if (variants.isNotEmpty()) {
                    variants.forEach { match ->
                        val bandwidth = match.groupValues[1].toLongOrNull() ?: 0L
                        val width     = match.groupValues[2].toIntOrNull() ?: 0
                        val height    = match.groupValues[3].toIntOrNull() ?: 0
                        val streamUrl = match.groupValues[4].trim()
                        
                        val quality = when {
                            height >= 1080 || width >= 1920 -> Qualities.P1080.value
                            height >= 720  || width >= 1280 -> Qualities.P720.value
                            height >= 480  || width >= 640  -> Qualities.P480.value
                            else                            -> Qualities.P360.value
                        }
                        val label = when {
                            height >= 1080 -> "FHD 1080p"
                            height >= 720  -> "HD 720p"
                            height >= 480  -> "SD 480p"
                            else           -> "LD ${height}p"
                        }
                        
                        callback(newExtractorLink(name, "$name $label", streamUrl) {
                            this.quality = quality
                            this.referer = ""
                            type = ExtractorLinkType.M3U8
                        })
                    }
                    true
                } else {
                    // Không parse được variants → emit master
                    callback(newExtractorLink(name, "$name HD", masterUrl) {
                        this.quality = Qualities.P720.value
                        this.referer = ""
                        type = ExtractorLinkType.M3U8
                    })
                    true
                }
            } else {
                // Không phải HLS → emit trực tiếp
                callback(newExtractorLink(name, name, masterUrl) {
                    this.quality = Qualities.Unknown.value
                    this.referer = ""
                    type = ExtractorLinkType.M3U8
                })
                true
            }
        } catch (_: Exception) {
            // Nếu fetch master fail → emit trực tiếp
            callback(newExtractorLink(name, "$name HD", masterUrl) {
                this.quality = Qualities.P720.value
                this.referer = ""
                type = ExtractorLinkType.M3U8
            })
            true
        }
    }
}
