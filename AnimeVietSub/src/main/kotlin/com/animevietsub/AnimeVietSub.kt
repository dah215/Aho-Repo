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

object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    // Cập nhật danh sách PASSES mạnh nhất dựa trên phân tích log
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub", 
        "VSub@2025", "VSub@2024",
        "streaming_key", "player_key"
    )

    fun decryptToUrl(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim().replace("_", "/").replace("-", "+") // Fix base64 safe url
        if (s.startsWith("http")) return s
        
        for (pass in PASSES) {
            val r = openSSL(s, pass) ?: continue
            if (isStreamUrl(r)) return r
        }
        return null
    }

    private fun openSSL(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null
        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        val salt = if (hasSalt) raw.copyOfRange(8, 16) else null
        val ct   = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
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
            String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    fun isStreamUrl(s: String?): Boolean {
        if (s == null) return false
        return s.contains("googleapiscdn.com") || s.contains(".m3u8") || s.contains(".mp4")
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl  = "https://animevietsub.ee"
    override var name     = "AnimeVietSub"
    override var lang     = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts.getOrNull(1) ?: ""
        
        val pageRes = app.get(epUrl, interceptor = cf)
        val body = pageRes.text ?: return false
        
        // Biện pháp mạnh 1: Quét trực tiếp hàm AnimeVsub trong Script (Log @số 7)
        Regex("""AnimeVsub\(\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.get(1)?.let { hash ->
            if (fetchAndEmit(hash, filmId, epUrl, callback)) return true
        }

        // Biện pháp mạnh 2: Quét các server button (Log @số 3)
        val doc = Jsoup.parse(body)
        doc.select("a[data-hash], a[data-href]").forEach { btn ->
            val hash = btn.attr("data-hash").ifBlank { btn.attr("data-href") }
            if (hash.length > 20) {
                if (fetchAndEmit(hash, filmId, epUrl, callback)) return true
            }
        }

        return false
    }

    private suspend fun fetchAndEmit(hash: String, filmId: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        // Gửi request giống hệt Log @số 2
        val res = app.post("$mainUrl/ajax/player", 
            data = mapOf("link" to hash, "id" to filmId),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to ref),
            interceptor = cf
        ).text ?: return false

        try {
            val json = mapper.readTree(res)
            val linkNode = json.get("link")
            if (linkNode != null && linkNode.isArray) {
                for (item in linkNode) {
                    val encFile = item.get("file")?.asText() ?: continue
                    val decUrl = Crypto.decryptToUrl(encFile) ?: continue
                    
                    cb(newExtractorLink(name, name, decUrl) {
                        this.referer = ref
                        this.type = if (decUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        // Quan trọng: Header để bypass chặn segment .html (Log @số 6)
                        this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K)")
                    })
                    return true
                }
            }
        } catch (e: Exception) { }
        return false
    }

    // Các hàm getMainPage, search, load giữ nguyên như cũ nhưng cần update regex lấy filmId
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf).document
        val filmId = Regex("""filmID\s*=\s*parseInt\(['"](\d+)""").find(doc.html())?.groupValues?.get(1) ?: ""
        val episodes = doc.select("ul.list-episode li a").map {
            newEpisode("${fixUrl(it.attr("href"))}@@$filmId") {
                name = it.text()
            }
        }
        return newAnimeLoadResponse(doc.selectFirst("h1.Title")?.text() ?: "", url, TvType.Anime) {
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }
}
