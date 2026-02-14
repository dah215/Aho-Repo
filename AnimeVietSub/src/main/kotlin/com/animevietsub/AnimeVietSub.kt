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
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
// ENCRYPTION BREAKER  –  fast-path first, brute-force last
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
object EncryptionBreaker {

    // The site's confirmed key – tried FIRST at O(1) cost
    private const val KNOWN_PASS = "dm_thang_suc_vat_get_link_an_dbt"

    // Short fallback list, ordered by likelihood
    private val FALLBACK_PASSES = listOf(
        "animevietsub", "animevsub", "VSub@2025", "VSub@2024",
        "video_decrypt_key", "streaming_key", "player_key", ""
    )

    // Only the 6 most common combos (hash × ivStrategy), both with CBC/PKCS5
    private val FAST_COMBOS = listOf(
        Triple("MD5",    "AES/CBC/PKCS5Padding", "prefix"),
        Triple("SHA-256","AES/CBC/PKCS5Padding", "prefix"),
        Triple("MD5",    "AES/CBC/PKCS5Padding", "zero"),
        Triple("SHA-256","AES/CBC/PKCS5Padding", "zero"),
        Triple("MD5",    "AES/CBC/PKCS5Padding", "fromKey"),
        Triple("SHA-256","AES/CBC/PKCS5Padding", "fromKey")
    )

    fun autoBreak(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        val c = encrypted.trim().replace("\\s".toRegex(), "")

        // 1. Plain Base64 → URL?
        try {
            val raw = String(Base64.decode(c, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (isValidUrl(raw)) return raw
        } catch (_: Exception) {}

        // 2. Known password × fast combos
        for ((hash, mode, iv) in FAST_COMBOS) {
            decrypt(c, KNOWN_PASS, hash, mode, iv)?.let { if (isValidUrl(it)) return it }
        }

        // 3. Fallback passwords × fast combos
        for (pass in FALLBACK_PASSES) {
            for ((hash, mode, iv) in FAST_COMBOS) {
                decrypt(c, pass, hash, mode, iv)?.let { if (isValidUrl(it)) return it }
            }
        }
        return null
    }

    private fun decrypt(enc: String, password: String, hashAlgo: String,
                        mode: String, ivStrat: String): String? {
        val decoded = try { Base64.decode(enc, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (decoded.size < 17) return null
        val key = when (hashAlgo) {
            "MD5"    -> md5(password)
            "SHA-256"-> sha256(password)
            "SHA-1"  -> sha1_16(password)
            else     -> return null
        }
        val (iv, ct) = when (ivStrat) {
            "prefix" -> decoded.copyOfRange(0, 16) to decoded.copyOfRange(16, decoded.size)
            "zero"   -> ByteArray(16) to decoded
            "fromKey"-> key.copyOfRange(0, 16) to decoded
            else     -> return null
        }
        if (ct.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(mode)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            tryDecompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun md5(s: String)     = MessageDigest.getInstance("MD5")   .digest(s.toByteArray())
    private fun sha256(s: String)  = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun sha1_16(s: String) = MessageDigest.getInstance("SHA-1") .digest(s.toByteArray()).copyOf(16)

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

    fun isValidUrl(s: String?) = s != null &&
        (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") ||
         s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mpd"))
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// URL / RESPONSE HELPERS
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
                listOf("data-src","data-url","data-href","data-file","data-link","data-source").forEach { attr ->
                    doc.select("[$attr]").forEach { el -> el.attr(attr).takeIf { it.isNotBlank() }?.let { urls.add(it) } }
                }
                doc.select("source[src],video[src],iframe[src]").forEach { urls.add(it.attr("src")) }
            } catch (_: Exception) {}
        }
        return urls.map { absolute(it, base) }.distinct().filter { it.startsWith("http") }
    }

    private fun absolute(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { URL(URL(base), url).toString() } catch (_: Exception) { url }
    }

    fun isStream(url: String) = url.contains(".m3u8") || url.contains(".mp4") ||
                                url.contains(".mpd")  || url.contains("/stream/")
}

object DataMiner {
    private val ENC_PAT = Regex(
        """(?:file|link|source|url|enc|stream|video)\s*[:=]\s*["']([A-Za-z0-9+/]{24,}={0,2})["']"""
    )

    fun mineEncrypted(body: String): List<String> {
        val out = mutableListOf<String>()
        ENC_PAT.findAll(body).forEach { out.add(it.groupValues[1]) }
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
        repeat(5) { val next = unpackOne(r); if (next == r) return r; r = next }
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
            try {
                val d = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                return js.replace(m.value, "'$d'")
            } catch (_: Exception) {}
        }
        Regex("""String\.fromCharCode\s*\(([\d\s,]+)\)""").find(js)?.let { m ->
            try {
                val d = m.groupValues[1].split(",").map { it.trim().toInt() }.map { it.toChar() }.joinToString("")
                return js.replace(m.value, "'$d'")
            } catch (_: Exception) {}
        }
        return js
    }

    fun extractUrls(js: String): List<String> {
        val urls = mutableListOf<String>()
        listOf(
            Regex("""['"]https?://[^'"]+\.m3u8[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]+\.mp4[^'"]*['"]"""),
            Regex("""['"]https?://[^'"]*(?:stream|hls|video|cdn|play)[^'"]*['"]""")
        ).forEach { pat -> pat.findAll(js).forEach { urls.add(it.value.trim('\'', '"')) } }
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
    private val ua        = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent"      to ua,
        "Accept"          to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest"  to "document",
        "Sec-Fetch-Mode"  to "navigate",
        "Sec-Fetch-Site"  to "none"
    )

    private fun ajaxHeaders(ref: String) = defaultHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer"          to ref,
        "Origin"           to mainUrl,
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
        "Sec-Fetch-Dest"   to "empty",
        "Sec-Fetch-Mode"   to "cors",
        "Sec-Fetch-Site"   to "same-origin"
    )

    private fun videoHeaders(ref: String) = mapOf(
        "User-Agent" to ua,
        "Referer"    to ref,
        "Origin"     to mainUrl,
        "Accept"     to "*/*"
    )

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
        "$mainUrl/"                           to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
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

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document

        val sel = "ul.list-episode li a, .list-eps a, .server-list a, " +
                  ".list-episode a, .episodes a, #list_episodes a"
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
            val dataId = ep.attr("data-id").trim().ifBlank { ep.attr("data-episodeid").trim() }
            val hrefId = epIdFromHref(href)
            val name   = ep.text().trim().ifBlank { ep.attr("title").trim() }
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
            posterUrl    = poster
            plot         = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // LOAD LINKS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts  = data.split("@@")
        val epUrl  = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val dataId = parts.getOrNull(2) ?: ""
        val hrefId = parts.getOrNull(3) ?: ""

        log("LINKS", "epUrl=$epUrl filmId='$filmId' dataId='$dataId' hrefId='$hrefId'")

        val vHeaders = videoHeaders(epUrl)
        val aHeaders = ajaxHeaders(epUrl)
        val cookies  = mutableMapOf<String, String>()
        var pageBody = ""

        // ── Fetch page ONCE – cache body ──────────────────────────────────────
        try {
            val res = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
            cookies.putAll(res.cookies)
            pageBody = res.text ?: ""
            log("LINKS", "Page cached, body=${pageBody.length}b cookies=${cookies.keys}")
        } catch (e: Exception) {
            log("LINKS", "Page fetch failed: ${e.message}")
            return false
        }

        var found = false

        // ── A: AJAX API (15 s timeout) ────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(15_000L) {
            strategyAjax(epUrl, filmId, dataId, hrefId, cookies, aHeaders, vHeaders, subtitleCallback, callback)
        } ?: false
        log("LINKS", "After A: found=$found")

        // ── B: Scrape cached page (8 s timeout) ───────────────────────────────
        if (!found) found = withTimeoutOrNull(8_000L) {
            strategyPageScrape(pageBody, epUrl, vHeaders, subtitleCallback, callback)
        } ?: false
        log("LINKS", "After B: found=$found")

        // ── C: JS reverse (5 s timeout) ───────────────────────────────────────
        if (!found) found = withTimeoutOrNull(5_000L) {
            strategyJSReverse(pageBody, epUrl, vHeaders, subtitleCallback, callback)
        } ?: false
        log("LINKS", "After C: found=$found")

        // ── D: Data mine (8 s timeout) ────────────────────────────────────────
        if (!found) found = withTimeoutOrNull(8_000L) {
            strategyDataMine(pageBody, epUrl, vHeaders, subtitleCallback, callback)
        } ?: false
        log("LINKS", "After D: found=$found")

        // ── E: Alt endpoints (10 s timeout) ──────────────────────────────────
        if (!found) found = withTimeoutOrNull(10_000L) {
            strategyAltEndpoints(epUrl, filmId, dataId, hrefId, vHeaders, subtitleCallback, callback)
        } ?: false
        log("LINKS", "Final: found=$found")

        return found
    }

    // ── Strategy A ────────────────────────────────────────────────────────────
    private suspend fun strategyAjax(
        epUrl: String, filmId: String, dataId: String, hrefId: String,
        cookies: MutableMap<String, String>,
        aHeaders: Map<String, String>, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = listOf(dataId, hrefId, filmId).filter { it.isNotBlank() }.distinct()
        for (episodeId in candidates) {
            log("LINKS", "AJAX episodeId='$episodeId'")
            try {
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to episodeId, "backup" to "1"),
                    headers = aHeaders, cookies = cookies, interceptor = cfKiller)
                cookies.putAll(r1.cookies)
                val body1 = r1.text ?: continue

                val htmlContent = try {
                    (mapper.readValue(body1, Map::class.java) as? Map<*, *>)
                        ?.get("html")?.toString() ?: body1
                } catch (_: Exception) { body1 }

                val btns = Jsoup.parse(htmlContent).select(
                    "a.btn3dsv, a[data-href], a[data-play], " +
                    ".server-item a, .btn-server, li[data-id] a, .episodes-btn a"
                )
                log("LINKS", "Server buttons: ${btns.size}")

                if (btns.isEmpty()) {
                    for (u in ResponseAnalyzer.extractUrls(htmlContent, epUrl))
                        if (ResponseAnalyzer.isStream(u) && emitStream(u, vHeaders, subtitleCallback, callback)) return true
                    continue
                }

                for (btn in btns) {
                    val hash  = btn.attr("data-href").ifBlank { btn.attr("href") }.trim()
                    val play  = btn.attr("data-play").trim()
                    val btnId = btn.attr("data-id").trim().ifBlank { episodeId }
                    if (hash.isBlank()) continue

                    if (hash.startsWith("http")) {
                        if (emitStream(hash, vHeaders, subtitleCallback, callback)) return true
                        continue
                    }

                    runCatching {
                        app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
                            headers = aHeaders, cookies = cookies, interceptor = cfKiller)
                            .also { cookies.putAll(it.cookies) }
                    }

                    val paramSets = mutableListOf<Map<String, String>>()
                    if (play == "api" || play.isBlank()) {
                        paramSets += mapOf("link" to hash, "id" to episodeId)
                        paramSets += mapOf("link" to hash, "id" to btnId)
                        if (filmId.isNotBlank()) paramSets += mapOf("link" to hash, "id" to filmId)
                    } else {
                        paramSets += mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
                        paramSets += mapOf("link" to hash, "play" to play, "id" to episodeId)
                    }

                    for (params in paramSets) {
                        try {
                            val r2 = app.post("$mainUrl/ajax/player",
                                data = params, headers = aHeaders,
                                cookies = cookies, interceptor = cfKiller)
                            cookies.putAll(r2.cookies)
                            val body2 = r2.text ?: continue
                            log("LINKS", "Step2: ${body2.take(120)}")
                            if (processPlayerBody(body2, epUrl, vHeaders, subtitleCallback, callback)) return true
                        } catch (e: Exception) { log("LINKS", "Step2 err: ${e.message}") }
                    }
                }
            } catch (e: Exception) { log("LINKS", "AJAX err: ${e.message}") }
        }
        return false
    }

    private suspend fun processPlayerBody(
        body: String, epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false

        val (directLink, encFiles) = try {
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            when (val lnk = json["link"]) {
                is String  -> lnk.takeIf { it.isNotBlank() } to emptyList()
                is List<*> -> null to lnk.filterIsInstance<Map<String, Any?>>()
                                         .mapNotNull { it["file"] as? String }
                                         .filter { it.isNotBlank() }
                else       -> (json["url"] as? String) to emptyList<String>()
            }
        } catch (_: Exception) { null to emptyList<String>() }

        if (!directLink.isNullOrBlank() && directLink.startsWith("http"))
            if (emitStream(directLink, vHeaders, subtitleCallback, callback)) return true

        for (enc in encFiles) {
            if (enc.startsWith("http")) {
                if (emitStream(enc, vHeaders, subtitleCallback, callback)) return true
                continue
            }
            val dec = EncryptionBreaker.autoBreak(enc) ?: continue
            log("LINKS", "Decrypted → $dec")
            if (emitStream(dec, vHeaders, subtitleCallback, callback)) return true
        }

        for (u in ResponseAnalyzer.extractUrls(body, epUrl))
            if (ResponseAnalyzer.isStream(u) && emitStream(u, vHeaders, subtitleCallback, callback)) return true

        if (!body.trimStart().startsWith("{") && !body.trimStart().startsWith("[") &&
            !body.trimStart().startsWith("<")) {
            val dec = EncryptionBreaker.autoBreak(body.trim())
            if (dec != null && EncryptionBreaker.isValidUrl(dec))
                if (emitStream(dec, vHeaders, subtitleCallback, callback)) return true
        }

        return false
    }

    // ── Strategy B ────────────────────────────────────────────────────────────
    private suspend fun strategyPageScrape(
        body: String, epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (body.isBlank()) return false
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }

        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fixUrl(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            log("LINKS", "iframe → $src")
            try { loadExtractor(src, epUrl, subtitleCallback, callback); return true } catch (_: Exception) {}
            try {
                val iBody = app.get(src, headers = vHeaders, interceptor = cfKiller).text ?: continue
                for (u in ResponseAnalyzer.extractUrls(iBody, src))
                    if (ResponseAnalyzer.isStream(u) && emitStream(u, vHeaders, subtitleCallback, callback)) return true
            } catch (_: Exception) {}
        }

        for (el in doc.select("video source[src],video[src]")) {
            val src = fixUrl(el.attr("src")) ?: continue
            if (emitStream(src, vHeaders, subtitleCallback, callback)) return true
        }

        for (el in doc.select("[data-file],[data-url],[data-source],[data-stream]")) {
            val raw = listOf("data-file","data-url","data-source","data-stream")
                .firstNotNullOfOrNull { attr -> el.attr(attr).takeIf { it.isNotBlank() } } ?: continue
            val src = fixUrl(raw)
            if (src != null && ResponseAnalyzer.isStream(src)) {
                if (emitStream(src, vHeaders, subtitleCallback, callback)) return true
            } else {
                val dec = EncryptionBreaker.autoBreak(raw)
                if (dec != null && emitStream(dec, vHeaders, subtitleCallback, callback)) return true
            }
        }
        return false
    }

    // ── Strategy C ────────────────────────────────────────────────────────────
    private suspend fun strategyJSReverse(
        body: String, epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }
        for (script in doc.select("script:not([src])").map { it.html() }.filter { it.length > 50 }) {
            val unpacked = JSReverse.unpack(script)
            for (u in (JSReverse.extractUrls(unpacked) + ResponseAnalyzer.extractUrls(unpacked, epUrl)).distinct())
                if (ResponseAnalyzer.isStream(u) && emitStream(u, vHeaders, subtitleCallback, callback)) return true
        }
        return false
    }

    // ── Strategy D ────────────────────────────────────────────────────────────
    private suspend fun strategyDataMine(
        body: String, epUrl: String, vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (u in ResponseAnalyzer.extractUrls(body, epUrl))
            if (ResponseAnalyzer.isStream(u) && emitStream(u, vHeaders, subtitleCallback, callback)) return true
        for (enc in DataMiner.mineEncrypted(body)) {
            if (enc.startsWith("http")) {
                if (emitStream(enc, vHeaders, subtitleCallback, callback)) return true; continue
            }
            val dec = EncryptionBreaker.autoBreak(enc) ?: continue
            if (emitStream(dec, vHeaders, subtitleCallback, callback)) return true
        }
        return false
    }

    // ── Strategy E ────────────────────────────────────────────────────────────
    private suspend fun strategyAltEndpoints(
        epUrl: String, filmId: String, dataId: String, hrefId: String,
        vHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = listOf(dataId, hrefId, filmId).filter { it.isNotBlank() }.distinct()
        for (id in ids) {
            for (ep in listOf(
                "$mainUrl/ajax/player?episodeId=$id",
                "$mainUrl/ajax/getLink?filmId=$filmId&episodeId=$id",
                "$mainUrl/ajax/stream/$id",
                "$mainUrl/ajax/player?id=$id",
                "$mainUrl/api/get_link/$id"
            )) {
                try {
                    val body = app.get(ep, headers = defaultHeaders, interceptor = cfKiller).text ?: continue
                    if (processPlayerBody(body, epUrl, vHeaders, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    // ── Emit stream (NO blocking M3u8Helper call) ─────────────────────────────
    private suspend fun emitStream(
        url: String, headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http")) return false
        val referer = headers["Referer"] ?: mainUrl
        log("EMIT", clean)
        return try {
            when {
                clean.contains(".m3u8") -> {
                    callback(newExtractorLink(name, name, clean) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type    = ExtractorLinkType.M3U8
                        this.headers = headers
                    })
                    true
                }
                clean.contains(".mp4") || clean.contains(".mkv") || clean.contains(".webm") -> {
                    callback(newExtractorLink(name, name, clean) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type    = ExtractorLinkType.VIDEO
                        this.headers = headers
                    })
                    true
                }
                else -> try {
                    loadExtractor(clean, referer, subtitleCallback, callback); true
                } catch (_: Exception) {
                    callback(newExtractorLink(name, name, clean) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.type    = ExtractorLinkType.VIDEO
                        this.headers = headers
                    })
                    true
                }
            }
        } catch (e: Exception) { log("EMIT", "err: ${e.message}"); false }
    }
}
