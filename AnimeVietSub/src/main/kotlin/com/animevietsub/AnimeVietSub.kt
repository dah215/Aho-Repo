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

object Crypto {
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub", "animevsub",
        "VSub@2025", "VSub@2024", "streaming_key", "player_key", "secret", ""
    )
    private val SALTED = "Salted__".toByteArray(StandardCharsets.ISO_8859_1)

    fun decrypt(b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        val cleaned = b64.trim().replace("\\s".toRegex(), "")

        try {
            val s = String(Base64.decode(cleaned, Base64.DEFAULT), StandardCharsets.UTF_8)
            if (isUrl(s)) return s
        } catch (_: Exception) {}

        for (pass in PASSES) {
            val r = tryOpenSSL(cleaned, pass) ?: continue
            if (isUrl(r)) return r
        }

        for (pass in PASSES) {
            for (algo in listOf("MD5", "SHA-256")) {
                for (usePrefix in listOf(true, false)) {
                    val r = tryRaw(cleaned, pass, algo, usePrefix) ?: continue
                    if (isUrl(r)) return r
                }
            }
        }
        return null
    }

    private fun tryOpenSSL(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null

        val hasSalt = raw.size >= 16 && raw.copyOfRange(0, 8).contentEquals(SALTED)
        val (salt, ct) = if (hasSalt) raw.copyOfRange(8, 16) to raw.copyOfRange(16, raw.size)
                         else            null                  to raw
        if (ct.size < 16) return null

        val (key, iv) = evpKDF(pass.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return tryDecrypt(ct, key, iv)
    }

    private fun evpKDF(pwd: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < keyLen + ivLen) {
            md.reset(); md.update(prev); md.update(pwd)
            if (salt != null) md.update(salt)
            prev = md.digest(); out.write(prev)
        }
        val b = out.toByteArray()
        return b.copyOfRange(0, keyLen) to b.copyOfRange(keyLen, keyLen + ivLen)
    }

    private fun tryRaw(b64: String, pass: String, hashAlgo: String, ivFromPrefix: Boolean): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 17) return null
        val key = MessageDigest.getInstance(hashAlgo).digest(pass.toByteArray())
        val (iv, ct) = if (ivFromPrefix) raw.copyOfRange(0, 16) to raw.copyOfRange(16, raw.size)
                       else              ByteArray(16)           to raw
        return tryDecrypt(ct, key, iv)
    }

    private fun tryDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): String? {
        if (ct.isEmpty() || key.size !in listOf(16, 24, 32)) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            decompress(plain) ?: String(plain, StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun decompress(d: ByteArray): String? {
        try {
            val g = GZIPInputStream(ByteArrayInputStream(d)); val o = ByteArrayOutputStream()
            g.copyTo(o); g.close(); return String(o.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
        try {
            val i = Inflater(true); i.setInput(d)
            val o = ByteArrayOutputStream(); val b = ByteArray(8192)
            while (!i.finished()) { val n = i.inflate(b); if (n == 0) break; o.write(b, 0, n) }
            i.end(); return String(o.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
        return null
    }

    fun isUrl(s: String?) = s != null && s.length > 8 &&
        (s.startsWith("http://") || s.startsWith("https://") ||
         s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mpd") || s.contains("storage.googleapiscdn.com"))
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name    = "AnimeVietSub"
    override var lang    = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cfKiller = CloudflareKiller()
    private val mapper   = jacksonObjectMapper()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Priority" to "u=0, i"
    )

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun vidH(ref: String) = mapOf(
        "User-Agent" to ua,
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "video",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "cross-site"
    )

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
        return ""
    }

    private fun log(tag: String, msg: String) = println("AVS[$tag] $msg")

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
        val doc  = app.get(fixUrl(url) ?: mainUrl, interceptor = cfKiller, headers = baseHeaders).document
        val items = doc.select("article,.TPostMv,.item,.list-film li,.TPost")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title,h3,h2,.title,.name")?.text()?.trim()
                    ?: a.attr("title").trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = baseHeaders).document
            .select("article,.TPostMv,.item,.list-film li").mapNotNull { it.toSR() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = baseHeaders).document

        val sel = "ul.list-episode li a,.list-eps a,.server-list a,.list-episode a,.episodes a,#list_episodes a"
        var epNodes = doc.select(sel)
        if (epNodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-see,a[href*='/tap-'],a[href*='/episode-'],.btn-watch a,a.watch_button,a.xem-phim")
                ?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = baseHeaders).document
                epNodes = doc.select(sel)
            }
        }
        val filmId = filmIdFromUrl(fixedUrl)

        val episodes = epNodes.mapNotNull { ep ->
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val dataId = listOf(
                ep.attr("data-id"), ep.attr("data-episodeid"),
                ep.attr("id").filter { it.isDigit() },
                ep.parent()?.attr("data-id") ?: "",
                ep.parent()?.attr("data-episodeid") ?: ""
            ).firstOrNull { it.isNotBlank() } ?: ""
            val name = ep.text().trim().ifBlank { ep.attr("title").trim() }
            newEpisode("$href@@$filmId@@$dataId") {
                this.name = name
                this.episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            }
        }
        val title  = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }
        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            posterUrl     = poster
            plot          = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts  = data.split("@@")
        val epUrl  = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val savedId = parts.getOrNull(2) ?: ""

        log("START", "epUrl=$epUrl filmId='$filmId' savedId='$savedId'")

        val vH = vidH(epUrl)
        val aH = ajaxH(epUrl)
        val cookies = mutableMapOf<String, String>()

        // CRITICAL: Pre-fetch main page to get fresh cookies/tokens
        try {
            val mainRes = app.get(mainUrl, interceptor = cfKiller, headers = baseHeaders)
            cookies.putAll(mainRes.cookies)
        } catch (_: Exception) {}

        val pageRes = try {
            app.get(epUrl, interceptor = cfKiller, headers = baseHeaders, cookies = cookies)
        } catch (e: Exception) { log("ERR", "Page fetch: ${e.message}"); return false }
        cookies.putAll(pageRes.cookies)
        val pageBody = pageRes.text ?: return false

        val episodeIds = mutableListOf<String>()
        if (savedId.isNotBlank()) episodeIds.add(savedId)

        Regex("""(?:episodeId|episode_id|epId|ep_id)\s*[=:]\s*["']?(\d+)["']?""")
            .findAll(pageBody).forEach { episodeIds.add(it.groupValues[1]) }

        Jsoup.parse(pageBody).select("input[name*=episode]").forEach { el ->
            el.attr("value").filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { episodeIds.add(it) }
        }

        Jsoup.parse(pageBody).select("[data-id]").forEach { el ->
            el.attr("data-id").filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { episodeIds.add(it) }
        }

        Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.let { episodeIds.add(it) }
        Regex("""-(\d+)-v""").find(epUrl)?.groupValues?.get(1)?.let { episodeIds.add(it) }

        val candidates = episodeIds.distinct().filter { it.isNotBlank() }
        log("IDS", "candidates=$candidates")

        var found = false

        // STRATEGY A: RSS/XML get_episode API (Most stable for direct links)
        found = withTimeoutOrNull(20_000L) {
            rssStrategy(filmId, candidates, aH, cookies, vH, subtitleCallback, callback)
        } ?: false

        // STRATEGY B: Official AJAX API
        if (!found) found = withTimeoutOrNull(15_000L) {
            ajaxStrategy(epUrl, filmId, candidates, cookies, aH, vH, subtitleCallback, callback)
        } ?: false
        
        // STRATEGY C: Page scraping
        if (!found) found = withTimeoutOrNull(8_000L) {
            pageStrategy(pageBody, epUrl, vH, subtitleCallback, callback)
        } ?: false

        return found
    }

    private suspend fun rssStrategy(
        filmId: String, candidates: List<String>,
        aH: Map<String, String>, cookies: Map<String, String>,
        vH: Map<String, String>, sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        for (epId in candidates) {
            log("RSS", "Trying epId=$epId")
            try {
                val res = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$epId",
                    headers = aH, cookies = cookies, interceptor = cfKiller).text ?: continue
                
                if (res.contains("<file>")) {
                    val encrypted = res.substringAfter("<file>").substringBefore("</file>")
                    if (encrypted.isNotBlank()) {
                        val decrypted = Crypto.decrypt(encrypted)
                        if (decrypted != null && emit(decrypted, vH, sub, cb)) return true
                    }
                }
                
                Regex("""<file><!\[CDATA\[(https?://[^\]]+)\]\]></file>""").find(res)?.groupValues?.get(1)?.let {
                    if (emit(it, vH, sub, cb)) return true
                }
            } catch (e: Exception) { log("RSS", "Error: ${e.message}") }
        }
        return false
    }

    private suspend fun ajaxStrategy(
        epUrl: String, filmId: String, candidates: List<String>,
        cookies: MutableMap<String, String>,
        aH: Map<String, String>, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        for (epId in candidates) {
            try {
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH, cookies = cookies, interceptor = cfKiller)
                cookies.putAll(r1.cookies)
                val raw1 = r1.text ?: continue

                val html = try {
                    @Suppress("UNCHECKED_CAST")
                    (mapper.readValue(raw1, Map::class.java) as Map<String, Any?>)["html"]?.toString()
                } catch (_: Exception) { null } ?: raw1

                val doc = Jsoup.parse(html)
                val btns = doc.select("a.btn3dsv,a[data-href],a[data-play],.server-item a,.btn-server,li[data-id] a,button[data-href],a[data-link]")

                if (btns.isEmpty()) {
                    if (processPlayerResponse(raw1, epUrl, vH, sub, cb)) return true
                }

                for (btn in btns) {
                    val hash = (btn.attr("data-href").ifBlank { btn.attr("data-link").ifBlank { btn.attr("href") } }).trim()
                    val play = btn.attr("data-play").trim()
                    val btnId = btn.attr("data-id").trim().ifBlank { epId }
                    if (hash.isBlank()) continue

                    if (hash.startsWith("http")) { if (emit(hash, vH, sub, cb)) return true; continue }

                    val paramSets = listOf(
                        mapOf("link" to hash, "id" to btnId, "play" to play),
                        mapOf("link" to hash, "id" to epId, "play" to play),
                        mapOf("link" to hash, "episodeId" to epId)
                    )

                    for (params in paramSets) {
                        try {
                            val r2 = app.post("$mainUrl/ajax/player",
                                data = params, headers = aH, cookies = cookies, interceptor = cfKiller)
                            if (processPlayerResponse(r2.text ?: "", epUrl, vH, sub, cb)) return true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun processPlayerResponse(
        raw: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        if (raw.isBlank()) return false
        try {
            @Suppress("UNCHECKED_CAST")
            val j = mapper.readValue(raw, Map::class.java) as Map<String, Any?>
            val lnk = j["link"] ?: j["url"] ?: j["stream"] ?: j["file"]
            if (lnk is String && lnk.isNotBlank()) {
                if (lnk.startsWith("http") && emit(lnk, vH, sub, cb)) return true
                Crypto.decrypt(lnk)?.let { if (emit(it, vH, sub, cb)) return true }
            }
        } catch (_: Exception) {}

        if (tryUrlsFrom(raw, epUrl, vH, sub, cb)) return true
        return false
    }

    private suspend fun pageStrategy(
        body: String, epUrl: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.parse(body)
        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fixUrl(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            try { loadExtractor(src, epUrl, sub, cb); return true } catch (_: Exception) {}
        }
        for (el in doc.select("[data-file],[data-url],[data-source],[data-stream]")) {
            val raw = listOf("data-file","data-url","data-source","data-stream")
                .firstNotNullOfOrNull { a -> el.attr(a).takeIf { it.isNotBlank() } } ?: continue
            if (raw.startsWith("http") && emit(raw, vH, sub, cb)) return true
            Crypto.decrypt(raw)?.let { if (emit(it, vH, sub, cb)) return true }
        }
        return false
    }

    private suspend fun tryUrlsFrom(
        body: String, base: String, vH: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = mutableListOf<String>()
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""").findAll(body).forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://\S+\.m3u8\S*""").findAll(body).forEach { urls.add(it.value.trimEnd('"','\'',')')) }
        
        for (u in urls.map { absolute(it, base) }.distinct().filter { it.startsWith("http") }) {
            if (emit(u, vH, sub, cb)) return true
        }
        return false
    }

    private fun absolute(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { URL(URL(base), url).toString() } catch (_: Exception) { url }
    }

    private suspend fun emit(
        url: String, headers: Map<String, String>,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http")) return false
        val ref = headers["Referer"] ?: mainUrl
        log("EMIT", clean)
        
        // CRITICAL: Ensure headers are sent with the stream request to bypass server checks
        val streamHeaders = headers.toMutableMap().apply {
            put("Referer", ref)
            put("Origin", mainUrl)
            put("User-Agent", ua)
            put("Sec-Fetch-Dest", "video")
            put("Sec-Fetch-Mode", "no-cors")
            put("Sec-Fetch-Site", "cross-site")
        }

        return try {
            when {
                clean.contains(".m3u8") || clean.contains("storage.googleapiscdn.com") -> {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8; this.headers = streamHeaders
                    }); true
                }
                clean.contains(".mp4") || clean.contains(".mkv") || clean.contains(".webm") -> {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO; this.headers = streamHeaders
                    }); true
                }
                else -> try {
                    loadExtractor(clean, ref, sub, cb); true
                } catch (_: Exception) {
                    cb(newExtractorLink(name, name, clean) {
                        this.referer = ref; this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO; this.headers = streamHeaders
                    }); true
                }
            }
        } catch (e: Exception) { log("EMIT", "err: ${e.message}"); false }
    }
}
