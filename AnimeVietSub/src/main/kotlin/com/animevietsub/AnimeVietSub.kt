package com.animevietsub

import android.util.Base64
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
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

private const val PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"

private val AES_KEY: ByteArray by lazy {
    Base64.decode("ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ=", Base64.DEFAULT)
}

private val SALTED_PREFIX = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)

object Crypto {
    private val mapper = jacksonObjectMapper()

    fun decryptVideoData(encryptedBase64: String): String? {
        if (encryptedBase64.isBlank()) return null
        if (encryptedBase64.startsWith("http")) return encryptedBase64
        if (encryptedBase64.startsWith("#EXTM3U")) return encryptedBase64
        
        return try {
            var b64 = encryptedBase64.trim()
                .replace("-", "+")
                .replace("_", "/")
            while (b64.length % 4 != 0) b64 += "="
            
            val rawData = Base64.decode(b64, Base64.DEFAULT)
            println("AVS-DECRYPT: decoded len=${rawData.size}")
            if (rawData.size < 17) return null

            val isSalted = rawData.copyOfRange(0, 8).contentEquals(SALTED_PREFIX)
            println("AVS-DECRYPT: isSalted=$isSalted")
            
            val decrypted = if (isSalted) {
                val salt = rawData.copyOfRange(8, 16)
                val ciphertext = rawData.copyOfRange(16, rawData.size)
                val (key, iv) = evpKDF(PASSWORD.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
                aesDecrypt(ciphertext, key, iv)
            } else {
                val iv = rawData.copyOfRange(0, 16)
                val ciphertext = rawData.copyOfRange(16, rawData.size)
                aesDecrypt(ciphertext, AES_KEY, iv)
            }
            
            if (decrypted == null) {
                println("AVS-DECRYPT: AES failed")
                return null
            }
            
            println("AVS-DECRYPT: AES success, len=${decrypted.size}")
            val jsonStr = decompress(decrypted) ?: return null
            parseVideoUrl(jsonStr)
        } catch (e: Exception) {
            println("AVS-DECRYPT error: ${e.message}")
            null
        }
    }

    private fun aesDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) { null }
    }

    private fun evpKDF(password: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val output = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (output.size() < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(password)
            if (salt != null) md.update(salt)
            prev = md.digest()
            output.write(prev)
        }
        val result = output.toByteArray()
        return result.copyOfRange(0, keyLen) to result.copyOfRange(keyLen, keyLen + ivLen)
    }

    private fun decompress(data: ByteArray): String? {
        println("AVS-DECOMPRESS: size=${data.size}, hex=${data.take(8).joinToString("") { "%02x".format(it) }}")
        
        // Raw deflate (pako)
        try {
            val inf = Inflater(true)
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            inf.end()
            val result = out.toString("UTF-8")
            if (result.contains("{") || result.contains("http")) {
                println("AVS-DECOMPRESS: raw deflate OK")
                return result
            }
        } catch (_: Exception) {}

        // GZIP
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                val result = out.toString("UTF-8")
                if (result.contains("{") || result.contains("http")) return result
            }
        } catch (_: Exception) {}

        // Zlib
        try {
            val inf = Inflater(false)
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
            inf.end()
            val result = out.toString("UTF-8")
            if (result.contains("{") || result.contains("http")) return result
        } catch (_: Exception) {}

        // Plain
        try {
            val result = String(data, StandardCharsets.UTF_8)
            if (result.contains("{") || result.contains("http")) return result
        } catch (_: Exception) {}

        return null
    }

    private fun parseVideoUrl(jsonStr: String): String? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(jsonStr, Map::class.java) as Map<String, Any?>
            
            val sources = json["sources"] as? List<*>
            if (sources != null && sources.isNotEmpty()) {
                val src = sources[0] as? Map<String, Any?>
                val file = src?.get("file")?.toString()
                if (!file.isNullOrBlank()) return file
            }
            
            val links = json["link"] as? List<*>
            if (links != null && links.isNotEmpty()) {
                val link = links[0] as? Map<String, Any?>
                val file = link?.get("file")?.toString()
                if (!file.isNullOrBlank()) return file
            }
            
            json["file"]?.toString()
        } catch (_: Exception) { null }
    }

    fun isStreamUrl(s: String?): Boolean {
        if (s == null || !s.startsWith("http")) return false
        return s.contains("googleapiscdn.com") || s.contains(".m3u8") || 
               s.contains(".mp4") || s.contains("/hls/")
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

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private const val CDN = "storage.googleapiscdn.com"

    private const val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val pageH = mapOf(
        "User-Agent" to ua, "Accept" to "text/html,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )
    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref, "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )
    private val cdnH = mapOf("User-Agent" to ua)

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u == "#" || u.startsWith("javascript")) return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> "$mainUrl/$u"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.item,.list-film li").mapNotNull { it.toSR() }
        return newHomePageResponse(req.name, items)
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = selectFirst(".Title,h3,h2")?.text()?.trim() ?: a.attr("title").trim()
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> =
        app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/", interceptor = cf, headers = pageH).document
            .select("article,.TPostMv,.item").mapNotNull { it.toSR() }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        val doc = app.get(fUrl, interceptor = cf, headers = pageH).document
        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        
        val episodes = doc.select("ul.list-episode li a,.list-eps a,.episodes a").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").ifBlank { ep.attr("data-episodeid") }
            val name = ep.text().trim().ifBlank { ep.attr("title") }
            newEpisode("$href@@$filmId@@$epId") {
                this.name = name
                episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            }
        }
        
        val title = doc.selectFirst("h1.Title,h1")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,img[itemprop=image]")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }
        
        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts.getOrNull(0)?.takeIf { it.startsWith("http") } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val savedEpId = parts.getOrNull(2) ?: ""

        val pageRes = app.get(epUrl, interceptor = cf, headers = pageH)
        val body = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()

        // Collect episode IDs
        val ids = mutableSetOf<String>()
        if (savedEpId.isNotBlank()) ids.add(savedEpId)
        Jsoup.parse(body).select("[data-id]").forEach {
            it.attr("data-id").filter { c -> c.isDigit() }.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        Regex("""episodeId\s*=\s*["']?(\d+)""").findAll(body).forEach { ids.add(it.groupValues[1]) }

        return ajaxFlow(epUrl, filmId, ids.toList(), cookies, callback) ||
               scrapeFallback(body, epUrl, subtitleCallback, callback)
    }

    private suspend fun ajaxFlow(
        epUrl: String, filmId: String, ids: List<String>,
        cookies: MutableMap<String, String>, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val aH = ajaxH(epUrl)
        
        for (epId in ids.take(5)) {
            try {
                // Step 1: Get server buttons
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH, cookies = cookies, interceptor = cf)
                cookies.putAll(r1.cookies)
                val body1 = r1.text ?: continue

                val htmlStr = try {
                    @Suppress("UNCHECKED_CAST")
                    (mapper.readValue(body1, Map::class.java) as Map<String, Any?>)["html"]?.toString()
                } catch (_: Exception) { body1 } ?: continue

                val buttons = Jsoup.parse(htmlStr).select("a[data-href],a.btn3dsv")
                
                for (btn in buttons) {
                    val hash = btn.attr("data-href").trim()
                    if (hash.isBlank() || hash == "#") continue
                    
                    if (hash.startsWith("http") && emitStream(hash, epUrl, cb)) return true

                    // Step 2: Get encrypted data
                    val params = if (filmId.isNotBlank()) 
                        mapOf("link" to hash, "id" to filmId)
                    else 
                        mapOf("link" to hash, "id" to epId)
                    
                    try {
                        val r2 = app.post("$mainUrl/ajax/player",
                            data = params, headers = aH, cookies = cookies, interceptor = cf)
                        val body2 = r2.text ?: continue
                        
                        @Suppress("UNCHECKED_CAST")
                        val json = try {
                            mapper.readValue(body2, Map::class.java) as Map<String, Any?>
                        } catch (_: Exception) { null } ?: continue
                        
                        val links = json["link"] as? List<*> ?: continue
                        for (item in links.filterIsInstance<Map<String, Any?>>()) {
                            val encrypted = item["file"]?.toString() ?: continue
                            println("AVS: encrypted len=${encrypted.length}")
                            
                            val decrypted = Crypto.decryptVideoData(encrypted)
                            println("AVS: decrypted=${decrypted?.take(80)}")
                            
                            if (decrypted != null && decrypted.startsWith("http")) {
                                if (emitStream(decrypted, epUrl, cb)) return true
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun scrapeFallback(
        body: String, epUrl: String,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.parse(body)
        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fix(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            try { loadExtractor(src, epUrl, sub, cb); return true } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun emitStream(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false
        println("AVS-EMIT: $url")
        
        if (url.contains(CDN)) {
            val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
            val m3u8 = if (hexId != null) "https://$CDN/chunks/$hexId/original/index.m3u8"
                       else if (url.contains(".m3u8")) url else return false
            cb(newExtractorLink(name, name, m3u8) {
                referer = ref
                type = ExtractorLinkType.M3U8
                headers = cdnH
            })
            return true
        }
        
        if (url.contains(".m3u8") || url.contains("/hls/")) {
            cb(newExtractorLink(name, name, url) {
                referer = ref
                type = ExtractorLinkType.M3U8
                headers = cdnH
            })
            return true
        }
        
        if (url.contains(".mp4")) {
            cb(newExtractorLink(name, name, url) {
                referer = ref
                type = ExtractorLinkType.VIDEO
                headers = cdnH
            })
            return true
        }
        
        return false
    }
}
