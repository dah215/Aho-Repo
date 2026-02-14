package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
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
import javax.crypto.spec.GCMParameterSpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

// ================================================================================
// CORE DATA STRUCTURES
// ================================================================================

/**
 * Response type classification
 */
enum class ResponseType {
    HTML_DOM,
    JAVASCRIPT,
    JSON_API,
    M3U8_PLAYLIST,
    MPD_MANIFEST,
    ENCRYPTED_PAYLOAD,
    PACKED_JS,
    UNKNOWN
}

/**
 * Stream candidate với validation
 */
data class StreamCandidate(
    val url: String,
    val source: String,
    val extractionPath: List<String>,
    val encryptionRequired: Boolean,
    val headers: Map<String, String>,
    val isValid: Boolean = false
)

/**
 * Session context cho stateful resolution
 */
data class SessionContext(
    val id: String = java.util.UUID.randomUUID().toString(),
    var cookies: MutableMap<String, String> = mutableMapOf(),
    var currentDepth: Int = 0,
    val maxDepth: Int = 15,
    val startTime: Long = System.currentTimeMillis(),
    var failedStrategies: MutableList<String> = mutableListOf(),
    val traceLog: MutableList<String> = mutableListOf()
)

// ================================================================================
// RESPONSE INTELLIGENCE ANALYZER
// ================================================================================

/**
 * Phân loại response tự động bằng heuristic
 */
object ResponseAnalyzer {
    
    private val htmlSignatures = listOf(
        Regex("""<!DOCTYPE\s*html""", RegexOption.IGNORE_CASE),
        Regex("""<html[\s>]""", RegexOption.IGNORE_CASE),
        Regex("""<head[\s>]""", RegexOption.IGNORE_CASE)
    )
    
    private val m3u8Signatures = listOf(
        Regex("""#EXTM3U"""),
        Regex("""#EXT-X-VERSION"""),
        Regex("""#EXTINF""")
    )
    
    private val jsonSignatures = listOf(
        Regex("""^\s*\{[\s\S]*\}\s*$"""),
        Regex("""^\s*\[[\s\S]*\]\s*$""")
    )
    
    private val packedJsSignatures = listOf(
        Regex("""eval\(function\(p,a,c,k,e,d"""),
        Regex("""String\.fromCharCode"""),
        Regex("""\\x[0-9a-fA-F]{2}""")
    )
    
    fun analyzeType(body: String?): ResponseType {
        if (body.isNullOrBlank()) return ResponseType.UNKNOWN
        
        if (m3u8Signatures.any { it.containsMatchIn(body) }) {
            return ResponseType.M3U8_PLAYLIST
        }
        
        if (htmlSignatures.any { it.containsMatchIn(body) }) {
            return ResponseType.HTML_DOM
        }
        
        if (jsonSignatures.any { it.containsMatchIn(body) }) {
            return ResponseType.JSON_API
        }
        
        if (packedJsSignatures.any { it.containsMatchIn(body) }) {
            return ResponseType.PACKED_JS
        }
        
        // Check for encrypted data (high entropy Base64)
        val base64Pattern = Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
        if (base64Pattern.containsMatchIn(body) && !body.contains("<")) {
            return ResponseType.ENCRYPTED_PAYLOAD
        }
        
        return ResponseType.UNKNOWN
    }
    
    fun extractUrlsFromResponse(body: String, baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Direct URL patterns
        val urlPatterns = listOf(
            Regex("""https?://[^\s"'`<>]+\.m3u8[^\s"'`<>]*"""),
            Regex("""https?://[^\s"'`<>]+\.mp4[^\s"'`<>]*"""),
            Regex("""https?://[^\s"'`<>]+\.mpd[^\s"'`<>]*"""),
            Regex("""["']https?://[^"']+["']""")
        )
        
        urlPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                val url = match.value.trim('"', '\'')
                urls.add(url)
            }
        }
        
        // DOM extraction if HTML
        if (body.contains("<")) {
            try {
                val doc = Jsoup.parse(body)
                
                // Data attributes
                listOf("data-src", "data-url", "data-href", "data-file", "data-link").forEach { attr ->
                    doc.select("[$attr]").forEach { el ->
                        val value = el.attr(attr)
                        if (value.isNotBlank()) urls.add(value)
                    }
                }
                
                // Standard attributes
                listOf("src", "href").forEach { attr ->
                    doc.select("[$attr]").forEach { el ->
                        val value = el.attr(attr)
                        if (value.isNotBlank() && (value.contains("http") || value.contains(".m3u8") || value.contains(".mp4"))) {
                            urls.add(value)
                        }
                    }
                }
            } catch (e: Exception) { /* Skip */ }
        }
        
        // Fix relative URLs
        return urls.map { url -> fixUrl(url, baseUrl) }.distinct()
    }
    
    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        
        return try {
            val base = URL(baseUrl)
            URL(base, url).toString()
        } catch (e: Exception) { url }
    }
}

// ================================================================================
// UNIVERSAL DATA MINING ENGINE
// ================================================================================

/**
 * Khai thác mọi response bằng multi-regex deep scan
 */
object DataMiner {
    
    private val hashPatterns = listOf(
        Regex("""data-hash\s*=\s*["']([^"']+)["']"""),
        Regex("""data-id\s*=\s*["']([^"']+)["']"""),
        Regex("""data-key\s*=\s*["']([^"']+)["']""")
    )
    
    private val encryptedPatterns = listOf(
        Regex("""file\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""link\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""source\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""url\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""enc\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']""")
    )
    
    private val jsonInJsPatterns = listOf(
        Regex("""\{[\s\S]*?"file"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"url"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"source"[\s\S]*?\}""")
    )
    
    fun mineHashes(body: String): List<String> {
        val hashes = mutableListOf<String>()
        hashPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                hashes.add(match.groupValues[1])
            }
        }
        return hashes.distinct()
    }
    
    fun mineEncryptedData(body: String): List<String> {
        val encrypted = mutableListOf<String>()
        encryptedPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                encrypted.add(match.groupValues[1])
            }
        }
        
        // Also find high-entropy blocks
        val base64Pattern = Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
        base64Pattern.findAll(body).forEach { match ->
            val block = match.value
            if (calculateEntropy(block) > 4.0) {
                encrypted.add(block)
            }
        }
        
        return encrypted.distinct()
    }
    
    fun mineJsonObjects(body: String): List<Map<String, Any?>> {
        val objects = mutableListOf<Map<String, Any?>>()
        jsonInJsPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                try {
                    val jsonStr = match.value
                    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    val parsed = mapper.readValue(jsonStr, Map::class.java) as Map<String, Any?>
                    if (parsed.isNotEmpty()) objects.add(parsed)
                } catch (e: Exception) { /* Skip */ }
            }
        }
        return objects
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

/**
 * Unpack eval-packed JavaScript
 */
object JSReverseEngine {
    
    fun unpackEval(js: String): String {
        var result = js
        var iterations = 0
        val maxIterations = 10
        
        while (iterations < maxIterations) {
            val unpacked = tryUnpackOneLevel(result)
            if (unpacked == result) break
            result = unpacked
            iterations++
        }
        
        return result
    }
    
    private fun tryUnpackOneLevel(js: String): String {
        // Dean Edwards packer
        val deanEdwardsPattern = Regex(
            """eval\(function\(p,a,c,k,e,d?\)\s*\{[\s\S]*?\}\s*\([\s\S]*?\)\)"""
        )
        deanEdwardsPattern.find(js)?.let { match ->
            try {
                val unpacked = unpackDeanEdwards(match.value)
                if (unpacked != null) return js.replace(match.value, unpacked)
            } catch (e: Exception) { /* Continue */ }
        }
        
        // eval('...') patterns
        val evalPattern = Regex("""eval\s*\(\s*(['"])([\s\S]*?)\1\s*\)""")
        evalPattern.find(js)?.let { match ->
            val content = match.groupValues[2]
            val decoded = decodeEscapes(content)
            return js.replace(match.value, decoded)
        }
        
        // atob() Base64
        val atobPattern = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")
        atobPattern.find(js)?.let { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                return js.replace(match.value, "'$decoded'")
            } catch (e: Exception) { /* Continue */ }
        }
        
        // String.fromCharCode
        val charCodePattern = Regex("""String\.fromCharCode\s*\(([\d\s,]+)\)""")
        charCodePattern.find(js)?.let { match ->
            try {
                val codes = match.groupValues[1].split(",").map { it.trim().toInt() }
                val decoded = codes.map { it.toChar() }.joinToString("")
                return js.replace(match.value, "'$decoded'")
            } catch (e: Exception) { /* Continue */ }
        }
        
        return js
    }
    
    private fun unpackDeanEdwards(packed: String): String? {
        try {
            val altPattern = Regex(
                """\}\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*,"""
            )
            
            val match = altPattern.find(packed) ?: return null
            
            val p = match.groupValues[1]
            val a = match.groupValues[2].toInt()
            val c = match.groupValues[3].toInt()
            val k = match.groupValues[4].split("|")
            
            var result = p
            for (i in c - 1 downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    val pattern = Regex("\\b${i.toString(a)}\\b")
                    result = result.replace(pattern, k[i])
                }
            }
            
            return result
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun decodeEscapes(str: String): String {
        return str
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\x([0-9a-fA-F]{2})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
    }
    
    fun extractUrlsFromJS(js: String): List<String> {
        val urls = mutableListOf<String>()
        
        val urlPatterns = listOf(
            Regex("""['"]https?://[^'"]+\.m3u8[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mp4[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]*(?:stream|video|play)[^'"]*['"]""")
        )
        
        urlPatterns.forEach { pattern ->
            pattern.findAll(js).forEach { match ->
                val url = match.value.trim('\'', '"')
                urls.add(url)
            }
        }
        
        return urls.distinct()
    }
}

// ================================================================================
// ENCRYPTION AUTOBREAK SYSTEM
// ================================================================================

/**
 * Hệ thống brute-strategy tự động phá mã hóa
 */
object EncryptionBreaker {
    
    // Known passwords to try
    private val knownPasswords = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub",
        "animevsub",
        "video_decrypt_key",
        "streaming_key",
        "player_key",
        "secret_key",
        "encryption_key",
        "pass",
        "password",
        "key"
    )
    
    // AES modes to try
    private val aesModes = listOf(
        "AES/CBC/PKCS5Padding",
        "AES/CBC/PKCS7Padding",
        "AES/CFB/NoPadding",
        "AES/OFB/NoPadding",
        "AES/CTR/NoPadding"
    )
    
    // Hash algorithms for key derivation
    private val hashAlgorithms = listOf(
        "SHA-256",
        "SHA-1",
        "MD5"
    )
    
    fun autoBreak(encrypted: String): String? {
        // Strategy 1: Try known passwords
        for (password in knownPasswords) {
            val result = tryDecryptWithPassword(encrypted, password)
            if (result != null && isValidOutput(result)) {
                return result
            }
        }
        
        // Strategy 2: Try different IV strategies
        for (password in knownPasswords) {
            for (ivStrategy in listOf("prefix", "zero", "fromKey")) {
                val result = tryDecryptWithIV(encrypted, password, ivStrategy)
                if (result != null && isValidOutput(result)) {
                    return result
                }
            }
        }
        
        // Strategy 3: Multi-layer decryption
        return tryMultiLayer(encrypted)
    }
    
    private fun tryDecryptWithPassword(encrypted: String, password: String): String? {
        for (hashAlgo in hashAlgorithms) {
            for (aesMode in aesModes) {
                try {
                    val result = decryptAES(encrypted, password, hashAlgo, aesMode, "prefix")
                    if (result != null && isValidOutput(result)) {
                        return result
                    }
                } catch (e: Exception) { /* Continue */ }
            }
        }
        return null
    }
    
    private fun tryDecryptWithIV(encrypted: String, password: String, ivStrategy: String): String? {
        for (hashAlgo in hashAlgorithms) {
            for (aesMode in aesModes) {
                try {
                    val result = decryptAES(encrypted, password, hashAlgo, aesMode, ivStrategy)
                    if (result != null && isValidOutput(result)) {
                        return result
                    }
                } catch (e: Exception) { /* Continue */ }
            }
        }
        return null
    }
    
    private fun decryptAES(
        encrypted: String,
        password: String,
        hashAlgorithm: String,
        aesMode: String,
        ivStrategy: String
    ): String? {
        // Decode Base64
        val decoded = try {
            Base64.decode(encrypted.replace("\\s".toRegex(), ""), Base64.DEFAULT)
        } catch (e: Exception) { return null }
        
        if (decoded.size < 16) return null
        
        // Derive key
        val key = when (hashAlgorithm) {
            "SHA-256" -> MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8))
            "SHA-1" -> {
                val sha1 = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(StandardCharsets.UTF_8))
                sha1.copyOf(16)
            }
            "MD5" -> MessageDigest.getInstance("MD5").digest(password.toByteArray(StandardCharsets.UTF_8))
            else -> return null
        }
        
        // Extract IV and ciphertext based on strategy
        val (iv, ciphertext) = when (ivStrategy) {
            "prefix" -> Pair(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, decoded.size))
            "zero" -> Pair(ByteArray(16), decoded)
            "fromKey" -> Pair(key.copyOfRange(0, 16), decoded)
            else -> Pair(decoded.copyOfRange(0, 16), decoded.copyOfRange(16, decoded.size))
        }
        
        // Decrypt
        return try {
            val cipher = Cipher.getInstance(aesMode)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ciphertext)
            
            // Try decompression
            tryDecompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) { null }
    }
    
    private fun tryDecompress(data: ByteArray): String? {
        // Try zlib decompression
        try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) { /* Continue */ }
        
        // Try gzip decompression
        try {
            val gzipIn = GZIPInputStream(ByteArrayInputStream(data))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n = gzipIn.read(buf)
            while (n > 0) {
                out.write(buf, 0, n)
                n = gzipIn.read(buf)
            }
            gzipIn.close()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) { /* Continue */ }
        
        return null
    }
    
    private fun tryMultiLayer(encrypted: String): String? {
        var current = encrypted
        
        for (layer in 1..3) {
            val result = autoBreak(current)
            if (result == null) break
            
            if (looksEncrypted(result)) {
                current = result
            } else {
                return result
            }
        }
        
        return null
    }
    
    private fun isValidOutput(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        
        return text.startsWith("http://") ||
               text.startsWith("https://") ||
               text.startsWith("//") ||
               text.contains(".m3u8") ||
               text.contains(".mp4") ||
               !looksEncrypted(text)
    }
    
    private fun looksEncrypted(text: String): Boolean {
        val printable = text.count { it.code in 32..126 || it in "\n\r\t" }
        return printable.toFloat() / text.length.coerceAtLeast(1) < 0.7f
    }
}

// ================================================================================
// STREAM VALIDATOR
// ================================================================================

/**
 * Xác thực stream link
 */
object StreamValidator {
    
    private val m3u8Signatures = listOf("#EXTM3U", "#EXT-X-VERSION", "#EXTINF")
    private val mpdSignatures = listOf("<MPD", "<Period", "<AdaptationSet")
    
    fun isM3U8(content: String?): Boolean {
        if (content == null) return false
        return m3u8Signatures.any { content.contains(it) }
    }
    
    fun isMPD(content: String?): Boolean {
        if (content == null) return false
        return mpdSignatures.any { content.contains(it, ignoreCase = true) }
    }
    
    fun isStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") ||
               url.contains(".mp4") ||
               url.contains(".mpd") ||
               url.contains("/stream/")
    }
}

// ================================================================================
// MAIN PLUGIN CLASS
// ================================================================================

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    
    // Debug mode - bật để xem forensic trace
    private val debugMode = true
    
    private val cfKiller = CloudflareKiller()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    
    private val defaultHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Priority" to "u=0, i",
        "Sec-Ch-Ua" to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
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
        
        val res = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders)
        val doc = res.document
        
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost").mapNotNull { 
            it.toSearchResponse() 
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title, h3, h2, .title, .name")?.text()?.trim() 
                    ?: a.attr("title").trim()
                    ?: return null
        
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } 
                     ?: img?.attr("src"))
        
        return newAnimeSearchResponse(title, href, TvType.Anime) { 
            posterUrl = poster 
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article, .TPostMv, .item, .list-film li").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }
    
    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document
        
        var episodesNodes = doc.select("ul.list-episode li a")
        if (episodesNodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-see, a[href*='/tap-'], .btn-watch a, a.watch_button")?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = defaultHeaders).document
                episodesNodes = doc.select("ul.list-episode li a")
            }
        }
        
        val filmId = Regex("[/-]a(\\d+)").find(fixedUrl)?.groupValues?.get(1) ?: ""
        val episodes = episodesNodes.mapNotNull { ep ->
            val id = ep.attr("data-id").trim()
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim().ifEmpty { ep.attr("title") }
            newEpisode("$href@@$filmId@@$id") {
                this.name = name
                this.episode = Regex("\\d+").find(name ?: "")?.value?.toIntOrNull()
            }
        }
        
        val title = doc.selectFirst("h1.Title, .Title, h1")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let { 
            fixUrl(it.attr("data-src").ifEmpty { it.attr("src") }) 
        }
        
        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }
    
    /**
     * ========================================================================
     * ADAPTIVE RESOLUTION PIPELINE
     * ========================================================================
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false
        
        val (epUrl, filmId, episodeId) = parts
        
        // Create session
        val session = SessionContext()
        
        logTrace(session, "INIT", "Session started: filmId=$filmId, episodeId=$episodeId")
        
        val videoHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to epUrl,
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )
        
        val ajaxHeaders = defaultHeaders + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to epUrl,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )
        
        return try {
            // =========================================================================
            // STRATEGY 1: DIRECT API
            // =========================================================================
            logTrace(session, "STRATEGY", "Trying DIRECT_API")
            
            // Step 1: Get initial page and cookies
            val pageReq = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            session.cookies.putAll(pageReq.cookies)
            
            // Step 2: Get player HTML
            val step1Req = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("episodeId" to episodeId, "backup" to "1"),
                headers = ajaxHeaders,
                cookies = session.cookies.toMap(),
                interceptor = cfKiller
            )
            session.cookies.putAll(step1Req.cookies)
            
            val step1 = step1Req.parsedSafe<ServerSelectionResp>()
            if (step1 == null || step1.html.isNullOrBlank()) {
                logTrace(session, "FAIL", "No server selection HTML")
                return tryFallbackStrategies(epUrl, filmId, episodeId, session, videoHeaders, subtitleCallback, callback)
            }
            
            val serverDoc = Jsoup.parse(step1.html)
            val serverButtons = serverDoc.select("a.btn3dsv")
            
            if (serverButtons.isEmpty()) {
                logTrace(session, "FAIL", "No server buttons found")
                return tryFallbackStrategies(epUrl, filmId, episodeId, session, videoHeaders, subtitleCallback, callback)
            }
            
            // Step 3: Try each server
            for (btn in serverButtons) {
                val hash = btn.attr("data-href")
                val play = btn.attr("data-play")
                val btnId = btn.attr("data-id")
                
                if (hash.isBlank()) continue
                
                logTrace(session, "SERVER", "Trying server: play=$play, hash=$hash")
                
                // Activate session
                val activeReq = app.get(
                    "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
                    headers = ajaxHeaders,
                    cookies = session.cookies.toMap(),
                    interceptor = cfKiller
                )
                session.cookies.putAll(activeReq.cookies)
                
                // Try both filmId and episodeId
                val idsToTry = listOf(filmId, episodeId)
                
                for (id in idsToTry) {
                    val params = if (play == "api") {
                        mapOf("link" to hash, "id" to id)
                    } else {
                        mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
                    }
                    
                    val step2Req = app.post(
                        "$mainUrl/ajax/player",
                        data = params,
                        headers = ajaxHeaders,
                        cookies = session.cookies.toMap(),
                        interceptor = cfKiller
                    )
                    session.cookies.putAll(step2Req.cookies)
                    
                    val parsed = step2Req.parsedSafe<PlayerResp>()
                    
                    if (play == "api") {
                        // Encrypted link
                        val enc = parsed?.linkArray?.firstOrNull()?.file
                        if (!enc.isNullOrBlank()) {
                            logTrace(session, "DECRYPT", "Found encrypted link, attempting decryption")
                            
                            val decrypted = EncryptionBreaker.autoBreak(enc)
                            
                            if (decrypted != null && decrypted.startsWith("http")) {
                                logTrace(session, "SUCCESS", "Decrypted successfully: $decrypted")
                                
                                // Follow redirects if needed
                                val finalUrl = followRedirects(decrypted, session.cookies.toMap(), videoHeaders)
                                
                                if (finalUrl.contains(".m3u8")) {
                                    return emitM3U8(finalUrl, videoHeaders, callback)
                                } else if (finalUrl.startsWith("http")) {
                                    return emitDirectLink(finalUrl, videoHeaders, callback)
                                }
                            } else {
                                logTrace(session, "FAIL", "Decryption failed for this link")
                            }
                        }
                    } else {
                        // Direct link
                        val direct = parsed?.linkString
                        if (!direct.isNullOrBlank() && direct.startsWith("http")) {
                            logTrace(session, "SUCCESS", "Found direct link: $direct")
                            
                            if (direct.contains(".m3u8")) {
                                return emitM3U8(direct, videoHeaders, callback)
                            }
                            
                            // Try to load via extractor
                            runCatching {
                                loadExtractor(direct, epUrl, subtitleCallback, callback)
                            }
                            
                            return true
                        }
                    }
                }
            }
            
            // =========================================================================
            // STRATEGY 2: FALLBACK STRATEGIES
            // =========================================================================
            return tryFallbackStrategies(epUrl, filmId, episodeId, session, videoHeaders, subtitleCallback, callback)
            
        } catch (e: Exception) {
            logTrace(session, "ERROR", e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Fallback strategies khi direct API fail
     */
    private suspend fun tryFallbackStrategies(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        videoHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logTrace(session, "STRATEGY", "Trying JS_REVERSE")
        
        // Strategy: JS Reverse - analyze page for inline JS
        try {
            val pageRes = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            val body = pageRes.text ?: return false
            
            // Unpack any packed JS
            val unpacked = JSReverseEngine.unpackEval(body)
            val urls = JSReverseEngine.extractUrlsFromJS(unpacked)
            
            for (url in urls) {
                if (StreamValidator.isStreamUrl(url)) {
                    logTrace(session, "SUCCESS", "Found URL via JS reverse: $url")
                    
                    val finalUrl = fixUrl(url) ?: continue
                    
                    if (finalUrl.contains(".m3u8")) {
                        return emitM3U8(finalUrl, videoHeaders, callback)
                    }
                }
            }
        } catch (e: Exception) {
            logTrace(session, "FAIL", "JS_REVERSE failed: ${e.message}")
        }
        
        // Strategy: Deep Data Mining
        logTrace(session, "STRATEGY", "Trying DATA_MINING")
        try {
            val pageRes = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            val body = pageRes.text ?: return false
            
            // Mine encrypted data from response
            val encryptedData = DataMiner.mineEncryptedData(body)
            
            for (enc in encryptedData) {
                val decrypted = EncryptionBreaker.autoBreak(enc)
                if (decrypted != null && decrypted.startsWith("http")) {
                    logTrace(session, "SUCCESS", "Found URL via data mining: $decrypted")
                    
                    if (decrypted.contains(".m3u8")) {
                        return emitM3U8(decrypted, videoHeaders, callback)
                    }
                }
            }
            
            // Mine URLs directly
            val urls = ResponseAnalyzer.extractUrlsFromResponse(body, epUrl)
            for (url in urls) {
                if (StreamValidator.isStreamUrl(url)) {
                    logTrace(session, "SUCCESS", "Found URL via response analysis: $url")
                    
                    if (url.contains(".m3u8")) {
                        return emitM3U8(url, videoHeaders, callback)
                    }
                }
            }
        } catch (e: Exception) {
            logTrace(session, "FAIL", "DATA_MINING failed: ${e.message}")
        }
        
        // Strategy: Alternative endpoints
        logTrace(session, "STRATEGY", "Trying ALTERNATIVE_ENDPOINTS")
        val altEndpoints = listOf(
            "$mainUrl/ajax/player?episodeId=$episodeId",
            "$mainUrl/ajax/getLink?filmId=$filmId&episodeId=$episodeId",
            "$mainUrl/ajax/stream/$episodeId"
        )
        
        for (endpoint in altEndpoints) {
            try {
                val res = app.get(endpoint, headers = defaultHeaders, interceptor = cfKiller)
                val body = res.text ?: continue
                
                // Analyze response
                val type = ResponseAnalyzer.analyzeType(body)
                
                when (type) {
                    ResponseType.M3U8_PLAYLIST -> {
                        // Extract URL from response
                        val urls = ResponseAnalyzer.extractUrlsFromResponse(body, endpoint)
                        for (url in urls) {
                            if (url.contains(".m3u8")) {
                                logTrace(session, "SUCCESS", "Found M3U8 via alt endpoint: $url")
                                return emitM3U8(url, videoHeaders, callback)
                            }
                        }
                    }
                    ResponseType.JSON_API -> {
                        // Parse JSON and extract
                        val urls = ResponseAnalyzer.extractUrlsFromResponse(body, endpoint)
                        for (url in urls) {
                            if (StreamValidator.isStreamUrl(url)) {
                                logTrace(session, "SUCCESS", "Found URL via JSON: $url")
                                return emitM3U8(url, videoHeaders, callback)
                            }
                        }
                    }
                    ResponseType.ENCRYPTED_PAYLOAD -> {
                        // Try to decrypt
                        val decrypted = EncryptionBreaker.autoBreak(body.trim())
                        if (decrypted != null && decrypted.startsWith("http")) {
                            logTrace(session, "SUCCESS", "Decrypted from alt endpoint: $decrypted")
                            return emitM3U8(decrypted, videoHeaders, callback)
                        }
                    }
                    else -> { /* Continue */ }
                }
            } catch (e: Exception) {
                // Continue to next endpoint
            }
        }
        
        logTrace(session, "FAIL", "All strategies failed")
        return false
    }
    
    /**
     * Follow redirects to get final URL
     */
    private suspend fun followRedirects(
        startUrl: String,
        cookies: Map<String, String>,
        headers: Map<String, String>
    ): String {
        if (startUrl.contains(".m3u8")) return startUrl
        
        var currentUrl = startUrl
        
        runCatching {
            for (i in 1..5) {
                val res = app.get(currentUrl, headers = headers, cookies = cookies, interceptor = cfKiller)
                if (res.url.contains(".m3u8")) {
                    return res.url
                }
                if (res.url == currentUrl) break
                currentUrl = res.url
            }
        }
        
        return currentUrl
    }
    
    /**
     * Emit M3U8 stream
     */
    private fun emitM3U8(
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(newExtractorLink(name, name, url) {
            this.headers = headers
            this.type = ExtractorLinkType.M3U8
        })
        
        // Also generate quality variants
        runCatching {
            M3u8Helper.generateM3u8(name, url, url, headers = headers).forEach {
                it.headers = headers
                callback(it)
            }
        }
        
        return true
    }
    
    /**
     * Emit direct video link
     */
    private fun emitDirectLink(
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(newExtractorLink(name, name, url) {
            this.headers = headers
        })
        return true
    }
    
    /**
     * Log trace for debugging
     */
    private fun logTrace(session: SessionContext, phase: String, message: String) {
        if (debugMode) {
            val entry = "[$phase] $message"
            session.traceLog.add(entry)
            println("ASES[${session.id.take(8)}] $entry")
        }
    }
}

// ================================================================================
// DATA CLASSES FOR API RESPONSES
// ================================================================================

data class ServerSelectionResp(@JsonProperty("html") val html: String? = null)

data class PlayerResp(
    @JsonProperty("link") val linkRaw: Any? = null,
    @JsonProperty("success") val success: Int? = null
) {
    @Suppress("UNCHECKED_CAST")
    val linkArray: List<LinkFile>? 
        get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { 
            LinkFile(it["file"] as? String) 
        }
    val linkString: String? get() = linkRaw as? String
}

data class LinkFile(@JsonProperty("file") val file: String? = null)
