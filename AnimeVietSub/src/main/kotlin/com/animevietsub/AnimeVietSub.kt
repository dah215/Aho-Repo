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
    private val pageH = mapOf("User-Agent" to ua, "Accept-Language" to "vi-VN,vi;q=0.9")

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }

    private fun detailToWatch(url: String) = url.replace("/thong-tin-phim/", "/xem-phim/")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/the-loai/anime-moi/" to "Anime Mới",
        "$mainUrl/the-loai/anime-bo/" to "Anime Bộ",
        "$mainUrl/the-loai/hanh-dong/" to "Action"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url  = if (page == 1) req.data else "${req.data.removeSuffix("/")}/?page=$page"
        val doc  = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a") ?: return null
        val href = fix(a.attr("href")) ?: return null
        if (!href.contains("/thong-tin-phim/")) return null
        val ttl = (a.selectFirst("h2,h3,.title,.name")?.text() ?: a.attr("title"))
            .trim().ifBlank { return null }
        val img = a.selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { img?.attr("src") })
        return newAnimeSearchResponse(ttl, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/",
            interceptor = cf, headers = pageH).document
        return doc.select("a[href*='/thong-tin-phim/']")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val detailUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        val detailDoc = app.get(detailUrl, interceptor = cf, headers = pageH).document

        val title = (detailDoc.selectFirst("h1")?.text()
            ?: detailDoc.title().substringBefore(" - ")).trim().ifBlank { "Anime" }
        val poster = detailDoc.selectFirst(".img-film img, figure img, img[itemprop=image]")
            ?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
        val plot = detailDoc.selectFirst(".description, .desc, .film-content")?.text()?.trim()

        val watchUrl = detailToWatch(detailUrl)
        val watchDoc = app.get(watchUrl, interceptor = cf, headers = pageH).document

        val episodes = watchDoc
            .select("a[href*='/xem-phim/'][href*='/tap-']")
            .distinctBy { it.attr("href") }
            .mapNotNull { ep ->
                val href = fix(ep.attr("href")) ?: return@mapNotNull null
                val label = ep.text().trim().ifBlank { ep.attr("title").trim() }.ifBlank { href }
                val epNum = Regex("""tap-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+""").find(label)?.value?.toIntOrNull()
                newEpisode(href) { name = label; episode = epNum }
            }

        return newAnimeLoadResponse(title, detailUrl, TvType.Anime) {
            posterUrl = poster; this.plot = plot
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ===== GIẢI PHÁP: Parse JavaScript variable từ HTML =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        
        try {
            // Fetch HTML của trang episode
            val html = app.get(epUrl, interceptor = cf, headers = pageH).text
            
            // Tìm var streamUrl = "..."
            val streamUrlMatch = Regex("""var\s+streamUrl\s*=\s*["']([^"']+)["']""")
                .find(html)
            
            if (streamUrlMatch != null) {
                val masterUrl = streamUrlMatch.groupValues[1]
                
                // Fetch master playlist
                val masterText = try {
                    app.get(masterUrl, headers = mapOf("User-Agent" to ua)).text
                } catch (_: Exception) { "" }
                
                if (masterText.contains("#EXTM3U")) {
                    // Parse variants từ master playlist
                    val baseUrl = masterUrl.substringBeforeLast("/")
                    var foundAny = false
                    
                    Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+)x(\d+).*?\n([^\s]+)""")
                        .findAll(masterText).forEach { match ->
                            val width = match.groupValues[1].toIntOrNull() ?: 0
                            val height = match.groupValues[2].toIntOrNull() ?: 0
                            val path = match.groupValues[3]
                            
                            val streamUrl = if (path.startsWith("http")) path 
                                          else "$baseUrl/$path"
                            
                            val quality = when {
                                height >= 720 || width >= 1280 -> Qualities.P720.value
                                height >= 480 || width >= 640  -> Qualities.P480.value
                                else                           -> Qualities.P360.value
                            }
                            
                            val qualityLabel = "${height}p"
                            
                            callback(newExtractorLink(
                                name,
                                "$name $qualityLabel",
                                streamUrl
                            ) {
                                this.quality = quality
                                type = ExtractorLinkType.M3U8
                            })
                            foundAny = true
                        }
                    
                    return foundAny
                }
                
                // Fallback: Emit master URL trực tiếp
                callback(newExtractorLink(
                    name,
                    name,
                    masterUrl
                ) {
                    quality = Qualities.Unknown.value
                    type = ExtractorLinkType.M3U8
                })
                return true
            }
            
            // Fallback 2: Tìm trực tiếp URL trong HTML
            Regex("""https://video\.twimg\.com/amplify_video/[^"'\s]+\.m3u8""")
                .findAll(html).forEach { match ->
                    callback(newExtractorLink(
                        name,
                        name,
                        match.value
                    ) {
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.M3U8
                    })
                    return true
                }
            
            // Fallback 3: loadExtractor cho các iframe khác
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("iframe[src]").forEach { iframe ->
                val src = fix(iframe.attr("src")) ?: return@forEach
                if (!src.contains("googleads") && !src.contains("/ads/")) {
                    try {
                        if (loadExtractor(src, epUrl, subtitleCallback, callback))
                            return true
                    } catch (_: Exception) {}
                }
            }
            
        } catch (e: Exception) {
            return false
        }
        
        return false
    }
}
