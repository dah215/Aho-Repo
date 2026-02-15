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
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub", "animevsub",
        "VSub@2025", "VSub@2024", "streaming_key", "player_key", "api_key", "secret", ""
    )

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http") || s.startsWith("#EXTM3U")) return s
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (isStreamUrl(plain) || plain.startsWith("#EXTM3U")) return plain
        } catch (_: Exception) {}
        for (pass in PASSES) {
            val r = openSSL(s, pass) ?: continue
            if (isStreamUrl(r) || r.startsWith("#EXTM3U") || r.contains("googleapiscdn.com")) {
                println("AVS-CRYPTO ok pass='${pass.take(8)}..'")
                return r
            }
        }
        return null
    }

    fun decryptToUrl(enc: String?): String? {
        val r = decrypt(enc) ?: return null
        return r.takeIf { isStreamUrl(it) }
    }

    private fun openSSL(b64: String, pass: String): String? {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (raw.size < 16) return null
        val hasSalt = raw.copyOfRange(0, 8).contentEquals(SALTED)
        val salt = if (hasSalt) raw.copyOfRange(8, 16) else null
        val ct   = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
        if (ct.isEmpty()) return null
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
        if (ct.isEmpty()) return null
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

    fun isStreamUrl(s: String?): Boolean {
        if (s == null || s.length < 8 || !s.startsWith("http")) return false
        return s.contains("googleapiscdn.com") ||
               s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mpd") ||
               s.contains(".mkv") || s.contains(".webm") || s.contains("/hls/")
    }

    fun extractHexFromM3u8(content: String): String? =
        Regex("""/chunks/([0-9a-f]{24})/""").find(content)?.groupValues?.get(1)
}

class AnimeVietSub : MainAPI() {
    override var mainUrl  = "https://animevietsub.ee"
    override var name     = "AnimeVietSub"
    override var lang     = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf     = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private val CDN    = "storage.googleapiscdn.com"

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val pageH = mapOf(
        "User-Agent" to ua, "Accept" to "text/html,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9", "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none"
    )
    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua, "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref, "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9", "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin"
    )
    private val cdnH = mapOf("User-Agent" to ua)

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }
    private fun log(t: String, m: String) = println("AVS[$t] ${m.take(280)}")

    override val mainPage = mainPageOf(
        "$mainUrl/"                           to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )
    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val base = req.data.ifBlank { mainUrl }
        val url  = if (page == 1) base else "${base.removeSuffix("/")}/trang-$page.html"
        val doc  = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.item,.list-film li,.TPost")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }
    private fun Element.toSR(): SearchResponse? {
        val a   = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = (selectFirst(".Title,h3,h2,.title,.name")?.text()
                  ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }
    override suspend fun search(q: String): List<SearchResponse> =
        app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/",
            interceptor = cf, headers = pageH).document
            .select("article,.TPostMv,.item,.list-film li")
            .mapNotNull { it.toSR() }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc  = app.get(fUrl, interceptor = cf, headers = pageH).document
        val sel  = "ul.list-episode li a,.list-eps a,.server-list a,.list-episode a,.episodes a,#list_episodes a"
        var epNodes = doc.select(sel)
        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*='/tap-'],a[href*='/episode-'],.btn-watch a")
                ?.attr("href")?.let { fix(it) }?.let { wUrl ->
                    doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                    epNodes = doc.select(sel)
                }
        }
        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1)
                  ?: Regex("""-(\\d+)(?:\.html|/)?$""").find(fUrl)?.groupValues?.get(1) ?: ""
        val episodes = epNodes.mapNotNull { ep ->
            val href   = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataId = listOf(
                ep.attr("data-id"), ep.attr("data-episodeid"),
                ep.parent()?.attr("data-id") ?: "", ep.parent()?.attr("data-episodeid") ?: ""
            ).firstOrNull { it.matches(Regex("\\d+")) } ?: ""
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            newEpisode("$href@@$filmId@@$dataId") {
                name = nm; episode = Regex("\\d+").find(nm)?.value?.toIntOrNull()
            }
        }
        val title  = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image]")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }
        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts   = data.split("@@")
        val epUrl   = parts.getOrNull(0)?.takeIf { it.startsWith("http") } ?: return false
        val filmId  = parts.getOrNull(1) ?: ""
        val savedId = parts.getOrNull(2) ?: ""
        log("START", "url=$epUrl film=$filmId saved=$savedId")

        val pageRes = try { app.get(epUrl, interceptor = cf, headers = pageH) }
                      catch (e: Exception) { log("ERR", "page: ${e.message}"); return false }
        val body    = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()
        log("PAGE", "len=${body.length} cookies=${cookies.keys}")

        val ids = linkedSetOf<String>()
        if (savedId.isNotBlank()) ids.add(savedId)
        Jsoup.parse(body).select("[data-id]").forEach { el ->
            el.attr("data-id").filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        Regex("""(?:episodeId|episode_id|epId|filmEpisodeId)\s*[=:]\s*["']?(\d+)["']?""")
            .findAll(body).forEach { ids.add(it.groupValues[1]) }
        Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.let { ids.add(it) }
        Regex("""-(\\d+)-v\\d*""").find(epUrl)?.groupValues?.get(1)?.let { ids.add(it) }
        log("IDS", ids.joinToString(","))

        var found = withTimeoutOrNull(20_000L) {
            ajaxFlow(epUrl, filmId, ids.toList(), cookies, callback)
        } ?: false
        log("A", "found=$found")

        if (!found) found = withTimeoutOrNull(12_000L) {
            cdnProbeStrategy(body, epUrl, callback)
        } ?: false
        log("B", "found=$found")

        if (!found) found = withTimeoutOrNull(8_000L) {
            scrapeStrategy(body, epUrl, subtitleCallback, callback)
        } ?: false
        log("C", "found=$found")

        if (!found) found = withTimeoutOrNull(6_000L) {
            encMineStrategy(body, epUrl, callback)
        } ?: false
        log("D", "found=$found")

        log("DONE", "found=$found")
        return found
    }

    private suspend fun ajaxFlow(
        epUrl: String, filmId: String, ids: List<String>,
        cookies: MutableMap<String, String>, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val aH = ajaxH(epUrl)
        for (epId in ids.take(6)) {
            try {
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH, cookies = cookies, interceptor = cf)
                cookies.putAll(r1.cookies)
                val body1 = r1.text ?: continue
                log("S1", "id=$epId code=${r1.code} body=${body1.take(300)}")

                val j1 = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(body1, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    if (handleBody(body1, epUrl, filmId, cb)) return true; continue
                }

                val lnk = j1["link"] ?: j1["url"] ?: j1["stream"]
                if (lnk is String && lnk.isNotBlank()) {
                    val u = if (lnk.startsWith("http")) lnk else Crypto.decryptToUrl(lnk)
                    log("S1_LINK", u ?: "decrypt_failed")
                    if (u != null && emitStream(u, epUrl, cb)) return true
                }
                if (lnk is List<*>) {
                    for (item in lnk.filterIsInstance<Map<String, Any?>>()) {
                        val f = (item["file"] ?: item["src"] ?: item["url"])?.toString() ?: continue
                        if (resolveAndEmit(f, epUrl, cb)) return true
                    }
                }

                val htmlStr = j1["html"]?.toString()
                    ?: if (!body1.trimStart().startsWith("{")) body1 else ""
                if (htmlStr.isBlank()) continue

                val buttons = Jsoup.parse(htmlStr).select(
                    "a.btn3dsv,a[data-href],a[data-play],a[data-link]," +
                    ".server-item a,.btn-server,li[data-id] a,.episodes-btn a,button[data-href]"
                )
                log("S1_BTNS", "${buttons.size} buttons")

                if (buttons.isEmpty()) { if (handleBody(htmlStr, epUrl, filmId, cb)) return true; continue }

                for (btn in buttons) {
                    val hash = listOf("data-href", "data-link", "data-url")
                        .firstNotNullOfOrNull { btn.attr(it).trim().takeIf { v -> v.isNotBlank() && v != "#" } }
                        ?: run {
                            val h = btn.attr("href").trim()
                            if (h.startsWith("http") && emitStream(h, epUrl, cb)) return true
                            continue
                        }

                    if (hash.startsWith("http")) {
                        if (emitStream(hash, epUrl, cb)) return true; continue
                    }

                    val play  = btn.attr("data-play").trim()
                    val btnId = btn.attr("data-id").filter { it.isDigit() }.ifBlank { epId }
                    log("S2", "hash=${hash.take(50)} play=$play btnId=$btnId")

                    val paramSets = buildList {
                        if (filmId.isNotBlank()) add(mapOf("link" to hash, "id" to filmId))
                        add(mapOf("link" to hash, "id" to epId, "backup" to "1"))
                        add(mapOf("link" to hash, "play" to play.ifBlank { "api" }, "id" to filmId.ifBlank { epId }, "backup" to "1"))
                        add(mapOf("link" to hash, "id" to btnId, "backup" to "1"))
                    }

                    for (params in paramSets) {
                        try {
                            val r2 = app.post("$mainUrl/ajax/player",
                                data = params, headers = aH, cookies = cookies, interceptor = cf)
                            cookies.putAll(r2.cookies)
                            val body2 = r2.text ?: continue
                            log("S2_RESP", "code=${r2.code} body=${body2.take(300)}")
                            if (handleBody(body2, epUrl, filmId, cb)) return true
                        } catch (e: Exception) { log("S2_ERR", e.message ?: "") }
                    }
                }
            } catch (e: Exception) { log("AJAX_ERR", "id=$epId ${e.message}") }
        }
        return false
    }

    private suspend fun resolveAndEmit(f: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (f.startsWith("http")) return emitStream(f, ref, cb)
        val dec = Crypto.decrypt(f) ?: return false
        log("RESOLVE", dec.take(120))
        return when {
            dec.startsWith("http") -> emitStream(dec, ref, cb)
            dec.contains("#EXTM3U") -> {
                val hexId = Crypto.extractHexFromM3u8(dec)
                log("M3U8_CONTENT", "hexId=$hexId")
                if (hexId != null) probeCdn(hexId, ref, cb) else false
            }
            else -> false
        }
    }

    private suspend fun handleBody(body: String, ref: String, filmId: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (body.isBlank()) return false

        val cdnUrl = extractCdnUrl(body)
        if (cdnUrl != null) { log("HDL_CDN", cdnUrl); if (emitStream(cdnUrl, ref, cb)) return true }

        for (u in extractDirectUrls(body)) { if (emitStream(u, ref, cb)) return true }

        try {
            @Suppress("UNCHECKED_CAST")
            val j = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            for (key in listOf("link", "url", "stream", "src", "file", "m3u8")) {
                val v = j[key] ?: continue
                when {
                    v is String && v.startsWith("http") -> if (emitStream(v, ref, cb)) return true
                    v is String && v.isNotBlank() -> {
                        if (resolveAndEmit(v, ref, cb)) return true
                    }
                    v is List<*> -> for (item in v.filterIsInstance<Map<String, Any?>>()) {
                        val f = (item["file"] ?: item["src"] ?: item["url"])?.toString() ?: continue
                        if (resolveAndEmit(f, ref, cb)) return true
                    }
                }
            }
        } catch (_: Exception) {}

        if (body.length > 20 && body.trimStart().first().let { it != '{' && it != '[' && it != '<' }) {
            val dec = Crypto.decrypt(body.trim())
            log("HDL_BODY_DEC", dec?.take(120) ?: "null")
            if (dec != null) {
                when {
                    dec.startsWith("http") && Crypto.isStreamUrl(dec) -> if (emitStream(dec, ref, cb)) return true
                    dec.contains("#EXTM3U") -> {
                        val hexId = Crypto.extractHexFromM3u8(dec)
                        if (hexId != null && probeCdn(hexId, ref, cb)) return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun probeCdn(hexId: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        log("PROBE_CDN", hexId)
        for (suffix in listOf("index.m3u8", "playlist.m3u8", "master.m3u8")) {
            val url = "https://$CDN/chunks/$hexId/original/$suffix"
            try {
                val r = app.get(url, headers = cdnH, interceptor = cf)
                val text = r.text ?: continue
                if (r.code == 200 && text.contains("#EXTM3U")) {
                    log("PROBE_HIT", url)
                    return emitVerifiedM3u8(url, ref, cb)
                }
            } catch (_: Exception) {}
        }
        log("PROBE_MISS", hexId)
        return false
    }

    private suspend fun cdnProbeStrategy(body: String, epUrl: String, cb: (ExtractorLink) -> Unit): Boolean {
        val hexIds = linkedSetOf<String>()
        try {
            Regex("""[0-9a-f]{24}""").findAll(
                Jsoup.parse(body).select("script").joinToString("\n") { it.html() }
            ).forEach { hexIds.add(it.value) }
        } catch (_: Exception) {}
        Regex("""[0-9a-f]{24}""").findAll(body).forEach { hexIds.add(it.value) }
        val unique = hexIds.take(8).toList()
        log("CDN_PROBE", "hexIds=$unique")

        for (hexId in unique) {
            if (probeCdn(hexId, epUrl, cb)) return true
        }
        return false
    }

    private suspend fun scrapeStrategy(
        body: String, epUrl: String,
        sub: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }
        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fix(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            if (!src.startsWith("http")) continue
            val isKnownHost = listOf(
                "doodstream", "streamtape", "mixdrop", "upstream", "vidcloud",
                "filemoon", "vidplay", "vidsrc", "embed", "player", "cdn"
            ).any { src.contains(it, ignoreCase = true) }
            if (!isKnownHost) continue
            try { loadExtractor(src, epUrl, sub, cb); return true } catch (_: Exception) {}
        }
        for (el in doc.select("video source[src],video[src]")) {
            val src = fix(el.attr("src")) ?: continue
            if (Crypto.isStreamUrl(src) && emitStream(src, epUrl, cb)) return true
        }
        for (el in doc.select("[data-file],[data-url],[data-source],[data-stream]")) {
            val raw = listOf("data-file", "data-url", "data-source", "data-stream")
                .firstNotNullOfOrNull { el.attr(it).takeIf { v -> v.isNotBlank() } } ?: continue
            if (resolveAndEmit(raw, epUrl, cb)) return true
        }
        return false
    }

    private suspend fun encMineStrategy(body: String, epUrl: String, cb: (ExtractorLink) -> Unit): Boolean {
        for (m in Regex("""(?:file|link|source|url|enc|m3u8)\s*[:=]\s*["']([A-Za-z0-9+/=]{40,})["']""").findAll(body)) {
            if (resolveAndEmit(m.groupValues[1], epUrl, cb)) return true
        }
        return false
    }

    private fun extractCdnUrl(body: String): String? {
        Regex("""https?://storage\.googleapiscdn\.com/chunks/([0-9a-f]{24})/[^\s"'<>\\]+""")
            .find(body)?.let { m ->
                val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(m.value)
                    ?.groupValues?.get(1) ?: return null
                return if (m.value.contains(".m3u8")) m.value
                       else "https://$CDN/chunks/$hexId/original/index.m3u8"
            }
        return null
    }

    private fun extractDirectUrls(body: String): List<String> {
        val urls = linkedSetOf<String>()
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd|mkv|webm)[^"']*)["']""")
            .findAll(body).forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://\S+\.m3u8[^\s"'<>\\]*""")
            .findAll(body).forEach { urls.add(it.value.trimEnd('"', '\'', ')', '\\')) }
        return urls.toList()
    }

    private suspend fun emitStream(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false
        log("EMIT", url.take(120))
        return when {
            url.contains(CDN) -> {
                val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
                val m3u8 = if (hexId != null) "https://$CDN/chunks/$hexId/original/index.m3u8"
                           else if (url.contains(".m3u8")) url
                           else return false
                emitVerifiedM3u8(m3u8, ref, cb)
            }
            url.contains(".m3u8") || url.contains("/hls/") -> emitVerifiedM3u8(url, ref, cb)
            url.contains(".mp4") || url.contains(".mkv") || url.contains(".webm") ->
                emitVideo(url, ref, cb)
            else -> false
        }
    }

    private suspend fun emitVerifiedM3u8(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        return try {
            val r = app.get(url, headers = cdnH, interceptor = cf)
            val text = r.text ?: return false
            if (r.code != 200 || !text.contains("#EXTM3U")) {
                log("VERIFY_FAIL", "code=${r.code} url=$url")
                return false
            }
            log("VERIFY_OK", url)
            cb(newExtractorLink(name, name, url) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.type    = ExtractorLinkType.M3U8
                this.headers = cdnH
            })
            true
        } catch (e: Exception) { log("VERIFY_ERR", "${e.message}"); false }
    }

    private suspend fun emitVideo(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        return try {
            cb(newExtractorLink(name, name, url) {
                this.referer = ref; this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO; this.headers = cdnH
            }); true
        } catch (e: Exception) { log("EMIT_ERR", "${e.message}"); false }
    }
}
