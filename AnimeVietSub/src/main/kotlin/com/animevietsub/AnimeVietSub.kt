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
        "$mainUrl/"                    to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/" to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/"  to "Anime Bộ",
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

        val poster = listOf(
            ".film-poster img", ".poster img", ".thumb img",
            "figure img", ".info img", "img[itemprop=image]",
            ".img-film img", ".film-info img", ".cover img"
        ).firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.let { imgOf(it) } }
            ?: doc.select("img").firstNotNullOfOrNull { el ->
                val src = imgOf(el) ?: return@firstNotNullOfOrNull null
                if (src.contains(".jpg") || src.contains(".webp") || src.contains(".png")) src else null
            }

        val plot = doc.selectFirst(
            "[itemprop=description], .description, .desc, .film-content, .synopsis"
        )?.text()?.trim()

        val tags = doc.select("a[href*='/the-loai/'], a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val year = doc.select("a, span, td, li")
            .firstNotNullOfOrNull { Regex("""\b(20\d{2})\b""").find(it.text())?.value?.toIntOrNull() }

        val watchUrl = detailUrl.replace("/thong-tin-phim/", "/xem-phim/")
        val watchDoc = try {
            app.get(watchUrl, interceptor = cf, headers = hdrs).document
        } catch (_: Exception) { doc }

        val episodes = watchDoc.select("a[href*='/tap-']")
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
            posterUrl   = poster
            this.plot   = plot
            this.tags   = tags
            this.year   = year
            this.showStatus = if (doc.text().contains("Hoàn", ignoreCase = true))
                ShowStatus.Completed else ShowStatus.Ongoing
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // Patterns tìm video URL - ưu tiên từ cao đến thấp
    private val streamPatterns = listOf(
        Regex("""var\s+streamUrl\s*=\s*["']([^"']+)["']"""),
        Regex("""streamUrl\s*=\s*["']([^"']+)["']"""),
        Regex("""["'](https://video\.twimg\.com/amplify_video/[^"'\s]+\.m3u8)["']"""),
        Regex("""source\s*:\s*["'](https://[^"']+\.m3u8)["']"""),
        Regex("""file\s*:\s*["'](https://[^"']+\.m3u8)["']"""),
        Regex("""src\s*:\s*["'](https://[^"']+\.m3u8)["']"""),
        Regex("""["'](https://[^"'\s]+\.m3u8[^"'\s]*)["']""")
    )

    private fun searchInHtml(html: String): String? {
        for (pat in streamPatterns) {
            val found = pat.find(html)?.groupValues?.getOrNull(1)
                ?.takeIf { it.startsWith("http") } ?: continue
            return found
        }
        return null
    }

    private suspend fun fetchIframe(iframeSrc: String, referer: String, cookies: Map<String, String>): String? {
        // Thử 1: Không CF, nhanh hơn
        try {
            val resp = app.get(iframeSrc, headers = mapOf(
                "User-Agent"      to ua,
                "Referer"         to referer,
                "Accept"          to "text/html,application/xhtml+xml,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9",
                "Sec-Fetch-Dest"  to "iframe",
                "Sec-Fetch-Mode"  to "navigate",
                "Sec-Fetch-Site"  to "cross-site"
            ), cookies = cookies)
            val html = resp.text
            // Nếu bị CF challenge thì HTML rất ngắn hoặc chứa "challenge"
            if (html.length > 1000 && !html.contains("cf-browser-verification")) {
                return html
            }
        } catch (_: Exception) {}

        // Thử 2: Với CF interceptor
        return try {
            app.get(iframeSrc, interceptor = cf, headers = mapOf(
                "User-Agent"      to ua,
                "Referer"         to referer,
                "Accept"          to "text/html,application/xhtml+xml,*/*",
                "Accept-Language" to "vi-VN,vi;q=0.9"
            ), cookies = cookies).text
        } catch (_: Exception) { null }
    }

    private fun unescapeHtml(s: String) = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private suspend fun findStreamUrl(html: String, epUrl: String, cookies: Map<String, String>): String? {
        // Tìm trong HTML chính
        searchInHtml(html)?.let { return it }

        // Tìm tất cả URL streamfree bằng regex trong raw HTML
        // (iframe src có thể rỗng hoặc bị &amp; encode)
        val iframeUrls = mutableListOf<String>()

        // Pattern 1: src="..." hoặc data-src="..."
        Regex("""(?:src|data-src)=["']([^"']*streamfree\.casa[^"']*)["']""")
            .findAll(html)
            .forEach { iframeUrls.add(unescapeHtml(it.groupValues[1])) }

        // Pattern 2: URL nằm trong JavaScript string hoặc attribute khác
        Regex("""["']((?:https?:)?//streamfree\.casa/\?[^"'\s<>]+)["']""")
            .findAll(html)
            .forEach { iframeUrls.add(unescapeHtml(it.groupValues[1])) }

        // Pattern 3: Tìm mọi nơi có streamfree.casa (kể cả &amp; encoded)
        Regex("""((?:https?:)?//streamfree\.casa/\?url=[A-Za-z0-9+/=]+(?:&(?:amp;)?csrftoken=[a-f0-9]+)?(?:&(?:amp;)?exp=\d+)?)""")
            .findAll(html)
            .forEach { iframeUrls.add(unescapeHtml(it.groupValues[1])) }

        val uniqueUrls = iframeUrls
            .map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http") && it.contains("streamfree.casa") }
            .distinct()

        for (iframeSrc in uniqueUrls) {
            val iframeHtml = fetchIframe(iframeSrc, epUrl, cookies) ?: continue
            searchInHtml(iframeHtml)?.let { return it }
        }

        // Fallback: tất cả iframe src/data-src
        val doc = org.jsoup.Jsoup.parse(html)
        val jsoupIframes = doc.select("iframe")
            .mapNotNull { el ->
                (el.attr("src").ifBlank { null } ?: el.attr("data-src").ifBlank { null })
            }
            .filter { !it.contains("googleads") }
            .map { when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/")  -> "$mainUrl$it"
                else                -> it
            }}
            .filter { it.startsWith("http") }

        for (iframeSrc in jsoupIframes) {
            val iframeHtml = fetchIframe(iframeSrc, epUrl, cookies) ?: continue
            searchInHtml(iframeHtml)?.let { return it }
        }

        return null
    }

    private suspend fun emitFromMaster(masterUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val masterText = app.get(masterUrl, headers = mapOf(
                "User-Agent" to ua,
                "Origin"     to "https://streamfree.casa"
            )).text

            if (!masterText.contains("#EXTM3U")) {
                callback(newExtractorLink(name, "$name Auto", masterUrl) {
                    quality = Qualities.P720.value
                    referer = "https://streamfree.casa/"
                    type    = ExtractorLinkType.M3U8
                })
                return true
            }

            val lines = masterText.lines()
            var foundAny = false
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                    // Hỗ trợ cả absolute URL (http) và relative path
                    val streamUrl = when {
                        nextLine.startsWith("http") -> nextLine
                        nextLine.startsWith("/") -> {
                            val base = Regex("""^(https?://[^/]+)""").find(masterUrl)?.value ?: ""
                            "$base$nextLine"
                        }
                        nextLine.isNotBlank() -> {
                            val base = masterUrl.substringBeforeLast("/")
                            "$base/$nextLine"
                        }
                        else -> { i++; continue }
                    }
                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                        .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val quality = when {
                        height >= 1080 -> Qualities.P1080.value
                        height >= 720  -> Qualities.P720.value
                        height >= 480  -> Qualities.P480.value
                        else           -> Qualities.P360.value
                    }
                    val label = when {
                        height >= 1080 -> "FHD 1080p"
                        height >= 720  -> "HD 720p"
                        height >= 480  -> "SD 480p"
                        height > 0     -> "${height}p"
                        else           -> "Auto"
                    }
                    callback(newExtractorLink(name, "$name $label", streamUrl) {
                        this.quality = quality
                        this.referer = "https://streamfree.casa/"
                        type = ExtractorLinkType.M3U8
                    })
                    foundAny = true
                    i += 2
                    continue
                }
                i++
            }

            if (!foundAny) {
                callback(newExtractorLink(name, "$name HD", masterUrl) {
                    quality = Qualities.P720.value
                    referer = "https://streamfree.casa/"
                    type    = ExtractorLinkType.M3U8
                })
            }
            true
        } catch (_: Exception) {
            callback(newExtractorLink(name, "$name HD", masterUrl) {
                quality = Qualities.P720.value
                referer = "https://streamfree.casa/"
                type    = ExtractorLinkType.M3U8
            })
            true
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        val epResponse = try {
            app.get(epUrl, interceptor = cf, headers = hdrs)
        } catch (_: Exception) { return false }

        val html    = epResponse.text
        val cookies = epResponse.cookies

        val masterUrl = findStreamUrl(html, epUrl, cookies) ?: return false
        return emitFromMaster(masterUrl, callback)
    }
}
