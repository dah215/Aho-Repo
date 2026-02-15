package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

// ==================== DATA CLASSES ====================

data class PlayerResponse(
    @JsonProperty("_fxStatus") val fxStatus: Int? = null,
    @JsonProperty("success") val success: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("link") val link: List<LinkItem>? = null,
    @JsonProperty("html") val html: String? = null,
    @JsonProperty("playTech") val playTech: String? = null
)

data class LinkItem(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null
)

// ==================== CRYPTO UTILITIES ====================

/**
 * Crypto utilities for AnimeVietSub
 * Supports 2 decrypt methods:
 * 1. OpenSSL format with evpKDF (MD5-based) - newer format
 * 2. Simple SHA-256 key + 16-byte IV - old 2019 format
 */
object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)

    // Password from old 2019 plugin: base64Decode("ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ=")
    private const val MAIN_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"

    private val PASSWORDS = listOf(
        MAIN_PASSWORD,
        "animevietsub",
        "animevsub",
        "VSub@2025",
        "VSub@2024",
        "streaming_key",
        "player_key",
        "api_key",
        "secret",
        ""
    )

    /**
     * Main decrypt function - tries both methods with all passwords
     */
    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()

        // Already plain text URL or M3U8
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s

        // Try simple base64 decode first
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (plain.startsWith("http") || plain.startsWith("#EXTM3U")) return plain
        } catch (_: Exception) {}

        // Try each password with both decrypt methods
        for (pass in PASSWORDS) {
            // Method 1: OpenSSL format (evpKDF with MD5)
            val result1 = openSSLDecrypt(s, pass)
            if (result1 != null && isValidResult(result1)) {
                println("[AVS-CRYPTO] OpenSSL decrypt OK with pass='${pass.take(8)}...'")
                return result1
            }

            // Method 2: Simple SHA-256 key + 16-byte IV (old 2019 format)
            val result2 = simpleSHA256Decrypt(s, pass)
            if (result2 != null && isValidResult(result2)) {
                println("[AVS-CRYPTO] Simple SHA-256 decrypt OK with pass='${pass.take(8)}...'")
                return result2
            }
        }

        println("[AVS-CRYPTO] All decrypt methods failed")
        return null
    }

    private fun isValidResult(r: String): Boolean {
        return r.startsWith("http") || r.startsWith("#EXTM3U") || r.contains("googleapiscdn.com")
    }

    /**
     * Method 1: OpenSSL format decryption
     * Format: "Salted__" + 8-byte salt + ciphertext
     * Key/IV derived using evpKDF (MD5-based)
     */
    private fun openSSLDecrypt(b64: String, pass: String): String? {
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }

        if (raw.size < 16) return null

        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        val salt = if (hasSalt) raw.copyOfRange(8, 16) else null
        val ciphertext = if (hasSalt) raw.copyOfRange(16, raw.size) else raw

        if (ciphertext.isEmpty()) return null

        val (key, iv) = evpKDF(pass.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return aesCbcDecrypt(ciphertext, key, iv)
    }

    /**
     * Method 2: Simple SHA-256 key + 16-byte IV (from old 2019 plugin)
     * Format: 16-byte IV + ciphertext
     * Key = SHA-256(password)
     */
    private fun simpleSHA256Decrypt(b64: String, pass: String): String? {
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }

        if (raw.size < 17) return null // Need at least 16 bytes IV + 1 byte ciphertext

        // Skip if it starts with "Salted__" - that's OpenSSL format
        if (raw.copyOfRange(0, 8).contentEquals(SALTED)) return null

        try {
            // SHA-256 of password as key
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(pass.toByteArray(StandardCharsets.UTF_8))
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // First 16 bytes = IV, rest = ciphertext
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val plaintext = cipher.doFinal(ciphertext)
            return decompress(plaintext) ?: String(plaintext, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * EVP Key Derivation Function (MD5-based)
     * Used by OpenSSL for key/IV generation
     */
    private fun evpKDF(
        password: ByteArray,
        salt: ByteArray?,
        keyLength: Int,
        ivLength: Int
    ): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val output = ByteArrayOutputStream()
        var previous = ByteArray(0)

        while (output.size() < keyLength + ivLength) {
            md.reset()
            md.update(previous)
            md.update(password)
            if (salt != null) md.update(salt)
            previous = md.digest()
            output.write(previous)
        }

        val bytes = output.toByteArray()
        val key = bytes.copyOfRange(0, keyLength)
        val iv = bytes.copyOfRange(keyLength, keyLength + ivLength)
        return key to iv
    }

    /**
     * AES-CBC decryption with PKCS5 padding
     */
    private fun aesCbcDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (ciphertext.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plaintext = cipher.doFinal(ciphertext)
            decompress(plaintext) ?: String(plaintext, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Try to decompress data (GZIP or raw deflate)
     */
    private fun decompress(data: ByteArray): String? {
        // Try GZIP
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzipStream ->
                val output = ByteArrayOutputStream()
                gzipStream.copyTo(output)
                return String(output.toByteArray(), StandardCharsets.UTF_8).trim()
            }
        } catch (_: Exception) {}

        // Try raw deflate (zlib nowrap)
        try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                output.write(buffer, 0, count)
            }
            inflater.end()
            return String(output.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {}

        return null
    }

    /**
     * Check if string is a valid streaming URL
     */
    fun isStreamUrl(s: String?): Boolean {
        if (s == null || s.length < 8 || !s.startsWith("http")) return false
        return s.contains("googleapiscdn.com") ||
                s.contains(".m3u8") ||
                s.contains(".mp4") ||
                s.contains(".mpd") ||
                s.contains(".mkv") ||
                s.contains(".webm") ||
                s.contains("/hls/")
    }

    /**
     * Parse M3U8 content to extract hex ID and segment URLs
     */
    fun parseM3u8Content(content: String): Pair<String?, List<String>> {
        val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(content)?.groupValues?.get(1)
        val segments = Regex("""https?://[^\s#"']+""")
            .findAll(content)
            .map { it.value }
            .filter { it.contains("googleapiscdn.com") }
            .toList()
        return hexId to segments
    }
}

// ==================== MAIN PROVIDER ====================

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private const val CDN = "storage.googleapiscdn.com"

    // Headers
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none"
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "User-Agent" to userAgent,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to referer,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    private val cdnHeaders = mapOf("User-Agent" to userAgent)

    // ==================== HELPER FUNCTIONS ====================

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        return when {
            url.startsWith("http") -> url.trim()
            url.startsWith("//") -> "https:${url.trim()}"
            url.startsWith("/") -> "$mainUrl${url.trim()}"
            else -> "$mainUrl/$url"
        }
    }

    private fun log(tag: String, message: String) {
        println("[AVS-$tag] ${message.take(300)}")
    }

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.ifBlank { mainUrl }
        val url = if (page == 1) baseUrl else "${baseUrl.removeSuffix("/")}/trang-$page.html"

        val document = app.get(
            fixUrl(url) ?: mainUrl,
            interceptor = cf,
            headers = pageHeaders
        ).document

        val items = document
            .select("article, .TPostMv, .item, .list-film li, .TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val url = fixUrl(linkElement.attr("href")) ?: return null

        val title = (
                selectFirst(".Title, h3, h2, .title, .name")?.text()
                    ?: linkElement.attr("title")
                ).trim().ifBlank { return null }

        val imgElement = selectFirst("img")
        val posterUrl = fixUrl(
            imgElement?.attr("data-src")?.ifBlank { null }
                ?: imgElement?.attr("src")
        )

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "$mainUrl/tim-kiem/$encodedQuery/",
            interceptor = cf,
            headers = pageHeaders
        ).document

        return document
            .select("article, .TPostMv, .item, .list-film li")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")

        var document = app.get(fixedUrl, interceptor = cf, headers = pageHeaders).document

        // Episode selector
        val episodeSelector = "ul.list-episode li a, .list-eps a, .server-list a, " +
                ".list-episode a, .episodes a, #list_episodes a"

        var episodeNodes = document.select(episodeSelector)

        // Try to find episode page if not found
        if (episodeNodes.isEmpty()) {
            document.selectFirst("a.btn-see, a[href*='/tap-'], a[href*='/episode-'], .btn-watch a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?.let { watchUrl ->
                    document = app.get(watchUrl, interceptor = cf, headers = pageHeaders).document
                    episodeNodes = document.select(episodeSelector)
                }
        }

        // Extract filmId from URL: /phim/name-a5868/... → 5868
        val filmId = Regex("""[/-]a(\d+)""").find(fixedUrl)?.groupValues?.get(1) ?: ""

        // Parse episodes
        val episodes = episodeNodes.mapNotNull { ep ->
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null

            // Try multiple data attributes for episode ID
            val dataId = listOf(
                ep.attr("data-id"),
                ep.attr("data-episodeid"),
                ep.parent()?.attr("data-id") ?: "",
                ep.parent()?.attr("data-episodeid") ?: ""
            ).firstOrNull { it.matches(Regex("\\d+")) } ?: ""

            // Get data-hash and data-play from episode link
            val dataHash = ep.attr("data-hash").ifBlank { "" }
            val dataPlay = ep.attr("data-play").ifBlank { "api" }

            val episodeName = ep.text().trim().ifBlank { ep.attr("title").trim() }
            val episodeNumber = Regex("\\d+").find(episodeName)?.value?.toIntOrNull()

            // Data format: url@@filmId@@dataId@@dataHash@@dataPlay
            newEpisode("$href@@$filmId@@$dataId@@$dataHash@@$dataPlay") {
                name = episodeName
                episode = episodeNumber
            }
        }

        // Get metadata
        val title = document.selectFirst("h1.Title, h1, .Title")?.text()?.trim() ?: "Anime"
        val posterUrl = document.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val plot = document.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")

        val episodeUrl = parts.getOrNull(0)?.takeIf { it.startsWith("http") } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val savedEpisodeId = parts.getOrNull(2) ?: ""
        val dataHash = parts.getOrNull(3) ?: ""
        val dataPlay = parts.getOrNull(4) ?: "api"

        log("LOAD_LINKS", "url=$episodeUrl film=$filmId epId=$savedEpisodeId hash=${dataHash.take(30)} play=$dataPlay")

        // Get episode page
        val pageResponse = try {
            app.get(episodeUrl, interceptor = cf, headers = pageHeaders)
        } catch (e: Exception) {
            log("ERROR", "Failed to get page: ${e.message}")
            return false
        }

        val pageBody = pageResponse.text ?: return false
        val cookies = pageResponse.cookies.toMutableMap()

        // ==================== STRATEGY 1: Direct hash ====================

        if (dataHash.isNotBlank() && filmId.isNotBlank()) {
            log("DIRECT", "Trying direct hash=${dataHash.take(30)} with filmId=$filmId")
            if (tryPlayerRequest(dataHash, filmId, episodeUrl, cookies, callback)) {
                log("SUCCESS", "Direct hash worked!")
                return true
            }
        }

        // ==================== STRATEGY 2: Parse hashes from page ====================

        val document = Jsoup.parse(pageBody)
        val hashElements = document.select("a[data-hash], a[data-href], a[data-link], a[data-url]")

        for (element in hashElements) {
            val hash = listOf("data-hash", "data-href", "data-link", "data-url")
                .firstNotNullOfOrNull { attr ->
                    element.attr(attr).trim().takeIf { it.isNotBlank() && it != "#" }
                } ?: continue

            if (hash.startsWith("http")) {
                if (emitStream(hash, episodeUrl, callback)) return true
                continue
            }

            log("PARSE_HASH", "Found hash=${hash.take(30)}")
            if (tryPlayerRequest(hash, filmId, episodeUrl, cookies, callback)) return true
        }

        // ==================== STRATEGY 3: Collect episode IDs and use AJAX flow ====================

        val episodeIds = linkedSetOf<String>()
        if (savedEpisodeId.isNotBlank()) episodeIds.add(savedEpisodeId)

        document.select("[data-id]").forEach { el ->
            el.attr("data-id").filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let {
                episodeIds.add(it)
            }
        }

        Regex("""(?:episodeId|episode_id|epId|filmEpisodeId)\s*[=:]\s*["']?(\d+)["']?""")
            .findAll(pageBody).forEach { episodeIds.add(it.groupValues[1]) }

        Regex("""tap-(\d+)""").find(episodeUrl)?.groupValues?.get(1)?.let { episodeIds.add(it) }

        log("EPISODE_IDS", episodeIds.joinToString(","))

        var found = withTimeoutOrNull(25_000L) {
            ajaxFlow(episodeUrl, filmId, episodeIds.toList(), cookies, callback)
        } ?: false

        if (found) {
            log("SUCCESS", "AJAX flow succeeded")
            return true
        }

        // ==================== STRATEGY 4: Scrape alternative sources ====================

        found = withTimeoutOrNull(10_000L) {
            scrapeAlternative(pageBody, episodeUrl, subtitleCallback, callback)
        } ?: false

        log("FINAL", "Result: $found")
        return found
    }

    // ==================== PLAYER REQUEST ====================

    /**
     * Try POST to /ajax/player with link=hash&id=filmId
     * This is the correct flow from network capture
     */
    private suspend fun tryPlayerRequest(
        hash: String,
        filmId: String,
        referer: String,
        cookies: MutableMap<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = ajaxHeaders(referer)

        // Try different ID values
        val idsToTry = buildList {
            if (filmId.isNotBlank()) add(filmId)
            add("0") // Some servers use 0
        }

        for (id in idsToTry) {
            try {
                val params = mapOf("link" to hash, "id" to id)
                log("PLAYER_REQ", "POST link=${hash.take(40)} id=$id")

                val response = app.post(
                    "$mainUrl/ajax/player",
                    data = params,
                    headers = headers,
                    cookies = cookies,
                    interceptor = cf
                )

                cookies.putAll(response.cookies)
                val body = response.text ?: continue

                log("PLAYER_RESP", "code=${response.code} len=${body.length}")

                if (handleResponseBody(body, referer, callback)) return true

            } catch (e: Exception) {
                log("PLAYER_ERR", "id=$id error=${e.message}")
            }
        }

        return false
    }

    // ==================== AJAX FLOW ====================

    private suspend fun ajaxFlow(
        episodeUrl: String,
        filmId: String,
        episodeIds: List<String>,
        cookies: MutableMap<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = ajaxHeaders(episodeUrl)

        for (epId in episodeIds.take(6)) {
            try {
                // Step 1: POST episodeId=X&backup=1 to get server buttons
                val response1 = app.post(
                    "$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = headers,
                    cookies = cookies,
                    interceptor = cf
                )

                cookies.putAll(response1.cookies)
                val body1 = response1.text ?: continue

                log("AJAX_S1", "epId=$epId code=${response1.code} len=${body1.length}")

                // Try to handle body directly first
                if (handleResponseBody(body1, episodeUrl, callback)) return true

                // Parse JSON response
                val json = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(body1, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    continue
                }

                // Check for direct links in response
                val linkValue = json["link"] ?: json["url"] ?: json["stream"]
                if (linkValue is String && linkValue.isNotBlank()) {
                    if (resolveAndEmit(linkValue, episodeUrl, callback)) return true
                }

                if (linkValue is List<*>) {
                    for (item in linkValue.filterIsInstance<Map<String, Any?>>()) {
                        val file = (item["file"] ?: item["src"] ?: item["url"])?.toString() ?: continue
                        if (resolveAndEmit(file, episodeUrl, callback)) return true
                    }
                }

                // Parse HTML buttons from response
                val html = json["html"]?.toString() ?: continue
                if (html.isBlank()) continue

                val buttons = Jsoup.parse(html).select(
                    "a.btn3dsv, a[data-href], a[data-play], a[data-link], " +
                            ".server-item a, .btn-server, li[data-id] a, .episodes-btn a"
                )

                log("BUTTONS", "Found ${buttons.size} buttons")

                // Step 2: For each button, POST link=HASH&id=FILM_ID
                for (btn in buttons) {
                    val hash = listOf("data-href", "data-link", "data-url", "data-hash")
                        .firstNotNullOfOrNull { btn.attr(it).trim().takeIf { it.isNotBlank() && it != "#" } }
                        ?: run {
                            val href = btn.attr("href").trim()
                            if (href.startsWith("http") && emitStream(href, episodeUrl, callback)) return true
                            continue
                        }

                    if (hash.startsWith("http")) {
                        if (emitStream(hash, episodeUrl, callback)) return true
                        continue
                    }

                    val btnId = btn.attr("data-id").filter { it.isDigit() }.ifBlank { "0" }

                    log("BUTTON", "hash=${hash.take(40)} btnId=$btnId filmId=$filmId")

                    // Priority: filmId first
                    val paramSets = buildList {
                        if (filmId.isNotBlank()) add(mapOf("link" to hash, "id" to filmId))
                        add(mapOf("link" to hash, "id" to epId))
                        if (btnId != "0" && btnId != epId && btnId != filmId) {
                            add(mapOf("link" to hash, "id" to btnId))
                        }
                    }

                    for (params in paramSets) {
                        try {
                            val response2 = app.post(
                                "$mainUrl/ajax/player",
                                data = params,
                                headers = headers,
                                cookies = cookies,
                                interceptor = cf
                            )

                            cookies.putAll(response2.cookies)
                            val body2 = response2.text ?: continue

                            log("BUTTON_RESP", "code=${response2.code} len=${body2.length}")

                            if (handleResponseBody(body2, episodeUrl, callback)) return true

                        } catch (e: Exception) {
                            log("BUTTON_ERR", e.message ?: "")
                        }
                    }
                }

            } catch (e: Exception) {
                log("AJAX_ERR", "epId=$epId error=${e.message}")
            }
        }

        return false
    }

    // ==================== RESPONSE HANDLERS ====================

    private suspend fun handleResponseBody(
        body: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false

        // Check for direct URLs in body
        for (url in extractDirectUrls(body)) {
            if (emitStream(url, referer, callback)) return true
        }

        // Try to parse as JSON
        try {
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>

            for (key in listOf("link", "url", "stream", "src", "file", "m3u8")) {
                val value = json[key] ?: continue

                when {
                    value is String && value.startsWith("http") -> {
                        if (emitStream(value, referer, callback)) return true
                    }

                    value is String && value.isNotBlank() -> {
                        if (resolveAndEmit(value, referer, callback)) return true
                    }

                    value is List<*> -> {
                        for (item in value.filterIsInstance<Map<String, Any?>>()) {
                            val file = (item["file"] ?: item["src"] ?: item["url"])?.toString() ?: continue
                            if (resolveAndEmit(file, referer, callback)) return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // If body doesn't look like JSON/HTML, try to decrypt directly
        if (body.length > 20 && body.trimStart().first().let { it != '{' && it != '[' && it != '<' }) {
            if (resolveAndEmit(body.trim(), referer, callback)) return true
        }

        return false
    }

    private suspend fun resolveAndEmit(
        raw: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (raw.isBlank()) return false

        // Direct URL
        if (raw.startsWith("http")) return emitStream(raw, referer, callback)

        // Decrypt
        val decrypted = Crypto.decrypt(raw) ?: return false

        log("DECRYPT", "result=${decrypted.take(100)}")

        return when {
            decrypted.startsWith("http") -> emitStream(decrypted, referer, callback)

            decrypted.contains("#EXTM3U") -> {
                val (hexId, segments) = Crypto.parseM3u8Content(decrypted)
                log("M3U8", "hexId=$hexId segments=${segments.size}")

                when {
                    segments.isNotEmpty() -> emitM3u8FromContent(decrypted, hexId, segments, referer, callback)
                    hexId != null -> emitCdnUrl(hexId, referer, callback)
                    else -> false
                }
            }

            else -> false
        }
    }

    // ==================== EMIT FUNCTIONS ====================

    private suspend fun emitM3u8FromContent(
        m3u8Text: String,
        hexId: String?,
        segments: List<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try CDN index.m3u8
        if (hexId != null) {
            val indexUrl = "https://$CDN/chunks/$hexId/original/index.m3u8"
            log("CDN_INDEX", indexUrl)

            val indexExists = try {
                withTimeoutOrNull(3_000L) {
                    val r = app.get(indexUrl, headers = cdnHeaders)
                    r.code == 200 && r.text?.contains("#EXTM3U") == true
                } ?: false
            } catch (_: Exception) { false }

            if (indexExists) {
                callback(
                    newExtractorLink(name, name, indexUrl) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = cdnHeaders
                    }
                )
                return true
            }
        }

        // Verify CDN is reachable via first segment
        if (segments.isNotEmpty()) {
            val firstSegment = segments.first()
            val segOk = try {
                withTimeoutOrNull(3_000L) {
                    app.get(firstSegment, headers = cdnHeaders).code == 200
                } ?: false
            } catch (_: Exception) { false }

            if (segOk && hexId != null) {
                val indexUrl = "https://$CDN/chunks/$hexId/original/index.m3u8"
                callback(
                    newExtractorLink(name, name, indexUrl) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = cdnHeaders
                    }
                )
                return true
            }
        }

        return false
    }

    private suspend fun emitCdnUrl(
        hexId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "https://$CDN/chunks/$hexId/original/index.m3u8"

        return try {
            val response = withTimeoutOrNull(4_000L) {
                app.get(url, headers = cdnHeaders)
            }

            if (response != null && response.code == 200 && response.text?.contains("#EXTM3U") == true) {
                callback(
                    newExtractorLink(name, name, url) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = cdnHeaders
                    }
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun emitStream(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.startsWith("http")) return false

        log("EMIT", url.take(120))

        return when {
            url.contains(CDN) -> {
                val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
                val m3u8Url = if (hexId != null) {
                    "https://$CDN/chunks/$hexId/original/index.m3u8"
                } else if (url.contains(".m3u8")) {
                    url
                } else {
                    return false
                }

                try {
                    val response = withTimeoutOrNull(4_000L) {
                        app.get(m3u8Url, headers = cdnHeaders)
                    }

                    if (response != null && response.code == 200 && response.text?.contains("#EXTM3U") == true) {
                        callback(
                            newExtractorLink(name, name, m3u8Url) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.M3U8
                                this.headers = cdnHeaders
                            }
                        )
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }

            url.contains(".m3u8") || url.contains("/hls/") -> {
                try {
                    val response = withTimeoutOrNull(4_000L) {
                        app.get(url, headers = cdnHeaders)
                    }

                    if (response != null && response.code == 200 && response.text?.contains("#EXTM3U") == true) {
                        callback(
                            newExtractorLink(name, name, url) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.type = ExtractorLinkType.M3U8
                                this.headers = cdnHeaders
                            }
                        )
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }

            url.contains(".mp4") || url.contains(".mkv") || url.contains(".webm") -> {
                callback(
                    newExtractorLink(name, name, url) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO
                        this.headers = cdnHeaders
                    }
                )
                true
            }

            else -> false
        }
    }

    // ==================== SCRAPE ALTERNATIVE ====================

    private suspend fun scrapeAlternative(
        body: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            Jsoup.parse(body)
        } catch (_: Exception) {
            return false
        }

        // Check for iframes
        for (element in document.select("iframe[src], iframe[data-src]")) {
            val src = fixUrl(
                element.attr("src").ifBlank { element.attr("data-src") }
            ) ?: continue

            if (!src.startsWith("http")) continue

            val isKnown = listOf(
                "doodstream", "streamtape", "mixdrop", "upstream", "vidcloud",
                "filemoon", "vidplay", "vidsrc", "embed", "player", "cdn"
            ).any { src.contains(it, ignoreCase = true) }

            if (!isKnown) continue

            try {
                loadExtractor(src, episodeUrl, subtitleCallback, callback)
                return true
            } catch (_: Exception) {}
        }

        // Check for video elements
        for (element in document.select("video source[src], video[src]")) {
            val src = fixUrl(element.attr("src")) ?: continue
            if (Crypto.isStreamUrl(src) && emitStream(src, episodeUrl, callback)) return true
        }

        return false
    }

    // ==================== URL EXTRACTOR ====================

    private fun extractDirectUrls(body: String): List<String> {
        val urls = linkedSetOf<String>()

        // Extract URLs from quotes
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd|mkv|webm)[^"']*)["']""")
            .findAll(body).forEach { urls.add(it.groupValues[1]) }

        // Extract bare M3U8 URLs
        Regex("""https?://\S+\.m3u8[^\s"'<>\\]*""")
            .findAll(body).forEach { urls.add(it.value.trimEnd('"', '\'', ')', '\\')) }

        return urls.toList()
    }
}
