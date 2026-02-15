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

object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    // Cập nhật danh sách Key mới nhất 2025 từ hệ thống
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "VSub@2025", "VSub@2024", "animevietsub", 
        "streaming_key", "player_key", "api_key"
    )

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        // Nếu là URL hoặc nội dung M3U8 thô thì trả về luôn
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s
        
        for (pass in PASSES) {
            val r = openSSL(s, pass) ?: continue
            if (r.startsWith("http") || r.contains("#EXTM3U") || r.contains("googleapiscdn.com")) {
                return r
            }
        }
        return null
    }

    private fun openSSL(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null
        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        val salt    = if (hasSalt) raw.copyOfRange(8, 16) else null
        val ct      = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
        val (key, iv) = evpKDF(pass.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return aesCbc(ct, key, iv)
    }

    private fun evpKDF(pwd: ByteArray, salt: ByteArray?, kLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream(); var prev = ByteArray(0)
        while (out.size() < kLen + ivLen) {
            md.reset(); md.update(prev); md.update(pwd)
            if (salt != null) md.update(salt)
            prev = md.digest(); out.write(prev)
        }
        val b = out.toByteArray()
        return b.copyOfRange(0, kLen) to b.copyOfRange(kLen, kLen + ivLen)
    }

    private fun aesCbc(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = c.doFinal(ct)
            decompress(plain) ?: String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun decompress(d: ByteArray): String? {
        try { GZIPInputStream(ByteArrayInputStream(d)).use { g ->
            val o = ByteArrayOutputStream(); g.copyTo(o)
            return String(o.toByteArray(), StandardCharsets.UTF_8).trim()
        }} catch (_: Exception) {}
        try {
            val inf = Inflater(true); inf.setInput(d)
            val o = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; o.write(buf, 0, n) }
            inf.end(); return String(o.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {}
        return null
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl  = "https://animevietsub.ee"
    override var name     = "AnimeVietSub"
    override var lang     = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()

    private val pageH = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf, headers = pageH).document
        
        // Fix Regex lấy Film ID chính xác từ meta hoặc script
        val filmId = Regex("""filmID\s*=\s*parseInt\('(\d+)'\)""").find(doc.html())?.groupValues?.get(1) ?: ""
        
        val episodes = doc.select("ul.list-episode li a").mapNotNull { ep ->
            val href = ep.attr("href")
            // Lấy Episode ID từ phần cuối của URL (ví dụ: 111803)
            val epId = Regex("""tap-\d+-(\d+)""").find(href)?.groupValues?.get(1) 
                      ?: Regex("""-(\d+)\.html""").find(href)?.groupValues?.get(1) ?: ""
            
            newEpisode("$href@@$filmId@@$epId") {
                name = ep.text().trim()
            }
        }
        return newAnimeLoadResponse(doc.selectFirst("h1.Title")?.text() ?: "", url, TvType.Anime) {
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts[1]
        val epId = parts[2]

        val aH = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to epUrl,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )

        // Bước 1: Lấy danh sách Server
        val r1 = app.post("$mainUrl/ajax/player", 
            data = mapOf("episodeId" to epId, "backup" to "1"), 
            headers = aH, interceptor = cf)
        
        val htmlServer = mapper.readTree(r1.text).get("html")?.asText() ?: return false
        val buttons = Jsoup.parse(htmlServer).select("a[data-href]")

        var found = false
        for (btn in buttons) {
            val hash = btn.attr("data-href")
            // Bước 2: Gửi Hash + FilmID (Theo đúng log @số 2)
            val r2 = app.post("$mainUrl/ajax/player",
                data = mapOf("link" to hash, "id" to filmId),
                headers = aH, interceptor = cf)
            
            val resJson = mapper.readTree(r2.text)
            val encryptedFile = resJson.get("link")?.get(0)?.get("file")?.asText() ?: continue
            
            // Bước 3: Giải mã nội dung M3U8
            val decrypted = Crypto.decrypt(encryptedFile) ?: continue
            
            if (decrypted.contains("#EXTM3U")) {
                // Nếu trả về nội dung M3U8, ta tạo một link ảo hoặc trích xuất segment
                // Ở đây AVS dùng Google CDN, ta có thể trích xuất URL gốc từ nội dung
                val cdnUrl = Regex("""https://storage.googleapiscdn.com/[^\s]+""").find(decrypted)?.value
                if (cdnUrl != null) {
                    callback(newExtractorLink(name, name, cdnUrl, epUrl, Qualities.Unknown.value, true))
                    found = true
                }
            } else if (decrypted.startsWith("http")) {
                callback(newExtractorLink(name, name, decrypted, epUrl, Qualities.Unknown.value, decrypted.contains(".m3u8")))
                found = true
            }
        }
        return found
    }
}
