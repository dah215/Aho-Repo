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
import java.net.URL
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ENCRYPTION BREAKER
// Critical fix: CryptoJS uses OpenSSL EVP_BytesToKey(MD5, 1 iter, salt),
// NOT raw MD5(password) as key. This is the #1 reason decryption was failing.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
object EncryptionBreaker {

    private val PASSWORDS = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",   // confirmed key for this site
        "animevietsub", "animevsub",
        "VSub@2025", "VSub@2024",
        "streaming_key", "player_key", ""
    )

    // OpenSSL "Salted__" magic header
    private val SALTED_MAGIC = byteArrayOf(
        0x53, 0x61, 0x6c, 0x74, 0x65, 0x64, 0x5f, 0x5f  // "Salted__"
    )

    // ── Public entry ─────────────────────────────────────────────────────────
    fun autoBreak(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        val c = encrypted.trim().replace("\\s+".toRegex(), "")

        // 0. Already a URL?
        if (isValidUrl(c)) return c

        // 1. Plain Base64 → try as URL
        try {
            val raw = String(Base64.decode(c, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (isValidUrl(raw)) return raw
        } catch (_: Exception) {}

        // 2. CryptoJS / OpenSSL format (MAIN strategy – tries each password once)
        for (pass in PASSWORDS) {
            val r = decryptCryptoJS(c, pass)
            if (r != null && isValidUrl(r)) return r
        }

        // 3. Raw AES fallback (key = MD5/SHA256 of password, IV from prefix/zero)
        for (pass in PASSWORDS) {
            for (algo in listOf("MD5", "SHA-256")) {
                for (ivStrat in listOf("prefix", "zero")) {
                    val r = decryptRaw(c, pass, algo, ivStrat) ?: continue
                    if (isValidUrl(r)) return r
                }
            }
        }

        return null
    }

    // ── CryptoJS / OpenSSL EVP_BytesToKey decryption ─────────────────────────
    //
    // This matches exactly what JavaScript's CryptoJS.AES.decrypt(enc, "password") does:
    //   1. Base64-decode the ciphertext
    //   2. Strip the 8-byte "Salted__" header (if present)
    //   3. Read 8-byte salt from bytes 8-16
    //   4. Derive 32-byte key + 16-byte IV via evpKDF(MD5, password, salt)
    //   5. Decrypt with AES-256-CBC / PKCS7
    //
    private fun decryptCryptoJS(enc: String, password: String): String? {
        val raw = try { Base64.decode(enc, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null

        val hasSalt = raw.size >= 16 && raw.copyOfRange(0, 8).contentEquals(SALTED_MAGIC)

        val (salt, ct) = if (hasSalt) {
            raw.copyOfRange(8, 16) to raw.copyOfRange(16, raw.size)
        } else {
            // No salt – try with null salt (less common but possible)
            null to raw
        }

        val (key, iv) = evpBytesToKey(password.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            tryDecompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    // ── OpenSSL EVP_BytesToKey with MD5, 1 iteration ─────────────────────────
    // Produces `keyLen + ivLen` bytes from password + optional salt.
    private fun evpBytesToKey(
        password: ByteArray, salt: ByteArray?,
        keyLen: Int, ivLen: Int
    ): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(password)
            if (salt != null) md.update(salt)
            prev = md.digest()
            out.write(prev)
        }
        val b = out.toByteArray()
        return b.copyOfRange(0, keyLen) to b.copyOfRange(keyLen, keyLen + ivLen)
    }

    // ── Raw AES fallback (key = Hash(password), IV from prefix or zeros) ─────
    private fun decryptRaw(enc: String, password: String, hashAlgo: String, ivStrat: String): String? {
        val decoded = try { Base64.decode(enc, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (decoded.size < 17) return null

        val key = when (hashAlgo) {
            "MD5"    -> MessageDigest.getInstance("MD5")   .digest(password.toByteArray())
            "SHA-256"-> MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            else     -> return null
        }
        val (iv, ct) = when (ivStrat) {
            "prefix" -> decoded.copyOfRange(0, 16) to decoded.copyOfRange(16, decoded.size)
            "zero"   -> ByteArray(16) to decoded
            else     -> return null
        }
        if (ct.isEmpty()) return null

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            tryDecompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    // ── Decompression ─────────────────────────────────────────────────────────
    private fun tryDecompress(data: ByteArray): String? {
        try {
            val gz = GZIPInputStream(ByteArrayInputStream(data))
            val out = ByteArrayOutputStream(); gz.copyTo(out); gz.close()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
        try {
            val inf = Inflater(true); inf.setInput(data)
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0) break; out.write(buf, 0, n) }
            inf.end()
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
        return null
    }

    fun isValidUrl(s: String?) = s != null && s.length > 8 &&
        (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") ||
         s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mpd"))
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// HELPERS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
object ResponseAnalyzer {
    fun extractUrls(body: String, base: String): List<String> {
        val urls = mutableListOf<String>()
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""")
            .findAll(body).forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://\S+\.m3u8\S*""")
            .findAll(body).forEach { urls.add(it.value.trimEnd('"', '\'', ')')) }
        Regex("""["'](https?://[^"']{10,})["']""")
            .findAll(body).forEach { urls.add(it.groupValues[1]) }
        if (body.contains("<")) {
            try {
                val doc = Jsoup.parse(body)
                listOf("data-src","data-url","data-href","data-file","data-link","data-source").forEach { a ->
                    doc.select("[$a]").forEach { el -> el.attr(a).takeIf { it.isNotBlank() }?.let { urls.add(it) } }
                }
                doc.select("source[src],video[src],iframe[src]").forEach { el ->
                    el.attr("src").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                }
            } catch (_: Exception) {}
        }
        return urls.map { abs(it, base) }.distinct().filter { it.startsWith("http") }
    }
    private fun abs(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { URL(URL(base), url).toString() } catch (_: Exception) { url }
    }
    fun isStream(url: String) = url.contains(".m3u8") || url.contains(".mp4") ||
                                url.contains(".mpd") || url.contains("/stream/")
}

object DataMiner {
    private val ENC = Regex(
        """(?:file|link|source|url|enc|stream|video)\s*[:=]\s*["']([A-Za-z0-9+/]{24,}={0,2})["']"""
    )
    fun mineEncrypted(body: String): List<String> {
        val out = mutableListOf<String>()
        ENC.findAll(body).forEach { out.add(it.groupValues[1]) }
        Regex("""["']([A-Za-z0-9+/]{40,}={0,2})["']""").findAll(body).forEach {
            val v = it.groupValues[1]; if (entropy(v) > 4.0) out.add(v)
        }
        return out.distinct()
    }
    private fun entropy(s: String): Double {
        val f = mutableMapOf<Char, Int>()
        s.forEach { f[it] = (f[it] ?: 0) + 1 }
        var e = 0.0; val l = s.length.toDouble()
        f.values.forEach { c -> val p = c / l; if (p > 0) e -= p * Math.log(p) / Math.log(2.0) }
        return e
    }
}

object JSReverse {
    fun unpack(js: String): String {
        var r = js
        repeat(5) { val n = unpackOne(r); if (n == r) return r; r = n }
        return r
    }
    private fun unpackOne(js: String): String {
        Regex("""\}\s*\(\s*['"]([^'"]+)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([^'"]+)['"]\s*,""")
            .find(js)?.let { m ->
                try {
                    val p = m.groupValues[1]; val a = m.groupValues[2].toInt()
                    val c = m.groupValues[3].toInt(); val k = m.groupValues[4].split("|")
                    var res = p
                    for (i in c - 1 downTo 0) if (i < k.size && k[i].isNotEmpty())
                        res = res.replace(Regex("\\b${i.toString(a)}\\b"), k[i])
                    return res
                } catch (_: Exception) {}
            }
        Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""").find(js)?.let { m ->
            try { return js.replace(m.value, "'${String(Base64.decode(m.groupValues[1], Base64.DEFAULT))}'") }
            catch (_: Exception) {}
        }
        return js
    }
    fun extractUrls(js: String): List<String> {
        val urls = mutableListOf<String>()
        listOf(
            Regex("""['"]https?://[^'"]+\.m3u8[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mp4[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]*(?:stream|hls|video|cdn)[^'"]*['"]""")
        ).forEach { p -> p.findAll(js).forEach { urls.add(it.value.trim('\'', '"')) } }
        return urls.distinct()
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MAIN PLUGIN
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name    = "AnimeVietSub"
    override var lang    = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val debugMode = true
    private val cfKiller  = CloudflareKiller()
    private val mapper    = jacksonObjectMapper()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to ua, "Accept" to "text/html,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none"
    )
    private fun ajaxH(ref: String) = defaultHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref, "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin"
    )
    private fun vidH(ref: String) = mapOf("User-Agent" to ua, "Referer" to ref, "Origin" to mainUrl, "Accept" to "*/*")

    // ── Utils ─────────────────────────────────────────────────────────────────
    private fun fixUrl(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        val t = u.trim()
        return when {
            t.startsWith("http") -> t
            t.startsWith("//")   -> "https:$t"
            t.startsWith("/")    -> "$mainUrl$t"
            else                 -> "$mainUrl/$t"
        }
    }

    private fun filmIdFromUrl(url: String): String {
        Regex("""[/-]a(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""-(\d+)(?:\.html|/)?$""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private fun epIdFromHref(href: String): String {
        Regex("""tap[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        Regex("""ep[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        Regex("""episode[-_]?(\d+)""").find(href)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private fun log(tag: String, msg: String) { if (debugMode) println("AVS[$tag] $msg") }

    // ── Pages ─────────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.ifBlank { mainUrl }
        val url  = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"
        val doc  = app.get(fixUrl(url) ?: mainUrl, interceptor = cfKiller, headers = defaultHeaders).document
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }
    private fun Element.toSearchResponse(): SearchResponse? {
        val a     = selectFirst("a") ?: return null
        val href  = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title, h3, h2, .title, .name")?.text()?.trim()
                    ?: a.attr("title").trim().ifBlank { return null }
        val img   = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article, .TPostMv, .item, .list-film li")
            .mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document

        val sel = "ul.list-episode li a, .list-eps a, .server-list a, .list-episode a, .episodes a, #list_episodes a"
        var epNodes = doc.select(sel)
        if (epNodes.isEmpty()) {
            val watchUrl = doc.selectFirst(
                "a.btn-see, a[href*='/tap-'], a[href*='/episode-'], .btn-watch a, a.watch_button, a.xem-phim"
            )?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = defaultHeaders).document
                epNodes = doc.select(sel)
            }
        }

        val filmId = filmIdFromUrl(fixedUrl)

        val episodes = epNodes.mapNotNull { ep ->
            val href   = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            // Check both the <a> itself and its parent <li> for data-id
            val dataId = ep.attr("data-id").trim()
                .ifBlank { ep.attr("data-episodeid").trim() }
                .ifBlank { ep.parent()?.attr("data-id")?.trim() ?: "" }
                .ifBlank { ep.parent()?.attr("data-episodeid")?.trim() ?: "" }
            val hrefId = epIdFromHref(href)
            val name   = ep.text().trim().ifBlank { ep.attr("title").trim() }

            log("LOAD", "ep: href=$href dataId='$dataId' hrefId='$hrefId'")

            newEpisode("$href@@$filmId@@$dataId@@$hrefId") {
                this.name    = name
                this.episode = Regex("\\d+").find(name)?.value?.toIntOrNull() ?: hrefId.toIntOrNull()
            }
        }

        val title  = doc.selectFirst("h1.Title, h1, .Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }
        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            posterUrl     = poster
            plot          = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // LOAD LINKS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts  = data.split("@@")
        val epUrl  = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val dataId = parts.getOrNull(2) ?: ""
        val hrefId = parts.getOrNull(3) ?: ""

        log("LINKS", "epUrl=$epUrl filmId='$filmId' dataId='$dataId' hrefId='$hrefId'")

        val vH = vidH(epUrl)
        val aH = ajaxH(epUrl)
        val cookies = mutableMapOf<String, String>()
        var pageBody = ""

        // Fetch page ONCE and cache
        try {
            val res = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            cookies.putAll(res.cookies)
            pageBody = res.text ?: ""
            log("LINKS", "Page cached ${pageBody.length}b")
        } catch (e: Exception) { log("LINKS", "Page fetch failed: ${e.message}"); return false }

        var found = false

        // ── A: AJAX (15s) ────────────────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(15_000L) {
            strategyAjax(epUrl, filmId, dataId, hrefId, cookies, aH, vH, subtitleCallback, callback)
        } ?: false
        log("LINKS", "A=$found")

        // ── B: Page scrape (8s) ──────────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(8_000L) {
            strategyPageScrape(pageBody, epUrl, vH, subtitleCallback, callback)
        } ?: false
        log("LINKS", "B=$found")

        // ── C: JS reverse (5s) ───────────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(5_000L) {
            strategyJS(pageBody, epUrl, vH, subtitleCallback, callback)
        } ?: false
        log("LINKS", "C=$found")

        // ── D: Data mine + decrypt (8s) ──────────────────────────────────────
        if (!found) found = withTimeoutOrNull(8_000L) {
            strategyMine(pageBody, epUrl, vH, subtitleCallback, callback)
        } ?: false
        log("LINKS", "D=$found")

        // ── E: Alt endpoints (10s) ───────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(10_000L) {
            strategyAlt(epUrl, filmId, dataId, hrefId, vH, subtitleCallback, callback)
        } ?: false
        log("LINKS", "Final=$found")

        return found
    }

    // ── Strategy A: AJAX player API ───────────────────────────────────────────
    private suspend fun strategyAjax(
        epUrl: String, filmId: String, dataId: String, hrefId: String,
        cookies: MutableMap<String, String>,
        aH: Map<String, String>, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        // Try dataId first (database ID), then hrefId (sequential), then filmId
        val candidates = listOf(dataId, hrefId, filmId).filter { it.isNotBlank() }.distinct()
        log("LINKS", "AJAX candidates: $candidates")

        for (epId in candidates) {
            try {
                // Step 1: get server list HTML
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH, cookies = cookies, interceptor = cfKiller)
                cookies.putAll(r1.cookies)
                val raw1 = r1.text ?: continue
                log("LINKS", "Step1 epId=$epId raw: ${raw1.take(200)}")

                val htmlContent = try {
                    @Suppress("UNCHECKED_CAST")
                    (mapper.readValue(raw1, Map::class.java) as Map<String, Any?>)["html"]?.toString() ?: raw1
                } catch (_: Exception) { raw1 }

                val btns = Jsoup.parse(htmlContent).select(
                    "a.btn3dsv, a[data-href], a[data-play], .server-item a, .btn-server, li[data-id] a"
                )
                log("LINKS", "Buttons found: ${btns.size} for epId=$epId")

                if (btns.isEmpty()) {
                    // No buttons → try direct URL extraction
                    for (u in ResponseAnalyzer.extractUrls(htmlContent, epUrl))
                        if (ResponseAnalyzer.isStream(u) && emit(u, vH, sub, cb)) return true
                    continue
                }

                for (btn in btns) {
                    val hash  = btn.attr("data-href").ifBlank { btn.attr("href") }.trim()
                    val play  = btn.attr("data-play").trim()
                    val btnId = btn.attr("data-id").trim().ifBlank { epId }
                    if (hash.isBlank()) continue
                    log("LINKS", "Btn: play='$play' hash='${hash.take(60)}'")

                    if (hash.startsWith("http")) { if (emit(hash, vH, sub, cb)) return true; continue }

                    // Optional: activate session
                    runCatching {
                        app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$epId",
                            headers = aH, cookies = cookies, interceptor = cfKiller)
                            .also { cookies.putAll(it.cookies) }
                    }

                    // Build param combinations
                    val paramSets = buildList {
                        if (play == "api" || play.isBlank()) {
                            add(mapOf("link" to hash, "id" to epId))
                            add(mapOf("link" to hash, "id" to btnId))
                            if (filmId.isNotBlank()) add(mapOf("link" to hash, "id" to filmId))
                        } else {
                            add(mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1"))
                            add(mapOf("link" to hash, "play" to play, "id" to epId))
                        }
                    }

                    for (params in paramSets) {
                        try {
                            val r2 = app.post("$mainUrl/ajax/player",
                                data = params, headers = aH, cookies = cookies, interceptor = cfKiller)
                            cookies.putAll(r2.cookies)
                            val raw2 = r2.text ?: continue
                            log("LINKS", "Step2: ${raw2.take(200)}")
                            if (processBody(raw2, epUrl, vH, sub, cb)) return true
                        } catch (e: Exception) { log("LINKS", "Step2 err: ${e.message}") }
                    }
                }
            } catch (e: Exception) { log("LINKS", "AJAX err epId=$epId: ${e.message}") }
        }
        return false
    }

    // ── Parse and handle a player AJAX response body ──────────────────────────
    private suspend fun processBody(
        body: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false

        // Parse JSON: link = String | [{file: ...}]
        val (direct, files) = try {
            @Suppress("UNCHECKED_CAST")
            val j = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            when (val lnk = j["link"]) {
                is String  -> lnk.takeIf { it.isNotBlank() } to emptyList<String>()
                is List<*> -> null to lnk.filterIsInstance<Map<String, Any?>>()
                                         .mapNotNull { (it["file"] ?: it["src"] ?: it["url"]) as? String }
                                         .filter { it.isNotBlank() }
                else       -> (j["url"] as? String ?: j["stream"] as? String) to emptyList<String>()
            }
        } catch (_: Exception) { null to emptyList<String>() }

        log("LINKS", "processBody: direct=$direct files=${files.size}")

        // Case 1: direct URL
        if (!direct.isNullOrBlank() && direct.startsWith("http"))
            if (emit(direct, vH, sub, cb)) return true

        // Case 2: encrypted file list – THIS is where CryptoJS decrypt is critical
        for (enc in files) {
            if (enc.startsWith("http")) { if (emit(enc, vH, sub, cb)) return true; continue }
            log("LINKS", "Decrypting: ${enc.take(80)}...")
            val dec = EncryptionBreaker.autoBreak(enc)
            log("LINKS", "Decrypted → $dec")
            if (dec != null && emit(dec, vH, sub, cb)) return true
        }

        // Case 3: mine URLs from raw body
        for (u in ResponseAnalyzer.extractUrls(body, epUrl))
            if (ResponseAnalyzer.isStream(u) && emit(u, vH, sub, cb)) return true

        // Case 4: entire body might be encrypted
        if (!body.trimStart().run { startsWith("{") || startsWith("[") || startsWith("<") }) {
            val dec = EncryptionBreaker.autoBreak(body.trim())
            if (dec != null && EncryptionBreaker.isValidUrl(dec) && emit(dec, vH, sub, cb)) return true
        }
        return false
    }

    // ── Strategy B: Scrape cached page ───────────────────────────────────────
    private suspend fun strategyPageScrape(
        body: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }

        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fixUrl(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            log("LINKS", "iframe $src")
            try { loadExtractor(src, epUrl, sub, cb); return true } catch (_: Exception) {}
            try {
                val iBody = app.get(src, headers = vH, interceptor = cfKiller).text ?: continue
                for (u in ResponseAnalyzer.extractUrls(iBody, src))
                    if (ResponseAnalyzer.isStream(u) && emit(u, vH, sub, cb)) return true
            } catch (_: Exception) {}
        }
        for (el in doc.select("video source[src],video[src]")) {
            val src = fixUrl(el.attr("src")) ?: continue
            if (emit(src, vH, sub, cb)) return true
        }
        for (el in doc.select("[data-file],[data-url],[data-source],[data-stream]")) {
            val raw = listOf("data-file","data-url","data-source","data-stream")
                .firstNotNullOfOrNull { a -> el.attr(a).takeIf { it.isNotBlank() } } ?: continue
            fixUrl(raw)?.takeIf { ResponseAnalyzer.isStream(it) }?.let { if (emit(it, vH, sub, cb)) return true }
            EncryptionBreaker.autoBreak(raw)?.let { if (emit(it, vH, sub, cb)) return true }
        }
        return false
    }

    // ── Strategy C: JS unpack ────────────────────────────────────────────────
    private suspend fun strategyJS(
        body: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }
        for (script in doc.select("script:not([src])").map { it.html() }.filter { it.length > 50 }) {
            val u = JSReverse.unpack(script)
            for (url in (JSReverse.extractUrls(u) + ResponseAnalyzer.extractUrls(u, epUrl)).distinct())
                if (ResponseAnalyzer.isStream(url) && emit(url, vH, sub, cb)) return true
        }
        return false
    }

    // ── Strategy D: Data mine ─────────────────────────────────────────────────
    private suspend fun strategyMine(
        body: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        for (u in ResponseAnalyzer.extractUrls(body, epUrl))
            if (ResponseAnalyzer.isStream(u) && emit(u, vH, sub, cb)) return true
        for (enc in DataMiner.mineEncrypted(body)) {
            if (enc.startsWith("http")) { if (emit(enc, vH, sub, cb)) return true; continue }
            val dec = EncryptionBreaker.autoBreak(enc) ?: continue
            log("LINKS", "Mine dec $dec")
            if (emit(dec, vH, sub, cb)) return true
        }
        return false
    }

    // ── Strategy E: Alt AJAX endpoints ────────────────────────────────────────
    private suspend fun strategyAlt(
        epUrl: String, filmId: String, dataId: String, hrefId: String,
        vH: Map<String, String>, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = listOf(dataId, hrefId, filmId).filter { it.isNotBlank() }.distinct()
        for (id in ids) {
            for (ep in listOf(
                "$mainUrl/ajax/player?episodeId=$id",
                "$mainUrl/ajax/getLink?filmId=$filmId&episodeId=$id",
                "$mainUrl/ajax/stream/$id",
                "$mainUrl/ajax/player?id=$id"
            )) {
                try {
                    val b = app.get(ep, headers = defaultHeaders, interceptor = cfKiller).text ?: continue
                    if (processBody(b, epUrl, vH, sub, cb)) return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    // ── Emit a stream URL ─────────────────────────────────────────────────────
    private suspend fun emit(
        url: String, headers: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http")) return false
        val ref = headers["Referer"] ?: mainUrl
        log("EMIT", clean)
        return try {
            when {
                clean.contains(".m3u8") -> {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8; this.headers = headers
                    }); true
                }
                clean.contains(".mp4") || clean.contains(".mkv") || clean.contains(".webm") -> {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO; this.headers = headers
                    }); true
                }
                else -> try {
                    loadExtractor(clean, ref, sub, cb); true
                } catch (_: Exception) {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO; this.headers = headers
                    }); true
                }
            }
        } catch (e: Exception) { log("EMIT", "err: ${e.message}"); false }
    }
}
