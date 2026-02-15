package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
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

// ============================================
// CRYPTO - Decrypt AES-256-CBC OpenSSL format
// ============================================
object AVS_Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    
    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        
        // Đã là URL hoặc M3U8
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s
        
        // Thử decode base64 trực tiếp
        try {
            val decoded = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http") || decoded.startsWith("#EXTM3U")) return decoded
        } catch (_: Exception) {}
        
        // Thử decrypt với các password
        val passwords = listOf(
            "dm_thang_suc_vat_get_link_an_dbt",
            "animevietsub",
            "animevsub"
        )
        
        for (pass in passwords) {
            try {
                val result = decryptOpenSSL(s, pass)
                if (result != null && (result.startsWith("http") || result.contains("#EXTM3U"))) {
                    println("AVS: Decrypt OK with '${pass.take(10)}...'")
                    return result
                }
            } catch (_: Exception) {}
        }
        return null
    }
    
    private fun decryptOpenSSL(b64: String, password: String): String? {
        val raw = Base64.decode(b64, Base64.DEFAULT)
        if (raw.size < 16) return null
        
        val (ct, salt) = if (raw.copyOfRange(0, 8).contentEquals(SALTED)) {
            raw.copyOfRange(16, raw.size) to raw.copyOfRange(8, 16)
        } else {
            raw to null
        }
        
        if (ct.isEmpty()) return null
        
        val (key, iv) = evpKDF(password.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ct)
        
        return decompress(plain) ?: String(plain, StandardCharsets.UTF_8).trim()
    }
    
    private fun evpKDF(pwd: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        
        while (out.size() < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(pwd)
            if (salt != null) md.update(salt)
            prev = md.digest()
            out.write(prev)
        }
        
        val b = out.toByteArray()
        return b.copyOfRange(0, keyLen) to b.copyOfRange(keyLen, keyLen + ivLen)
    }
    
    private fun decompress(data: ByteArray): String? {
        // GZIP
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                return String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {}
        
        // Zlib raw
        try {
            val inf = Inflater(true)
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inf.end()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
        
        return null
    }
    
    fun extractHexId(m3u8: String): String? {
        return Regex("""/chunks/([0-9a-f]{24})/""").find(m3u8)?.groupValues?.get(1)
    }
}

// ============================================
// MAIN PROVIDER
// ============================================
class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    
    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    
    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )
    
    private fun ajaxHeaders(ref: String) = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/javascript, */*",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to ref
    )

    private fun fix(url: String?): String? {
        if (url.isNullOrBlank() || url == "#") return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/anime-moi/" to "Anime Mới",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}trang-$page.html"
        val doc = app.get(fix(url)!!, interceptor = cf, headers = headers).document
        val items = doc.select("article, .TPostMv, .item, .list-film li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fix(a.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst(".Title, h3, .title, .name")?.text()?.trim() 
                ?: a.attr("title").trim().ifBlank { return@mapNotNull null }
            val poster = el.selectFirst("img")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
            newAnimeSearchResponse(title, href) { posterUrl = poster }
        }.distinctBy { it.url }
        return newHomePageResponse(req.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", 
            interceptor = cf, headers = headers).document
        return doc.select("article, .item, .list-film li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fix(a.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst(".Title, h3, .title")?.text()?.trim() 
                ?: a.attr("title").trim().ifBlank { return@mapNotNull null }
            newAnimeSearchResponse(title, href)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf, headers = headers).document
        
        // Extract filmId từ URL: /phim/name-a5868/ → 5868
        val filmId = Regex("""[/-]a(\d+)""").find(url)?.groupValues?.get(1) ?: ""
        println("AVS: filmId from URL = $filmId")
        
        // Lấy episodes
        val episodes = doc.select("ul.list-episode li a, .list-eps a, .episode-link").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").ifBlank {
                Regex("""[/-](\d+)\.html""").find(href)?.groupValues?.get(1)
            } ?: ""
            val name = ep.text().trim().ifBlank { "Tập ${ep.attr("data-id")}" }
            newEpisode("$href|||$filmId|||$epId") {
                this.name = name
                episode = Regex("""\d+""").find(name)?.value?.toIntOrNull()
            }
        }
        
        val title = doc.selectFirst("h1.Title, h1")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst("img[itemprop=image], .Image img")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val plot = doc.selectFirst(".Description, #film-content")?.text()?.trim()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes.ifEmpty {
                listOf(newEpisode(url) { name = "Xem Phim" })
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("AVS: ========== loadLinks START ==========")
        println("AVS: data = $data")
        
        val parts = data.split("|||")
        val epUrl = parts.getOrNull(0) ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val epId = parts.getOrNull(2) ?: ""
        
        println("AVS: epUrl=$epUrl, filmId=$filmId, epId=$epId")
        
        // Bước 1: GET trang episode để lấy real IDs và cookies
        val pageRes = app.get(epUrl, interceptor = cf, headers = headers)
        val html = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()
        
        // Extract real IDs từ JavaScript trong HTML
        val realEpId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\(['"]?(\d+)""").find(html)?.groupValues?.get(1) ?: epId
        val realFilmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"]?(\d+)""").find(html)?.groupValues?.get(1) ?: filmId
        
        println("AVS: realEpId=$realEpId, realFilmId=$realFilmId")
        
        if (realEpId.isBlank()) {
            println("AVS: ERROR - No episodeId found")
            return false
        }
        
        val ajaxH = ajaxHeaders(epUrl)
        
        // Bước 2: POST để lấy danh sách server
        println("AVS: Step 1 - Getting server list...")
        val step1 = app.post("$mainUrl/ajax/player",
            data = mapOf("episodeId" to realEpId, "backup" to "1"),
            headers = ajaxH,
            cookies = cookies
        )
        cookies.putAll(step1.cookies)
        
        val step1Text = step1.text ?: return false
        println("AVS: Step1 response (${step1Text.length} chars): ${step1Text.take(300)}")
        
        // Parse JSON
        val step1Json = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(step1Text, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            println("AVS: Step1 parse error: ${e.message}")
            return false
        }
        
        val htmlButtons = step1Json["html"]?.toString() ?: run {
            println("AVS: No 'html' in response")
            return false
        }
        
        println("AVS: HTML buttons: ${htmlButtons.take(500)}")
        
        // Parse buttons
        val buttons = Jsoup.parse(htmlButtons).select("a[data-href], a.btn3dsv")
        println("AVS: Found ${buttons.size} buttons")
        
        for (btn in buttons) {
            val hash = btn.attr("data-href").trim()
            val serverName = btn.text().trim()
            
            if (hash.isBlank() || hash == "#") {
                println("AVS: Skip button - empty hash")
                continue
            }
            
            println("AVS: Processing server '$serverName' hash=${hash.take(40)}...")
            
            // Bước 3: POST để lấy encrypted link
            // QUAN TRỌNG: Dùng realFilmId (5868), KHÔNG phải realEpId (111803)
            val step2 = app.post("$mainUrl/ajax/player",
                data = mapOf("link" to hash, "id" to realFilmId),
                headers = ajaxH,
                cookies = cookies
            )
            cookies.putAll(step2.cookies)
            
            val step2Text = step2.text ?: continue
            println("AVS: Step2 response: ${step2Text.take(300)}")
            
            // Parse JSON
            val step2Json = try {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(step2Text, Map::class.java) as Map<String, Any?>
            } catch (e: Exception) {
                println("AVS: Step2 parse error: ${e.message}")
                continue
            }
            
            // Lấy encrypted file
            val linkData = step2Json["link"]
            val encFile = when (linkData) {
                is String -> linkData
                is List<*> -> (linkData.firstOrNull() as? Map<*, *>)?.get("file")?.toString()
                is Map<*, *> -> linkData["file"]?.toString()
                else -> null
            }
            
            if (encFile.isNullOrBlank()) {
                println("AVS: No encrypted file found in response")
                continue
            }
            
            println("AVS: Encrypted file length = ${encFile.length}")
            
            // Decrypt
            val decrypted = AVS_Crypto.decrypt(encFile)
            if (decrypted.isNullOrBlank()) {
                println("AVS: Decrypt FAILED")
                continue
            }
            
            println("AVS: Decrypted preview: ${decrypted.take(200)}")
            
            // Xử lý kết quả
            when {
                decrypted.startsWith("http") -> {
                    println("AVS: Direct URL found: $decrypted")
                    callback(newExtractorLink(name, "$name - $serverName", decrypted) {
                        this.referer = "$mainUrl/"
                    })
                    return true
                }
                decrypted.contains("#EXTM3U") -> {
                    println("AVS: M3U8 content found")
                    val hexId = AVS_Crypto.extractHexId(decrypted)
                    if (hexId != null) {
                        val m3u8Url = "https://storage.googleapiscdn.com/chunks/$hexId/original/index.m3u8"
                        println("AVS: Emitting M3U8: $m3u8Url")
                        callback(newExtractorLink(name, "$name - $serverName", m3u8Url) {
                            this.referer = "$mainUrl/"
                        })
                        return true
                    } else {
                        println("AVS: Could not extract hexId from M3U8")
                    }
                }
                else -> {
                    println("AVS: Unknown decrypted format")
                }
            }
        }
        
        println("AVS: ========== loadLinks END (no links) ==========")
        return false
    }
}
