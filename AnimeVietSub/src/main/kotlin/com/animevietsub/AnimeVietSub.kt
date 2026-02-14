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
            "animevietsub.vip",
            "xem_anime_viet_sub",
            "phim1080",
            "animehay",
            "animetv"
        )
        
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"
        )
    }

    private val cfKiller = CloudflareKiller()
    
    private fun getHeaders(ua: String = USER_AGENTS[0]) = mapOf(
        "User-Agent" to ua,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "Cache-Control" to "max-age=0",
        "Sec-Ch-Ua"  to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Priority"   to "u=0, i"
    )

    private fun getAjaxHeaders(referer: String, ua: String = USER_AGENTS[0]) = mapOf(
        "User-Agent" to ua,
        "Accept"     to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin"           to mainUrl,
        "Referer"          to referer,
        "Sec-Ch-Ua"  to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/"                           to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ",
        "$mainUrl/hoat-hinh-trung-quoc/"      to "Hoạt Hình Trung Quốc"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#" || url == "null") return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.ifBlank { mainUrl }
        val url = if (page == 1) data else "${data.removeSuffix("/")}/trang-$page.html"
        
        val fixedUrl = fixUrl(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        
        val res = app.get(fixedUrl, interceptor = cfKiller, headers = getHeaders())
        val doc = res.document
        
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost, .film-item").mapNotNull { 
            it.toSearchResponse() 
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title, h3, h2, .title, .name, .film-name")?.text()?.trim() 
                    ?: a.attr("title").trim()
                    ?: return null
        
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                     ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                     ?: img?.attr("src"))
        
        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            posterUrl = poster 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = getHeaders()).document
            .select("article, .TPostMv, .item, .list-film li, .film-item").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw Exception("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = getHeaders()).document
        
        // Try to find watch button if not on episode page
        var episodesNodes = doc.select("ul.list-episode li a, .episode-list a, .ep-item a")
        if (episodesNodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-see, a[href*='/tap-'], .btn-watch a, a.watch_button, a[href*='/xem-phim/']")?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = getHeaders()).document
                episodesNodes = doc.select("ul.list-episode li a, .episode-list a, .ep-item a")
            }
        }

        val filmId = Regex("[/-]a(\\d+)").find(fixedUrl)?.groupValues?.get(1) 
            ?: doc.selectFirst("[data-film-id]")?.attr("data-film-id")
            ?: doc.selectFirst("input[name=film_id]")?.attr("value")
            ?: ""

        val episodes = episodesNodes.mapNotNull { ep ->
            val id = ep.attr("data-id").trim().ifEmpty { ep.attr("data-episode-id").trim() }
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim().ifEmpty { ep.attr("title") }
            val epNum = Regex("\\d+").find(name ?: "")?.value?.toIntOrNull()
            newEpisode("$href@@$filmId@@$id@@$name") {
                this.name = name
                this.episode = epNum
            }
        }

        if (episodes.isEmpty() && filmId.isNotBlank()) {
            // Single movie/OVA case
            return newAnimeLoadResponse(
                doc.selectFirst("h1.Title, .Title, h1, .film-name")?.text()?.trim() ?: "Anime",
                fixedUrl, 
                TvType.AnimeMovie
            ) {
                this.posterUrl = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image], .film-poster img")?.let { 
                    fixUrl(it.attr("data-src").ifEmpty { it.attr("data-original").ifEmpty { it.attr("src") } }) 
                }
                this.plot = doc.selectFirst(".Description, .InfoDesc, #film-content, .film-description")?.text()?.trim()
            }
        }

        val title = doc.selectFirst("h1.Title, .Title, h1, .film-name")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image], .film-poster img")?.let { 
            fixUrl(it.attr("data-src").ifEmpty { it.attr("data-original").ifEmpty { it.attr("src") } }) 
        }

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".Description, .InfoDesc, #film-content, .film-description")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) {
            logError("Invalid data format: $data")
            return false
        }
        
        val epUrl = parts[0]
        val filmId = parts[1]
        val episodeId = parts[2]
        val epName = parts.getOrNull(3) ?: "Unknown"

        logInfo("Loading links for: $epName (Film: $filmId, Episode: $episodeId)")

        // Strategy 1: Try multiple User Agents
        for (ua in USER_AGENTS) {
            try {
                if (tryLoadLinksWithUA(epUrl, filmId, episodeId, ua, subtitleCallback, callback)) {
                    return true
                }
            } catch (e: Exception) {
                logWarn("Failed with UA ${ua.take(30)}...: ${e.message}")
            }
        }

        // Strategy 2: Try alternative endpoints
        if (tryAlternativeEndpoints(epUrl, filmId, episodeId, subtitleCallback, callback)) {
            return true
        }

        // Strategy 3: Try direct extraction from page
        if (tryDirectExtraction(epUrl, subtitleCallback, callback)) {
            return true
        }

        logError("All strategies failed for: $epUrl")
        return false
    }

    private suspend fun tryLoadLinksWithUA(
        epUrl: String,
        filmId: String,
        episodeId: String,
        ua: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = getHeaders(ua)
        val ajaxHeaders = getAjaxHeaders(epUrl, ua)

        // Step 1: Get initial page and cookies
        val pageReq = app.get(epUrl, interceptor = cfKiller, headers = headers)
        var cookies = pageReq.cookies
        val pageDoc = pageReq.document

        // Extract any embedded data
        val embeddedUrl = pageDoc.selectFirst("iframe[src*=\".m3u8\"], iframe[data-src*=\".m3u8\"]")?.attr("src")
            ?: pageDoc.selectFirst("video source[src]")?.attr("src")
            ?: pageDoc.selectFirst("[data-video-url]")?.attr("data-video-url")
        
        if (!embeddedUrl.isNullOrBlank() && embeddedUrl.startsWith("http")) {
            logInfo("Found embedded URL: $embeddedUrl")
            return processVideoUrl(embeddedUrl, epUrl, callback)
        }

        // Step 2: Activate session
        val sessionUrls = listOf(
            "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
            "$mainUrl/ajax/get_episode?episodeId=$episodeId&filmId=$filmId",
            "$mainUrl/ajax/episode?filmId=$filmId&episodeId=$episodeId"
        )

        for (sessionUrl in sessionUrls) {
            try {
                val activeReq = app.get(sessionUrl, headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
                cookies = cookies + activeReq.cookies
                logInfo("Session activated: $sessionUrl")
                break
            } catch (e: Exception) {
                logWarn("Session activation failed for $sessionUrl: ${e.message}")
            }
        }

        // Step 3: Get player HTML
        val playerParams = listOf(
            mapOf("episodeId" to episodeId, "backup" to "1"),
            mapOf("episodeId" to episodeId, "backup" to "0"),
            mapOf("episodeId" to episodeId),
            mapOf("id" to episodeId, "filmId" to filmId)
        )

        var serverHtml: String? = null
        var finalCookies = cookies

        for (params in playerParams) {
            try {
                val step1Req = app.post("$mainUrl/ajax/player", data = params, 
                    headers = ajaxHeaders, cookies = finalCookies, interceptor = cfKiller)
                finalCookies = finalCookies + step1Req.cookies
                
                val resp = step1Req.parsedSafe<ServerSelectionResp>()
                if (!resp?.html.isNullOrBlank()) {
                    serverHtml = resp?.html
                    logInfo("Got player HTML with params: $params")
                    break
                }
            } catch (e: Exception) {
                logWarn("Player request failed with $params: ${e.message}")
            }
        }

        if (serverHtml.isNullOrBlank()) {
            // Try alternative player endpoint
            try {
                val altReq = app.get("$mainUrl/ajax/player?episodeId=$episodeId", 
                    headers = ajaxHeaders, cookies = finalCookies, interceptor = cfKiller)
                val altResp = altReq.parsedSafe<ServerSelectionResp>()
                serverHtml = altResp?.html
            } catch (e: Exception) {
                logWarn("Alternative player endpoint failed: ${e.message}")
            }
        }

        if (serverHtml.isNullOrBlank()) {
            logError("Could not get player HTML")
            return false
        }

        // Step 4: Parse server buttons
        val serverDoc = Jsoup.parse(serverHtml)
        val buttons = serverDoc.select("a.btn3dsv, a.server-btn, .server-item a, [data-play]")
        
        if (buttons.isEmpty()) {
            logError("No server buttons found in HTML")
            return false
        }

        logInfo("Found ${buttons.size} server buttons")

        // Try each button
        for (btn in buttons) {
            val hash = btn.attr("data-href").ifEmpty { btn.attr("data-link") }.ifEmpty { btn.attr("href") }
            val play = btn.attr("data-play")
            val btnId = btn.attr("data-id")
            
            if (hash.isBlank()) continue

            logInfo("Trying server button - play: $play, hash: ${hash.take(20)}...")

            // Try to get link
            val linkParamsList = mutableListOf<Map<String, String>>()
            
            if (play == "api" || play.isBlank()) {
                linkParamsList.add(mapOf("link" to hash, "id" to filmId))
                linkParamsList.add(mapOf("link" to hash, "id" to episodeId))
                linkParamsList.add(mapOf("link" to hash, "id" to filmId, "play" to "api"))
                linkParamsList.add(mapOf("link" to hash, "id" to episodeId, "play" to "api"))
            } else {
                linkParamsList.add(mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1"))
                linkParamsList.add(mapOf("link" to hash, "play" to play, "id" to btnId))
            }

            for (params in linkParamsList) {
                try {
                    val linkReq = app.post("$mainUrl/ajax/player", data = params,
                        headers = ajaxHeaders, cookies = finalCookies, interceptor = cfKiller)
                    val linkResp = linkReq.parsedSafe<PlayerResp>()
                    
                    // Try to extract link
                    val encrypted = linkResp?.linkArray?.firstOrNull()?.file 
                        ?: linkResp?.linkString
                    
                    if (!encrypted.isNullOrBlank()) {
                        logInfo("Got encrypted link: ${encrypted.take(50)}...")
                        
                        if (encrypted.startsWith("http")) {
                            if (processVideoUrl(encrypted, epUrl, callback)) return true
                        } else {
                            // Try all passwords
                            for (password in DECODE_PASSWORDS) {
                                val decrypted = decryptLink(encrypted, password)
                                if (!decrypted.isNullOrBlank() && decrypted.startsWith("http")) {
                                    logInfo("Decrypted with password: ${password.take(10)}...")
                                    if (processVideoUrl(decrypted, epUrl, callback)) return true
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logWarn("Link request failed: ${e.message}")
                }
            }
        }

        return false
    }

    private suspend fun tryAlternativeEndpoints(
        epUrl: String,
        filmId: String,
        episodeId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val endpoints = listOf(
            "$mainUrl/api/episode/$episodeId",
            "$mainUrl/api/player/$episodeId",
            "$mainUrl/player/$episodeId",
            "$mainUrl/embed/$episodeId",
            "$mainUrl/link/$episodeId"
        )

        for (endpoint in endpoints) {
            try {
                val resp = app.get(endpoint, interceptor = cfKiller, headers = getHeaders())
                val text = resp.text
                
                // Look for m3u8 or mp4 URLs
                val urlRegex = Regex("(https?://[^\\s\"'<>]+\\.(?:m3u8|mp4|mkv|webm))", RegexOption.IGNORE_CASE)
                val matches = urlRegex.findAll(text)
                
                for (match in matches) {
                    val url = match.groupValues[1]
                    if (processVideoUrl(url, epUrl, callback)) return true
                }

                // Try JSON parsing
                val json = try {
                    resp.parsedSafe<Map<String, Any>>()
                } catch (e: Exception) { null }
                
                if (json != null) {
                    val urls = extractUrlsFromJson(json)
                    for (url in urls) {
                        if (processVideoUrl(url, epUrl, callback)) return true
                    }
                }
            } catch (e: Exception) {
                logWarn("Alternative endpoint failed: $endpoint - ${e.message}")
            }
        }

        return false
    }

    private fun extractUrlsFromJson(json: Map<String, Any?>): List<String> {
        val urls = mutableListOf<String>()
        val jsonStr = json.toString()
        val urlRegex = Regex("(https?://[^\\s\"'<>{}\\[\\]]+\\.(?:m3u8|mp4|mkv|webm|ts))", RegexOption.IGNORE_CASE)
        urlRegex.findAll(jsonStr).forEach { urls.add(it.groupValues[1]) }
        return urls
    }

    private suspend fun tryDirectExtraction(
        epUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(epUrl, interceptor = cfKiller, headers = getHeaders()).document
            
            // Look for video elements
            val videoSelectors = listOf(
                "video source[src]",
                "video[src]",
                "[data-video-url]",
                "[data-src*=\".m3u8\"]",
                "[data-src*=\".mp4\"]",
                "iframe[src*=\"player\"]",
                "iframe[src*=\"embed\"]"
            )

            for (selector in videoSelectors) {
                val el = doc.selectFirst(selector) ?: continue
                val url = el.attr("src").ifEmpty { el.attr("data-src") }.ifEmpty { el.attr("data-video-url") }
                if (!url.isNullOrBlank() && url.startsWith("http")) {
                    logInfo("Direct extraction found: $url")
                    if (processVideoUrl(url, epUrl, callback)) return true
                }
            }

            // Look in scripts
            val scripts = doc.select("script")
            for (script in scripts) {
                val text = script.data()
                if (text.contains(".m3u8") || text.contains("player") || text.contains("source")) {
                    val urlRegex = Regex("(https?://[^\\s\"'<>]+\\.(?:m3u8|mp4))", RegexOption.IGNORE_CASE)
                    val matches = urlRegex.findAll(text)
                    for (match in matches) {
                        val url = match.groupValues[1]
                        if (processVideoUrl(url, epUrl, callback)) return true
                    }
                }
            }
        } catch (e: Exception) {
            logWarn("Direct extraction failed: ${e.message}")
        }

        return false
    }

    private suspend fun processVideoUrl(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var finalUrl = url
        
        // Follow redirects for non-m3u8 URLs
        if (!url.contains(".m3u8") && !url.contains(".mp4")) {
            try {
                val headResp = app.get(url, headers = mapOf(
                    "User-Agent" to USER_AGENTS[0],
                    "Referer" to referer
                ), interceptor = cfKiller)
                finalUrl = headResp.url
            } catch (e: Exception) {
                logWarn("Redirect follow failed: ${e.message}")
            }
        }

        val videoHeaders = mapOf(
            "User-Agent" to USER_AGENTS[0],
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9",
            "Range" to "bytes=0-"
        )

        return when {
            finalUrl.contains(".m3u8", ignoreCase = true) -> {
                try {
                    callback(newExtractorLink(name, "Auto", finalUrl) {
                        this.headers = videoHeaders
                        this.type = ExtractorLinkType.M3U8
                    })
                    
                    // Also extract qualities
                    M3u8Helper.generateM3u8(name, finalUrl, referer, headers = videoHeaders).forEach { link ->
                        callback(link)
                    }
                    true
                } catch (e: Exception) {
                    logWarn("M3U8 processing failed: ${e.message}")
                    false
                }
            }
            finalUrl.contains(".mp4", ignoreCase = true) || 
            finalUrl.contains(".mkv", ignoreCase = true) ||
            finalUrl.contains(".webm", ignoreCase = true) -> {
                callback(newExtractorLink(name, "Direct", finalUrl) {
                    this.headers = videoHeaders
                })
                true
            }
            else -> {
                logWarn("Unknown video format: $finalUrl")
                false
            }
        }
    }

    private fun decryptLink(encrypted: String, password: String): String? {
        return try {
            if (encrypted.startsWith("http")) return encrypted
            
            // Clean the encrypted string
            val cleanEncrypted = encrypted.replace("\\s".toRegex(), "")
                .replace("\"", "")
                .trim()

            val decoded = Base64.decode(cleanEncrypted, Base64.DEFAULT)
            if (decoded.size < 17) return null
            
            val iv = decoded.copyOfRange(0, 16)
            val ct = decoded.copyOfRange(16, decoded.size)
            
            val key = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            
            // Try inflate first
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
            
            result.replace("\"", "").trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) { 
            null 
        }
    }

    private fun logInfo(msg: String) = println("[$name] INFO: $msg")
    private fun logWarn(msg: String) = println("[$name] WARN: $msg")
    private fun logError(msg: String) = println("[$name] ERROR: $msg")

    data class ServerSelectionResp(@JsonProperty("html") val html: String? = null)
    
    data class PlayerResp(
        @JsonProperty("link") val linkRaw: Any? = null,
        @JsonProperty("success") val success: Int? = null,
        @JsonProperty("data") val data: Any? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkFile>? 
            get() = when (linkRaw) {
                is List<*> -> (linkRaw as? List<Map<String, Any?>>)?.map { LinkFile(it["file"] as? String) }
                is Map<*, *> -> listOf(LinkFile((linkRaw as? Map<String, Any?>)?.get("file") as? String))
                else -> null
            }
        
        val linkString: String? 
            get() = when (linkRaw) {
                is String -> linkRaw
                else -> null
            }
        
        @Suppress("UNCHECKED_CAST")
        val dataArray: List<LinkFile>?
            get() = (data as? List<Map<String, Any?>>)?.map { LinkFile(it["file"] as? String) }
    }

    data class LinkFile(@JsonProperty("file") val file: String? = null)
}
