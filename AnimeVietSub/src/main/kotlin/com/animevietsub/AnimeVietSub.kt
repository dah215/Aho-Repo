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
    override var name     = "AnimeVietSub(Fake)"
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
        "$mainUrl/the-loai/anime-moi/" to "Anime Mới",
        "$mainUrl/the-loai/anime-le/"  to "Anime Lẻ",
        "$mainUrl/the-loai/anime-bo/" to "Anime Bộ"
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
        val text = a.text() + a.attr("class") + (a.selectFirst(".quality,.badge,.label")?.text() ?: "")
        val dubStatus = if (text.contains("Lồng tiếng", ignoreCase = true) ||
                            text.contains("Thuyet minh", ignoreCase = true) ||
                            text.contains("Thuyết minh", ignoreCase = true))
            DubStatus.Dubbed else DubStatus.Subbed
        return newAnimeSearchResponse(ttl, href, TvType.Anime) {
            posterUrl = poster
            addDubStatus(dubStatus, -1)
            quality = SearchQuality.HD
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

        // Tìm episode links - thử nhiều selector
        val epLinks = watchDoc.select("a[href*='/tap-'], a[href*='/episode-'], a[href*='/ep-']")
            .ifEmpty { watchDoc.select("a[href*='/xem-phim/']") }
            .distinctBy { it.attr("href") }

        val episodes = epLinks.mapNotNull { ep ->
                val href = fix(ep.attr("href")) ?: return@mapNotNull null
                val raw  = ep.text().trim().ifBlank { ep.attr("title").trim() }
                val epNum = Regex("""(?:tap|ep|episode)-0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    name    = raw.ifBlank { "Tập ${epNum ?: ""}" }.trim()
                    episode = epNum
                }
            }
            .sortedBy { it.episode ?: 0 }

        val fullText = doc.text()
        val isCompleted = fullText.contains("Hoàn Tất", ignoreCase = true) ||
                          fullText.contains("Full", ignoreCase = true)
        val isDubbed = fullText.contains("Lồng tiếng", ignoreCase = true) ||
                       fullText.contains("Thuyết minh", ignoreCase = true)

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl   = poster
            this.plot   = plot
            this.tags   = tags
            this.year   = year
            this.showStatus = if (isCompleted) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodes)
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

    private suspend fun emitMaster(masterUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Emit master playlist trực tiếp - ExoPlayer tự xử lý HLS quality selection
        callback(newExtractorLink(name, name, masterUrl) {
            quality = Qualities.P1080.value
            referer = ""
            type    = ExtractorLinkType.M3U8
        })
        return true
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
        return emitMaster(masterUrl, callback)
    }
}
