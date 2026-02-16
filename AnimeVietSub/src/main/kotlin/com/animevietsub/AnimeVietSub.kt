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

object Crypto {
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)
    private const val PASS = "dm_thang_suc_vat_get_link_an_dbt"

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http") || s.startsWith("{") || s.startsWith("[")) return s
        return openSSL(s)
    }

    private fun openSSL(b64: String): String? {
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 16) return null
            val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
            val salt = if (hasSalt) raw.copyOfRange(8, 16) else null
            val ct = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
            if (ct.isEmpty()) return null
            val (key, iv) = evpKDF(PASS.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
            aesCbc(ct, key, iv)
        } catch (_: Exception) { null }
    }

    private fun evpKDF(pwd: ByteArray, salt: ByteArray?, kLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < kLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(pwd)
            if (salt != null) md.update(salt)
            prev = md.digest()
            out.write(prev)
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
        try {
            GZIPInputStream(ByteArrayInputStream(d)).use { g ->
                val o = ByteArrayOutputStream()
                g.copyTo(o)
                return String(o.toByteArray(), StandardCharsets.UTF_8).trim()
            }
        } catch (_: Exception) {}
        try {
            val inf = Inflater(true)
            inf.setInput(d)
            val o = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                o.write(buf, 0, n)
            }
            inf.end()
            return String(o.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {}
        return null
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
    private const val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf("User-Agent" to ua, "Accept-Language" to "vi-VN,vi;q=0.9")

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:${u.trim()}"
            u.startsWith("/") -> "$mainUrl${u.trim()}"
            else -> "$mainUrl/$u"
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
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = (selectFirst(".Title,h3,h2,.title,.name")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> =
        app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/", interceptor = cf, headers = pageH).document
            .select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageH).document

        val sel = ".btn-episode,.episode-link,a[data-id][data-hash],ul.list-episode li a"
        var epNodes = doc.select(sel)

        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*='/tap-']")?.attr("href")?.let { fix(it) }?.let { wUrl ->
                doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                epNodes = doc.select(sel)
            }
        }

        // Film ID từ URL: /phim/xxx-a5868/ hoặc /xxx-a5868.html
        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""

        val episodes = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataHash = ep.attr("data-hash").trim().ifBlank { null } ?: return@mapNotNull null
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            // Format: href@@filmId@@dataHash
            newEpisode("$href@@$filmId@@$dataHash") {
                name = nm
                episode = Regex("\\d+").find(nm)?.value?.toIntOrNull()
            }
        }

        val title = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image],.MovieThumb img,figure.Objf img")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
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
        val filmId = parts.getOrNull(1) ?: return false
        val dataHash = parts.getOrNull(2) ?: return false

        // Bước 1: POST link=HASH&id=FILM_ID đến /ajax/player
        val resp = try {
            app.post("$mainUrl/ajax/player",
                data = mapOf("link" to dataHash, "id" to filmId),
                headers = ajaxH(epUrl),
                interceptor = cf
            ).text
        } catch (_: Exception) { return false } ?: return false

        // Bước 2: Parse JSON {"link":[{"file":"ENCRYPTED"}]}
        val json = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(resp, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) { return false }

        val linkArray = json["link"] as? List<*> ?: return false

        // Bước 3: Decrypt và emit
        for (item in linkArray.filterIsInstance<Map<String, Any?>>()) {
            val encryptedFile = item["file"]?.toString() ?: continue
            
            // Decrypt
            val decrypted = Crypto.decrypt(encryptedFile) ?: continue

            // Parse decrypted - có thể là URL hoặc JSON array
            when {
                decrypted.startsWith("http") -> {
                    emitUrl(decrypted, epUrl, "", callback)
                    return true
                }
                decrypted.trimStart().startsWith("[") -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = mapper.readValue(decrypted, List::class.java) as List<*>
                    for (src in arr.filterIsInstance<Map<String, Any?>>()) {
                        val file = (src["file"] ?: src["url"])?.toString() ?: continue
                        val label = src["label"]?.toString() ?: ""
                        emitUrl(file, epUrl, label, callback)
                    }
                    return true
                }
                decrypted.trimStart().startsWith("{") -> {
                    @Suppress
