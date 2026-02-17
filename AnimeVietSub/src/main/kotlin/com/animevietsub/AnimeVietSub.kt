package com.animevietsub

import android.util.Log
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fix(data) ?: return false
        Log.d("AnimeVui", "Loading: $epUrl")
        
        try {
            val response = app.get(epUrl, interceptor = cf, headers = pageH)
            val html = response.text
            
            Log.d("AnimeVui", "HTML length: ${html.length}")
            
            // Thử nhiều pattern khác nhau
            val patterns = listOf(
                Regex("""var\s+streamUrl\s*=\s*["']([^"']+)["']"""),
                Regex("""streamUrl\s*=\s*["']([^"']+)["']"""),
                Regex("""https://video\.twimg\.com/amplify_video/\d+/pl/[A-Za-z0-9_\-]+\.m3u8""")
            )
            
            var foundUrl: String? = null
            
            for ((index, pattern) in patterns.withIndex()) {
                val match = pattern.find(html)
                if (match != null) {
                    foundUrl = if (match.groupCount > 0) match.groupValues[1] else match.value
                    Log.d("AnimeVui", "Pattern $index matched: $foundUrl")
                    break
                }
            }
            
            if (foundUrl == null) {
                Log.d("AnimeVui", "No streamUrl found. Checking HTML snippet:")
                // Log một đoạn HTML để debug
                val scriptSnippet = html.indexOf("<script").let { start ->
                    if (start > 0) html.substring(start, minOf(start + 500, html.length))
                    else "No script tag found"
                }
                Log.d("AnimeVui", "Script snippet: $scriptSnippet")
                
                // Thử fetch iframe streamfree.casa
                val doc = org.jsoup.Jsoup.parse(html)
                val iframe = doc.selectFirst("iframe[src*='streamfree']")
                if (iframe != null) {
                    val iframeSrc = iframe.attr("src")
                    Log.d("AnimeVui", "Found iframe: $iframeSrc")
                    
                    // Fetch iframe content
                    try {
                        val iframeHtml = app.get(iframeSrc, headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to epUrl
                        )).text
                        
                        Log.d("AnimeVui", "Iframe HTML length: ${iframeHtml.length}")
                        
                        // Tìm streamUrl trong iframe
                        patterns.forEach { pattern ->
                            val match = pattern.find(iframeHtml)
                            if (match != null) {
                                foundUrl = if (match.groupCount > 0) match.groupValues[1] else match.value
                                Log.d("AnimeVui", "Found in iframe: $foundUrl")
                                return@forEach
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("AnimeVui", "Iframe fetch error: ${e.message}")
                    }
                }
                
                if (foundUrl == null) return false
            }
            
            // Emit video URL
            val masterUrl = foundUrl!!
            Log.d("AnimeVui", "Final master URL: $masterUrl")
            
            // Fetch master playlist
            val masterText = try {
                app.get(masterUrl, headers = mapOf("User-Agent" to ua)).text
            } catch (e: Exception) {
                Log.d("AnimeVui", "Master fetch error: ${e.message}")
                ""
            }
            
            if (masterText.contains("#EXTM3U")) {
                val baseUrl = masterUrl.substringBeforeLast("/")
                var foundAny = false
                
                Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+)x(\d+).*?\n([^\s]+)""")
                    .findAll(masterText).forEach { match ->
                        val width = match.groupValues[1].toIntOrNull() ?: 0
                        val height = match.groupValues[2].toIntOrNull() ?: 0
                        val path = match.groupValues[3]
                        
                        val streamUrl = if (path.startsWith("http")) path else "$baseUrl/$path"
                        
                        val quality = when {
                            height >= 720 || width >= 1280 -> Qualities.P720.value
                            height >= 480 || width >= 640  -> Qualities.P480.value
                            else                           -> Qualities.P360.value
                        }
                        
                        Log.d("AnimeVui", "Emitting: ${height}p - $streamUrl")
                        
                        callback(newExtractorLink(
                            name,
                            "$name ${height}p",
                            streamUrl
                        ) {
                            this.quality = quality
                            type = ExtractorLinkType.M3U8
                        })
                        foundAny = true
                    }
                
                return foundAny
            }
            
            // Fallback: emit master
            Log.d("AnimeVui", "Emitting master fallback")
            callback(newExtractorLink(name, name, masterUrl) {
                quality = Qualities.Unknown.value
                type = ExtractorLinkType.M3U8
            })
            return true
            
        } catch (e: Exception) {
            Log.e("AnimeVui", "Error: ${e.message}", e)
            return false
        }
    }
}
