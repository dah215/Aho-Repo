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
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

data class PlayerResponse(
    @JsonProperty("_fxStatus") val fxStatus: Int? = null,
    @JsonProperty("success") val success: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("link") val link: List<LinkItem>? = null,
    @JsonProperty("html") val html: String? = null
)

data class LinkItem(
    @JsonProperty("file") val file: String? = null
)

object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    private const val MAIN_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s

        // Try simple base64
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (plain.startsWith("http") || plain.startsWith("#EXTM3U")) return plain
        } catch (_: Exception) {}

        // Try OpenSSL format with main password
        openSSLDecrypt(s, MAIN_PASSWORD)?.let { if (isValid(it)) return it }
        
        // Try SHA-256 format with main password
        simpleSHA256Decrypt(s, MAIN_PASSWORD)?.let { if (isValid(it)) return it }

        return null
    }

    private fun isValid(r: String) = r.startsWith("http") || r.startsWith("#EXTM3U") || r.contains("googleapiscdn.com")

    private fun openSSLDecrypt(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null

        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        if (!hasSalt) return null

        val salt = raw.copyOfRange(8, 16)
        val ciphertext = raw.copyOfRange(16, raw.size)
        if (ciphertext.isEmpty()) return null

        val (key, iv) = evpKDF(pass.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return aesDecrypt(ciphertext, key, iv)
    }

    private fun simpleSHA256Decrypt(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 17) return null
        if (raw.copyOfRange(0, 8).contentEquals(SALTED)) return null

        return try {
            val keyBytes = MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(StandardCharsets.UTF_8))
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            aesDecrypt(ciphertext, keyBytes, iv)
        } catch (_: Exception) { null }
    }

    private fun evpKDF(password: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val output = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (output.size() < keyLen + ivLen) {
            md.reset(); md.update(prev); md.update(password)
            if (salt != null) md.update(salt)
            prev = md.digest(); output.write(prev)
        }
        val b = output.toByteArray()
        return b.copyOfRange(0, keyLen) to b.copyOfRange(keyLen, keyLen + ivLen)
    }

    private fun aesDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (ct.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            decompress(plain) ?: String(plain, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) { null }
    }

    private fun decompress(data: ByteArray): String? {
        // Try raw deflate
        try {
            val inf = Inflater(true)
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            return String(out.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {}
        // Try GZIP
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
                val out = ByteArrayOutputStream()
                gzip.copyTo(out)
                return String(out.toByteArray(), StandardCharsets.UTF_8).trim()
            }
        } catch (_: Exception) {}
        return null
    }

    fun parseM3u8(content: String): String? {
        return Regex("""/chunks/([0-9a-f]{24})/""").find(content)?.groupValues?.get(1)
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private val CDN = "storage.googleapiscdn.com"

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val pageH = mapOf("User-Agent" to ua, "Accept" to "text/html", "Accept-Language" to "vi-VN,vi;q=0.9")
    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref, "Origin" to mainUrl, "Accept" to "application/json"
    )
    private val cdnH = mapOf("User-Agent" to ua)

    private fun fix(u: String?): String? = when {
        u.isNullOrBlank() || u == "#" || u.startsWith("javascript") -> null
        u.startsWith("http") -> u.trim()
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.ifBlank { mainUrl }
        val url = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = (selectFirst(".Title, h2, h3, .title, .name")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", interceptor = cf, headers = pageH).document
        return doc.select("article, .TPostMv, .item, .list-film li").mapNotNull { it.toSR() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageH).document
        val sel = "ul.list-episode li a, .list-eps a, .server-list a, .list-episode a, .episodes a, #list_episodes a"
        var epNodes = doc.select(sel)

        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see, a[href*='/tap-'], a[href*='/episode-'], .btn-watch a")
                ?.attr("href")?.let { fix(it) }?.let { w ->
                    doc = app.get(w, interceptor = cf, headers = pageH).document
                    epNodes = doc.select(sel)
                }
        }

        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        val episodes = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = listOf(ep.attr("data-id"), ep.attr("data-episodeid"))
                .firstOrNull { it.matches(Regex("\\d+")) } ?: ""
            val hash = ep.attr("data-hash").ifBlank { "" }
            val name = ep.text().trim().ifBlank { ep.attr("title").trim() }
            newEpisode("$href@@$filmId@@$epId@@$hash") {
                this.name = name
                episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            }
        }

        val title = doc.selectFirst("h1.Title, h1, .Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
        val plot = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
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
        val epId = parts.getOrNull(2) ?: ""
        val hash = parts.getOrNull(3) ?: ""

        println("[AVS] Loading: url=$epUrl film=$filmId epId=$epId hash=${hash.take(20)}")

        // Get page
        val pageRes = try { app.get(epUrl, interceptor = cf, headers = pageH) }
            catch (e: Exception) { println("[AVS] Page error: ${e.message}"); return false }
        val pageBody = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()

        // Strategy 1: Direct hash from episode
        if (hash.isNotBlank()) {
            println("[AVS] Try direct hash")
            if (getPlayerAndEmit(hash, filmId, epUrl, cookies, callback)) return true
        }

        // Strategy 2: Find hash in page
        val doc = Jsoup.parse(pageBody)
        for (el in doc.select("a[data-hash], a[data-href]")) {
            val h = el.attr("data-hash").ifBlank { el.attr("data-href") }.trim()
            if (h.isNotBlank() && h != "#" && !h.startsWith("http")) {
                println("[AVS] Found hash in page: ${h.take(20)}")
                if (getPlayerAndEmit(h, filmId, epUrl, cookies, callback)) return true
            }
        }

        // Strategy 3: AJAX with episodeId
        if (epId.isNotBlank()) {
            println("[AVS] Try AJAX with epId=$epId")
            val r1 = try { app.post("$mainUrl/ajax/player",
                data = mapOf("episodeId" to epId, "backup" to "1"),
                headers = ajaxH(epUrl), cookies = cookies, interceptor = cf) } catch (_: Exception) { null }
            
            r1?.text?.let { body ->
                // Parse JSON
                try {
                    @Suppress("UNCHECKED_CAST")
                    val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                    val html = json["html"]?.toString() ?: ""
                    if (html.isNotBlank()) {
                        val buttons = Jsoup.parse(html).select("a[data-href], a.btn3dsv")
                        for (btn in buttons) {
                            val h = btn.attr("data-href").trim()
                            if (h.isNotBlank() && h != "#") {
                                println("[AVS] Button hash: ${h.take(20)}")
                                if (getPlayerAndEmit(h, filmId, epUrl, cookies, callback)) return true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        println("[AVS] No links found")
        return false
    }

    private suspend fun getPlayerAndEmit(
        hash: String, filmId: String, referer: String,
        cookies: MutableMap<String, String>, callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try filmId first, then "0"
        val ids = mutableListOf<String>()
        if (filmId.isNotBlank()) ids.add(filmId)
        ids.add("0")

        for (id in ids) {
            println("[AVS] POST link=${hash.take(20)} id=$id")
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("link" to hash, "id" to id),
                    headers = ajaxH(referer), cookies = cookies, interceptor = cf)
                
                val body = r.text ?: continue
                println("[AVS] Response code=${r.code} len=${body.length}")

                // Parse JSON
                try {
                    @Suppress("UNCHECKED_CAST")
                    val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                    val links = json["link"] as? List<*>
                    if (links != null) {
                        for (item in links) {
                            if (item is Map<*, *>) {
                                val file = item["file"]?.toString() ?: continue
                                println("[AVS] Got file: ${file.take(50)}")
                                if (emitFromEncrypted(file, referer, callback)) return true
                            }
                        }
                    }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                println("[AVS] Error: ${e.message}")
            }
        }
        return false
    }

    private suspend fun emitFromEncrypted(enc: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (enc.isBlank()) return false

        // Direct URL?
        if (enc.startsWith("http")) return emit(enc, referer, callback)

        // Decrypt
        val dec = Crypto.decrypt(enc)
        if (dec == null) {
            println("[AVS] Decrypt failed")
            return false
        }
        println("[AVS] Decrypted: ${dec.take(100)}")

        // URL?
        if (dec.startsWith("http")) return emit(dec, referer, callback)

        // M3U8 content?
        if (dec.contains("#EXTM3U")) {
            val hexId = Crypto.parseM3u8(dec)
            if (hexId != null) {
                val url = "https://$CDN/chunks/$hexId/original/index.m3u8"
                println("[AVS] CDN URL: $url")
                return emit(url, referer, callback)
            }
        }

        return false
    }

    private suspend fun emit(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false

        // Extract hexId if CDN URL
        val finalUrl = if (url.contains(CDN)) {
            val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
            if (hexId != null) "https://$CDN/chunks/$hexId/original/index.m3u8"
            else if (url.contains(".m3u8")) url else return false
        } else url

        // Quick check
        try {
            val r = withTimeoutOrNull(3000L) { app.get(finalUrl, headers = cdnH) } ?: return false
            if (r.code != 200 || r.text?.contains("#EXTM3U") != true) return false
        } catch (_: Exception) { return false }

        callback(newExtractorLink(name, name, finalUrl) {
            this.referer = referer
            quality = Qualities.Unknown.value
            type = ExtractorLinkType.M3U8
            headers = cdnH
        })
        return true
    }
}
