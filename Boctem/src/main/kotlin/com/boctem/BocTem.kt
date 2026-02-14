package com.boctem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// ============================================================================
// GLOBAL DATA CLASSES & ENUMS (Outside Provider)
// ============================================================================

data class ForensicTrace(
    val timestamp: Long = System.currentTimeMillis(),
    val phase: String,
    val action: String,
    val data: Map<String, Any> = emptyMap(),
    val rawContent: String? = null
)

data class NetworkSnapshot(
    val url: String,
    val method: String,
    val headers: Map<String, List<String>>,
    val requestBody: String?,
    val responseCode: Int,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: String?,
    val redirectChain: List<String> = emptyList(),
    val cookies: Map<String, String> = emptyMap()
)

data class StreamCandidate(
    val url: String,
    val source: String,
    val extractionMethod: String,
    val confidence: Double,
    val context: Map<String, String> = emptyMap(),
    val validationStatus: ValidationStatus = ValidationStatus.UNTESTED
)

enum class ValidationStatus { UNTESTED, VALID, INVALID, TIMEOUT }

data class ResolutionContext(
    val originalUrl: String,
    val depth: Int = 0,
    val maxDepth: Int = 10,
    val visitedUrls: MutableSet<String> = mutableSetOf(),
    val extractedCandidates: MutableList<StreamCandidate> = mutableListOf(),
    val forensicLog: MutableList<ForensicTrace> = mutableListOf(),
    val networkSnapshots: MutableList<NetworkSnapshot> = mutableListOf(),
    val decryptionAttempts: MutableList<DecryptionAttempt> = mutableListOf(),
    val strategyHistory: MutableList<String> = mutableListOf()
)

data class DecryptionAttempt(
    val strategy: String,
    val input: String,
    val output: String?,
    val success: Boolean
)

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

fun calculateEntropy(input: String): Double {
    if (input.isEmpty()) return 0.0
    val freq = input.groupingBy { it }.eachCount()
    val len = input.length.toDouble()
    return -freq.values.sumOf { count ->
        val p = count / len
        p * kotlin.math.log2(p)
    }
}

fun normalizeUrl(url: String?, mainUrl: String = "https://boctem.com"): String? {
    if (url.isNullOrBlank()) return null
    
    var normalized = url.trim()
        .replace("\\/", "/")
        .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
    
    // Decode URL encoding
    try {
        normalized = URLDecoder.decode(normalized, "UTF-8")
    } catch (_: Exception) {}
    
    // Add protocol if missing
    when {
        normalized.startsWith("http://") || normalized.startsWith("https://") -> {}
        normalized.startsWith("//") -> normalized = "https:$normalized"
        normalized.startsWith("/") -> normalized = "$mainUrl$normalized"
        else -> normalized = "https://$normalized"
    }
    
    // Validate URL structure
    return try {
        URL(normalized).toString()
    } catch (_: Exception) {
        null
    }
}

// ============================================================================
// MODULE 1: NETWORK RECONSTRUCTION ENGINE
// ============================================================================

class NetworkReconstructionEngine(private val provider: BocTemProvider) {
    
    suspend fun executeWithTracing(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: Map<String, String>? = null,
        context: ResolutionContext
    ): NetworkSnapshot = withContext(Dispatchers.IO) {
        val redirectChain = mutableListOf<String>()
        var currentUrl = url
        var finalResponse: Response? = null
        val cookiesMap = mutableMapOf<String, String>()
        
        // Follow redirects manually to capture chain
        var redirectCount = 0
        while (redirectCount < 5) {
            val requestHeaders = provider.requestHeaders(currentUrl, headers)
            
            val response = if (method == "POST" && body != null) {
                provider.app.post(currentUrl, data = body, headers = requestHeaders, referer = currentUrl)
            } else {
                provider.app.get(currentUrl, headers = requestHeaders)
            }
            
            // Capture cookies
            response.headers.values("Set-Cookie").forEach { cookie ->
                cookie.split(";").firstOrNull()?.let { kv ->
                    val parts = kv.split("=", limit = 2)
                    if (parts.size == 2) cookiesMap[parts[0].trim()] = parts[1].trim()
                }
            }
            
            if (response.isSuccessful && response.headers["Location"] != null) {
                redirectChain.add(currentUrl)
                currentUrl = normalizeUrl(response.headers["Location"], provider.mainUrl) ?: currentUrl
                if (redirectChain.contains(currentUrl)) break // Circular redirect
                redirectCount++
            } else {
                finalResponse = response
                break
            }
        }
        
        val snapshot = NetworkSnapshot(
            url = url,
            method = method,
            headers = headers.mapValues { listOf(it.value) },
            requestBody = body?.toString(),
            responseCode = finalResponse?.code ?: 0,
            responseHeaders = finalResponse?.headers?.toMultimap() ?: emptyMap(),
            responseBody = finalResponse?.text,
            redirectChain = redirectChain,
            cookies = cookiesMap.toMap()
        )
        
        context.networkSnapshots.add(snapshot)
        context.forensicLog.add(ForensicTrace(
            phase = "NETWORK",
            action = "REQUEST_CHAIN_CAPTURED",
            data = mapOf(
                "originalUrl" to url,
                "finalUrl" to currentUrl,
                "redirectCount" to redirectChain.size,
                "statusCode" to snapshot.responseCode
            )
        ))
        
        snapshot
    }
    
    suspend fun replayFlow(snapshot: NetworkSnapshot, mainUrl: String): NetworkSnapshot {
        return executeWithTracing(
            snapshot.url,
            snapshot.headers.mapValues { it.value.firstOrNull() ?: "" },
            snapshot.method,
            snapshot.requestBody?.let { parseBody(it) },
            ResolutionContext(snapshot.url)
        )
    }
    
    private fun parseBody(bodyStr: String): Map<String, String> {
        return bodyStr.removeSurrounding("{", "}").split(",").associate {
            val (k, v) = it.split("=", limit = 2)
            k.trim() to v.trim().removeSurrounding("\"")
        }
    }
}

// ============================================================================
// MODULE 2: RESPONSE INTELLIGENCE ANALYZER
// ============================================================================

class ResponseIntelligenceAnalyzer {
    
    sealed class ContentType {
        object HtmlDOM : ContentType()
        object ObfuscatedJS : ContentType()
        object PackedJS : ContentType()
        object EncryptedPayload : ContentType()
        object BinaryPlaylist : ContentType()
        object StreamingManifest : ContentType()
        object JsonAPI : ContentType()
        object Unknown : ContentType()
        data class Mixed(val types: List<ContentType>) : ContentType()
    }
    
    fun analyze(content: String?): ContentType {
        if (content.isNullOrBlank()) return ContentType.Unknown
        
        val scores = mutableMapOf<String, Double>()
        
        // HTML detection
        scores["html"] = when {
            content.contains("<!DOCTYPE html>", ignoreCase = true) -> 0.9
            content.contains("<html", ignoreCase = true) -> 0.8
            content.contains("<script", ignoreCase = true) -> 0.6
            else -> 0.0
        }
        
        // Obfuscated JS detection (high entropy, packed patterns)
        val entropy = calculateEntropy(content)
        scores["obfuscated_js"] = when {
            entropy > 7.5 && content.contains("eval(") -> 0.9
            entropy > 7.0 && content.contains("function") -> 0.7
            content.contains("p,a,c,k,e,d") -> 0.95 // Dean Edwards packer signature
            else -> 0.0
        }
        
        // Packed JS detection
        scores["packed_js"] = when {
            content.contains("eval(function(p,a,c,k,e,d)") -> 0.95
            content.contains("eval(function(p,a,c,k,e,r)") -> 0.95
            content.contains("String.fromCharCode") && entropy > 6.5 -> 0.7
            else -> 0.0
        }
        
        // Encrypted payload detection
        scores["encrypted"] = when {
            content.matches(Regex("^[A-Za-z0-9+/=]{100,}$")) -> 0.8
            content.contains("\"encrypted\"") || content.contains("'encrypted'") -> 0.7
            content.contains("AES") || content.contains("CryptoJS") -> 0.6
            else -> 0.0
        }
        
        // Binary/Playlist detection
        scores["playlist"] = when {
            content.contains("#EXTM3U") -> 0.95
            content.contains("#EXT-X-VERSION") -> 0.95
            content.contains(".ts") && content.contains("#EXTINF") -> 0.9
            else -> 0.0
        }
        
        // JSON API detection
        scores["json"] = when {
            (content.startsWith("{") && content.endsWith("}")) ||
            (content.startsWith("[") && content.endsWith("]")) -> 0.8
            content.contains("\"data\"") && content.contains("\"status\"") -> 0.7
            else -> 0.0
        }
        
        // Streaming manifest
        scores["manifest"] = when {
            content.contains("<MPD") || content.contains("</MPD>") -> 0.9 // DASH
            content.contains("<SmoothStreamingMedia") -> 0.9 // SmoothStreaming
            else -> 0.0
        }
        
        val detected = scores.filter { it.value > 0.7 }.keys
        
        return when (detected.size) {
            0 -> ContentType.Unknown
            1 -> when (detected.first()) {
                "html" -> ContentType.HtmlDOM
                "obfuscated_js" -> ContentType.ObfuscatedJS
                "packed_js" -> ContentType.PackedJS
                "encrypted" -> ContentType.EncryptedPayload
                "playlist" -> ContentType.BinaryPlaylist
                "json" -> ContentType.JsonAPI
                "manifest" -> ContentType.StreamingManifest
                else -> ContentType.Unknown
            }
            else -> ContentType.Mixed(detected.map {
                when (it) {
                    "html" -> ContentType.HtmlDOM
                    "obfuscated_js" -> ContentType.ObfuscatedJS
                    "packed_js" -> ContentType.PackedJS
                    "encrypted" -> ContentType.EncryptedPayload
                    "playlist" -> ContentType.BinaryPlaylist
                    "json" -> ContentType.JsonAPI
                    "manifest" -> ContentType.StreamingManifest
                    else -> ContentType.Unknown
                }
            })
        }
    }
}

// ============================================================================
// MODULE 3: UNIVERSAL DATA MINING ENGINE
// ============================================================================

class UniversalDataMiningEngine {
    
    private val urlPatterns = listOf(
        Regex("""(https?://[^\s\"'<>]+)"""),
        Regex("""(//[^\s\"'<>]+)"""),
        Regex("""\\x([0-9a-fA-F]{2})"""),
        Regex("""\\u([0-9a-fA-F]{4})"""),
        Regex("""([A-Za-z0-9+/]{50,}={0,2})"""),
        Regex("""(data:[^\s\"'<>]+)"""),
        Regex("""(magnet:[^\s\"'<>]+)"""),
        Regex("""(https?://\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}[^\s\"'<>]*)""")
    )
    
    private val streamIndicators = listOf(
        "\\.m3u8", "\\.mp4", "\\.mkv", "\\.webm", "\\.ts", "\\.flv",
        "playlist", "manifest", "stream", "video", "chunk", "segment",
        "master", "index", "init", "hls", "dash", "cdn"
    )
    
    fun deepScan(content: String?, context: ResolutionContext, mainUrl: String = "https://boctem.com"): List<StreamCandidate> {
        if (content.isNullOrBlank()) return emptyList()
        
        val candidates = mutableListOf<StreamCandidate>()
        val seenUrls = mutableSetOf<String>()
        
        // Layer 1: Direct regex extraction
        urlPatterns.forEachIndexed { index, pattern ->
            pattern.findAll(content).forEach { match ->
                val raw = match.value
                val decoded = decodeEscapes(raw)
                
                normalizeAndAdd(decoded, "regex_pattern_$index", candidates, seenUrls, context, mainUrl)
                
                // Try base64 decode if looks like it
                if (raw.matches(Regex("[A-Za-z0-9+/]{40,}={0,2}"))) {
                    tryBase64Decode(raw, candidates, seenUrls, context, mainUrl)
                }
            }
        }
        
        // Layer 2: DOM traversal if HTML
        if (content.contains("<")) {
            extractFromDOM(content, candidates, seenUrls, context, mainUrl)
        }
        
        // Layer 3: Script token scanning
        extractScriptTokens(content, candidates, seenUrls, context, mainUrl)
        
        // Layer 4: Inline JSON recovery
        extractJsonFields(content, candidates, seenUrls, context, mainUrl)
        
        // Layer 5: Entropy-based hidden data detection
        extractHighEntropyBlocks(content, candidates, seenUrls, context, mainUrl)
        
        // Layer 6: Concatenated string reconstruction
        reconstructConcatenatedStrings(content, candidates, seenUrls, context, mainUrl)
        
        context.forensicLog.add(ForensicTrace(
            phase = "MINING",
            action = "DEEP_SCAN_COMPLETE",
            data = mapOf("candidatesFound" to candidates.size, "uniqueUrls" to seenUrls.size)
        ))
        
        return candidates.sortedByDescending { it.confidence }
    }
    
    private fun normalizeAndAdd(
        url: String,
        method: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val normalized = normalizeUrl(url, mainUrl) ?: return
        if (normalized in seen) return
        if (normalized.length < 10) return
        
        seen.add(normalized)
        
        val confidence = calculateConfidence(normalized, context)
        candidates.add(StreamCandidate(
            url = normalized,
            source = context.originalUrl,
            extractionMethod = method,
            confidence = confidence,
            context = mapOf("raw" to url.take(100))
        ))
    }
    
    private fun calculateConfidence(url: String, context: ResolutionContext): Double {
        var score = 0.5
        
        streamIndicators.forEach { indicator ->
            if (url.contains(Regex(indicator, RegexOption.IGNORE_CASE))) {
                score += 0.1
            }
        }
        
        if (url.matches(Regex(".*\\.(m3u8|mp4|mkv|webm|ts)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
            score += 0.3
        }
        
        if (url.contains(Regex("(cdn|cloudfront|fastly|akamai)", RegexOption.IGNORE_CASE))) {
            score += 0.1
        }
        
        if (url.length > 500) score -= 0.1
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun decodeEscapes(input: String): String {
        return input
            .replace(Regex("""\\x([0-9a-fA-F]{2})""")) { 
                it.groupValues[1].toInt(16).toChar().toString() 
            }
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { 
                it.groupValues[1].toInt(16).toChar().toString() 
            }
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
    }
    
    private fun tryBase64Decode(
        input: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val variants = listOf(
            input,
            input.replace("-", "+").replace("_", "/"),
            input.padEnd(input.length + (4 - input.length % 4) % 4, '=')
        )
        
        variants.forEach { variant ->
            try {
                val decoded = String(Base64.getDecoder().decode(variant), StandardCharsets.UTF_8)
                if (decoded.length > 10 && (decoded.contains("http") || decoded.contains(".m3u8"))) {
                    normalizeAndAdd(decoded, "base64_decode", candidates, seen, context, mainUrl)
                }
            } catch (_: Exception) {}
        }
    }
    
    private fun extractFromDOM(
        html: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        try {
            val doc = Jsoup.parse(html)
            
            listOf("src", "href", "data-src", "data-url", "data-link", 
                   "data-video", "data-stream", "data-file").forEach { attr ->
                doc.select("[$attr]").forEach { elem ->
                    elem.attr(attr).takeIf { it.isNotBlank() }?.let {
                        normalizeAndAdd(it, "dom_attr_$attr", candidates, seen, context, mainUrl)
                    }
                }
            }
            
            doc.select("iframe").forEach { iframe ->
                iframe.attr("src").takeIf { it.isNotBlank() }?.let {
                    normalizeAndAdd(it, "dom_iframe", candidates, seen, context, mainUrl)
                }
            }
            
            doc.select("video source").forEach { source ->
                source.attr("src").takeIf { it.isNotBlank() }?.let {
                    normalizeAndAdd(it, "dom_video", candidates, seen, context, mainUrl)
                }
            }
            
            doc.select("meta[property=og:video], meta[property=og:video:url]").forEach { meta ->
                meta.attr("content").takeIf { it.isNotBlank() }?.let {
                    normalizeAndAdd(it, "dom_meta_video", candidates, seen, context, mainUrl)
                }
            }
            
        } catch (_: Exception) {}
    }
    
    private fun extractScriptTokens(
        content: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val varPatterns = listOf(
            Regex("""(?:var|let|const)\s+(\w+)\s*=\s*["']([^"']+)["']"""),
            Regex("""(\w+)\s*=\s*["']([^"']*(?:http|//|m3u8|mp4)[^"']*)["']"""),
            Regex("""["']([^"']*(?:http|//|m3u8|mp4)[^"']*)["']\s*[,;]""")
        )
        
        varPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                val potentialUrl = match.groupValues.last()
                normalizeAndAdd(potentialUrl, "script_token", candidates, seen, context, mainUrl)
            }
        }
    }
    
    private fun extractJsonFields(
        content: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val jsonPatterns = listOf(
            Regex(""""(?:src|url|link|file|stream|video|source)"\s*:\s*"([^"]+)""""),
            Regex("""'(?:src|url|link|file|stream|video|source)'\s*:\s*'([^']+)'"""),
            Regex(""""(?:src|url|link|file|stream|video|source)"\s*:\s*`([^`]+)`""")
        )
        
        jsonPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { match ->
                normalizeAndAdd(match.groupValues[1], "json_field", candidates, seen, context, mainUrl)
            }
        }
    }
    
    private fun extractHighEntropyBlocks(
        content: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val blocks = Regex("""[A-Za-z0-9+/=]{100,500}""").findAll(content)
            .map { it.value }
            .distinct()
        
        blocks.forEach { block ->
            val entropy = calculateEntropy(block)
            if (entropy > 7.0) {
                tryBase64Decode(block, candidates, seen, context, mainUrl)
            }
        }
    }
    
    private fun reconstructConcatenatedStrings(
        content: String,
        candidates: MutableList<StreamCandidate>,
        seen: MutableSet<String>,
        context: ResolutionContext,
        mainUrl: String
    ) {
        val concatPattern = Regex("""(["'][^"']+["']\s*\+\s*)+["'][^"']+["']""")
        
        concatPattern.findAll(content).forEach { match ->
            val reconstructed = match.value
                .replace(Regex("""["']\s*\+\s*["']"""), "")
                .replace("\"", "").replace("'", "")
            
            if (reconstructed.contains("http") || reconstructed.contains(".m3u8")) {
                normalizeAndAdd(reconstructed, "concat_reconstruction", candidates, seen, context, mainUrl)
            }
        }
    }
}

// ============================================================================
// MODULE 4: JS STATIC REVERSE ENGINE
// ============================================================================

class JSStaticReverseEngine {
    
    private val unpackers = listOf<(String) -> String?>(
        ::unpackDeanEdwards,
        ::unpackPacker,
        ::unpackEval,
        ::unpackArrayLookup
    )
    
    fun analyzeAndExtract(content: String, context: ResolutionContext): List<String> {
        val extractedUrls = mutableListOf<String>()
        var currentJS = content
        val maxIterations = 5
        
        context.forensicLog.add(ForensicTrace(
            phase = "JS_REVERSE",
            action = "START_ANALYSIS",
            data = mapOf("inputLength" to content.length)
        ))
        
        repeat(maxIterations) { iteration ->
            var modified = false
            
            for (unpacker in unpackers) {
                val result = unpacker(currentJS)
                if (result != null && result != currentJS) {
                    currentJS = result
                    modified = true
                    
                    context.forensicLog.add(ForensicTrace(
                        phase = "JS_REVERSE",
                        action = "UNPACK_SUCCESS",
                        data = mapOf("iteration" to iteration, "unpacker" to unpacker.toString())
                    ))
                    
                    extractUrlsFromJS(currentJS, extractedUrls)
                }
            }
            
            val resolved = resolveVariableGraph(currentJS)
            if (resolved != currentJS) {
                currentJS = resolved
                modified = true
                extractUrlsFromJS(currentJS, extractedUrls)
            }
            
            if (!modified) break
        }
        
        extractUrlsFromJS(currentJS, extractedUrls)
        
        return extractedUrls.distinct()
    }
    
    private fun unpackDeanEdwards(js: String): String? {
        val pattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{[^}]+\}\(([^)]+)\)\)""")
        val match = pattern.find(js) ?: return null
        
        return try {
            val args = match.groupValues[1].split(",").map { it.trim().removeSurrounding("\"") }
            if (args.size >= 4) {
                args[0].replace("\\", "")
            } else null
        } catch (_: Exception) { null }
    }
    
    private fun unpackPacker(js: String): String? {
        val patterns = listOf(
            Regex("""\}\('([^']+)',\s*(\d+),\s*(\d+),\s*'([^']+)'\.split\('\|'\)"""),
            Regex("""unescape\(['"]([^'"]+)['"]\)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(js) ?: continue
            return try {
                URLDecoder.decode(match.groupValues[1], "UTF-8")
            } catch (_: Exception) { null }
        }
        return null
    }
    
    private fun unpackEval(js: String): String? {
        val pattern = Regex("""eval\((["'])(.+?)\1\)""")
        val match = pattern.find(js) ?: return null
        
        return try {
            val inner = match.groupValues[2]
            inner.replace("\\x", "%").let {
                URLDecoder.decode(it, "UTF-8")
            }
        } catch (_: Exception) { null }
    }
    
    private fun unpackArrayLookup(js: String): String? {
        val arrayPattern = Regex("""var\s+(_0x\w+)\s*=\s*(\[[^\]]+\])""")
        val arrayMatch = arrayPattern.find(js) ?: return null
        
        val varName = arrayMatch.groupValues[1]
        val arrayContent = arrayMatch.groupValues[2]
            .removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
        
        var result = js
        val lookupPattern = Regex("""$varName\[(\d+)\]""")
        lookupPattern.findAll(js).forEach { lookup ->
            val index = lookup.groupValues[1].toIntOrNull()
            if (index != null && index < arrayContent.size) {
                result = result.replace(lookup.value, "\"${arrayContent[index]}\"")
            }
        }
        
        return if (result != js) result else null
    }
    
    private fun resolveVariableGraph(js: String): String {
        val varPattern = Regex("""var\s+(\w+)\s*=\s*["']([^"']+)["'];""")
        val variables = varPattern.findAll(js).associate {
            it.groupValues[1] to it.groupValues[2]
        }
        
        var result = js
        variables.forEach { (name, value) ->
            result = result.replace(Regex("""\b$name\b"), "\"$value\"")
        }
        
        return result
    }
    
    private fun extractUrlsFromJS(js: String, output: MutableList<String>) {
        val patterns = listOf(
            Regex("""(https?://[^\s\"'<>]+)"""),
            Regex("""["']([^\"']*(?:m3u8|mp4|stream)[^\"']*)["']""")
        )
        
        patterns.forEach { pattern ->
            pattern.findAll(js).forEach { match ->
                output.add(match.value.removeSurrounding("\"", "'"))
            }
        }
    }
}

// ============================================================================
// MODULE 5: ENCRYPTION AUTOBREAK SYSTEM
// ============================================================================

class EncryptionAutobreakSystem {
    
    data class DecryptionResult(
        val success: Boolean,
        val output: String?,
        val method: String,
        val score: Double
    )
    
    private val commonKeys = listOf(
        "0123456789abcdef",
        "abcdef0123456789",
        "0000000000000000",
        "1234567890123456",
        "boctem",
        "halim",
        "anime",
        "video",
        "stream"
    )
    
    private val commonIVs = listOf(
        "0000000000000000",
        "0123456789abcdef",
        "abcdef0123456789"
    )
    
    fun attemptDecryption(
        input: String,
        context: ResolutionContext
    ): DecryptionResult {
        context.forensicLog.add(ForensicTrace(
            phase = "DECRYPT",
            action = "START_AUTO_BREAK",
            data = mapOf("inputLength" to input.length, "inputSample" to input.take(50))
        ))
        
        val base64Result = tryBase64Variants(input)
        if (base64Result.success) {
            context.decryptionAttempts.add(DecryptionAttempt("base64", input, base64Result.output, true))
            return base64Result
        }
        
        val aesResult = tryAESVariants(input)
        if (aesResult.success) {
            context.decryptionAttempts.add(DecryptionAttempt("aes", input, aesResult.output, true))
            return aesResult
        }
        
        val xorResult = tryXORVariants(input)
        if (xorResult.success) {
            context.decryptionAttempts.add(DecryptionAttempt("xor", input, xorResult.output, true))
            return xorResult
        }
        
        return DecryptionResult(false, null, "none", 0.0)
    }
    
    private fun tryBase64Variants(input: String): DecryptionResult {
        val variants = listOf(
            input,
            input.replace("-", "+").replace("_", "/"),
            input.padEnd(input.length + (4 - input.length % 4) % 4, '='),
            input.reversed()
        )
        
        variants.forEach { variant ->
            try {
                val decoded = String(Base64.getDecoder().decode(variant), StandardCharsets.UTF_8)
                val score = validateOutput(decoded)
                if (score > 0.7) {
                    return DecryptionResult(true, decoded, "base64", score)
                }
            } catch (_: Exception) {}
        }
        
        return DecryptionResult(false, null, "base64", 0.0)
    }
    
    private fun tryAESVariants(input: String): DecryptionResult {
        val data = try {
            Base64.getDecoder().decode(input)
        } catch (_: Exception) {
            input.toByteArray()
        }
        
        if (data.size < 16) return DecryptionResult(false, null, "aes", 0.0)
        
        for (key in commonKeys) {
            for (iv in commonIVs) {
                for (mode in listOf("AES/CBC/PKCS5Padding", "AES/ECB/PKCS5Padding")) {
                    try {
                        val cipher = Cipher.getInstance(mode)
                        val keySpec = SecretKeySpec(key.padEnd(16, '0').take(16).toByteArray(), "AES")
                        
                        if (mode.contains("CBC")) {
                            val ivSpec = IvParameterSpec(iv.padEnd(16, '0').take(16).toByteArray())
                            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                        } else {
                            cipher.init(Cipher.DECRYPT_MODE, keySpec)
                        }
                        
                        val decrypted = cipher.doFinal(data)
                        val output = String(decrypted, StandardCharsets.UTF_8)
                        val score = validateOutput(output)
                        
                        if (score > 0.7) {
                            return DecryptionResult(true, output, "aes_${mode}_$key", score)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        
        return DecryptionResult(false, null, "aes", 0.0)
    }
    
    private fun tryXORVariants(input: String): DecryptionResult {
        val data = input.toByteArray()
        
        for (key in 0..255) {
            val decrypted = data.map { (it.toInt() xor key).toByte() }.toByteArray()
            try {
                val output = String(decrypted, StandardCharsets.UTF_8)
                val score = validateOutput(output)
                if (score > 0.8) {
                    return DecryptionResult(true, output, "xor_$key", score)
                }
            } catch (_: Exception) {}
        }
        
        return DecryptionResult(false, null, "xor", 0.0)
    }
    
    private fun validateOutput(output: String): Double {
        var score = 0.0
        
        if (output.contains("http")) score += 0.3
        if (output.contains(".m3u8") || output.contains(".mp4")) score += 0.4
        
        if ((output.startsWith("{") && output.endsWith("}")) ||
            (output.startsWith("[") && output.endsWith("]"))) {
            score += 0.2
        }
        
        val printableRatio = output.count { it.isLetterOrDigit() || it in ":/.?&=-" } / output.length.toDouble()
        score += printableRatio * 0.3
        
        val entropy = calculateEntropy(output)
        if (entropy > 7.5) score -= 0.3
        
        return score.coerceIn(0.0, 1.0)
    }
}

// ============================================================================
// MODULE 7: STREAM AUTHENTICITY VALIDATOR
// ============================================================================

class StreamAuthenticityValidator {
    
    data class ValidationResult(
        val isValid: Boolean,
        val type: StreamType,
        val quality: String?,
        val headers: Map<String, String>
    )
    
    enum class StreamType { M3U8, MP4, MKV, WEBM, DASH, UNKNOWN }
    
    suspend fun validate(
        candidate: StreamCandidate,
        networkEngine: NetworkReconstructionEngine,
        context: ResolutionContext
    ): ValidationResult = withContext(Dispatchers.IO) {
        
        context.forensicLog.add(ForensicTrace(
            phase = "VALIDATE",
            action = "START_VALIDATION",
            data = mapOf("url" to candidate.url)
        ))
        
        try {
            val headSnapshot = networkEngine.executeWithTracing(
                candidate.url,
                method = "HEAD",
                context = context
            )
            
            val contentType = headSnapshot.responseHeaders["Content-Type"]?.firstOrNull() ?: ""
            
            val type = when {
                contentType.contains("mpegurl") || contentType.contains("m3u8") -> StreamType.M3U8
                contentType.contains("mp4") -> StreamType.MP4
                contentType.contains("webm") -> StreamType.WEBM
                contentType.contains("dash") -> StreamType.DASH
                candidate.url.endsWith(".m3u8", ignoreCase = true) -> StreamType.M3U8
                candidate.url.endsWith(".mp4", ignoreCase = true) -> StreamType.MP4
                else -> StreamType.UNKNOWN
            }
            
            if (type == StreamType.UNKNOWN) {
                val getSnapshot = networkEngine.executeWithTracing(
                    candidate.url,
                    headers = mapOf("Range" to "bytes=0-1024"),
                    context = context
                )
                
                val body = getSnapshot.responseBody ?: ""
                val detectedType = detectByContent(body)
                
                return@withContext ValidationResult(
                    isValid = detectedType != StreamType.UNKNOWN,
                    type = detectedType,
                    quality = inferQuality(candidate.url),
                    headers = generateHeaders(candidate)
                )
            }
            
            ValidationResult(
                isValid = true,
                type = type,
                quality = inferQuality(candidate.url),
                headers = generateHeaders(candidate)
            )
            
        } catch (e: Exception) {
            context.forensicLog.add(ForensicTrace(
                phase = "VALIDATE",
                action = "VALIDATION_ERROR",
                data = mapOf("error" to e.message.toString())
            ))
            
            ValidationResult(false, StreamType.UNKNOWN, null, emptyMap())
        }
    }
    
    private fun detectByContent(content: String): StreamType {
        return when {
            content.contains("#EXTM3U") -> StreamType.M3U8
            content.startsWith("\u0000\u0000\u0000") && content.contains("ftyp") -> StreamType.MP4
            content.startsWith("\u001A\u0045\u00DF\u00A3") -> StreamType.MKV
            content.startsWith("<MPD") -> StreamType.DASH
            else -> StreamType.UNKNOWN
        }
    }
    
    private fun inferQuality(url: String): String? {
        val patterns = listOf(
            Regex("""(\d{3,4})p""") to { m: MatchResult -> m.groupValues[1] + "p" },
            Regex("""(\d{3,4})[xX](\d{3,4})""") to { m: MatchResult -> m.groupValues[2] + "p" },
            Regex("""[/_](720|1080|480|360|240)[/_]""") to { m: MatchResult -> m.groupValues[1] + "p" }
        )
        
        for ((pattern, extractor) in patterns) {
            pattern.find(url)?.let { return extractor(it) }
        }
        
        return null
    }
    
    private fun generateHeaders(candidate: StreamCandidate): Map<String, String> {
        return mapOf(
            "Referer" to candidate.source,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.0.36",
            "Accept" to "*/*",
            "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8",
            "Origin" to "https://boctem.com"
        )
    }
}

// ============================================================================
// MODULE 6: MULTI-DEPTH PROVIDER CRAWLER
// ============================================================================

class MultiDepthProviderCrawler(
    private val networkEngine: NetworkReconstructionEngine,
    private val miningEngine: UniversalDataMiningEngine,
    private val jsEngine: JSStaticReverseEngine,
    private val decryptEngine: EncryptionAutobreakSystem,
    private val analyzer: ResponseIntelligenceAnalyzer
) {
    
    suspend fun crawl(
        startUrl: String,
        context: ResolutionContext,
        mainUrl: String = "https://boctem.com"
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(startUrl to 0)
        
        val allCandidates = mutableListOf<StreamCandidate>()
        
        while (queue.isNotEmpty() && context.depth < context.maxDepth) {
            val (currentUrl, currentDepth) = queue.removeFirst()
            
            if (currentUrl in context.visitedUrls) continue
            if (currentDepth > context.maxDepth) continue
            
            context.visitedUrls.add(currentUrl)
            
            context.forensicLog.add(ForensicTrace(
                phase = "CRAWL",
                action = "VISIT_URL",
                data = mapOf("url" to currentUrl, "depth" to currentDepth)
            ))
            
            try {
                val snapshot = networkEngine.executeWithTracing(currentUrl, context = context)
                val content = snapshot.responseBody ?: continue
                
                val contentType = analyzer.analyze(content)
                
                val candidates = when (contentType) {
                    is ResponseIntelligenceAnalyzer.ContentType.HtmlDOM -> {
                        processHTML(content, currentUrl, context, mainUrl)
                    }
                    is ResponseIntelligenceAnalyzer.ContentType.ObfuscatedJS,
                    is ResponseIntelligenceAnalyzer.ContentType.PackedJS -> {
                        processJS(content, currentUrl, context)
                    }
                    is ResponseIntelligenceAnalyzer.ContentType.EncryptedPayload -> {
                        processEncrypted(content, currentUrl, context, mainUrl)
                    }
                    is ResponseIntelligenceAnalyzer.ContentType.BinaryPlaylist,
                    is ResponseIntelligenceAnalyzer.ContentType.StreamingManifest -> {
                        listOf(StreamCandidate(
                            url = currentUrl,
                            source = startUrl,
                            extractionMethod = "direct_manifest",
                            confidence = 0.95
                        ))
                    }
                    else -> miningEngine.deepScan(content, context, mainUrl)
                }
                
                allCandidates.addAll(candidates)
                
                candidates.filter { it.confidence < 0.9 }.forEach { candidate ->
                    if (candidate.url !in context.visitedUrls) {
                        queue.add(candidate.url to currentDepth + 1)
                    }
                }
                
            } catch (e: Exception) {
                context.forensicLog.add(ForensicTrace(
                    phase = "CRAWL",
                    action = "ERROR",
                    data = mapOf("url" to currentUrl, "error" to e.message.toString())
                ))
            }
        }
        
        allCandidates.distinctBy { it.url }
    }
    
    private fun processHTML(
        html: String,
        sourceUrl: String,
        context: ResolutionContext,
        mainUrl: String
    ): List<StreamCandidate> {
        val candidates = miningEngine.deepScan(html, context, mainUrl).toMutableList()
        
        val iframePattern = Regex("""iframe[^>]+src=["']([^"']+)["']""")
        iframePattern.findAll(html).forEach { match ->
            val iframeUrl = match.groupValues[1]
            context.forensicLog.add(ForensicTrace(
                phase = "CRAWL",
                action = "IFRAME_DETECTED",
                data = mapOf("iframeUrl" to iframeUrl)
            ))
        }
        
        return candidates
    }
    
    private fun processJS(
        js: String,
        sourceUrl: String,
        context: ResolutionContext
    ): List<StreamCandidate> {
        val extractedUrls = jsEngine.analyzeAndExtract(js, context)
        
        return extractedUrls.map { url ->
            StreamCandidate(
                url = url,
                source = sourceUrl,
                extractionMethod = "js_reverse",
                confidence = 0.7
            )
        }
    }
    
    private fun processEncrypted(
        content: String,
        sourceUrl: String,
        context: ResolutionContext,
        mainUrl: String
    ): List<StreamCandidate> {
        val result = decryptEngine.attemptDecryption(content, context)
        
        return if (result.success && result.output != null) {
            miningEngine.deepScan(result.output, context, mainUrl).map { 
                it.copy(extractionMethod = "decrypted_${result.method}") 
            }
        } else emptyList()
    }
}

// ============================================================================
// MODULE 8: ADAPTIVE DECISION ENGINE
// ============================================================================

class AdaptiveDecisionEngine(
    private val networkEngine: NetworkReconstructionEngine,
    private val miningEngine: UniversalDataMiningEngine,
    private val jsEngine: JSStaticReverseEngine,
    private val decryptEngine: EncryptionAutobreakSystem,
    private val crawler: MultiDepthProviderCrawler,
    private val validator: StreamAuthenticityValidator
) {
    
    sealed class Decision {
        data class Success(val links: List<ExtractorLink>) : Decision()
        data class Fallback(val reason: String, val nextStrategy: String) : Decision()
        data class Failure(val reason: String) : Decision()
    }
    
    suspend fun resolve(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String = "https://boctem.com"
    ): Boolean = withContext(Dispatchers.IO) {
        
        val strategies = listOf<(String, ResolutionContext, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit, String) -> Decision>(
            ::strategyDirectExtraction,
            ::strategyAjaxExtraction,
            ::strategyDeepCrawl,
            ::strategyBruteForceMining,
            ::strategyEncryptedPayload,
            ::strategyObfuscatedJS
        )
        
        for ((index, strategy) in strategies.withIndex()) {
            context.strategyHistory.add("Strategy_$index")
            
            val result = strategy(data, context, subtitleCallback, linkCallback, mainUrl)
            
            when (result) {
                is Decision.Success -> {
                    context.forensicLog.add(ForensicTrace(
                        phase = "DECISION",
                        action = "STRATEGY_SUCCESS",
                        data = mapOf("strategy" to index, "linksFound" to result.links.size)
                    ))
                    result.links.forEach(linkCallback)
                    return@withContext true
                }
                is Decision.Fallback -> {
                    context.forensicLog.add(ForensicTrace(
                        phase = "DECISION",
                        action = "STRATEGY_FALLBACK",
                        data = mapOf("strategy" to index, "reason" to result.reason)
                    ))
                    continue
                }
                is Decision.Failure -> {
                    context.forensicLog.add(ForensicTrace(
                        phase = "DECISION",
                        action = "STRATEGY_FAILED",
                        data = mapOf("strategy" to index, "reason" to result.reason)
                    ))
                }
            }
        }
        
        false
    }
    
    private suspend fun strategyDirectExtraction(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val snapshot = networkEngine.executeWithTracing(data, context = context)
        val content = snapshot.responseBody ?: return Decision.Fallback("No content", "ajax")
        
        if (content.contains("#EXTM3U") || data.contains(".m3u8")) {
            val links = M3u8Helper.generateM3u8(
                source = "BocTem",
                streamUrl = data,
                referer = data
            )
            return Decision.Success(links)
        }
        
        return Decision.Fallback("No direct stream", "ajax")
    }
    
    private suspend fun strategyAjaxExtraction(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val postId = extractPostId(data, context)
        val nonce = extractNonce(data, context)
        val episode = extractEpisode(data)
        
        if (postId == null || nonce == null) {
            return Decision.Fallback("Missing AJAX params", "deep_crawl")
        }
        
        val ajaxEndpoints = listOf(
            "$mainUrl/wp-admin/admin-ajax.php",
            "$mainUrl/ajax-player",
            "$mainUrl/api/player"
        )
        
        val payloads = listOf(
            mapOf("action" to "halim_ajax_player", "nonce" to nonce, "postid" to postId, "episode" to episode, "server" to "1"),
            mapOf("action" to "ajax_player", "nonce" to nonce, "postid" to postId, "episode" to episode, "server" to "1"),
            mapOf("action" to "player", "nonce" to nonce, "id" to postId, "ep" to episode)
        )
        
        for (endpoint in ajaxEndpoints) {
            for (payload in payloads) {
                for (server in listOf("1", "2", "3", "4", "5")) {
                    val finalPayload = payload + mapOf("server" to server)
                    
                    try {
                        val snapshot = networkEngine.executeWithTracing(
                            endpoint,
                            method = "POST",
                            body = finalPayload,
                            context = context
                        )
                        
                        val response = snapshot.responseBody ?: continue
                        
                        val candidates = miningEngine.deepScan(response, context, mainUrl)
                        val validLinks = validateAndConvert(candidates, context)
                        
                        if (validLinks.isNotEmpty()) {
                            return Decision.Success(validLinks)
                        }
                        
                        if (response.length > 100 && !response.contains("<")) {
                            val decryptResult = decryptEngine.attemptDecryption(response, context)
                            if (decryptResult.success && decryptResult.output != null) {
                                val decryptedCandidates = miningEngine.deepScan(decryptResult.output, context, mainUrl)
                                val decryptedLinks = validateAndConvert(decryptedCandidates, context)
                                if (decryptedLinks.isNotEmpty()) {
                                    return Decision.Success(decryptedLinks)
                                }
                            }
                        }
                        
                    } catch (_: Exception) {}
                }
            }
        }
        
        return Decision.Fallback("AJAX failed", "deep_crawl")
    }
    
    private suspend fun strategyDeepCrawl(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val candidates = crawler.crawl(data, context, mainUrl)
        val validLinks = validateAndConvert(candidates, context)
        
        return if (validLinks.isNotEmpty()) {
            Decision.Success(validLinks)
        } else {
            Decision.Fallback("Deep crawl found no valid streams", "brute_force")
        }
    }
    
    private suspend fun strategyBruteForceMining(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val snapshot = networkEngine.executeWithTracing(data, context = context)
        val content = snapshot.responseBody ?: return Decision.Failure("No content to mine")
        
        val expandedPatterns = generateExpandedPatterns(content)
        val allMatches = mutableListOf<String>()
        
        expandedPatterns.forEach { pattern ->
            pattern.findAll(content).forEach { 
                allMatches.add(it.value) 
            }
        }
        
        val candidates = allMatches.mapNotNull { match ->
            try {
                StreamCandidate(
                    url = normalizeUrl(match, mainUrl) ?: return@mapNotNull null,
                    source = data,
                    extractionMethod = "brute_force",
                    confidence = 0.5
                )
            } catch (_: Exception) { null }
        }
        
        val validLinks = validateAndConvert(candidates, context)
        return if (validLinks.isNotEmpty()) {
            Decision.Success(validLinks)
        } else {
            Decision.Fallback("Brute force failed", "encrypted")
        }
    }
    
    private suspend fun strategyEncryptedPayload(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val snapshot = networkEngine.executeWithTracing(data, context = context)
        val content = snapshot.responseBody ?: return Decision.Failure("No content")
        
        val encryptedBlocks = Regex("""[A-Za-z0-9+/=]{100,500}""").findAll(content)
            .map { it.value }
            .distinct()
        
        for (block in encryptedBlocks) {
            val result = decryptEngine.attemptDecryption(block, context)
            if (result.success && result.output != null) {
                val candidates = miningEngine.deepScan(result.output, context, mainUrl)
                val validLinks = validateAndConvert(candidates, context)
                if (validLinks.isNotEmpty()) {
                    return Decision.Success(validLinks)
                }
            }
        }
        
        return Decision.Fallback("No encrypted payload found", "obfuscated_js")
    }
    
    private suspend fun strategyObfuscatedJS(
        data: String,
        context: ResolutionContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        linkCallback: (ExtractorLink) -> Unit,
        mainUrl: String
    ): Decision {
        val snapshot = networkEngine.executeWithTracing(data, context = context)
        val content = snapshot.responseBody ?: return Decision.Failure("No content")
        
        val doc = Jsoup.parse(content)
        val scripts = doc.select("script").mapNotNull { 
            it.data().takeIf { it.isNotBlank() } ?: it.html().takeIf { it.isNotBlank() }
        }
        
        for (script in scripts) {
            val urls = jsEngine.analyzeAndExtract(script, context)
            val candidates = urls.map { 
                StreamCandidate(it, data, "js_static", 0.7) 
            }
            val validLinks = validateAndConvert(candidates, context)
            if (validLinks.isNotEmpty()) {
                return Decision.Success(validLinks)
            }
        }
        
        return Decision.Failure("All strategies exhausted")
    }
    
    private suspend fun validateAndConvert(
        candidates: List<StreamCandidate>,
        context: ResolutionContext
    ): List<ExtractorLink> {
        val validLinks = mutableListOf<ExtractorLink>()
        
        candidates.sortedByDescending { it.confidence }.take(10).forEach { candidate ->
            val validation = validator.validate(candidate, networkEngine, context)
            
            if (validation.isValid) {
                val link = when (validation.type) {
                    StreamAuthenticityValidator.StreamType.M3U8 -> {
                        M3u8Helper.generateM3u8(
                            source = "BocTem",
                            streamUrl = candidate.url,
                            referer = candidate.source,
                            headers = validation.headers
                        ).firstOrNull()
                    }
                    else -> {
                        newExtractorLink(
                            source = "BocTem",
                            name = "BocTem ${validation.quality ?: "Auto"}",
                            url = candidate.url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = candidate.source
                            this.quality = validation.quality?.removeSuffix("p")?.toIntOrNull() ?: Qualities.Unknown.value
                            this.headers = validation.headers
                        }
                    }
                }
                
                link?.let { validLinks.add(it) }
            }
        }
        
        return validLinks
    }
    
    private fun extractPostId(url: String, context: ResolutionContext): String? {
        val patterns = listOf(
            Regex("""postid-(\d+)"""),
            Regex("""post-(\d+)"""),
            Regex("""p=(\d+)"""),
            Regex("""/(\d+)/"""),
            Regex("""id=(\d+)""")
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        
        context.networkSnapshots.forEach { snapshot ->
            val body = snapshot.responseBody ?: return@forEach
            for (pattern in patterns) {
                pattern.find(body)?.groupValues?.get(1)?.let { return it }
            }
        }
        
        return null
    }
    
    private fun extractNonce(url: String, context: ResolutionContext): String? {
        val patterns = listOf(
            Regex("""nonce["']?\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""data-nonce=["']([^"']+)["']"""),
            Regex("""nonce=([^&\s]+)""")
        )
        
        context.networkSnapshots.forEach { snapshot ->
            val body = snapshot.responseBody ?: return@forEach
            for (pattern in patterns) {
                pattern.find(body)?.groupValues?.get(1)?.let { return it }
            }
        }
        
        return null
    }
    
    private fun extractEpisode(url: String): String {
        val patterns = listOf(
            Regex("""tap-(\d+)"""),
            Regex("""episode[=/-](\d+)"""),
            Regex("""ep[=/-](\d+)"""),
            Regex("""/(\d+)(?:\.|$)""")
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        
        return "1"
    }
    
    private fun generateExpandedPatterns(content: String): List<Regex> {
        return listOf(
            Regex("""(https?://[^\s\"'<>]{20,})"""),
            Regex("""(//[^\s\"'<>]{20,})"""),
            Regex("""[\"']([^\"']*(?:m3u8|mp4)[^\"']*)[\"']"""),
            Regex("""(eyJ[A-Za-z0-9_-]*\.eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*)"""),
            Regex("""(U2FsdGVkX1[A-Za-z0-9+/=]+)""")
        )
    }
}

// ============================================================================
// MAIN PROVIDER IMPLEMENTATION
// ============================================================================

class BocTemProvider : MainAPI() {
    override var mainUrl = "https://boctem.com"
    override var name = "BocTem"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    
    // Module instances - lazy init to avoid circular dependency issues
    private val networkEngine by lazy { NetworkReconstructionEngine(this) }
    private val analyzer by lazy { ResponseIntelligenceAnalyzer() }
    private val miningEngine by lazy { UniversalDataMiningEngine() }
    private val jsEngine by lazy { JSStaticReverseEngine() }
    private val decryptEngine by lazy { EncryptionAutobreakSystem() }
    private val validator by lazy { StreamAuthenticityValidator() }
    private val crawler by lazy { MultiDepthProviderCrawler(networkEngine, miningEngine, jsEngine, decryptEngine, analyzer) }
    private val decisionEngine by lazy { AdaptiveDecisionEngine(networkEngine, miningEngine, jsEngine, decryptEngine, crawler, validator) }
    
    val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )
    
    fun requestHeaders(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = defaultHeaders.toMutableMap()
        headers["Referer"] = referer ?: mainUrl
        headers.putAll(extra)
        return headers
    }
    
    fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
    
    override val mainPage = mainPageOf(
        "/" to "Anime Mới",
        "release/2026/page/" to "Anime 2026"
    )
    
    // ==================== EXISTING METHODS (PRESERVED) ====================
    
    private fun parseCard(article: Element): SearchResponse? {
        val anchor = article.selectFirst("a") ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null
        
        val title = article.selectFirst(".entry-title")?.text()?.trim().orEmpty()
            .ifEmpty { anchor.attr("title").trim() }
            .ifEmpty { return null }
        
        val image = article.selectFirst("img")
        val poster = normalizeUrl(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )
        
        val statusText = article.selectFirst(".status")?.text().orEmpty()
        val episodeNumber = Regex("""(\d+)""").find(statusText)?.groupValues?.get(1)?.toIntOrNull()
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(episodeNumber)
        }
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}$page"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document
        
        val items = document
            .select("article.thumb.grid-item")
            .mapNotNull { parseCard(it) }
        
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url, headers = requestHeaders(mainUrl)).document
        
        return document
            .select("article.thumb.grid-item")
            .mapNotNull { parseCard(it) }
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizeUrl(url) ?: return null
        val document = app.get(fixedUrl, headers = requestHeaders(fixedUrl)).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst(".halim-movie-title")?.text()?.trim()
            ?: return null
        
        val poster = normalizeUrl(
            document.selectFirst(".halim-movie-poster img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".halim-movie-poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        
        val description = document.selectFirst(".entry-content")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val seen = HashSet<String>()
        val episodes = document
            .select("a[href*=/xem-phim/]")
            .mapNotNull { link ->
                val epUrl = normalizeUrl(link.attr("href")) ?: return@mapNotNull null
                if (!epUrl.contains("-tap-")) return@mapNotNull null
                if (!seen.add(epUrl)) return@mapNotNull null
                
                val epName = link.text().trim().ifBlank { null }
                val epNum = Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        
        val tags = document.select(".halim-movie-genres a, .post-category a").map { it.text() }
        val year = Regex("""/release/(\d+)/""").find(fixedUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: document.selectFirst(".halim-movie-year")?.text()?.toIntOrNull()
        
        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
    
    // ==================== AUTONOMOUS STREAM RESOLUTION ====================
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = ResolutionContext(originalUrl = data)
        
        context.forensicLog.add(ForensicTrace(
            phase = "INIT",
            action = "START_RESOLUTION",
            data = mapOf("targetUrl" to data, "timestamp" to System.currentTimeMillis())
        ))
        
        return try {
            val success = decisionEngine.resolve(data, context, subtitleCallback, callback, mainUrl)
            
            if (!success) {
                println("=== FORENSIC TRACE ===")
                context.forensicLog.forEach { trace ->
                    println("[${trace.phase}] ${trace.action}: ${trace.data}")
                }
                println("======================")
            }
            
            success
        } catch (e: Exception) {
            context.forensicLog.add(ForensicTrace(
                phase = "FATAL",
                action = "EXCEPTION",
                data = mapOf("error" to e.stackTraceToString())
            ))
            false
        }
    }
}

@CloudstreamPlugin
class BocTem : Plugin() {
    override fun load() {
        registerMainAPI(BocTemProvider())
    }
}
