package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name   = "AnimeVietSub"
    override var lang   = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private val DECODE_PASSWORDS = listOf(
            "dm_thang_suc_vat_get_link_an_dbt",
            "animevietsub",
            "animevietsub123",
            "AVS",
            "animevietsub.ee",
            "animevietsub.vip"
        )
    }

    private val cfKiller = CloudflareKiller()
    
    private fun headers() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // BRUTE FORCE PARSING - Try everything
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/trang-$page.html"
        
        val doc = app.get(url, interceptor = cfKiller, headers = headers()).document
        
        // Try to find any elements that look like anime items
        val items = mutableListOf<SearchResponse>()
        
        // Method 1: Look for article elements (most common)
        doc.select("article").forEach { element ->
            parseItem(element)?.let { items.add(it) }
        }
        
        // Method 2: Look for elements with class containing film/movie/anime
        if (items.isEmpty()) {
            doc.select("[class*=film], [class*=movie], [class*=anime], [class*=post]").forEach { element ->
                parseItem(element)?.let { items.add(it) }
            }
        }
        
        // Method 3: Look for any links with images
        if (items.isEmpty()) {
            doc.select("a[href*=\"/a\"], a[href*=\"/phim-\"]").forEach { link ->
                val href = fixUrl(link.attr("href")) ?: return@forEach
                val title = link.attr("title").takeIf { it.isNotBlank() } 
                    ?: link.text().trim().takeIf { it.isNotBlank() }
                    ?: link.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                
                val img = link.selectFirst("img")
                val poster = fixUrl(
                    img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                )
                
                items.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                    posterUrl = poster
                })
            }
        }
        
        // Remove duplicates
        val uniqueItems = items.distinctBy { it.url }
        
        return newHomePageResponse(request.name, uniqueItems, hasNext = uniqueItems.isNotEmpty())
    }

    private fun parseItem(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = fixUrl(link.attr("href")) ?: return null
        
        // Must be anime link
        if (!href.contains("/a") && !href.contains("/phim-")) return null
        
        // Get title from various sources
        val title = element.selectFirst("h2, h3, .Title, .title, [class*=title]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null
        
        // Get poster
        val img = element.selectFirst("img")
        val poster = fixUrl(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        val doc = app.get(url, interceptor = cfKiller, headers = headers()).document
        
        return doc.select("article, [class*=film], [class*=item]").mapNotNull { 
            parseItem(it) 
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cfKiller, headers = headers()).document
        
        // Get film ID
        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1)
            ?: doc.selectFirst("[data-film-id]")?.attr("data-film-id")
            ?: doc.selectFirst("[data-id]")?.attr("data-id")
            ?: ""
        
        // Get episodes
        var episodes = doc.select("ul.list-episode li a, .episode-list a, [href*=\"/tap-\"]").mapNotNull { ep ->
            val epHref = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").takeIf { it.isNotBlank() }
                ?: ep.attr("data-episode-id").takeIf { it.isNotBlank() }
                ?: Regex("tap-(\\d+)").find(epHref)?.groupValues?.get(1)
                ?: ""
            
            val name = ep.text().trim().takeIf { it.isNotBlank() } ?: "Tập $epId"
            val num = Regex("\\d+").find(name)?.value?.toIntOrNull()
            
            newEpisode("$epHref@@$filmId@@$epId@@$name") {
                this.name = name
                this.episode = num
            }
        }
        
        // If no episodes found, try to find watch page
        if (episodes.isEmpty()) {
            val watchLink = doc.selectFirst("a[href*=\"/tap-\"], a[href*=\"/xem-phim/\"], .btn-see, .watch_button")?.attr("href")
            if (watchLink != null) {
                val watchDoc = app.get(fixUrl(watchLink)!!, interceptor = cfKiller, headers = headers()).document
                episodes = watchDoc.select("ul.list-episode li a, .episode-list a").mapNotNull { ep ->
                    val epHref = fixUrl(ep.attr("href")) ?: return@mapNotNull null
                    val epId = ep.attr("data-id").takeIf { it.isNotBlank() } ?: ""
                    val name = ep.text().trim().takeIf { it.isNotBlank() } ?: "Tập $epId"
                    newEpisode("$epHref@@$filmId@@$epId@@$name") {
                        this.name = name
                    }
                }
            }
        }
        
        val title = doc.selectFirst("h1, .Title, [class*=title]")?.text()?.trim() ?: "Anime"
        val poster = fixUrl(
            doc.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("img[data-original]")?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".Image img, .poster img")?.attr("src")?.takeIf { it.isNotBlank() }
        )
        val plot = doc.selectFirst(".Description, [class*=desc], [class*=summary]")?.text()?.trim()
        
        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false
        
        val (epUrl, filmId, episodeId) = parts
        val epName = parts.getOrNull(3) ?: "Unknown"
        
        // Get page and cookies
        val pageReq = app.get(epUrl, interceptor = cfKiller, headers = headers())
        var cookies = pageReq.cookies
        
        // Try to activate session
        try {
            val sessionReq = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
                headers = ajaxHeaders(epUrl), cookies = cookies, interceptor = cfKiller)
            cookies = cookies + sessionReq.cookies
        } catch (e: Exception) { }
        
        // Get player HTML
        val playerReq = app.post("$mainUrl/ajax/player", 
            data = mapOf("episodeId" to episodeId, "backup" to "1"),
            headers = ajaxHeaders(epUrl), cookies = cookies, interceptor = cfKiller)
        
        val playerHtml = playerReq.parsedSafe<ServerHtml>()?.html ?: return false
        
        // Parse server buttons
        val serverDoc = Jsoup.parse(playerHtml)
        val buttons = serverDoc.select("a[data-href], a[data-play], .btn3dsv, [onclick*=\"player\"]")
        
        for (btn in buttons) {
            val hash = btn.attr("data-href").takeIf { it.isNotBlank() }
                ?: btn.attr("data-link").takeIf { it.isNotBlank() }
                ?: continue
            
            val play = btn.attr("data-play")
            val btnId = btn.attr("data-id")
            
            // Request link
            val params = if (play == "api" || play.isBlank()) {
                mapOf("link" to hash, "id" to filmId)
            } else {
                mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
            }
            
            val linkReq = app.post("$mainUrl/ajax/player", data = params,
                headers = ajaxHeaders(epUrl), cookies = cookies, interceptor = cfKiller)
            
            val resp = linkReq.parsedSafe<LinkResponse>()
            val encrypted = resp?.link?.firstOrNull()?.file ?: resp?.linkStr ?: continue
            
            // Decrypt
            val decrypted = if (encrypted.startsWith("http")) {
                encrypted
            } else {
                decryptLink(encrypted) ?: continue
            }
            
            // Process video
            if (decrypted.contains(".m3u8")) {
                callback(newExtractorLink(name, "Auto", decrypted) {
                    this.headers = mapOf("User-Agent" to headers()["User-Agent"]!!, "Referer" to epUrl)
                    this.type = ExtractorLinkType.M3U8
                })
                return true
            } else if (decrypted.startsWith("http")) {
                callback(newExtractorLink(name, "Direct", decrypted) {
                    this.headers = mapOf("User-Agent" to headers()["User-Agent"]!!, "Referer" to epUrl)
                })
                return true
            }
        }
        
        return false
    }

    private fun decryptLink(encrypted: String): String? {
        for (password in DECODE_PASSWORDS) {
            try {
                if (encrypted.startsWith("http")) return encrypted
                
                val clean = encrypted.replace("\\s".toRegex(), "").replace("\"", "").trim()
                val decoded = Base64.decode(clean, Base64.DEFAULT)
                if (decoded.size < 17) continue
                
                val iv = decoded.copyOfRange(0, 16)
                val ct = decoded.copyOfRange(16, decoded.size)
                
                val key = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
                
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val plain = cipher.doFinal(ct)
                
                val result = try {
                    val inflater = Inflater(true).apply { setInput(plain) }
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0) break
                        out.write(buf, 0, n)
                    }
                    inflater.end()
                    String(out.toByteArray(), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    String(plain, StandardCharsets.UTF_8)
                }
                
                val final = result.replace("\"", "").trim()
                if (final.startsWith("http")) return final
                
            } catch (e: Exception) { continue }
        }
        return null
    }

    data class ServerHtml(@JsonProperty("html") val html: String?)
    data class LinkResponse(
        @JsonProperty("link") val link: List<LinkFile>?,
        @JsonProperty("link") val linkStr: String?
    )
    data class LinkFile(@JsonProperty("file") val file: String?)
}
