package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

// ================================================================================
// RESPONSE TYPE CLASSIFICATION
// ================================================================================

enum class ResponseType {
    HTML_DOM, JAVASCRIPT, JSON_API, M3U8_PLAYLIST, MPD_MANIFEST, ENCRYPTED_PAYLOAD, PACKED_JS, UNKNOWN
}

// ================================================================================
// RESPONSE INTELLIGENCE ANALYZER
// ================================================================================

object ResponseAnalyzer {

    private val m3u8Signatures = listOf("#EXTM3U", "#EXT-X-VERSION", "#EXTINF")
    private val htmlSignatures = listOf("<!DOCTYPE html", "<html", "<head>", "<body>")
    private val packedJsSignatures = listOf("eval(function(p,a,c,k,e,d", "String.fromCharCode")

    fun analyzeType(body: String?): ResponseType {
        if (body.isNullOrBlank()) return ResponseType.UNKNOWN
        if (m3u8Signatures.any { body.contains(it) }) return ResponseType.M3U8_PLAYLIST
        if (htmlSignatures.any { body.contains(it, ignoreCase = true) }) return ResponseType.HTML_DOM
        if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) return ResponseType.JSON_API
        if (packedJsSignatures.any { body.contains(it) }) return ResponseType.PACKED_JS
        return ResponseType.UNKNOWN
    }

    fun extractUrlsFromResponse(body: String, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()

        // Direct stream URL patterns
        val streamPatterns = listOf(
            Regex("""https?://[^\s"'`<>]+\.m3u8[^\s"'`<>]*"""),
            Regex("""https?://[^\s"'`<>]+\.mp4[^\s"'`<>]*"""),
            Regex("""https?://[^\s"'`<>]+\.mpd[^\s"'`<>]*""")
        )
        streamPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { urls.add(it.value) }
        }

        // Quoted URLs
        Regex(""""(https?://[^"]+)"""").findAll(body).forEach { urls.add(it.groupValues[1]) }
        Regex("""'(https?://[^']+)'""").findAll(body).forEach { urls.add(it.groupValues[1]) }

        // DOM extraction
        if (body.contains("<")) {
            try {
                val doc = Jsoup.parse(body)
                listOf("data-src", "data-url", "data-href", "data-file", "data-link", "data-source").forEach { attr ->
                    doc.select("[$attr]").forEach { el ->
                        val v = el.attr(attr); if (v.isNotBlank()) urls.add(v)
                    }
                }
                doc.select("source[src], video[src], iframe[src]").forEach { el ->
                    val v = el.attr("src"); if (v.isNotBlank()) urls.add(v)
                }
            } catch (e: Exception) { /* Skip */ }
        }

        return urls.map { fixUrlStatic(it, baseUrl) }.distinct().filter { it.startsWith("http") }
    }

    private fun fixUrlStatic(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { URL(URL(baseUrl), url).toString() } catch (e: Exception) { url }
    }
}

// ================================================================================
// UNIVERSAL DATA MINER
// ================================================================================

object DataMiner {

    private val encryptedPatterns = listOf(
        Regex("""(?:file|link|source|url|enc|stream|video)\s*[:=]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""["']([A-Za-z0-9+/]{40,}={0,2})["']""")
    )

    fun mineEncryptedData(body: String): List<String> {
        val results = mutableListOf<String>()
        encryptedPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                val v = match.groupValues[1]
                if (v.length > 20 && calculateEntropy(v) > 3.5) results.add(v)
            }
        }
        return results.distinct()
    }

    fun mineHashes(body: String): List<String> {
        val results = mutableListOf<String>()
        listOf(
            Regex("""data-hash\s*=\s*["']([^"']+)["']"""),
            Regex("""data-id\s*=\s*["']([^"']+)["']"""),
            Regex("""data-key\s*=\s*["']([^"']+)["']"""),
            Regex("""data-href\s*=\s*["']([^"']+)["']""")
        ).forEach { pattern ->
            pattern.findAll(body).forEach { results.add(it.groupValues[1]) }
        }
        return results.distinct()
    }

    private fun calculateEntropy(str: String): Double {
        val freq = mutableMapOf<Char, Int>()
        str.forEach { freq[it] = (freq[it] ?: 0) + 1 }
        var entropy = 0.0
        val len = str.length.toDouble()
        freq.values.forEach { count ->
            val p = count / len
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2.0))
        }
        return entropy
    }
}

// ================================================================================
// JS STATIC REVERSE ENGINE
// ================================================================================

object JSReverseEngine {

    fun unpackEval(js: String): String {
        var result = js
        for (i in 1..5) {
            val next = tryUnpackOneLevel(result)
            if (next == result) break
            result = next
        }
        return result
    }

    private fun tryUnpackOneLevel(js: String): String {
        // Dean Edwards P,A,C,K,E,R unpacker
        val packed = Regex(
            """\}\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*,"""
        ).find(js)
        if (packed != null) {
            try {
                val p = packed.groupValues[1]
                val a = packed.groupValues[2].toInt()
                val c = packed.groupValues[3].toInt()
                val k = packed.groupValues[4].split("|")
                var res = p
                for (i in c - 1 downTo 0) {
                    if (i < k.size && k[i].isNotEmpty()) {
                        res = res.replace(Regex("\\b${i.toString(a)}\\b"), k[i])
                    }
                }
                return res
            } catch (e: Exception) { /* Continue */ }
        }

        // atob() decode
        val atob = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""").find(js)
        if (atob != null) {
            try {
                val decoded = String(Base64.decode(atob.groupValues[1], Base64.DEFAULT))
                return js.replace(atob.value, "'$decoded'")
            } catch (e: Exception) { /* Continue */ }
        }

        // String.fromCharCode
        val sfc = Regex("""String\.fromCharCode\s*\(([\d\s,]+)\)""").find(js)
        if (sfc != null) {
            try {
                val codes = sfc.groupValues[1].split(",").map { it.trim().toInt() }
                val decoded = codes.map { it.toChar() }.joinToString("")
                return js.replace(sfc.value, "'$decoded'")
            } catch (e: Exception) { /* Continue */ }
        }

        return js
    }

    fun extractUrlsFromJS(js: String): List<String> {
        val urls = mutableListOf<String>()
        listOf(
            Regex("""['"]https?://[^'"]+\.m3u8[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mp4[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]*(?:stream|video|play|hls|cdn)[^'"]*['"]""")
        ).forEach { pattern ->
            pattern.findAll(js).forEach { match ->
                urls.add(match.value.trim('\'', '"'))
            }
        }
        return urls.distinct()
    }
}

// ================================================================================
// ENCRYPTION AUTOBREAK SYSTEM
// ================================================================================

object EncryptionBreaker {

    // Known passwords – ordered by most likely
    private val knownPasswords = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub",
        "animevsub",
        "animevsub_secret",
        "video_decrypt_key",
        "streaming_key",
        "player_key",
        "secret_key",
        "encryption_key",
        "VSub@2024",
        "VSub@2025",
        "pass",
        "password",
        "key",
        ""
    )

    private val hashAlgorithms = listOf("SHA-256", "MD5", "SHA-1")
    private val aesModes = listOf(
        "AES/CBC/PKCS5Padding",
        "AES/CBC/PKCS7Padding",
        "AES/CFB/NoPadding",
        "AES/OFB/NoPadding"
    )

    fun autoBreak(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null

        // Clean up the input
        val cleaned = encrypted.trim()
            .replace("\n", "").replace("\r", "").replace(" ", "")

        // Strategy 1: direct Base64 decode
        try {
            val decoded = String(Base64.decode(cleaned, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (isValidOutput(decoded)) return decoded
        } catch (e: Exception) { /* Continue */ }

        // Strategy 2: known password AES
        for (password in knownPasswords) {
            val result = tryAllAES(cleaned, password)
            if (result != null) return result
        }

        // Strategy 3: multi-layer
        for (password in knownPasswords) {
            for (ivStrat in listOf("prefix", "zero", "fromKey")) {
                for (hash in hashAlgorithms) {
                    for (mode in aesModes) {
                        try {
                            val r = decryptAES(cleaned, password, hash, mode, ivStrat)
                            if (r != null && isValidOutput(r)) return r
                            // Try one more layer
                            if (r != null && r.length > 20) {
                                val r2 = tryAllAES(r, password)
                                if (r2 != null) return r2
                            }
                        } catch (e: Exception) { /* Continue */ }
                    }
                }
            }
        }

        return null
    }

    private fun tryAllAES(encrypted: String, password: String): String? {
        for (hash in hashAlgorithms) {
            for (mode in aesModes) {
                for (ivStrat in listOf("prefix", "zero", "fromKey")) {
                    try {
                        val result = decryptAES(encrypted, password, hash, mode, ivStrat)
                        if (result != null && isValidOutput(result)) return result
                    } catch (e: Exception) { /* Continue */ }
                }
            }
        }
        return null
    }

    private fun decryptAES(
        encrypted: String, password: String,
        hashAlgorithm: String, aesMode: String, ivStrategy: String
    ): String? {
        val decoded = try {
            Base64.decode(encrypted, Base64.DEFAULT)
        } catch (e: Exception) { return null }

        if (decoded.size < 16) return null

        val key = when (hashAlgorithm) {
            "SHA-256" -> MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(StandardCharsets.UTF_8))
            "SHA-1" -> MessageDigest.getInstance("SHA-1")
                .digest(password.toByteArray(StandardCharsets.UTF_8)).copyOf(16)
            "MD5" -> MessageDigest.getInstance("MD5")
                .digest(password.toByteArray(StandardCharsets.UTF_8))
            else -> return null
        }

        val (iv, ciphertext) = when (ivStrategy) {
            "prefix" -> Pair(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, decoded.size))
            "zero" -> Pair(ByteArray(16), decoded)
            "fromKey" -> Pair(key.copyOfRange(0, 16), decoded)
            else -> Pair(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, decoded.size))
        }

        if (ciphertext.isEmpty()) return null

        return try {
            val cipher = Cipher.getInstance(aesMode)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ciphertext)
            tryDecompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun tryDecompress(data: ByteArray): String? {
        try {
            val inflater = Inflater(true); inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf); if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) { /* Continue */ }
        try {
            val gzip = GZIPInputStream(ByteArrayInputStream(data))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192); var n = gzip.read(buf)
            while (n > 0) { out.write(buf, 0, n); n = gzip.read(buf) }
            gzip.close()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) { /* Continue */ }
        return null
    }

    fun isValidOutput(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.startsWith("http://") || text.startsWith("https://") ||
               text.startsWith("//") || text.contains(".m3u8") ||
               text.contains(".mp4") || text.contains(".mpd")
    }
}

// ================================================================================
// STREAM VALIDATOR
// ================================================================================

object StreamValidator {
    fun isStreamUrl(url: String): Boolean = url.contains(".m3u8") ||
            url.contains(".mp4") || url.contains(".mpd") || url.contains("/stream/")
}

// ================================================================================
// MAIN PLUGIN CLASS
// ================================================================================

class AnimeVietSub : MainAPI() {

    // Try multiple known domains
    private val domains = listOf(
        "https://animevietsub.ee",
        "https://animevietsub.cc",
        "https://animevietsub.tv",
        "https://animevietsub.io",
        "https://animevietsub.net",
        "https://animevietsub.me"
    )

    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val debugMode = true
    private val cfKiller = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none"
    )

    private fun ajaxHeaders(referer: String) = defaultHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to referer,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun videoHeaders(referer: String) = mapOf(
        "User-Agent" to ua,
        "Referer" to referer,
        "Origin" to mainUrl,
        "Accept" to "*/*"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    // ── Utility ──────────────────────────────────────────────────────────────

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        val t = url.trim()
        return when {
            t.startsWith("http") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "$mainUrl$t"
            else -> "$mainUrl/$t"
        }
    }

    /** Extract filmId from a URL using multiple patterns */
    private fun extractFilmId(url: String): String {
        // Pattern 1: -a12345 or /a12345
        Regex("""[/-]a(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        // Pattern 2: trailing digits before .html or /
        Regex("""-(\d+)(?:\.html|/)?$""").find(url)?.groupValues?.get(1)?.let { return it }
        // Pattern 3: query param id=
        Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    /** Extract episodeId from ep href using multiple patterns */
    private fun extractEpisodeHrefId(href: String): String {
        Regex("""tap[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        Regex("""ep[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        Regex("""episode[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private fun log(tag: String, msg: String) {
        if (debugMode) println("AVS[$tag] $msg")
    }

    // ── Safe JSON field extractor ─────────────────────────────────────────────

    /** Parse raw JSON response body – handles link as String OR Array */
    private fun parsePlayerResponse(body: String): Pair<String?, List<String>> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            val link = json["link"]
            when (link) {
                is String -> Pair(link.takeIf { it.isNotBlank() }, emptyList())
                is List<*> -> {
                    val files = link.filterIsInstance<Map<String, Any?>>()
                        .mapNotNull { it["file"] as? String }
                        .filter { it.isNotBlank() }
                    Pair(null, files)
                }
                else -> {
                    // Try "links" key
                    val linksArr = (json["links"] as? List<*>)
                        ?.filterIsInstance<Map<String, Any?>>()
                        ?.mapNotNull { it["file"] as? String } ?: emptyList()
                    Pair(json["url"] as? String, linksArr)
                }
            }
        } catch (e: Exception) {
            log("PARSE", "JSON parse error: ${e.message}")
            Pair(null, emptyList())
        }
    }

    // ── Main page / search / load ─────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.ifBlank { mainUrl }
        val url = if (page == 1) data else "${data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fixUrl(url) ?: mainUrl, interceptor = cfKiller, headers = defaultHeaders).document
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title, h3, h2, .title, .name")?.text()?.trim()
            ?: a.attr("title").trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fixUrl(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article, .TPostMv, .item, .list-film li")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document

        // Find episode list – try multiple selectors
        var episodesNodes = doc.select(
            "ul.list-episode li a, " +
            ".list-eps a, .server-list a, " +
            ".list-episode a, .episodes a, " +
            "#list_episodes a"
        )

        // If not found, follow "watch" link
        if (episodesNodes.isEmpty()) {
            val watchUrl = doc.selectFirst(
                "a.btn-see, a[href*='/tap-'], a[href*='/episode-'], .btn-watch a, a.watch_button, a.xem-phim"
            )?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = defaultHeaders).document
                episodesNodes = doc.select(
                    "ul.list-episode li a, .list-eps a, .server-list a, .list-episode a, .episodes a, #list_episodes a"
                )
            }
        }

        val filmId = extractFilmId(fixedUrl)
        log("LOAD", "filmId='$filmId' from $fixedUrl")

        val episodes = episodesNodes.mapNotNull { ep ->
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            // dataId from attribute, fallback to URL pattern
            val dataId = ep.attr("data-id").trim()
                .ifBlank { ep.attr("id").trim() }
                .ifBlank { ep.attr("data-episodeid").trim() }
            val hrefId = extractEpisodeHrefId(href)
            val name = ep.text().trim().ifBlank { ep.attr("title").trim() }

            log("LOAD", "ep href=$href dataId='$dataId' hrefId='$hrefId'")

            // Encode: epUrl@@filmId@@dataId@@hrefId
            newEpisode("$href@@$filmId@@$dataId@@$hrefId") {
                this.name = name
                this.episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
                    ?: hrefId.toIntOrNull()
            }
        }

        val title = doc.selectFirst("h1.Title, h1, .Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOADLINKS – STATEFUL RESOLUTION PIPELINE
    // ═══════════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl  = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val dataId = parts.getOrNull(2) ?: ""  // data-id attribute
        val hrefId = parts.getOrNull(3) ?: ""  // extracted from href

        log("LINKS", "epUrl=$epUrl filmId='$filmId' dataId='$dataId' hrefId='$hrefId'")

        val vHeaders = videoHeaders(epUrl)
        val aHeaders = ajaxHeaders(epUrl)
        var found = false

        // ── Warm up session & cookies ──────────────────────────────────────────
        val cookies = mutableMapOf<String, String>()
        try {
            val pageRes = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            cookies.putAll(pageRes.cookies)
            log("LINKS", "Cookies loaded: ${cookies.keys}")
        } catch (e: Exception) {
            log("LINKS", "Page load failed: ${e.message}")
        }

        // ── STRATEGY A: AJAX Player API ────────────────────────────────────────
        log("LINKS", "STRATEGY A: AJAX Player API")

        // Determine all episodeId candidates to try
        val episodeCandidates = mutableListOf<String>()
        if (dataId.isNotBlank()) episodeCandidates.add(dataId)
        if (hrefId.isNotBlank() && hrefId != dataId) episodeCandidates.add(hrefId)
        if (filmId.isNotBlank()) episodeCandidates.add(filmId)

        for (episodeId in episodeCandidates) {
            if (found) break
            log("LINKS", "Trying episodeId='$episodeId'")

            try {
                // Step 1: Get server list HTML
                val serverHtml = app.post(
                    "$mainUrl/ajax/player",
                    data = mapOf("episodeId" to episodeId, "backup" to "1"),
                    headers = aHeaders,
                    cookies = cookies,
                    interceptor = cfKiller
                )
                cookies.putAll(serverHtml.cookies)
                val body1 = serverHtml.text ?: continue

                log("LINKS", "Step1 response length=${body1.length}")

                // Parse server HTML – try multiple selectors
                val htmlContent = try {
                    val j1 = mapper.readValue(body1, Map::class.java) as Map<*, *>
                    (j1["html"] as? String) ?: body1
                } catch (e: Exception) { body1 }

                val serverDoc = Jsoup.parse(htmlContent)
                val serverBtns = serverDoc.select(
                    "a.btn3dsv, a[data-href], a[data-play], " +
                    ".server-item a, .btn-server, li[data-id] a, .episodes-btn a"
                )

                log("LINKS", "Found ${serverBtns.size} server buttons")

                if (serverBtns.isEmpty()) {
                    // Try to mine URLs directly from htmlContent
                    val directUrls = ResponseAnalyzer.extractUrlsFromResponse(htmlContent, epUrl)
                    for (u in directUrls) {
                        if (StreamValidator.isStreamUrl(u)) {
                            log("LINKS", "Direct URL from server HTML: $u")
                            found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                        }
                    }
                    continue
                }

                // Step 2: Try each server button
                for (btn in serverBtns) {
                    if (found) break

                    val hash   = btn.attr("data-href").ifBlank { btn.attr("href") }.trim()
                    val play   = btn.attr("data-play").trim()
                    val btnId  = btn.attr("data-id").trim().ifBlank { episodeId }

                    if (hash.isBlank()) continue
                    log("LINKS", "Server: play='$play' hash='$hash' btnId='$btnId'")

                    // If hash looks like a direct URL → just use it
                    if (hash.startsWith("http")) {
                        log("LINKS", "Direct URL server: $hash")
                        found = emitStream(hash, vHeaders, subtitleCallback, callback) || found
                        if (found) break
                    }

                    // Step 2a: activate episode (optional, ignore errors)
                    try {
                        app.get(
                            "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
                            headers = aHeaders, cookies = cookies, interceptor = cfKiller
                        ).let { cookies.putAll(it.cookies) }
                    } catch (e: Exception) { /* Continue */ }

                    // Step 2b: Request actual stream link
                    val paramSets = mutableListOf<Map<String, String>>()
                    if (play == "api" || play.isBlank()) {
                        paramSets.add(mapOf("link" to hash, "id" to episodeId))
                        paramSets.add(mapOf("link" to hash, "id" to btnId))
                        paramSets.add(mapOf("link" to hash, "id" to filmId))
                    } else {
                        paramSets.add(mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1"))
                        paramSets.add(mapOf("link" to hash, "play" to play, "id" to episodeId))
                    }

                    for (params in paramSets) {
                        if (found) break
                        try {
                            val step2 = app.post(
                                "$mainUrl/ajax/player",
                                data = params,
                                headers = aHeaders,
                                cookies = cookies,
                                interceptor = cfKiller
                            )
                            cookies.putAll(step2.cookies)
                            val body2 = step2.text ?: continue
                            log("LINKS", "Step2 body (first 200): ${body2.take(200)}")

                            found = processPlayerBody(body2, epUrl, play, vHeaders, subtitleCallback, callback) || found
                        } catch (e: Exception) {
                            log("LINKS", "Step2 error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                log("LINKS", "Strategy A error with episodeId=$episodeId: ${e.message}")
            }
        }

        // ── STRATEGY B: Direct page scraping ──────────────────────────────────
        if (!found) {
            log("LINKS", "STRATEGY B: Direct page scraping")
            found = strategyDirectScrape(epUrl, cookies, vHeaders, subtitleCallback, callback) || found
        }

        // ── STRATEGY C: JS Reverse ────────────────────────────────────────────
        if (!found) {
            log("LINKS", "STRATEGY C: JS Reverse")
            found = strategyJSReverse(epUrl, vHeaders, subtitleCallback, callback) || found
        }

        // ── STRATEGY D: Alternative AJAX endpoints ─────────────────────────────
        if (!found) {
            log("LINKS", "STRATEGY D: Alt endpoints")
            found = strategyAltEndpoints(epUrl, filmId, dataId, hrefId, vHeaders, subtitleCallback, callback) || found
        }

        // ── STRATEGY E: Data mining + encryption break ─────────────────────────
        if (!found) {
            log("LINKS", "STRATEGY E: Data mining")
            found = strategyDataMining(epUrl, vHeaders, subtitleCallback, callback) || found
        }

        log("LINKS", "Final result: found=$found")
        return found
    }

    // ── Process player API response body ──────────────────────────────────────

    private suspend fun processPlayerBody(
        body: String, epUrl: String, play: String,
        vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false

        val (directLink, encryptedFiles) = parsePlayerResponse(body)
        var found = false

        // Case 1: direct string link
        if (!directLink.isNullOrBlank()) {
            log("LINKS", "Direct link found: $directLink")
            if (directLink.startsWith("http")) {
                found = emitStream(directLink, vHeaders, subtitleCallback, callback) || found
            }
        }

        // Case 2: encrypted file array
        for (enc in encryptedFiles) {
            log("LINKS", "Encrypted file: ${enc.take(60)}...")
            // First try raw as URL
            if (enc.startsWith("http")) {
                found = emitStream(enc, vHeaders, subtitleCallback, callback) || found
                continue
            }
            // Try decryption
            val decrypted = EncryptionBreaker.autoBreak(enc)
            if (decrypted != null) {
                log("LINKS", "Decrypted: $decrypted")
                val finalUrl = followRedirects(decrypted, vHeaders)
                found = emitStream(finalUrl, vHeaders, subtitleCallback, callback) || found
            }
        }

        // Case 3: fallback – mine URLs from raw body
        if (!found) {
            val urls = ResponseAnalyzer.extractUrlsFromResponse(body, epUrl)
            for (u in urls) {
                if (StreamValidator.isStreamUrl(u)) {
                    found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                }
            }
        }

        // Case 4: body itself is an encrypted string
        if (!found && body.trim().let { !it.startsWith("{") && !it.startsWith("[") && !it.startsWith("<") }) {
            val dec = EncryptionBreaker.autoBreak(body.trim())
            if (dec != null) {
                log("LINKS", "Body itself decrypted: $dec")
                found = emitStream(dec, vHeaders, subtitleCallback, callback) || found
            }
        }

        return found
    }

    // ── Strategy B: Direct page scraping ──────────────────────────────────────

    private suspend fun strategyDirectScrape(
        epUrl: String,
        cookies: Map<String, String>,
        vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders, cookies = cookies).document

            // Look for video sources in iframes
            val iframeSrcs = doc.select("iframe[src], iframe[data-src]").map {
                it.attr("src").ifBlank { it.attr("data-src") }
            }.filter { it.isNotBlank() }

            log("LINKS", "Found ${iframeSrcs.size} iframes")

            var found = false
            for (src in iframeSrcs) {
                val fullSrc = fixUrl(src) ?: continue
                log("LINKS", "Iframe: $fullSrc")
                try {
                    // Try CloudStream extractors first
                    loadExtractor(fullSrc, epUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    // Try fetching iframe content
                    try {
                        val iframeBody = app.get(fullSrc, headers = vHeaders, interceptor = cfKiller).text ?: continue
                        val iUrls = ResponseAnalyzer.extractUrlsFromResponse(iframeBody, fullSrc)
                        for (u in iUrls) {
                            if (StreamValidator.isStreamUrl(u)) {
                                found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                            }
                        }
                    } catch (e2: Exception) { /* Continue */ }
                }
            }

            // Look for video tags
            val videoSrcs = doc.select("video source[src], video[src]").map {
                it.attr("src")
            }.filter { it.isNotBlank() }

            for (src in videoSrcs) {
                val fullSrc = fixUrl(src) ?: continue
                found = emitStream(fullSrc, vHeaders, subtitleCallback, callback) || found
            }

            // Look for data attributes that might contain stream URLs
            val dataSrcs = doc.select("[data-file], [data-url], [data-source], [data-stream]")
                .map { it.attr("data-file").ifBlank { it.attr("data-url").ifBlank { it.attr("data-source").ifBlank { it.attr("data-stream") } } } }
                .filter { it.isNotBlank() }

            for (src in dataSrcs) {
                val fullSrc = fixUrl(src) ?: continue
                if (StreamValidator.isStreamUrl(fullSrc)) {
                    found = emitStream(fullSrc, vHeaders, subtitleCallback, callback) || found
                } else {
                    val dec = EncryptionBreaker.autoBreak(src)
                    if (dec != null) {
                        found = emitStream(dec, vHeaders, subtitleCallback, callback) || found
                    }
                }
            }

            // Extract inline script URLs
            val scripts = doc.select("script:not([src])").map { it.html() }
            for (script in scripts) {
                if (script.length < 50) continue
                val unpacked = JSReverseEngine.unpackEval(script)
                val urls = JSReverseEngine.extractUrlsFromJS(unpacked) +
                           ResponseAnalyzer.extractUrlsFromResponse(unpacked, epUrl)
                for (u in urls) {
                    if (StreamValidator.isStreamUrl(u)) {
                        found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                    }
                }
            }

            found
        } catch (e: Exception) {
            log("LINKS", "DirectScrape error: ${e.message}")
            false
        }
    }

    // ── Strategy C: JS Reverse ─────────────────────────────────────────────────

    private suspend fun strategyJSReverse(
        epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val body = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders).text ?: return false
            val unpacked = JSReverseEngine.unpackEval(body)
            var found = false
            val urls = JSReverseEngine.extractUrlsFromJS(unpacked) +
                       ResponseAnalyzer.extractUrlsFromResponse(unpacked, epUrl)
            for (u in urls.distinct()) {
                if (StreamValidator.isStreamUrl(u)) {
                    found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                }
            }
            found
        } catch (e: Exception) {
            log("LINKS", "JSReverse error: ${e.message}")
            false
        }
    }

    // ── Strategy D: Alternative endpoints ─────────────────────────────────────

    private suspend fun strategyAltEndpoints(
        epUrl: String, filmId: String, dataId: String, hrefId: String,
        vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = listOf(dataId, hrefId, filmId).filter { it.isNotBlank() }.distinct()
        var found = false

        for (id in ids) {
            val endpoints = listOf(
                "$mainUrl/ajax/player?episodeId=$id",
                "$mainUrl/ajax/getLink?filmId=$filmId&episodeId=$id",
                "$mainUrl/ajax/stream/$id",
                "$mainUrl/ajax/player?id=$id",
                "$mainUrl/api/get_link/$id"
            )
            for (endpoint in endpoints) {
                if (found) return true
                try {
                    val res = app.get(endpoint, headers = defaultHeaders, interceptor = cfKiller)
                    val body = res.text ?: continue
                    log("LINKS", "Alt endpoint $endpoint → ${body.take(100)}")
                    found = processPlayerBody(body, epUrl, "", vHeaders, subtitleCallback, callback) || found
                } catch (e: Exception) { /* Continue */ }
            }
        }
        return found
    }

    // ── Strategy E: Data mining ───────────────────────────────────────────────

    private suspend fun strategyDataMining(
        epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val body = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders).text ?: return false
            var found = false

            val encData = DataMiner.mineEncryptedData(body)
            for (enc in encData) {
                if (enc.startsWith("http")) {
                    found = emitStream(enc, vHeaders, subtitleCallback, callback) || found
                    continue
                }
                val dec = EncryptionBreaker.autoBreak(enc)
                if (dec != null && EncryptionBreaker.isValidOutput(dec)) {
                    log("LINKS", "DataMining decrypted: $dec")
                    found = emitStream(dec, vHeaders, subtitleCallback, callback) || found
                }
            }

            val urls = ResponseAnalyzer.extractUrlsFromResponse(body, epUrl)
            for (u in urls) {
                if (StreamValidator.isStreamUrl(u)) {
                    found = emitStream(u, vHeaders, subtitleCallback, callback) || found
                }
            }

            found
        } catch (e: Exception) {
            log("LINKS", "DataMining error: ${e.message}")
            false
        }
    }

    // ── Stream emitter ────────────────────────────────────────────────────────

    private suspend fun emitStream(
        url: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val clean = url.trim()

        return try {
            val referer = headers["Referer"] ?: mainUrl
            when {
                clean.contains(".m3u8") -> {
                    callback(
                        newExtractorLink(this.name, this.name, clean) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.M3U8
                            this.headers = headers
                        }
                    )
                    // Also try to generate quality variants
                    try {
                        M3u8Helper.generateM3u8(this.name, clean, mainUrl, headers = headers)
                            .forEach { link -> link.headers = headers; callback(link) }
                    } catch (e: Exception) { /* Main link still works */ }
                    true
                }
                clean.contains(".mp4") -> {
                    callback(
                        newExtractorLink(this.name, this.name, clean) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.VIDEO
                            this.headers = headers
                        }
                    )
                    true
                }
                else -> {
                    // Try built-in extractors (handles many embed providers)
                    try {
                        loadExtractor(clean, referer, subtitleCallback, callback)
                        true
                    } catch (e: Exception) {
                        // Emit as generic link
                        callback(
                            newExtractorLink(this.name, this.name, clean) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.VIDEO
                                this.headers = headers
                            }
                        )
                        true
                    }
                }
            }
        } catch (e: Exception) {
            log("EMIT", "Error emitting $clean: ${e.message}")
            false
        }
    }

    // ── Redirect follower ─────────────────────────────────────────────────────

    private suspend fun followRedirects(startUrl: String, headers: Map<String, String>): String {
        if (startUrl.contains(".m3u8")) return startUrl
        var current = startUrl
        try {
            repeat(5) {
                val res = app.get(current, headers = headers, interceptor = cfKiller)
                if (res.url.contains(".m3u8") || res.url == current) return res.url
                current = res.url
            }
        } catch (e: Exception) { /* Return last known URL */ }
        return current
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class ServerSelectionResp(@JsonProperty("html") val html: String? = null)
