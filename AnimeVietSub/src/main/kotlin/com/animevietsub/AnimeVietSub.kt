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
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

/**
 * AnimeVietSub Decryptor
 * 
 * Thuật toán: AES-256-CBC + Zlib (Pako inflate)
 * Password: dm_thang_suc_vat_get_link_an_dbt
 * 
 * Flow: Base64 → AES Decrypt → Zlib Decompress → JSON/URL
 */
object Decryptor {
    private val PASSWORD = "dm_thang_suc_vat_get_link_an_dbt".toByteArray(StandardCharsets.UTF_8)
    private val SALTED = "Salted__".toByteArray(StandardCharsets.UTF_8)
    private val SHA256_KEY by lazy {
        MessageDigest.getInstance("SHA-256").digest(PASSWORD)
    }

    fun decrypt(enc: String): String? {
        if (enc.startsWith("http") || enc.startsWith("#EXTM3U")) return enc
        
        return try {
            val raw = Base64.decode(enc.trim(), Base64.NO_WRAP)
            if (raw.size < 17) return null

            // Check OpenSSL format
            if (raw.copyOfRange(0, 8).contentEquals(SALTED)) {
                // OpenSSL: "Salted__" + salt(8) + ciphertext
                val salt = raw.copyOfRange(8, 16)
                val ct = raw.copyOfRange(16, raw.size)
                val (key, iv) = evpKDF(salt)
                aesDecrypt(ct, key, iv)
            } else {
                // Simple: IV(16) + ciphertext, Key = SHA-256(password)
                val iv = raw.copyOfRange(0, 16)
                val ct = raw.copyOfRange(16, raw.size)
                aesDecrypt(ct, SHA256_KEY, iv)
            }
        } catch (_: Exception) { null }
    }

    private fun evpKDF(salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < 48) {
            md.reset()
            md.update(prev)
            md.update(PASSWORD)
            md.update(salt)
            prev = md.digest()
            out.write(prev)
        }
        val b = out.toByteArray()
        return b.copyOfRange(0, 32) to b.copyOfRange(32, 48)
    }

    private fun aesDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = c.doFinal(ct)
            inflate(plain) ?: String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun inflate(data: ByteArray): String? {
        return try {
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
            String(out.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }
}

private const val CDN = "storage.googleapiscdn.com"

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val json = jacksonObjectMapper()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val hPage = mapOf("User-Agent" to ua, "Accept" to "text/html")
    private fun hAjax(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl
    )

    private fun fix(u: String?) = when {
        u.isNullOrBlank() || u == "#" -> null
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fix(url)!!, interceptor = cf, headers = hPage).document
        val items = doc.select("article, .TPostMv, .item, .TPost").mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(req.name, items, items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = (selectFirst(".Title, h2, h3")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val poster = selectFirst("img")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "UTF-8")}/", interceptor = cf, headers = hPage).document
        return doc.select("article, .TPostMv, .item").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = hPage).document
        
        var epNodes = doc.select("ul.list-episode li a, .list-eps a, .server-list a, .episodes a")
        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see, a[href*='/tap-']")?.attr("href")?.let { fix(it) }?.let {
                doc = app.get(it, interceptor = cf, headers = hPage).document
                epNodes = doc.select("ul.list-episode li a, .list-eps a, .server-list a, .episodes a")
            }
        }

        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        val eps = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = listOf(ep.attr("data-id"), ep.attr("data-episodeid")).firstOrNull { it.matches(Regex("\\d+")) } ?: ""
            val h = ep.attr("data-hash").ifBlank { "" }
            newEpisode("$href@@$filmId@@$epId@@$h") {
                name = ep.text().trim().ifBlank { ep.attr("title") }
                episode = Regex("\\d+").find(name.orEmpty())?.value?.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(doc.selectFirst("h1")?.text() ?: "Anime", fUrl, TvType.Anime) {
            posterUrl = doc.selectFirst(".Image img")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
            plot = doc.selectFirst(".Description")?.text()
            this.episodes = mutableMapOf(DubStatus.Subbed to eps)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts.getOrElse(1) { "" }
        val epId = parts.getOrElse(2) { "" }
        val savedHash = parts.getOrElse(3) { "" }

        val page = app.get(epUrl, interceptor = cf, headers = hPage)
        val cookies = page.cookies.toMutableMap()
        val html = page.text ?: return false

        // Thu thập tất cả hash có thể
        val hashes = mutableListOf<String>()
        if (savedHash.isNotBlank()) hashes.add(savedHash)

        // Parse page tìm data-href
        Jsoup.parse(html).select("a[data-href], a[data-hash]").forEach { el ->
            val h = el.attr("data-href").ifBlank { el.attr("data-hash") }.trim()
            if (h.isNotBlank() && h != "#" && !h.startsWith("http")) hashes.add(h)
        }

        // AJAX để lấy thêm hash
        if (epId.isNotBlank()) {
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = hAjax(epUrl), cookies = cookies, interceptor = cf)
                
                @Suppress("UNCHECKED_CAST")
                val obj = json.readValue(r.text, Map::class.java) as Map<String, Any?>
                
                // Parse html chứa buttons
                obj["html"]?.toString()?.let { htmlStr ->
                    Jsoup.parse(htmlStr).select("a[data-href], a.btn3dsv").forEach { btn ->
                        val h = btn.attr("data-href").trim()
                        if (h.isNotBlank() && h != "#") hashes.add(h)
                    }
                }
            } catch (_: Exception) {}
        }

        // Thử từng hash với filmId
        hashes.distinct().forEach { hash ->
            if (getPlayer(hash, filmId, epUrl, cookies, callback)) return true
        }

        return false
    }

    private suspend fun getPlayer(
        hash: String, filmId: String, referer: String,
        cookies: MutableMap<String, String>, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = if (filmId.isNotBlank()) listOf(filmId, "0") else listOf("0")
        
        for (id in ids) {
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("link" to hash, "id" to id),
                    headers = hAjax(referer), cookies = cookies, interceptor = cf)
                
                @Suppress("UNCHECKED_CAST")
                val obj = json.readValue(r.text, Map::class.java) as Map<String, Any?>
                
                val links = obj["link"] as? List<*>
                links?.forEach { item ->
                    if (item is Map<*, *>) {
                        val file = item["file"]?.toString() ?: return@forEach
                        val dec = Decryptor.decrypt(file) ?: return@forEach
                        if (extractUrl(dec, callback)) return true
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun extractUrl(content: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Direct URL
        if (content.startsWith("http")) return emit(content, callback)
        
        // M3U8 content - extract hexId
        if (content.contains("#EXTM3U")) {
            val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(content)?.groupValues?.get(1)
            if (hexId != null) {
                return emit("https://$CDN/chunks/$hexId/original/index.m3u8", callback)
            }
            // Extract URL from content
            Regex("""https?://[^\s"']+""").find(content)?.value?.let { return emit(it, callback) }
        }
        
        return false
    }

    private fun emit(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false
        
        val finalUrl = if (url.contains(CDN)) {
            Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)?.let {
                "https://$CDN/chunks/$it/original/index.m3u8"
            } ?: url
        } else url

        callback(newExtractorLink(name, name, finalUrl) {
            referer = mainUrl
            quality = Qualities.Unknown.value
            type = ExtractorLinkType.M3U8
            headers = mapOf("User-Agent" to ua)
        })
        return true
    }
}
