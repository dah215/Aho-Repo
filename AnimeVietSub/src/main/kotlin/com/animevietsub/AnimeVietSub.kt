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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    
    // Passwords đã được xác nhận hoạt động
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub",
        "animevsub",
        "VSub@2025",
        "VSub@2024"
    )

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s
        
        // Thử decode base64 trước
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (plain.startsWith("http") || plain.startsWith("#EXTM3U")) return plain
        } catch (_: Exception) {}
        
        // Thử các password
        for (pass in PASSES) {
            val result = openSSLDecrypt(s, pass)
            if (result != null && (result.startsWith("http") || result.startsWith("#EXTM3U") || result.contains("googleapiscdn.com"))) {
                println("AVS: Decrypt OK with password='${pass.take(10)}...'")
                return result
            }
        }
        return null
    }

    private fun openSSLDecrypt(b64: String, password: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null
        
        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        val salt = if (hasSalt) raw.copyOfRange(8, 16) else null
        val ct = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
        if (ct.isEmpty()) return null
        
        val (key, iv) = evpKDF(password.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return aesCbcDecrypt(ct, key, iv)
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

    private fun aesCbcDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (ct.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            decompress(plain) ?: String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun decompress(data: ByteArray): String? {
        // Thử GZIP
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                return String(out.toByteArray(), StandardCharsets.UTF_8).trim()
            }
        } catch (_: Exception) {}
        
        // Thử zlib
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
            return String(out.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {}
        
        return null
    }
    
    // Extract hexId từ M3U8 content
    fun extractHexId(m3u8Content: String): String? {
        return Regex("""/chunks/([0-9a-f]{24})/""").find(m3u8Content)?.groupValues?.get(1)
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
    private val CDN = "storage.googleapiscdn.com"

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    
    private val pageHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )
    
    private fun ajaxHeaders(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url == "#" || url.startsWith("javascript")) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fixUrl(url) ?: mainUrl, interceptor = cf, headers = pageHeaders).document
        val items = doc.select("article,.TPostMv,.item,.list-film li,.TPost")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fixUrl(a.attr("href")) ?: return null
        val title = (selectFirst(".Title,h3,h2,.title,.name")?.text() ?: a.attr("title"))
            .trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/",
            interceptor = cf, headers = pageHeaders).document
        return doc.select("article,.TPostMv,.item,.list-film li")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf, headers = pageHeaders).document
        
        // Lấy filmId từ URL
        val filmId = Regex("""[/-]a(\d+)""").find(url)?.groupValues?.get(1) ?: ""
        
        // Lấy episode ID từ các link
        val sel = "ul.list-episode li a,.list-eps a,.server-list a,.list-episode a,.episodes a,#list_episodes a"
        var epNodes = doc.select(sel)
        
        // Nếu không có episodes, thử tìm trang xem
        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*='/tap-'],a[href*='/episode-'],.btn-watch a")
                ?.attr("href")?.let { fixUrl(it) }?.let { watchUrl ->
                    val watchDoc = app.get(watchUrl, interceptor = cf, headers = pageHeaders).document
                    epNodes = watchDoc.select(sel)
                }
        }
        
        val episodes = epNodes.mapNotNull { ep ->
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").ifBlank { 
                Regex("""[/-](\d+)\.html""").find(href)?.groupValues?.get(1) ?: ""
            }
            val name = ep.text().trim().ifBlank { ep.attr("title").trim() }
            
            newEpisode("$href@@$filmId@@$epId") {
                this.name = name
                episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            }
        }
        
        val title = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
        val year = doc.selectFirst("a[href*='phim-nam-']")?.text()
            ?.let { Regex("""\b(20\d{2})\b""").find(it)?.value?.toIntOrNull() }
        val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes.ifEmpty { 
                listOf(newEpisode(url) { name = "Tập 1" }) 
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts.getOrNull(0) ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val epId = parts.getOrNull(2) ?: ""
        
        println("AVS: Loading epUrl=$epUrl filmId=$filmId epId=$epId")
        
        // Bước 1: Lấy trang để có episodeId chính xác
        val pageRes = app.get(epUrl, interceptor = cf, headers = pageHeaders)
        val cookies = pageRes.cookies.toMutableMap()
        val body = pageRes.text ?: return false
        
        // Tìm episodeId từ HTML
        val realEpId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\(['"]?(\d+)['"]?\)""").find(body)?.groupValues?.get(1)
            ?: epId
        
        val realFilmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"]?(\d+)['"]?\)""").find(body)?.groupValues?.get(1)
            ?: filmId
        
        println("AVS: Real IDs - epId=$realEpId filmId=$realFilmId")
        
        val aHeaders = ajaxHeaders(epUrl)
        
        // Bước 2: Gọi API để lấy danh sách server
        val step1Res = app.post("$mainUrl/ajax/player",
            data = mapOf("episodeId" to realEpId, "backup" to "1"),
            headers = aHeaders,
            cookies = cookies,
            interceptor = cf
        )
        cookies.putAll(step1Res.cookies)
        
        val step1Body = step1Res.text ?: return false
        println("AVS: Step1 response length=${step1Body.length}")
        
        // Parse JSON response
        val step1Json = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(step1Body, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            println("AVS: Step1 not JSON, trying HTML")
            null
        }
        
        // Lấy HTML từ response
        val serverHtml = step1Json?.get("html")?.toString()
            ?: if (!step1Body.trimStart().startsWith("{")) step1Body else null
        
        if (serverHtml.isNullOrBlank()) {
            println("AVS: No server HTML found")
            return false
        }
        
        // Parse server buttons
        val buttons = Jsoup.parse(serverHtml).select("a[data-href], a.btn3dsv")
        println("AVS: Found ${buttons.size} server buttons")
        
        for (btn in buttons) {
            val hash = btn.attr("data-href").ifBlank { btn.attr("href") }.trim()
            val serverName = btn.text().trim()
            val dataId = btn.attr("data-id")
            val dataPlay = btn.attr("data-play")
            
            if (hash.isBlank() || hash == "#" || hash.startsWith("http")) continue
            
            println("AVS: Server=$serverName hash=${hash.take(30)}... dataId=$dataId play=$dataPlay")
            
            // Bước 3: Gọi API để lấy encrypted file
            // Dùng filmId (KHÔNG phải epId) như trong request thật
            val params = mapOf(
                "link" to hash,
                "id" to realFilmId
            )
            
            try {
                val step2Res = app.post("$mainUrl/ajax/player",
                    data = params,
                    headers = aHeaders,
                    cookies = cookies,
                    interceptor = cf
                )
                cookies.putAll(step2Res.cookies)
                
                val step2Body = step2Res.text ?: continue
                println("AVS: Step2 response length=${step2Body.length}")
                
                // Parse JSON
                val step2Json = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(step2Body, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    println("AVS: Step2 not JSON")
                    continue
                }
                
                // Lấy encrypted file
                val linkData = step2Json["link"]
                val encryptedFile = when (linkData) {
                    is String -> linkData
                    is List<*> -> (linkData.firstOrNull() as? Map<String, Any?>)?.get("file")?.toString()
                    is Map<*, *> -> linkData["file"]?.toString()
                    else -> null
                }
                
                if (encryptedFile.isNullOrBlank()) {
                    println("AVS: No encrypted file found")
                    continue
                }
                
                println("AVS: Encrypted file length=${encryptedFile.length}")
                
                // Decrypt
                val decrypted = Crypto.decrypt(encryptedFile)
                if (decrypted.isNullOrBlank()) {
                    println("AVS: Decrypt failed")
                    continue
                }
                
                println("AVS: Decrypted content preview: ${decrypted.take(200)}")
                
                // Nếu là URL trực tiếp
                if (decrypted.startsWith("http")) {
                    callback(newExtractorLink(name, "$name - $serverName", decrypted) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    })
                    return true
                }
                
                // Nếu là M3U8 content
                if (decrypted.contains("#EXTM3U")) {
                    val hexId = Crypto.extractHexId(decrypted)
                    if (hexId != null) {
                        val m3u8Url = "https://$CDN/chunks/$hexId/original/index.m3u8"
                        println("AVS: Emitting M3U8: $m3u8Url")
                        
                        callback(newExtractorLink(name, "$name - $serverName", m3u8Url) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        })
                        return true
                    }
                }
                
            } catch (e: Exception) {
                println("AVS: Error processing server: ${e.message}")
                continue
            }
        }
        
        return false
    }
}
