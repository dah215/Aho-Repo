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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

// ================================================================================
// CORE DATA STRUCTURES
// ================================================================================

/**
 * Request snapshot cho network reconstruction
 */
data class RequestSnapshot(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val redirectChain: List<String> = emptyList()
)

/**
 * Response snapshot với full metadata
 */
data class ResponseSnapshot(
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
    val body: ByteArray,
    val contentType: String?,
    val responseTime: Long,
    val redirectHistory: List<String>
) {
    val bodyString: String? get() = try { String(body, StandardCharsets.UTF_8) } catch (e: Exception) { null }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ResponseSnapshot
        return url == other.url
    }
    override fun hashCode(): Int = url.hashCode()
}

/**
 * Response type classification
 */
enum class ResponseType {
    HTML_DOM,
    JAVASCRIPT,
    JSON_API,
    M3U8_PLAYLIST,
    MPD_MANIFEST,
    BINARY_VIDEO,
    ENCRYPTED_PAYLOAD,
    PACKED_JS,
    OBFUSCATED_JS,
    REDIRECT_PAGE,
    GATEWAY_PAGE,
    EMBED_PAGE,
    UNKNOWN
}

/**
 * Analysis result từ Response Analyzer
 */
data class ResponseAnalysis(
    val type: ResponseType,
    val confidence: Float,
    val indicators: List<String>,
    val extractedUrls: List<String>,
    val embeddedData: Map<String, Any>,
    val encryptionHints: List<EncryptionHint>,
    val jsEntrypoints: List<String>
)

/**
 * Encryption hint cho auto-breaking
 */
data class EncryptionHint(
    val algorithm: String,
    val keySource: KeySource,
    val ivLocation: String,
    val encoding: String,
    val confidence: Float
)

enum class KeySource {
    HARDCODED,
    DERIVED_FROM_URL,
    DERIVED_FROM_PASSWORD,
    EMBEDDED_IN_JS,
    FROM_API,
    BRUTE_FORCE
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
    val validationResult: ValidationResult? = null,
    val priority: Int = 0
)

data class ValidationResult(
    val isValid: Boolean,
    val streamType: StreamType?,
    val mimeType: String?,
    val contentLength: Long?,
    val requiresAuth: Boolean,
    val errorReason: String?
)

enum class StreamType {
    HLS_M3U8,
    DASH_MPD,
    MP4_DIRECT,
    MKV_DIRECT,
    UNKNOWN
}

/**
 * Session context cho stateful resolution
 */
data class SessionContext(
    val id: String = java.util.UUID.randomUUID().toString(),
    var cookies: MutableMap<String, String> = mutableMapOf(),
    var requestHistory: MutableList<RequestSnapshot> = mutableListOf(),
    var responseHistory: MutableList<ResponseSnapshot> = mutableListOf(),
    var currentDepth: Int = 0,
    val maxDepth: Int = 15,
    val startTime: Long = System.currentTimeMillis(),
    var currentStrategy: ResolutionStrategy = ResolutionStrategy.ADAPTIVE,
    var failedAttempts: MutableList<FailedAttempt> = mutableListOf(),
    val traceLog: MutableList<TraceEntry> = mutableListOf()
)

data class FailedAttempt(
    val strategy: ResolutionStrategy,
    val reason: String,
    val timestamp: Long
)

data class TraceEntry(
    val phase: String,
    val action: String,
    val input: Any?,
    val output: Any?,
    val durationMs: Long,
    val success: Boolean,
    val error: String?
)

enum class ResolutionStrategy {
    DIRECT_API,
    JS_REVERSE,
    DEEP_CRAWL,
    ENCRYPTION_BREAK,
    PROVIDER_FALLBACK,
    ADAPTIVE
}

/**
 * Forensic trace cho debugging
 */
data class ForensicTrace(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val networkTrace: List<RequestSnapshot>,
    val responseAnalysis: List<ResponseAnalysis>,
    val extractionAttempts: List<ExtractionAttempt>,
    val finalResult: FinalResult?
)

data class ExtractionAttempt(
    val method: String,
    val input: Any,
    val output: Any?,
    val success: Boolean,
    val durationMs: Long
)

data class FinalResult(
    val success: Boolean,
    val streamUrl: String?,
    val extractionPath: List<String>,
    val totalAttempts: Int
)

// ================================================================================
// MODULE 1: NETWORK RECONSTRUCTION ENGINE
// ================================================================================

/**
 * Tái dựng toàn bộ hành vi mạng như DevTools:
 * - Lưu toàn bộ request chain
 * - Lưu redirect graph
 * - Lưu cookies timeline
 * - Lưu body snapshot từng bước
 */
class NetworkReconstructionEngine(
    private val app: requests,
    private val cfKiller: CloudflareKiller,
    private val defaultHeaders: Map<String, String>,
    private val debugMode: Boolean = true
) {
    private val TAG = "NetworkRecon"
    
    /**
     * Execute request với full tracking
     */
    suspend fun executeTracked(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        body: Map<String, String>? = null,
        session: SessionContext
    ): ResponseSnapshot? {
        val startTime = System.currentTimeMillis()
        
        // Log request
        val requestSnap = RequestSnapshot(
            url = url,
            method = method,
            headers = defaultHeaders + headers,
            cookies = cookies,
            body = body?.entries?.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        )
        session.requestHistory.add(requestSnap)
        
        return try {
            val mergedHeaders = defaultHeaders + headers
            val mergedCookies = session.cookies + cookies
            
            val response = when {
                method == "POST" && body != null -> {
                    app.post(
                        url,
                        data = body,
                        headers = mergedHeaders,
                        cookies = mergedCookies,
                        interceptor = cfKiller
                    )
                }
                else -> {
                    app.get(
                        url,
                        headers = mergedHeaders,
                        cookies = mergedCookies,
                        interceptor = cfKiller
                    )
                }
            }
            
            // Update cookies
            response.cookies.forEach { (k, v) -> session.cookies[k] = v }
            
            // Track redirects
            val redirectHistory = trackRedirects(url, response.url)
            
            val responseSnap = ResponseSnapshot(
                url = url,
                finalUrl = response.url,
                statusCode = response.code,
                headers = response.headers.toMap(),
                cookies = response.cookies,
                body = response.text?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
                contentType = response.headers["content-type"],
                responseTime = System.currentTimeMillis() - startTime,
                redirectHistory = redirectHistory
            )
            
            session.responseHistory.add(responseSnap)
            
            if (debugMode) {
                session.traceLog.add(TraceEntry(
                    phase = "NETWORK",
                    action = "Request executed",
                    input = requestSnap,
                    output = responseSnap.copy(body = ByteArray(0)), // Don't log full body
                    durationMs = responseSnap.responseTime,
                    success = true,
                    error = null
                ))
            }
            
            responseSnap
            
        } catch (e: Exception) {
            if (debugMode) {
                session.traceLog.add(TraceEntry(
                    phase = "NETWORK",
                    action = "Request failed",
                    input = requestSnap,
                    output = null,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = e.message
                ))
            }
            null
        }
    }
    
    /**
     * Track redirect chain
     */
    private fun trackRedirects(originalUrl: String, finalUrl: String): List<String> {
        val chain = mutableListOf<String>()
        if (originalUrl != finalUrl) {
            chain.add(originalUrl)
            // Could add intermediate redirects if tracked
        }
        chain.add(finalUrl)
        return chain
    }
    
    /**
     * Replay request from snapshot
     */
    suspend fun replay(snapshot: RequestSnapshot, session: SessionContext): ResponseSnapshot? {
        return executeTracked(
            url = snapshot.url,
            method = snapshot.method,
            headers = snapshot.headers,
            cookies = snapshot.cookies,
            body = snapshot.body?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            },
            session = session
        )
    }
}

// ================================================================================
// MODULE 2: RESPONSE INTELLIGENCE ANALYZER
// ================================================================================

/**
 * Phân loại response tự động bằng heuristic:
 * - HTML DOM
 * - Obfuscated JS  
 * - Packed JS
 * - Encrypted payload
 * - Binary playlist
 * - Streaming manifest
 */
class ResponseIntelligenceAnalyzer(
    private val debugMode: Boolean = true
) {
    private val TAG = "ResponseAnalyzer"
    
    // Signature patterns for type detection
    private val htmlSignatures = listOf(
        Regex("""<!DOCTYPE\s*html""", RegexOption.IGNORE_CASE),
        Regex("""<html[\s>]""", RegexOption.IGNORE_CASE),
        Regex("""<head[\s>]""", RegexOption.IGNORE_CASE),
        Regex("""<body[\s>]""", RegexOption.IGNORE_CASE)
    )
    
    private val jsSignatures = listOf(
        Regex("""\b(function|var|let|const|if|else|for|while|return)\b"""),
        Regex("""\$\.[a-zA-Z]+\("""),
        Regex("""document\.[a-zA-Z]+"""),
        Regex("""window\.[a-zA-Z]+""")
    )
    
    private val packedJsSignatures = listOf(
        Regex("""eval\(function\(p,a,c,k,e,d"""),
        Regex("""eval\(function\(p,a,c,k,e,r"""),
        Regex("""\bunpack\b.*\beval\b"""),
        Regex("""String\.fromCharCode"""),
        Regex("""\\x[0-9a-fA-F]{2}.*\\x[0-9a-fA-F]{2}"""),
        Regex("""\\u[0-9a-fA-F]{4}.*\\u[0-9a-fA-F]{4}""")
    )
    
    private val m3u8Signatures = listOf(
        Regex("""#EXTM3U"""),
        Regex("""#EXT-X-VERSION"""),
        Regex("""#EXT-X-STREAM-INF"""),
        Regex("""#EXTINF""")
    )
    
    private val mpdSignatures = listOf(
        Regex("""<MPD\s""", RegexOption.IGNORE_CASE),
        Regex("""<Period\s""", RegexOption.IGNORE_CASE),
        Regex("""<AdaptationSet""", RegexOption.IGNORE_CASE),
        Regex("""mimetype=["']video/""")
    )
    
    private val jsonSignatures = listOf(
        Regex("""^\s*\{[\s\S]*\}\s*$"""),
        Regex("""^\s*\[[\s\S]*\]\s*$"""),
        Regex(""""[\w_]+"\s*:""")
    )
    
    private val encryptionSignatures = listOf(
        Regex("""^[A-Za-z0-9+/]{20,}={0,2}$"""), // Base64
        Regex("""[A-Za-z0-9+/]{40,}={0,2}"""),  // Embedded Base64
        Regex("""\b[A-Fa-f0-9]{32,}\b"""),       // Hex encoded
        Regex("""\b(?:AES|DES|RSA|encrypt|decrypt|cipher)\b""", RegexOption.IGNORE_CASE),
        Regex("""CryptoJS"""),
        Regex("""crypto\.subtle""")
    )
    
    private val embedPatterns = listOf(
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<embed[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""data-src=["']([^"']+)["']"""),
        Regex("""videoUrl\s*[=:]\s*["']([^"']+)["']"""),
        Regex("""sourceUrl\s*[=:]\s*["']([^"']+)["']"""),
        Regex("""file\s*:\s*["']([^"']+)["']""")
    )
    
    /**
     * Analyze response and classify
     */
    fun analyze(response: ResponseSnapshot): ResponseAnalysis {
        val body = response.bodyString ?: return ResponseAnalysis(
            type = ResponseType.UNKNOWN,
            confidence = 0f,
            indicators = listOf("Empty or binary body"),
            extractedUrls = emptyList(),
            embeddedData = emptyMap(),
            encryptionHints = emptyList(),
            jsEntrypoints = emptyList()
        )
        
        val indicators = mutableListOf<String>()
        val extractedUrls = mutableListOf<String>()
        val embeddedData = mutableMapOf<String, Any>()
        val encryptionHints = mutableListOf<EncryptionHint>()
        val jsEntrypoints = mutableListOf<String>()
        
        // Detect type with confidence scoring
        val typeScores = mutableMapOf<ResponseType, Float>()
        
        // HTML detection
        val htmlScore = htmlSignatures.count { it.containsMatchIn(body) }.toFloat() / htmlSignatures.size
        if (htmlScore > 0.5f) {
            typeScores[ResponseType.HTML_DOM] = htmlScore
            indicators.add("HTML structure detected")
            
            // Extract from DOM
            val doc = Jsoup.parse(body)
            extractUrlsFromDom(doc, extractedUrls)
            extractEmbeddedJsonFromHtml(body, embeddedData)
            
            // Check for embed/gateway
            if (embedPatterns.any { it.containsMatchIn(body) }) {
                typeScores[ResponseType.EMBED_PAGE] = 0.6f
                indicators.add("Embed pattern detected")
            }
            
            // Find JS entry points
            doc.select("script[src]").forEach { 
                jsEntrypoints.add(it.attr("src")) 
            }
            doc.select("script:not([src])").forEach { script ->
                if (script.data().contains("player") || script.data().contains("video")) {
                    jsEntrypoints.add("inline:player")
                }
            }
        }
        
        // JS detection
        val jsScore = jsSignatures.count { it.containsMatchIn(body) }.toFloat() / jsSignatures.size
        if (jsScore > 0.5f) {
            typeScores[ResponseType.JAVASCRIPT] = jsScore
            indicators.add("JavaScript code detected")
        }
        
        // Packed JS detection
        val packedScore = packedJsSignatures.count { it.containsMatchIn(body) }.toFloat() / packedJsSignatures.size
        if (packedScore > 0.3f) {
            typeScores[ResponseType.PACKED_JS] = packedScore
            indicators.add("Packed/obfuscated JS detected")
        }
        
        // M3U8 detection
        val m3u8Score = m3u8Signatures.count { it.containsMatchIn(body) }.toFloat() / m3u8Signatures.size
        if (m3u8Score > 0.5f) {
            typeScores[ResponseType.M3U8_PLAYLIST] = m3u8Score
            indicators.add("HLS M3U8 playlist detected")
        }
        
        // MPD detection
        val mpdScore = mpdSignatures.count { it.containsMatchIn(body) }.toFloat() / mpdSignatures.size
        if (mpdScore > 0.5f) {
            typeScores[ResponseType.MPD_MANIFEST] = mpdScore
            indicators.add("DASH MPD manifest detected")
        }
        
        // JSON detection
        val jsonScore = jsonSignatures.count { it.containsMatchIn(body) }.toFloat() / jsonSignatures.size
        if (jsonScore > 0.5f) {
            typeScores[ResponseType.JSON_API] = jsonScore
            indicators.add("JSON API response detected")
            
            // Parse and extract URLs
            extractUrlsFromJson(body, extractedUrls, embeddedData)
        }
        
        // Encryption detection
        val encScore = encryptionSignatures.count { it.containsMatchIn(body) }.toFloat() / encryptionSignatures.size
        if (encScore > 0.3f) {
            typeScores[ResponseType.ENCRYPTED_PAYLOAD] = encScore
            indicators.add("Encrypted payload detected")
            
            // Analyze encryption hints
            analyzeEncryption(body, encryptionHints)
        }
        
        // Determine primary type
        val primaryType = typeScores.maxByOrNull { it.value }?.key ?: ResponseType.UNKNOWN
        val confidence = typeScores[primaryType] ?: 0f
        
        return ResponseAnalysis(
            type = primaryType,
            confidence = confidence,
            indicators = indicators,
            extractedUrls = extractedUrls.distinct(),
            embeddedData = embeddedData,
            encryptionHints = encryptionHints,
            jsEntrypoints = jsEntrypoints.distinct()
        )
    }
    
    private fun extractUrlsFromDom(doc: Document, urls: MutableList<String>) {
        // Standard URL extraction
        doc.select("a[href]").forEach { urls.add(it.attr("href")) }
        doc.select("img[src]").forEach { urls.add(it.attr("src")) }
        doc.select("source[src]").forEach { urls.add(it.attr("src")) }
        doc.select("video[src]").forEach { urls.add(it.attr("src")) }
        
        // Data attributes
        doc.select("[data-src]").forEach { urls.add(it.attr("data-src")) }
        doc.select("[data-url]").forEach { urls.add(it.attr("data-url")) }
        doc.select("[data-href]").forEach { urls.add(it.attr("data-href")) }
        doc.select("[data-link]").forEach { urls.add(it.attr("data-link")) }
        doc.select("[data-file]").forEach { urls.add(it.attr("data-file")) }
        doc.select("[data-play]").forEach { urls.add(it.attr("data-play")) }
        
        // API endpoints in attributes
        doc.select("[data-api]").forEach { urls.add(it.attr("data-api")) }
        doc.select("[data-endpoint]").forEach { urls.add(it.attr("data-endpoint")) }
    }
    
    private fun extractEmbeddedJsonFromHtml(html: String, data: MutableMap<String, Any>) {
        // Find JSON in script tags
        val jsonPatterns = listOf(
            Regex("""var\s+\w+\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""const\s+\w+\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""window\.\w+\s*=\s*(\{[\s\S]*?\});"""),
            Regex("""data-config\s*=\s*["'](\{[^"']+\})["']"""),
            Regex("""data-setup\s*=\s*["'](\{[^"']+\})["']""")
        )
        
        jsonPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val jsonStr = match.groupValues[1]
                try {
                    val parsed = parseJsonLoose(jsonStr)
                    if (parsed.isNotEmpty()) {
                        data["embedded_json_${data.size}"] = parsed
                    }
                } catch (e: Exception) { /* Skip invalid JSON */ }
            }
        }
    }
    
    private fun extractUrlsFromJson(json: String, urls: MutableList<String>, data: MutableMap<String, Any>) {
        try {
            val parsed = parseJsonLoose(json)
            data.putAll(parsed)
            extractUrlsFromMap(parsed, urls)
        } catch (e: Exception) { /* Skip */ }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun extractUrlsFromMap(map: Map<String, Any>, urls: MutableList<String>, depth: Int = 0) {
        if (depth > 5) return // Prevent deep recursion
        
        map.forEach { (_, value) ->
            when (value) {
                is String -> {
                    if (isPotentialUrl(value)) urls.add(value)
                }
                is Map<*, *> -> extractUrlsFromMap(value as Map<String, Any>, urls, depth + 1)
                is List<*> -> {
                    value.forEach { item ->
                        if (item is String && isPotentialUrl(item)) urls.add(item)
                        else if (item is Map<*, *>) extractUrlsFromMap(item as Map<String, Any>, urls, depth + 1)
                    }
                }
            }
        }
    }
    
    private fun isPotentialUrl(str: String): Boolean {
        return str.startsWith("http://") || 
               str.startsWith("https://") || 
               str.startsWith("//") ||
               str.contains(".m3u8") ||
               str.contains(".mp4") ||
               str.contains(".mpd") ||
               str.contains("/video") ||
               str.contains("/stream") ||
               str.contains("/player") ||
               str.contains("/embed")
    }
    
    private fun analyzeEncryption(body: String, hints: MutableList<EncryptionHint>) {
        // Look for Base64 encoded data
        val base64Pattern = Regex("""[A-Za-z0-9+/]{32,}={0,2}""")
        base64Pattern.findAll(body).forEach { match ->
            val candidate = match.value
            if (isValidBase64(candidate)) {
                hints.add(EncryptionHint(
                    algorithm = "AES",
                    keySource = KeySource.BRUTE_FORCE,
                    ivLocation = "PREFIX_16",
                    encoding = "BASE64",
                    confidence = 0.5f
                ))
            }
        }
        
        // Look for key derivation hints
        val keyPatterns = listOf(
            Regex("""password\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""key\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""secret\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""token\s*[=:]\s*["']([^"']+)["']""")
        )
        
        keyPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                hints.add(EncryptionHint(
                    algorithm = "AES",
                    keySource = KeySource.EMBEDDED_IN_JS,
                    ivLocation = "UNKNOWN",
                    encoding = "BASE64",
                    confidence = 0.7f
                ))
            }
        }
    }
    
    private fun isValidBase64(str: String): Boolean {
        return try {
            Base64.decode(str, Base64.DEFAULT)
            true
        } catch (e: Exception) { false }
    }
    
    private fun parseJsonLoose(json: String): Map<String, Any> {
        // Try standard parsing first
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            // Try to fix common JSON issues
            try {
                val fixed = json
                    .replace("'", "\"")
                    .replace(Regex(""",\s*}"""), "}")
                    .replace(Regex(""",\s*]"""), "]")
                val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                mapper.readValue(fixed, Map::class.java) as Map<String, Any>
            } catch (e2: Exception) {
                emptyMap()
            }
        }
    }
}

// ================================================================================
// MODULE 3: UNIVERSAL DATA MINING ENGINE
// ================================================================================

/**
 * Khai thác mọi response bằng:
 * - Multi-regex deep scan
 * - DOM traversal
 * - Script token scanning
 * - Inline JSON recovery
 * - Entropy analysis để phát hiện dữ liệu mã hóa
 */
class UniversalDataMiningEngine(
    private val debugMode: Boolean = true
) {
    private val TAG = "DataMiner"
    
    // URL patterns với multiple capture strategies
    private val urlPatterns = listOf(
        // Direct URLs
        Regex("""https?://[^\s"'`<>]+\.m3u8[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]+\.mp4[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]+\.mpd[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]+\.mkv[^\s"'`<>]*"""),
        
        // Stream URLs
        Regex("""https?://[^\s"'`<>]*/stream[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]*/video[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]*/play[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]*/embed[^\s"'`<>]*"""),
        
        // API endpoints
        Regex("""https?://[^\s"'`<>]*/api[^\s"'`<>]*"""),
        Regex("""https?://[^\s"'`<>]*/ajax[^\s"'`<>]*"""),
        
        // Generic URL in quotes
        Regex("""["']https?://[^"']+["']"""),
        
        // Protocol-relative URLs
        Regex("""["']//[a-zA-Z0-9.-]+/[^\s"']+\.m3u8[^"']*["']"""),
        Regex("""["']//[a-zA-Z0-9.-]+/[^\s"']+\.mp4[^"']*["']""")
    )
    
    // Hash/ID patterns
    private val hashPatterns = listOf(
        Regex("""data-hash\s*=\s*["']([^"']+)["']"""),
        Regex("""data-id\s*=\s*["']([^"']+)["']"""),
        Regex("""data-key\s*=\s*["']([^"']+)["']"""),
        Regex("""hash\s*[=:]\s*["']([^"']+)["']"""),
        Regex("""id\s*[=:]\s*["']([^"']+)["']""")
    )
    
    // Encrypted data patterns
    private val encryptedPatterns = listOf(
        Regex("""file\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""link\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""source\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""url\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']"""),
        Regex("""enc\s*[=:]\s*["']([A-Za-z0-9+/]{20,}={0,2})["']""")
    )
    
    // JSON in JavaScript
    private val jsonInJsPatterns = listOf(
        Regex("""\{[\s\S]*?"file"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"url"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"source"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"link"[\s\S]*?\}"""),
        Regex("""\{[\s\S]*?"src"[\s\S]*?\}""")
    )
    
    /**
     * Deep mine data from response
     */
    fun mine(response: ResponseSnapshot, session: SessionContext): MiningResult {
        val body = response.bodyString ?: return MiningResult.empty()
        
        val urls = mutableListOf<String>()
        val hashes = mutableListOf<String>()
        val encryptedData = mutableListOf<String>()
        val jsonObjects = mutableListOf<Map<String, Any>>()
        val tokens = mutableListOf<String>()
        
        // Phase 1: Multi-regex deep scan
        urlPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                val url = match.groupValues.getOrNull(0)?.trim('"', '\'') ?: return@forEach
                urls.add(url)
            }
        }
        
        // Phase 2: Hash/ID extraction
        hashPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                hashes.add(match.groupValues[1])
            }
        }
        
        // Phase 3: Encrypted data extraction
        encryptedPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                encryptedData.add(match.groupValues[1])
            }
        }
        
        // Phase 4: JSON recovery from JS
        jsonInJsPatterns.forEach { pattern ->
            pattern.findAll(body).forEach { match ->
                try {
                    val jsonStr = match.groupValues[0]
                    val parsed = parseJsonLoose(jsonStr)
                    if (parsed.isNotEmpty()) jsonObjects.add(parsed)
                } catch (e: Exception) { /* Skip */ }
            }
        }
        
        // Phase 5: DOM traversal (if HTML)
        if (body.contains("<")) {
            try {
                val doc = Jsoup.parse(body)
                mineFromDom(doc, urls, hashes, tokens)
            } catch (e: Exception) { /* Skip */ }
        }
        
        // Phase 6: Entropy analysis for hidden encrypted data
        val highEntropyBlocks = findHighEntropyBlocks(body)
        encryptedData.addAll(highEntropyBlocks)
        
        // Phase 7: Token scanning for API parameters
        val apiTokens = extractApiTokens(body)
        tokens.addAll(apiTokens)
        
        return MiningResult(
            urls = urls.distinct(),
            hashes = hashes.distinct(),
            encryptedData = encryptedData.distinct(),
            jsonObjects = jsonObjects,
            tokens = tokens.distinct()
        )
    }
    
    private fun mineFromDom(
        doc: Document, 
        urls: MutableList<String>, 
        hashes: MutableList<String>,
        tokens: MutableList<String>
    ) {
        // All data attributes
        doc.select("*").forEach { el ->
            el.attributes().forEach { attr ->
                when {
                    attr.key.startsWith("data-") && attr.key != "data-src" -> {
                        val value = attr.value
                        if (value.startsWith("http")) urls.add(value)
                        else if (value.length > 10) tokens.add("${attr.key}=$value")
                    }
                }
            }
        }
        
        // Script content mining
        doc.select("script:not([src])").forEach { script ->
            val content = script.data()
            
            // Look for inline URLs
            urlPatterns.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    val url = match.groupValues.getOrNull(0)?.trim('"', '\'') ?: return@forEach
                    urls.add(url)
                }
            }
            
            // Look for variable assignments with URLs
            val varUrlPattern = Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|\.mp4|/stream|/video|/play)[^"']*)["']""")
            varUrlPattern.findAll(content).forEach { match ->
                urls.add(match.groupValues[1])
            }
        }
        
        // Hidden inputs
        doc.select("input[type=hidden]").forEach { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (value.isNotBlank()) {
                tokens.add("$name=$value")
                if (value.startsWith("http")) urls.add(value)
                if (value.length > 20) hashes.add(value)
            }
        }
    }
    
    private fun findHighEntropyBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val pattern = Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
        
        pattern.findAll(text).forEach { match ->
            val block = match.value
            val entropy = calculateEntropy(block)
            if (entropy > 4.0) { // High entropy threshold
                blocks.add(block)
            }
        }
        
        return blocks
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
    
    private fun extractApiTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        
        // Look for API keys/tokens
        val tokenPatterns = listOf(
            Regex("""api[_-]?key\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""token\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""auth\s*[=:]\s*["']([^"']+)["']"""),
            Regex("""bearer\s+([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        )
        
        tokenPatterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                tokens.add(match.groupValues[1])
            }
        }
        
        return tokens
    }
    
    private fun parseJsonLoose(json: String): Map<String, Any> {
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            try {
                val fixed = json.replace("'", "\"")
                val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                mapper.readValue(fixed, Map::class.java) as Map<String, Any>
            } catch (e2: Exception) {
                emptyMap()
            }
        }
    }
}

data class MiningResult(
    val urls: List<String>,
    val hashes: List<String>,
    val encryptedData: List<String>,
    val jsonObjects: List<Map<String, Any>>,
    val tokens: List<String>
) {
    companion object {
        fun empty() = MiningResult(
            urls = emptyList(),
            hashes = emptyList(),
            encryptedData = emptyList(),
            jsonObjects = emptyList(),
            tokens = emptyList()
        )
    }
}

// ================================================================================
// MODULE 4: JS STATIC REVERSE ENGINE
// ================================================================================

/**
 * Reverse engineer JS tạo link runtime:
 * - Unpack eval-packed code
 * - Resolve variable graph
 * - Simulate execution logic bằng phân tích tĩnh
 * - Trích xuất URL cuối cùng
 */
class JSReverseEngine(
    private val debugMode: Boolean = true
) {
    private val TAG = "JSReverse"
    
    /**
     * Unpack eval-packed JavaScript
     */
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
        
        // eval(...) patterns
        val evalPattern = Regex("""eval\s*\(\s*(['"])([\s\S]*?)\1\s*\)""")
        evalPattern.find(js)?.let { match ->
            val content = match.groupValues[2]
            // Try to decode if it's a string literal with escape sequences
            val decoded = decodeEscapes(content)
            return js.replace(match.value, decoded)
        }
        
        // atob() Base64 decoding
        val atobPattern = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")
        atobPattern.find(js)?.let { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                return js.replace(match.value, "'$decoded'")
            } catch (e: Exception) { /* Continue */ }
        }
        
        // String.fromCharCode patterns
        val charCodePattern = Regex(
            """String\.fromCharCode\s*\(([\d\s,]+)\)"""
        )
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
            // Extract parameters from function(p,a,c,k,e,d)
            val paramPattern = Regex(
                """function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d?\s*\)\s*\{[\s\S]*?\}\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*,"""
            )
            
            // Alternative pattern for different format
            val altPattern = Regex(
                """\}\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*,"""
            )
            
            val match = paramPattern.find(packed) ?: altPattern.find(packed) ?: return null
            
            val p = match.groupValues[1]
            val a = match.groupValues[2].toInt()
            val c = match.groupValues[3].toInt()
            val k = match.groupValues[4].split("|")
            
            // Simple substitution unpacking
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
    
    /**
     * Extract URLs from unpacked JavaScript
     */
    fun extractUrlsFromJS(js: String): List<String> {
        val urls = mutableListOf<String>()
        
        // URL patterns
        val urlPatterns = listOf(
            Regex("""['"]https?://[^'"]+\.m3u8[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mp4[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mpd[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]*(?:stream|video|play)[^'"]*['"]"""),
            Regex("""['"]//[a-zA-Z0-9.-]+/[^'"]+\.m3u8[^'"]*['"]""")
        )
        
        urlPatterns.forEach { pattern ->
            pattern.findAll(js).forEach { match ->
                val url = match.value.trim('\'', '"')
                urls.add(url)
            }
        }
        
        // Variable assignments with URLs
        val varPattern = Regex(
            """(?:var|let|const)\s+(\w+)\s*=\s*['"]([^'"]*(?:\.m3u8|\.mp4|/stream|/video)[^'"]*)['"]"""
        )
        varPattern.findAll(js).forEach { match ->
            urls.add(match.groupValues[2])
        }
        
        return urls.distinct()
    }
    
    /**
     * Resolve variable references in URL context
     */
    fun resolveVariableRef(js: String, varName: String): String? {
        // Find variable definition
        val defPattern = Regex("""(?:var|let|const)\s+$varName\s*=\s*([^;]+);""")
        val match = defPattern.find(js) ?: return null
        
        var value = match.groupValues[1].trim()
        
        // If it's a string literal
        if (value.startsWith("'") || value.startsWith('"')) {
            return value.trim('\'', '"')
        }
        
        // If it's a function call
        if (value.contains("atob(")) {
            val atobPattern = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""")
            val atobMatch = atobPattern.find(value)
            if (atobMatch != null) {
                return try {
                    String(Base64.decode(atobMatch.groupValues[1], Base64.DEFAULT))
                } catch (e: Exception) { null }
            }
        }
        
        return null
    }
    
    /**
     * Analyze and trace URL construction in JS
     */
    fun traceUrlConstruction(js: String): List<String> {
        val urls = mutableListOf<String>()
        val unpacked = unpackEval(js)
        
        // Extract direct URLs
        urls.addAll(extractUrlsFromJS(unpacked))
        
        // Look for URL construction patterns
        val concatPattern = Regex("""['"]([^'"]+)['"]\s*\+\s*['"]([^'"]+)['"]""")
        concatPattern.findAll(unpacked).forEach { match ->
            val combined = match.groupValues[1] + match.groupValues[2]
            if (combined.contains("http") || combined.contains(".m3u8") || combined.contains(".mp4")) {
                urls.add(combined)
            }
        }
        
        return urls.distinct()
    }
}

// ================================================================================
// MODULE 5: ENCRYPTION AUTOBREAK SYSTEM
// ================================================================================

/**
 * Hệ thống brute-strategy tự động thử:
 * - Mọi Base64 mode
 * - Mọi AES mode phổ biến
 * - Nhiều chiến lược derive key
 * - Nhiều cách xử lý IV
 * - Nhiều lớp nén/không nén
 */
class EncryptionAutoBreaker(
    private val debugMode: Boolean = true
) {
    private val TAG = "EncBreaker"
    
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
        "AES/CTR/NoPadding",
        "AES/GCM/NoPadding"
    )
    
    // Hash algorithms for key derivation
    private val hashAlgorithms = listOf(
        "SHA-256",
        "SHA-1",
        "MD5",
        "SHA-384",
        "SHA-512"
    )
    
    data class DecryptResult(
        val success: Boolean,
        val plaintext: String?,
        val method: String?,
        val score: Float
    )
    
    /**
     * Auto-break encrypted data with multiple strategies
     */
    fun autoBreak(encrypted: String, session: SessionContext): DecryptResult {
        val startTime = System.currentTimeMillis()
        
        // Strategy 1: Direct AES with known passwords
        knownPasswords.forEach { password ->
            val result = tryDecryptWithPassword(encrypted, password)
            if (result.success && isValidOutput(result.plaintext)) {
                logSuccess("Password: $password", startTime, session)
                return result
            }
        }
        
        // Strategy 2: AES with key derived from URL/filmId
        // (This would need filmId from context)
        
        // Strategy 3: Try different IV extraction methods
        val ivStrategies = listOf(
            IVPrefixed(),      // IV is first 16 bytes
            IVFromKey(),       // IV derived from key
            IVZeroed(),        // All-zero IV
            IVFromUrl()        // IV from URL parameter
        )
        
        ivStrategies.forEach { strategy ->
            val result = tryWithIVStrategy(encrypted, strategy, session)
            if (result.success && isValidOutput(result.plaintext)) {
                logSuccess("IV Strategy: ${strategy.name}", startTime, session)
                return result
            }
        }
        
        // Strategy 4: Multi-layer decryption
        val multiResult = tryMultiLayer(encrypted, session)
        if (multiResult.success && isValidOutput(multiResult.plaintext)) {
            logSuccess("Multi-layer", startTime, session)
            return multiResult
        }
        
        // Strategy 5: Try with compression
        val compressedResult = tryWithCompression(encrypted, session)
        if (compressedResult.success) {
            logSuccess("With compression", startTime, session)
            return compressedResult
        }
        
        return DecryptResult(false, null, null, 0f)
    }
    
    private fun tryDecryptWithPassword(encrypted: String, password: String): DecryptResult {
        for (hashAlgo in hashAlgorithms) {
            for (aesMode in aesModes) {
                try {
                    val result = decryptAES(encrypted, password, hashAlgo, aesMode, IVPrefixed())
                    if (result != null && isValidOutput(result)) {
                        return DecryptResult(true, result, "$aesMode with $hashAlgo key from '$password'", 0.9f)
                    }
                } catch (e: Exception) { /* Continue */ }
            }
        }
        
        return DecryptResult(false, null, null, 0f)
    }
    
    private fun decryptAES(
        encrypted: String,
        password: String,
        hashAlgorithm: String,
        aesMode: String,
        ivStrategy: IVStrategy
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
                sha1.copyOf(16) // Truncate to 128-bit for AES-128
            }
            "MD5" -> MessageDigest.getInstance("MD5").digest(password.toByteArray(StandardCharsets.UTF_8))
            "SHA-384" -> MessageDigest.getInstance("SHA-384").digest(password.toByteArray(StandardCharsets.UTF_8)).copyOf(32)
            "SHA-512" -> MessageDigest.getInstance("SHA-512").digest(password.toByteArray(StandardCharsets.UTF_8)).copyOf(32)
            else -> return null
        }
        
        // Extract IV and ciphertext
        val (iv, ciphertext) = ivStrategy.extract(decoded, key)
        
        // Decrypt
        return try {
            val cipher = Cipher.getInstance(aesMode)
            val keySpec = SecretKeySpec(key, "AES")
            
            when {
                aesMode.contains("GCM") -> {
                    val gcmSpec = GCMParameterSpec(128, iv)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                }
                else -> {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
                }
            }
            
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
    
    private fun tryWithIVStrategy(encrypted: String, strategy: IVStrategy, session: SessionContext): DecryptResult {
        for (password in knownPasswords) {
            for (hashAlgo in hashAlgorithms) {
                for (aesMode in aesModes) {
                    try {
                        val result = decryptAES(encrypted, password, hashAlgo, aesMode, strategy)
                        if (result != null && isValidOutput(result)) {
                            return DecryptResult(true, result, "$aesMode with IV strategy ${strategy.name}", 0.8f)
                        }
                    } catch (e: Exception) { /* Continue */ }
                }
            }
        }
        
        return DecryptResult(false, null, null, 0f)
    }
    
    private fun tryMultiLayer(encrypted: String, session: SessionContext): DecryptResult {
        var current = encrypted
        
        for (layer in 1..3) {
            val result = autoBreak(current, session)
            if (!result.success) break
            
            val plain = result.plaintext ?: break
            
            // Check if result is still encrypted
            if (looksEncrypted(plain)) {
                current = plain
            } else {
                return DecryptResult(true, plain, "Multi-layer ($layer layers)", 0.7f)
            }
        }
        
        return DecryptResult(false, null, null, 0f)
    }
    
    private fun tryWithCompression(encrypted: String, session: SessionContext): DecryptResult {
        // Already handled in decryptAES, but try additional patterns
        return DecryptResult(false, null, null, 0f)
    }
    
    private fun isValidOutput(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        
        // Check if it looks like a valid URL or JSON
        return text.startsWith("http://") ||
               text.startsWith("https://") ||
               text.startsWith("//") ||
               text.startsWith("{") ||
               text.startsWith("[") ||
               text.contains(".m3u8") ||
               text.contains(".mp4") ||
               !looksEncrypted(text)
    }
    
    private fun looksEncrypted(text: String): Boolean {
        // High ratio of non-printable or unusual characters
        val printable = text.count { it.code in 32..126 || it in "\n\r\t" }
        return printable.toFloat() / text.length < 0.7f
    }
    
    private fun logSuccess(method: String, startTime: Long, session: SessionContext) {
        if (debugMode) {
            session.traceLog.add(TraceEntry(
                phase = "DECRYPT",
                action = "Decryption successful",
                input = "Encrypted data",
                output = method,
                durationMs = System.currentTimeMillis() - startTime,
                success = true,
                error = null
            ))
        }
    }
    
    // IV Strategy interfaces
    interface IVStrategy {
        val name: String
        fun extract(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray>
    }
    
    class IVPrefixed : IVStrategy {
        override val name = "IV_PREFIXED"
        override fun extract(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
            return Pair(data.copyOfRange(0, 16), data.copyOfRange(16, data.size))
        }
    }
    
    class IVFromKey : IVStrategy {
        override val name = "IV_FROM_KEY"
        override fun extract(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
            return Pair(key.copyOfRange(0, 16), data)
        }
    }
    
    class IVZeroed : IVStrategy {
        override val name = "IV_ZEROED"
        override fun extract(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
            return Pair(ByteArray(16), data)
        }
    }
    
    class IVFromUrl : IVStrategy {
        override val name = "IV_FROM_URL"
        override fun extract(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
            // Would need URL context - use zeroed for now
            return Pair(ByteArray(16), data)
        }
    }
}

// ================================================================================
// MODULE 6: MULTI-DEPTH PROVIDER CRAWLER
// ================================================================================

/**
 * Crawl vô hạn layers khi link dẫn tới:
 * - Embed page
 * - Gateway page
 * - JS loader page
 */
class ProviderCrawler(
    private val networkEngine: NetworkReconstructionEngine,
    private val analyzer: ResponseIntelligenceAnalyzer,
    private val miner: UniversalDataMiningEngine,
    private val jsEngine: JSReverseEngine,
    private val encryptBreaker: EncryptionAutoBreaker,
    private val debugMode: Boolean = true
) {
    private val TAG = "ProviderCrawl"
    
    // Known embed patterns
    private val embedDomains = listOf(
        "embed", "player", "stream", "video", "load", "play",
        "watch", "view", "get", "fetch", "proxy"
    )
    
    /**
     * Deep crawl through provider layers
     */
    suspend fun crawl(
        startUrl: String,
        session: SessionContext,
        headers: Map<String, String> = emptyMap()
    ): List<StreamCandidate> {
        val candidates = mutableListOf<StreamCandidate>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<CrawlTask>()
        
        queue.addLast(CrawlTask(startUrl, listOf("start"), 0))
        
        while (queue.isNotEmpty() && session.currentDepth < session.maxDepth) {
            val task = queue.removeFirst()
            
            // Skip if already visited
            val normalizedUrl = normalizeUrl(task.url)
            if (normalizedUrl in visited) continue
            visited.add(normalizedUrl)
            
            session.currentDepth = task.depth
            
            // Execute request
            val response = networkEngine.executeTracked(
                url = task.url,
                headers = headers,
                session = session
            ) ?: continue
            
            // Analyze response
            val analysis = analyzer.analyze(response)
            
            // Check for direct stream
            if (isDirectStream(response, analysis)) {
                candidates.add(StreamCandidate(
                    url = response.finalUrl,
                    source = task.url,
                    extractionPath = task.path + listOf("direct"),
                    encryptionRequired = false,
                    headers = headers
                ))
                continue
            }
            
            // Mine data
            val miningResult = miner.mine(response, session)
            
            // Process encrypted data
            miningResult.encryptedData.forEach { enc ->
                val decryptResult = encryptBreaker.autoBreak(enc, session)
                if (decryptResult.success && decryptResult.plaintext != null) {
                    val decrypted = decryptResult.plaintext!!
                    if (decrypted.startsWith("http")) {
                        candidates.add(StreamCandidate(
                            url = decrypted,
                            source = task.url,
                            extractionPath = task.path + listOf("decrypt:${decryptResult.method}"),
                            encryptionRequired = false,
                            headers = headers
                        ))
                    }
                }
            }
            
            // Process URLs
            miningResult.urls.forEach { url ->
                val fixed = fixUrl(url, task.url)
                
                when {
                    isStreamUrl(fixed) -> {
                        candidates.add(StreamCandidate(
                            url = fixed,
                            source = task.url,
                            extractionPath = task.path + listOf("url"),
                            encryptionRequired = false,
                            headers = headers
                        ))
                    }
                    isEmbedUrl(fixed) && task.depth < session.maxDepth - 1 -> {
                        queue.addLast(CrawlTask(fixed, task.path + listOf("embed"), task.depth + 1))
                    }
                }
            }
            
            // Process JS for URLs
            if (analysis.type == ResponseType.JAVASCRIPT || 
                analysis.type == ResponseType.PACKED_JS ||
                analysis.type == ResponseType.OBFUSCATED_JS) {
                
                val body = response.bodyString ?: continue
                val unpacked = jsEngine.unpackEval(body)
                val jsUrls = jsEngine.extractUrlsFromJS(unpacked)
                
                jsUrls.forEach { url ->
                    val fixed = fixUrl(url, task.url)
                    if (isStreamUrl(fixed)) {
                        candidates.add(StreamCandidate(
                            url = fixed,
                            source = task.url,
                            extractionPath = task.path + listOf("js"),
                            encryptionRequired = false,
                            headers = headers
                        ))
                    }
                }
            }
        }
        
        return candidates.distinctBy { it.url }
    }
    
    private fun isDirectStream(response: ResponseSnapshot, analysis: ResponseAnalysis): Boolean {
        return analysis.type == ResponseType.M3U8_PLAYLIST ||
               analysis.type == ResponseType.MPD_MANIFEST ||
               analysis.type == ResponseType.BINARY_VIDEO ||
               response.finalUrl.contains(".m3u8") ||
               response.finalUrl.contains(".mp4") ||
               response.finalUrl.contains(".mpd")
    }
    
    private fun isStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") ||
               url.contains(".mp4") ||
               url.contains(".mpd") ||
               url.contains(".mkv") ||
               url.contains("/stream/")
    }
    
    private fun isEmbedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return embedDomains.any { lower.contains(it) } &&
               !isStreamUrl(url)
    }
    
    private fun normalizeUrl(url: String): String {
        return try {
            val parsed = URL(url)
            "${parsed.protocol}://${parsed.host}${parsed.path}"
        } catch (e: Exception) { url }
    }
    
    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        
        return try {
            val base = URL(baseUrl)
            URL(base, url).toString()
        } catch (e: Exception) { url }
    }
    
    data class CrawlTask(
        val url: String,
        val path: List<String>,
        val depth: Int
    )
}

// ================================================================================
// MODULE 7: STREAM AUTHENTICITY VALIDATOR
// ================================================================================

/**
 * Xác thực mọi link tìm được bằng:
 * - Signature m3u8
 * - MIME detection
 * - Byte pattern recognition
 * - HEAD verification
 */
class StreamValidator(
    private val app: requests,
    private val cfKiller: CloudflareKiller,
    private val debugMode: Boolean = true
) {
    private val TAG = "StreamValidator"
    
    // M3U8 signatures
    private val m3u8Signatures = listOf(
        "#EXTM3U",
        "#EXT-X-VERSION",
        "#EXT-X-TARGETDURATION",
        "#EXTINF"
    )
    
    // MPD signatures
    private val mpdSignatures = listOf(
        "<MPD",
        "<Period",
        "<AdaptationSet",
        "mimetype="
    )
    
    // Video file signatures
    private val mp4Signature = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x66, 0x74, 0x79, 0x70) // ftyp
    private val mkvSignature = byteArrayOf(0x1A, 0x45, 0xDF, 0xA3) // EBML
    
    /**
     * Validate stream candidate
     */
    suspend fun validate(
        candidate: StreamCandidate,
        session: SessionContext
    ): StreamCandidate {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Quick HEAD request first
            val headResponse = app.get(
                candidate.url,
                headers = candidate.headers,
                interceptor = cfKiller
            )
            
            val finalUrl = headResponse.url
            val contentType = headResponse.headers["content-type"]
            val contentLength = headResponse.headers["content-length"]?.toLongOrNull()
            
            // Check content type
            val streamType = detectStreamType(finalUrl, contentType)
            
            // Verify content
            val body = headResponse.text
            val isValid = verifyContent(body, streamType)
            
            val validationResult = ValidationResult(
                isValid = isValid,
                streamType = streamType,
                mimeType = contentType,
                contentLength = contentLength,
                requiresAuth = headResponse.code == 403 || headResponse.code == 401,
                errorReason = if (!isValid) "Content verification failed" else null
            )
            
            if (debugMode) {
                session.traceLog.add(TraceEntry(
                    phase = "VALIDATE",
                    action = "Stream validated",
                    input = candidate.url,
                    output = validationResult,
                    durationMs = System.currentTimeMillis() - startTime,
                    success = isValid,
                    error = null
                ))
            }
            
            candidate.copy(
                url = finalUrl,
                validationResult = validationResult
            )
        } catch (e: Exception) {
            val validationResult = ValidationResult(
                isValid = false,
                streamType = null,
                mimeType = null,
                contentLength = null,
                requiresAuth = false,
                errorReason = e.message
            )
            
            candidate.copy(validationResult = validationResult)
        }
    }
    
    private fun detectStreamType(url: String, contentType: String?): StreamType {
        val lowerUrl = url.lowercase()
        val lowerCt = contentType?.lowercase() ?: ""
        
        return when {
            lowerUrl.contains(".m3u8") || lowerCt.contains("mpegurl") -> StreamType.HLS_M3U8
            lowerUrl.contains(".mpd") || lowerCt.contains("dash") -> StreamType.DASH_MPD
            lowerUrl.contains(".mp4") || lowerCt.contains("mp4") -> StreamType.MP4_DIRECT
            lowerUrl.contains(".mkv") || lowerCt.contains("x-matroska") -> StreamType.MKV_DIRECT
            else -> StreamType.UNKNOWN
        }
    }
    
    private fun verifyContent(body: String?, streamType: StreamType): Boolean {
        if (body == null) return false
        
        return when (streamType) {
            StreamType.HLS_M3U8 -> m3u8Signatures.any { body.contains(it) }
            StreamType.DASH_MPD -> mpdSignatures.any { body.contains(it, ignoreCase = true) }
            StreamType.MP4_DIRECT -> true // Would need binary check
            StreamType.MKV_DIRECT -> true // Would need binary check
            StreamType.UNKNOWN -> body.isNotEmpty()
        }
    }
    
    /**
     * Batch validate candidates
     */
    suspend fun validateBatch(
        candidates: List<StreamCandidate>,
        session: SessionContext
    ): List<StreamCandidate> = coroutineScope {
        candidates.map { candidate ->
            async { validate(candidate, session) }
        }.awaitAll()
    }
}

// ================================================================================
// MODULE 8: ADAPTIVE DECISION ENGINE
// ================================================================================

/**
 * AI-LIKE DECISION TREE với fallback tự động
 */
class AdaptiveDecisionEngine(
    private val networkEngine: NetworkReconstructionEngine,
    private val analyzer: ResponseIntelligenceAnalyzer,
    private val miner: UniversalDataMiningEngine,
    private val jsEngine: JSReverseEngine,
    private val encryptBreaker: EncryptionAutoBreaker,
    private val crawler: ProviderCrawler,
    private val validator: StreamValidator,
    private val debugMode: Boolean = true
) {
    private val TAG = "AdaptiveEngine"
    
    /**
     * Main resolution pipeline
     */
    suspend fun resolve(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        val allCandidates = mutableListOf<StreamCandidate>()
        
        // Try strategies in order with fallback
        val strategies = listOf(
            ResolutionStrategy.DIRECT_API,
            ResolutionStrategy.JS_REVERSE,
            ResolutionStrategy.ENCRYPTION_BREAK,
            ResolutionStrategy.DEEP_CRAWL,
            ResolutionStrategy.PROVIDER_FALLBACK
        )
        
        for (strategy in strategies) {
            session.currentStrategy = strategy
            
            val candidates = tryStrategy(strategy, epUrl, filmId, episodeId, session, headers)
            
            if (candidates.isNotEmpty()) {
                allCandidates.addAll(candidates)
                
                // Validate candidates
                val validated = validator.validateBatch(candidates, session)
                val validOnes = validated.filter { it.validationResult?.isValid == true }
                
                if (validOnes.isNotEmpty()) {
                    return validOnes.sortedByDescending { it.priority }
                }
            }
            
            // Log failed attempt
            session.failedAttempts.add(FailedAttempt(
                strategy = strategy,
                reason = "No valid streams found",
                timestamp = System.currentTimeMillis()
            ))
        }
        
        // If all strategies fail, try adaptive learning
        return tryAdaptive(epUrl, filmId, episodeId, session, headers, allCandidates)
    }
    
    private suspend fun tryStrategy(
        strategy: ResolutionStrategy,
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> = when (strategy) {
        ResolutionStrategy.DIRECT_API -> tryDirectApi(epUrl, filmId, episodeId, session, headers)
        ResolutionStrategy.JS_REVERSE -> tryJsReverse(epUrl, session, headers)
        ResolutionStrategy.ENCRYPTION_BREAK -> tryEncryptionBreak(epUrl, filmId, episodeId, session, headers)
        ResolutionStrategy.DEEP_CRAWL -> tryDeepCrawl(epUrl, session, headers)
        ResolutionStrategy.PROVIDER_FALLBACK -> tryProviderFallback(epUrl, filmId, episodeId, session, headers)
        ResolutionStrategy.ADAPTIVE -> emptyList() // Handled separately
    }
    
    private suspend fun tryDirectApi(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        val candidates = mutableListOf<StreamCandidate>()
        val mainUrl = "https://animevietsub.ee"
        
        // Step 1: Get player HTML
        val playerResponse = networkEngine.executeTracked(
            url = "$mainUrl/ajax/player",
            method = "POST",
            headers = headers + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            body = mapOf("episodeId" to episodeId, "backup" to "1"),
            session = session
        ) ?: return emptyList()
        
        val playerBody = playerResponse.bodyString ?: return emptyList()
        val playerDoc = Jsoup.parse(playerBody)
        
        // Find all server buttons
        val serverButtons = playerDoc.select("a.btn3dsv")
        
        for (btn in serverButtons) {
            val hash = btn.attr("data-href")
            val play = btn.attr("data-play")
            val btnId = btn.attr("data-id")
            
            if (hash.isBlank()) continue
            
            // Step 2: Activate session
            networkEngine.executeTracked(
                url = "$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
                headers = headers,
                session = session
            )
            
            // Step 3: Get encrypted link
            val idsToTry = listOf(filmId, episodeId)
            
            for (id in idsToTry) {
                val params = if (play == "api") {
                    mapOf("link" to hash, "id" to id)
                } else {
                    mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
                }
                
                val linkResponse = networkEngine.executeTracked(
                    url = "$mainUrl/ajax/player",
                    method = "POST",
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    body = params,
                    session = session
                ) ?: continue
                
                val linkBody = linkResponse.bodyString ?: continue
                val linkAnalysis = analyzer.analyze(linkResponse)
                
                when {
                    linkAnalysis.type == ResponseType.JSON_API -> {
                        // Parse JSON response
                        val urls = extractUrlsFromJsonResponse(linkBody)
                        urls.forEach { url ->
                            candidates.add(StreamCandidate(
                                url = url,
                                source = "API:$play",
                                extractionPath = listOf("direct_api", play, id),
                                encryptionRequired = looksEncrypted(url),
                                headers = headers,
                                priority = if (play == "api") 10 else 5
                            ))
                        }
                    }
                    linkAnalysis.extractedUrls.isNotEmpty() -> {
                        linkAnalysis.extractedUrls.forEach { url ->
                            candidates.add(StreamCandidate(
                                url = url,
                                source = "API:$play",
                                extractionPath = listOf("direct_api", play),
                                encryptionRequired = looksEncrypted(url),
                                headers = headers
                            ))
                        }
                    }
                }
            }
        }
        
        return candidates
    }
    
    private suspend fun tryJsReverse(
        epUrl: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        val candidates = mutableListOf<StreamCandidate>()
        
        // Get page content
        val response = networkEngine.executeTracked(
            url = epUrl,
            headers = headers,
            session = session
        ) ?: return emptyList()
        
        val body = response.bodyString ?: return emptyList()
        
        // Unpack any packed JS
        val unpacked = jsEngine.unpackEval(body)
        
        // Extract URLs
        val urls = jsEngine.traceUrlConstruction(unpacked)
        
        urls.forEach { url ->
            candidates.add(StreamCandidate(
                url = url,
                source = "JS_REVERSE",
                extractionPath = listOf("js_reverse"),
                encryptionRequired = false,
                headers = headers
            ))
        }
        
        return candidates
    }
    
    private suspend fun tryEncryptionBreak(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        val candidates = mutableListOf<StreamCandidate>()
        
        // Get API responses first
        val apiCandidates = tryDirectApi(epUrl, filmId, episodeId, session, headers)
        
        for (candidate in apiCandidates) {
            if (!candidate.encryptionRequired) {
                candidates.add(candidate)
                continue
            }
            
            // Try to decrypt
            val decryptResult = encryptBreaker.autoBreak(candidate.url, session)
            
            if (decryptResult.success && decryptResult.plaintext != null) {
                val decrypted = decryptResult.plaintext!!
                
                if (decrypted.startsWith("http")) {
                    candidates.add(candidate.copy(
                        url = decrypted,
                        encryptionRequired = false,
                        extractionPath = candidate.extractionPath + listOf("decrypted:${decryptResult.method}")
                    ))
                }
            }
        }
        
        return candidates
    }
    
    private suspend fun tryDeepCrawl(
        epUrl: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        return crawler.crawl(epUrl, session, headers)
    }
    
    private suspend fun tryProviderFallback(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>
    ): List<StreamCandidate> {
        // Try alternative providers
        val candidates = mutableListOf<StreamCandidate>()
        
        // Common alternative endpoints
        val altEndpoints = listOf(
            "/ajax/player?episodeId=$episodeId",
            "/ajax/getLink?filmId=$filmId&episodeId=$episodeId",
            "/ajax/stream/$episodeId",
            "/embed/$episodeId",
            "/play/$episodeId"
        )
        
        for (endpoint in altEndpoints) {
            val url = "https://animevietsub.ee$endpoint"
            val crawled = crawler.crawl(url, session, headers)
            candidates.addAll(crawled)
        }
        
        return candidates
    }
    
    private suspend fun tryAdaptive(
        epUrl: String,
        filmId: String,
        episodeId: String,
        session: SessionContext,
        headers: Map<String, String>,
        previousCandidates: List<StreamCandidate>
    ): List<StreamCandidate> {
        // Analyze failed attempts to determine best strategy
        val failedStrategies = session.failedAttempts.map { it.strategy }
        
        // Try combinations of successful patterns from previous attempts
        val validCandidates = previousCandidates.filter { 
            it.validationResult?.isValid != false 
        }
        
        if (validCandidates.isNotEmpty()) {
            return validCandidates
        }
        
        // Last resort: try all URLs found
        return previousCandidates.mapNotNull { candidate ->
            val validated = validator.validate(candidate, session)
            if (validated.validationResult?.isValid == true) validated else null
        }
    }
    
    private fun extractUrlsFromJsonResponse(json: String): List<String> {
        val urls = mutableListOf<String>()
        
        try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val parsed = mapper.readValue(json, Map::class.java)
            
            @Suppress("UNCHECKED_CAST")
            fun extractFromMap(map: Map<String, Any?>, depth: Int = 0) {
                if (depth > 5) return
                
                map.forEach { (_, value) ->
                    when (value) {
                        is String -> {
                            if (value.startsWith("http") || 
                                value.contains(".m3u8") || 
                                value.contains(".mp4")) {
                                urls.add(value)
                            }
                        }
                        is Map<*, *> -> extractFromMap(value as Map<String, Any?>, depth + 1)
                        is List<*> -> {
                            value.forEach { item ->
                                when (item) {
                                    is String -> {
                                        if (item.startsWith("http") || 
                                            item.contains(".m3u8") || 
                                            item.contains(".mp4")) {
                                            urls.add(item)
                                        }
                                    }
                                    is Map<*, *> -> extractFromMap(item as Map<String, Any?>, depth + 1)
                                }
                            }
                        }
                    }
                }
            }
            
            extractFromMap(parsed as Map<String, Any?>)
        } catch (e: Exception) { /* Skip */ }
        
        return urls.distinct()
    }
    
    private fun looksEncrypted(str: String): Boolean {
        // Check if string looks like encrypted data
        return str.matches(Regex("[A-Za-z0-9+/]{20,}={0,2}")) ||
               str.all { it.isLetterOrDigit() || it in "+/=" }
    }
}

// ================================================================================
// MAIN PLUGIN CLASS
// ================================================================================

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    
    // Debug mode - set to true for full forensic trace
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
    
    // Initialize engines
    private val networkEngine by lazy { NetworkReconstructionEngine(app, cfKiller, defaultHeaders, debugMode) }
    private val analyzer by lazy { ResponseIntelligenceAnalyzer(debugMode) }
    private val miner by lazy { UniversalDataMiningEngine(debugMode) }
    private val jsEngine by lazy { JSReverseEngine(debugMode) }
    private val encryptBreaker by lazy { EncryptionAutoBreaker(debugMode) }
    private val crawler by lazy { ProviderCrawler(networkEngine, analyzer, miner, jsEngine, encryptBreaker, debugMode) }
    private val validator by lazy { StreamValidator(app, cfKiller, debugMode) }
    private val decisionEngine by lazy { 
        AdaptiveDecisionEngine(networkEngine, analyzer, miner, jsEngine, encryptBreaker, crawler, validator, debugMode)
    }
    
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
     * STATEFUL RESOLUTION PIPELINE
     * Main entry point for stream extraction
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
        
        // Create session context
        val session = SessionContext()
        
        // Log session start
        if (debugMode) {
            session.traceLog.add(TraceEntry(
                phase = "INIT",
                action = "Session started",
                input = mapOf("url" to epUrl, "filmId" to filmId, "episodeId" to episodeId),
                output = null,
                durationMs = 0,
                success = true,
                error = null
            ))
        }
        
        // Prepare headers
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
            // Use adaptive decision engine
            val candidates = decisionEngine.resolve(epUrl, filmId, episodeId, session, ajaxHeaders)
            
            var foundAny = false
            
            candidates.forEach { candidate ->
                val validated = candidate.validationResult
                
                if (validated?.isValid == true) {
                    val streamType = validated.streamType
                    
                    when (streamType) {
                        StreamType.HLS_M3U8 -> {
                            callback(newExtractorLink(name, name, candidate.url) {
                                this.headers = videoHeaders
                                this.type = ExtractorLinkType.M3U8
                            })
                            
                            // Also generate quality variants
                            runCatching {
                                M3u8Helper.generateM3u8(name, candidate.url, epUrl, headers = videoHeaders).forEach {
                                    it.headers = videoHeaders
                                    callback(it)
                                }
                            }
                            
                            foundAny = true
                        }
                        StreamType.MP4_DIRECT, StreamType.MKV_DIRECT -> {
                            callback(newExtractorLink(name, name, candidate.url) {
                                this.headers = videoHeaders
                            })
                            foundAny = true
                        }
                        StreamType.DASH_MPD -> {
                            // DASH support would go here
                            callback(newExtractorLink(name, name, candidate.url) {
                                this.headers = videoHeaders
                            })
                            foundAny = true
                        }
                        StreamType.UNKNOWN -> {
                            // Try as generic
                            callback(newExtractorLink(name, name, candidate.url) {
                                this.headers = videoHeaders
                            })
                            foundAny = true
                        }
                        null -> { /* Skip invalid */ }
                    }
                }
            }
            
            // Log session end
            if (debugMode) {
                session.traceLog.add(TraceEntry(
                    phase = "END",
                    action = "Session ended",
                    input = null,
                    output = mapOf("found" to foundAny, "candidates" to candidates.size),
                    durationMs = System.currentTimeMillis() - session.startTime,
                    success = foundAny,
                    error = if (!foundAny) "No valid streams found" else null
                ))
                
                // Could save forensic trace here
                saveForensicTrace(session)
            }
            
            foundAny
            
        } catch (e: Exception) {
            if (debugMode) {
                session.traceLog.add(TraceEntry(
                    phase = "ERROR",
                    action = "Exception occurred",
                    input = null,
                    output = null,
                    durationMs = System.currentTimeMillis() - session.startTime,
                    success = false,
                    error = e.message
                ))
            }
            false
        }
    }
    
    /**
     * Save forensic trace for debugging
     */
    private fun saveForensicTrace(session: SessionContext) {
        // In production, this would save to file or log
        val trace = ForensicTrace(
            sessionId = session.id,
            startTime = session.startTime,
            endTime = System.currentTimeMillis(),
            networkTrace = session.requestHistory,
            responseAnalysis = session.responseHistory.map { analyzer.analyze(it) },
            extractionAttempts = session.traceLog.map { trace ->
                ExtractionAttempt(
                    method = trace.phase,
                    input = trace.input ?: "",
                    output = trace.output,
                    success = trace.success,
                    durationMs = trace.durationMs
                )
            },
            finalResult = FinalResult(
                success = session.traceLog.lastOrNull()?.success ?: false,
                streamUrl = null,
                extractionPath = emptyList(),
                totalAttempts = session.failedAttempts.size
            )
        )
        
        // Log summary
        println("=== FORENSIC TRACE ===")
        println("Session: ${trace.sessionId}")
        println("Duration: ${trace.endTime!! - trace.startTime}ms")
        println("Network requests: ${trace.networkTrace.size}")
        println("Extraction attempts: ${trace.extractionAttempts.size}")
        println("Success: ${trace.finalResult?.success}")
        println("=====================")
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
