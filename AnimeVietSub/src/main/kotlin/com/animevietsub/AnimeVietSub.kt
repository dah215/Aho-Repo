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
    override val hasMainPage         = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent"      to ua,
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
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

    private fun detailToWatch(url: String) =
        url.replace("/thong-tin-phim/", "/xem-phim/")

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
    // Flow: episode page → <iframe src="streamfree.casa?url=BASE64&csrftoken=...">
    //       → fetch streamfree.casa → tìm video.twimg.com/*.m3u8
    //       → m3u8 master playlist (270p / 360p / 720p)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl  = fix(data) ?: return false
        val result = mutableListOf<ExtractorLink>()

        // ── BƯỚC 1: Lấy iframe src từ trang tập ──────────────────────────────
        val epDoc = try {
            app.get(epUrl, interceptor = cf, headers = pageH).document
        } catch (_: Exception) { return false }

        val iframeSrc = epDoc.selectFirst(
            "iframe#player, iframe.player-iframe, iframe[title=player], " +
            "iframe[src*='streamfree'], iframe[src*='player']"
        )?.attr("src")?.let { fix(it) }

        // ── BƯỚC 2: Fetch streamfree.casa ────────────────────────────────────
        if (iframeSrc != null) {
            val streamHeaders = mapOf(
                "User-Agent"      to ua,
                "Referer"         to epUrl,
                "Accept"          to "text/html,application/xhtml+xml,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
                "Sec-Ch-Ua-Mobile" to "?1"
            )

            val streamHtml = try {
                app.get(iframeSrc, headers = streamHeaders).text
            } catch (_: Exception) { "" }

            if (streamHtml.isNotBlank()) {
                // Pattern chính: video.twimg.com master .m3u8
                // e.g. "https://video.twimg.com/amplify_video/ID/pl/HASH.m3u8"
                val twimgMaster = Regex(
                    """https://video\.twimg\.com/amplify_video/\d+/pl/[A-Za-z0-9_\-]+\.m3u8"""
                ).find(streamHtml)?.value

                if (twimgMaster != null) {
                    // Fetch master playlist để lấy từng chất lượng
                    val masterText = try {
                        app.get(twimgMaster, headers = mapOf("User-Agent" to ua)).text
                    } catch (_: Exception) { "" }

                    if (masterText.isNotBlank()) {
                        // Parse từng STREAM-INF để lấy resolution + URL
                        val baseUrl = "https://video.twimg.com"
                        val streamRegex = Regex(
                            """#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(/[^\s]+\.m3u8)""",
                            RegexOption.DOT_MATCHES_ALL
                        )
                        streamRegex.findAll(masterText).forEach { mr ->
                            val resolution = mr.groupValues[1]      // e.g. "1280x720"
                            val path       = mr.groupValues[2]       // e.g. "/amplify_video/.../pl/avc1/1280x720/HASH.m3u8"
                            val streamUrl  = "$baseUrl$path"
                            val quality    = when {
                                resolution.contains("1280") -> Qualities.P720.value
                                resolution.contains("640")  -> Qualities.P480.value
                                else                        -> Qualities.P360.value
                            }
                            result.add(newExtractorLink(name, "$name $resolution", streamUrl) {
                                referer     = ""          // video.twimg.com không cần Referer
                                this.quality = quality
                                type        = ExtractorLinkType.M3U8
                                headers     = mapOf("User-Agent" to ua)
                            })
                        }

                        // Nếu parse STREAM-INF thất bại → emit master trực tiếp
                        if (result.isEmpty()) {
                            result.add(newExtractorLink(name, name, twimgMaster) {
                                referer     = ""
                                quality     = Qualities.Unknown.value
                                type        = ExtractorLinkType.M3U8
                                headers     = mapOf("User-Agent" to ua)
                            })
                        }
                    } else {
                        // Không fetch được master → emit luôn
                        result.add(newExtractorLink(name, name, twimgMaster) {
                            referer = ""
                            quality = Qualities.Unknown.value
                            type    = ExtractorLinkType.M3U8
                            headers = mapOf("User-Agent" to ua)
                        })
                    }
                }

                // Fallback: tìm bất kỳ m3u8 nào khác trong streamHtml
                if (result.isEmpty()) {
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                        .findAll(streamHtml).forEach { mr ->
                            result.add(newExtractorLink(name, name, mr.value) {
                                referer = iframeSrc
                                quality = Qualities.Unknown.value
                                type    = ExtractorLinkType.M3U8
                                headers = mapOf("User-Agent" to ua)
                            })
                        }
                }

                // Fallback cuối: dùng loadExtractor
                if (result.isEmpty()) {
                    try { loadExtractor(iframeSrc, epUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }
            }
        }

        // ── BƯỚC 3: Fallback tổng – quét HTML trang tập ──────────────────────
        if (result.isEmpty()) {
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .findAll(epDoc.html()).forEach { mr ->
                    result.add(newExtractorLink(name, name, mr.value) {
                        referer = epUrl
                        quality = Qualities.Unknown.value
                        type    = ExtractorLinkType.M3U8
                        headers = mapOf("User-Agent" to ua)
                    })
                }

            epDoc.select("iframe[src]").forEach { iframe ->
                val src = fix(iframe.attr("src")) ?: return@forEach
                if (!src.contains("googleads") && !src.contains("/ads/")) {
                    try { loadExtractor(src, epUrl, subtitleCallback, callback) }
                    catch (_: Exception) {}
                }
            }
        }

        result.forEach { callback(it) }
        return result.isNotEmpty()
    }
}
