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
    private val PASSES = listOf(
        "dm_thang_suc_vat_get_link_an_dbt",
        "animevietsub", "animevsub",
        "VSub@2025", "VSub@2024", "streaming_key", "player_key", "api_key", "secret", ""
    )

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http") || s.startsWith("#EXTM3U") || s.startsWith("{") || s.startsWith("[")) return s
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (plain.startsWith("http") || plain.startsWith("#EXTM3U") || plain.startsWith("{") || plain.startsWith("[")) return plain
        } catch (_: Exception) {}
        for (pass in PASSES) {
            val r = openSSL(s, pass) ?: continue
            if (r.startsWith("http") || r.startsWith("#EXTM3U") || r.startsWith("{") || r.startsWith("[")) {
                println("AVS-CRYPTO ok pass='${pass.take(8)}..' result='${r.take(50)}...'")
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
        if (ct.isEmpty()) return null
        val (key, iv) = evpKDF(pass.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return aesCbc(ct, key, iv)
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
        if (ct.isEmpty()) return null
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
    override var mainUrl  = "https://animevietsub.ee"
    override var name     = "AnimeVietSub"
    override var lang     = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf     = CloudflareKiller()
    private val mapper = jacksonObjectMapper()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                     "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9"
    )

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    private val streamH = mapOf("User-Agent" to ua)

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u.startsWith("javascript") || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//")   -> "https:${u.trim()}"
            u.startsWith("/")    -> "$mainUrl${u.trim()}"
            else                 -> "$mainUrl/$u"
        }
    }

    private fun log(t: String, m: String) = println("AVS[$t] ${m.take(300)}")

    // ==================== MAIN PAGE ====================
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
        val items = doc.select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
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

    // ==================== SEARCH ====================
    override suspend fun search(q: String): List<SearchResponse> =
        app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/",
            interceptor = cf, headers = pageH).document
            .select("article,.TPostMv,.TPost,.item,.list-film li,figure.Objf,.movie-item")
            .mapNotNull { it.toSR() }.distinctBy { it.url }

    // ==================== LOAD ====================
    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc  = app.get(fUrl, interceptor = cf, headers = pageH).document

        val sel  = ".btn-episode,.episode-link,a[data-id][data-hash],ul.list-episode li a,.list-eps a,.server-list a,.list-episode a,.episodes a,#list_episodes a"
        var epNodes = doc.select(sel)

        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see,a[href*='/tap-'],a[href*='/episode-'],.btn-watch a")
                ?.attr("href")?.let { fix(it) }?.let { wUrl ->
                    doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                    epNodes = doc.select(sel)
                }
        }

        val filmId = Regex("""[/-]a(\d+)(?:[/-]|\.html)?""").find(fUrl)?.groupValues?.get(1)
                  ?: Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1)
                  ?: Regex("""-(\d+)(?:\.html|/)?$""").find(fUrl)?.groupValues?.get(1) ?: ""

        val episodes = epNodes.mapNotNull { ep ->
            val href   = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataId = listOf(
                ep.attr("data-id"),
                ep.attr("data-episodeid"),
                ep.parent()?.attr("data-id") ?: "",
                ep.parent()?.attr("data-episodeid") ?: ""
            ).firstOrNull { it.matches(Regex("\\d+")) } ?: ""
            val dataHash = ep.attr("data-hash").trim().takeIf { it.isNotBlank() } ?: ""
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            newEpisode("$href@@$filmId@@$dataId@@$dataHash") {
                name = nm
                episode = Regex("\\d+").find(nm)?.value?.toIntOrNull()
            }
        }

        val title  = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image],.MovieThumb img,.poster img,figure.Objf img,.wp-post-image")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl     = parts.getOrNull(0)?.takeIf { it.startsWith("http") } ?: return false
        val filmId    = parts.getOrNull(1) ?: ""
        val savedId   = parts.getOrNull(2) ?: ""
        val savedHash = parts.getOrNull(3) ?: ""

        log("START", "url=$epUrl film=$filmId savedId=$savedId savedHash=${savedHash.take(40)}...")

        // PRIORITY: Direct hash POST
        if (savedHash.isNotBlank() && filmId.isNotBlank()) {
            log("DIRECT_HASH", "POST link=$savedHash&id=$filmId")
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("link" to savedHash, "id" to filmId),
                    headers = ajaxH(epUrl),
                    interceptor = cf
                )
                val body = r.text ?: ""
                log("DIRECT_RESP", "code=${r.code} body=${body.take(200)}")
                if (handleApiResponse(body, epUrl, callback)) {
                    log("DIRECT_OK", "Success!")
                    return true
                }
            } catch (e: Exception) {
                log("DIRECT_ERR", e.message ?: "")
            }
        }

        // FALLBACK: Old flow
        val pageRes = try { app.get(epUrl, interceptor = cf, headers = pageH) }
            catch (e: Exception) { log("ERR", "page: ${e.message}"); return false }
        val body = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()

        val ids = linkedSetOf<String>()
        if (savedId.isNotBlank()) ids.add(savedId)
        Jsoup.parse(body).select("[data-id]").forEach { el ->
            el.attr("data-id").filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        Regex("""(?:episodeId|episode_id|epId|filmEpisodeId)\s*[=:]\s*["']?(\d+)["']?""")
            .findAll(body).forEach { ids.add(it.groupValues[1]) }
        Regex("""tap-(\d+)""").find(epUrl)?.groupValues?.get(1)?.let { ids.add(it) }
        log("IDS", ids.joinToString(","))

        val found = ajaxFlow(epUrl, filmId, ids.toList(), cookies, callback)
        log("AJAX", "found=$found")

        if (!found) scrapeStrategy(body, epUrl, subtitleCallback, callback)
        log("DONE", "done")
        return found
    }

    // ==================== AJAX FLOW ====================
    private suspend fun ajaxFlow(
        epUrl: String,
        filmId: String,
        ids: List<String>,
        cookies: MutableMap<String, String>,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        val aH = ajaxH(epUrl)

        for (epId in ids.take(6)) {
            try {
                val r1 = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH,
                    cookies = cookies,
                    interceptor = cf
                )
                cookies.putAll(r1.cookies)
                val body1 = r1.text ?: continue
                log("S1", "id=$epId code=${r1.code} len=${body1.length}")

                val j1 = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(body1, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) {
                    if (handleApiResponse(body1, epUrl, cb)) return true
                    continue
                }

                val lnk = j1["link"] ?: j1["url"] ?: j1["stream"]
                if (lnk is String && lnk.isNotBlank()) {
                    if (resolveAndEmit(lnk, epUrl, cb)) return true
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

                if (buttons.isEmpty()) {
                    if (handleApiResponse(htmlStr, epUrl, cb)) return true
                    continue
                }

                for (btn in buttons) {
                    val hash = listOf("data-href", "data-link", "data-url")
                        .firstNotNullOfOrNull { btn.attr(it).trim().takeIf { v -> v.isNotBlank() && v != "#" } }
                        ?: run {
                            val h = btn.attr("href").trim()
                            if (h.startsWith("http") && emitStream(h, epUrl, cb)) return true
                            continue
                        }

                    if (hash.startsWith("http")) {
                        if (emitStream(hash, epUrl, cb)) return true
                        continue
                    }

                    val btnId = btn.attr("data-id").filter { it.isDigit() }.ifBlank { "0" }
                    log("S2", "hash=${hash.take(50)} btnId=$btnId filmId=$filmId")

                    val paramSets = buildList {
                        if (filmId.isNotBlank()) add(mapOf("link" to hash, "id" to filmId))
                        add(mapOf("link" to hash, "id" to epId))
                        if (btnId != "0" && btnId != epId && btnId != filmId)
                            add(mapOf("link" to hash, "id" to btnId))
                    }

                    for (params in paramSets) {
                        try {
                            val r2 = app.post("$mainUrl/ajax/player",
                                data = params,
                                headers = aH,
                                cookies = cookies,
                                interceptor = cf
                            )
                            cookies.putAll(r2.cookies)
                            val body2 = r2.text ?: continue
                            log("S2_RESP", "code=${r2.code} body=${body2.take(200)}")
                            if (handleApiResponse(body2, epUrl, cb)) return true
                        } catch (e: Exception) {
                            log("S2_ERR", e.message ?: "")
                        }
                    }
                }
            } catch (e: Exception) {
                log("AJAX_ERR", "id=$epId ${e.message}")
            }
        }
        return false
    }

    // ==================== HANDLE API RESPONSE ====================
    private suspend fun handleApiResponse(body: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (body.isBlank()) return false

        for (u in extractDirectUrls(body)) {
            if (emitStream(u, ref, cb)) return true
        }

        val j = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(body, Map::class.java) as Map<String, Any?>
        } catch (_: Exception) { return false }

        val linkArray = j["link"] as? List<*>
        if (linkArray != null) {
            for (item in linkArray.filterIsInstance<Map<String, Any?>>()) {
                val file = (item["file"] ?: item["src"] ?: item["url"])?.toString() ?: continue
                log("LINK_ITEM", "file=${file.take(60)}")
                if (resolveAndEmit(file, ref, cb)) return true
            }
        }

        for (key in listOf("url", "stream", "src", "file", "m3u8")) {
            val v = j[key] ?: continue
            when {
                v is String && v.startsWith("http") -> if (emitStream(v, ref, cb)) return true
                v is String && v.isNotBlank() -> if (resolveAndEmit(v, ref, cb)) return true
            }
        }
        return false
    }

    // ==================== RESOLVE AND EMIT ====================
    private suspend fun resolveAndEmit(raw: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (raw.isBlank()) return false
        if (raw.startsWith("http")) return emitStream(raw, ref, cb)

        val dec = Crypto.decrypt(raw) ?: return false
        log("RESOLVE", "dec=${dec.take(100)}")

        return when {
            dec.startsWith("http") -> emitStream(dec, ref, cb)

            dec.trimStart().startsWith("{") -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val json = mapper.readValue(dec, Map::class.java) as Map<String, Any?>
                    val sources = json["sources"] as? List<*>
                    if (sources != null) {
                        for (src in sources.filterIsInstance<Map<String, Any?>>()) {
                            val file = (src["file"] ?: src["url"])?.toString() ?: continue
                            val label = src["label"]?.toString() ?: ""
                            log("JSON_SOURCE", "file=${file.take(60)} label=$label")
                            if (emitStream(file, ref, cb, label)) return true
                        }
                    }
                    false
                } catch (_: Exception) { false }
            }

            dec.trimStart().startsWith("[") -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val arr = mapper.readValue(dec, List::class.java) as List<*>
                    for (src in arr.filterIsInstance<Map<String, Any?>>()) {
                        val file = (src["file"] ?: src["url"])?.toString() ?: continue
                        val label = src["label"]?.toString() ?: ""
                        log("JSON_ARRAY", "file=${file.take(60)} label=$label")
                        if (emitStream(file, ref, cb, label)) return true
                    }
                    false
                } catch (_: Exception) { false }
            }

            dec.contains("#EXTM3U") -> {
                val urlMatch = Regex("""https?://[^\s#"']+""").find(dec)
                if (urlMatch != null && emitStream(urlMatch.value, ref, cb)) return true
                false
            }

            else -> false
        }
    }

    // ==================== EMIT STREAM ====================
    private suspend fun emitStream(url: String, ref: String, cb: (ExtractorLink) -> Unit, label: String = ""): Boolean {
        if (!url.startsWith("http")) return false
        log("EMIT", "url=${url.take(100)} label=$label")

        val isHls = url.contains(".m3u8", ignoreCase = true) ||
                    url.contains("/hls/", ignoreCase = true) ||
                    url.contains("m3u8.", ignoreCase = true)

        val quality = when {
            label.contains("1080", ignoreCase = true) -> 1080
            label.contains("720", ignoreCase = true) -> 720
            label.contains("480", ignoreCase = true) -> 480
            label.contains("360", ignoreCase = true) -> 360
            else -> Qualities.Unknown.value
        }

        val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val displayName = if (label.isNotBlank()) "$name $label" else name

        cb(newExtractorLink(name, displayName, url) {
            referer = ref
            this.quality = quality
            type = linkType
            headers = streamH
        })
        return true
    }

    // ==================== SCRAPE STRATEGY ====================
    private suspend fun scrapeStrategy(
        body: String,
        epUrl: String,
        sub: (SubtitleFile) -> Unit,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try { Jsoup.parse(body) } catch (_: Exception) { return false }

        for (el in doc.select("iframe[src],iframe[data-src]")) {
            val src = fix(el.attr("src").ifBlank { el.attr("data-src") }) ?: continue
            if (!src.startsWith("http")) continue
            val isKnown = listOf("doodstream", "streamtape", "mixdrop", "upstream",
                "vidcloud", "filemoon", "vidplay", "vidsrc", "embed", "player", "cdn")
                .any { src.contains(it, ignoreCase = true) }
            if (!isKnown) continue
            try {
                loadExtractor(src, epUrl, sub, cb)
                return true
            } catch (_: Exception) {}
        }

        for (el in doc.select("video source[src],video[src]")) {
            val src = fix(el.attr("src")) ?: continue
            if (emitStream(src, epUrl, cb)) return true
        }
        return false
    }

    // ==================== EXTRACT DIRECT URLS ====================
    private fun extractDirectUrls(body: String): List<String> {
        val urls = linkedSetOf<String>()
        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd|mkv|webm)[^"']*)["']""")
            .findAll(body).forEach { urls.add(it.groupValues[1]) }
        Regex("""https?://\S+\.m3u8[^\s"'<>\\]*""")
            .findAll(body).forEach { urls.add(it.value.trimEnd('"', '\'', ')', '\\')) }
        return urls.toList()
    }
}
