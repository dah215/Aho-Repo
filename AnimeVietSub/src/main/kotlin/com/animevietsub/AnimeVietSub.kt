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
        "animevietsub",
        "animevsub",
        "VSub@2025",
        "VSub@2024",
        "streaming_key",
        "player_key",
        "api_key",
        "secret",
        ""
    )

    fun decryptToUrl(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        val s = enc.trim()
        if (s.startsWith("http")) return s.takeIf { isStreamUrl(it) }
        try {
            val plain = String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (isStreamUrl(plain)) return plain
        } catch (_: Exception) {}
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
        val ct = if (hasSalt) raw.copyOfRange(16, raw.size) else raw
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

    fun isStreamUrl(s: String?): Boolean {
        if (s == null || s.length < 8 || !s.startsWith("http")) return false
        return s.contains("googleapiscdn.com") ||
               s.contains(".m3u8") || s.contains(".mp4") || s.contains(".mpd") ||
               s.contains(".mkv") || s.contains(".webm") || s.contains("/hls/")
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
    private val CDN = "storage.googleapiscdn.com"

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val pageH = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Accept-Encoding" to "gzip, deflate, br",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "vi-VN,vi;q=0.9",
        "Accept-Encoding" to "gzip, deflate, br",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    private val cdnH = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Origin" to "https://animevietsub.ee"
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
        
        val items = doc.select("article.TPost, .TPostMv, .item, .list-film li, .TPost, .movie-item, .film-item, [class*='item'], .owl-item").mapNotNull { 
            it.toSR() 
        }.distinctBy { it.url }
        
        if (items.isEmpty()) {
            val fallbackItems = doc.select("a[href*='/phim/'], a[href*='/xem-phim/']").mapNotNull { a ->
                val href = fix(a.attr("href")) ?: return@mapNotNull null
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (title.isBlank()) return@mapNotNull null
                val img = a.selectFirst("img")
                val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
                newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
            }.distinctBy { it.url }
            return newHomePageResponse(req.name, fallbackItems, hasNext = fallbackItems.isNotEmpty())
        }
        
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = (selectFirst(".Title, .name, h3, h2, .title, .film-name")?.text()?.trim()
            ?: a.attr("title").trim()
            ?: a.text().trim()).ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/"
        return app.get(url, interceptor = cf, headers = pageH).document
            .select("article, .TPostMv, .item, .list-film li, .TPost, .search-item")
            .mapNotNull { it.toSR() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageH).document
        
        val sel = "ul.list-episode li a, .list-eps a, .server-list a, .list-episode a, .episodes a, #list_episodes a, .btn-episode, a[href*='/tap-']"
        var epNodes = doc.select(sel)
        
        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see, a[href*='/tap-'], a[href*='/episode-'], .btn-watch a, .watch-btn a")?.attr("href")?.let { fix(it) }?.let { wUrl ->
                doc = app.get(wUrl, interceptor = cf, headers = pageH).document
                epNodes = doc.select(sel)
            }
        }
        
        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1)
            ?: Regex("""-(\d+)(?:\.html|/)?$""").find(fUrl)?.groupValues?.get(1)
            ?: doc.selectFirst("[data-filmid], .film-id")?.attr("data-filmid")
            ?: ""

        val episodes = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val dataId = listOf(
                ep.attr("data-id"),
                ep.attr("data-episodeid"),
                ep.attr("data-episode-id"),
                ep.parent()?.attr("data-id"),
                ep.parent()?.attr("data-episodeid")
            ).firstOrNull { !it.isNullOrBlank() && it.matches(Regex("\\d+")) } ?: ""
            
            val nm = ep.text().trim().ifBlank { ep.attr("title").trim() }
            val epNum = Regex("""\d+""").find(nm)?.value?.toIntOrNull() ?: ep.attr("data-id").toIntOrNull()
            val dataHash = ep.attr("data-hash")
            
            newEpisode("$href@@$filmId@@$dataId@@$dataHash") {
                name = nm
                episode = epNum
            }
        }

        val title = doc.selectFirst("h1.Title, h1, .Title, .film-title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image], .film-poster img")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val plot = doc.selectFirst(".Description, .InfoDesc, #film-content, .film-description")?.text()?.trim()

        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
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
        val epUrl = parts.getOrNull(0)?.takeIf { it.startsWith("http") } ?: return false
        val filmId = parts.getOrNull(1) ?: ""
        val savedId = parts.getOrNull(2) ?: ""
        val savedHash = parts.getOrNull(3) ?: ""

        val pageRes = try {
            app.get(epUrl, interceptor = cf, headers = pageH)
        } catch (e: Exception) {
            return false
        }
        
        val body = pageRes.text ?: return false
        val cookies = pageRes.cookies.toMutableMap()

        val jsEpId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\(['"](\d+)['"]\)""").find(body)?.groupValues?.get(1)
        val jsFilmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\(['"](\d+)['"]\)""").find(body)?.groupValues?.get(1)

        val ids = linkedSetOf<String>()
        if (savedId.isNotBlank()) ids.add(savedId)
        if (jsEpId != null) ids.add(jsEpId)
        
        Jsoup.parse(body).select("[data-id], [data-episode-id]").forEach { el ->
            listOf("data-id", "data-episode-id", "data-episodeid").forEach { attr ->
                el.attr(attr).filter { it.isDigit() }.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
        }
        
        Regex("""tap-(\d+)|episode-(\d+)|-(\d+)(?:\.html|/)""").find(epUrl)?.let { m ->
            (1..3).mapNotNull { m.groupValues.getOrNull(it) }.filter { it.isNotBlank() }.forEach { ids.add(it) }
        }

        val finalFilmId = jsFilmId ?: filmId

        var found = withTimeoutOrNull(25000L) {
            ajaxFlow(epUrl, finalFilmId, ids.toList(), savedHash, cookies, callback)
        } ?: false

        if (!found) {
            found = withTimeoutOrNull(15000L) {
                cdnProbeStrategy(body, epUrl, callback)
            } ?: false
        }

        if (!found) {
            found = withTimeoutOrNull(10000L) {
                directMineStrategy(body, epUrl, callback)
            } ?: false
        }

        return found
    }

    private suspend fun ajaxFlow(
        epUrl: String,
        filmId: String,
        ids: List<String>,
        savedHash: String,
        cookies: MutableMap<String, String>,
        cb: (ExtractorLink) -> Unit
    ): Boolean {
        val aH = ajaxH(epUrl)

        for (epId in ids.take(5)) {
            try {
                val r1 = app.post(
                    "$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = aH,
                    cookies = cookies,
                    interceptor = cf
                )
                cookies.putAll(r1.cookies)
                val body1 = r1.text ?: continue

                val json1 = try {
                    @Suppress("UNCHECKED_CAST")
                    mapper.readValue(body1, Map::class.java) as Map<String, Any?>
                } catch (e: Exception) {
                    if (handleDirectResponse(body1, epUrl, cb)) return true
                    continue
                }

                val directLink = json1["link"] ?: json1["url"] ?: json1["stream"] ?: json1["file"]
                if (directLink is String && directLink.isNotBlank()) {
                    val decrypted = if (directLink.startsWith("http")) directLink else Crypto.decryptToUrl(directLink)
                    if (decrypted != null && emitStream(decrypted, epUrl, cb)) return true
                }

                if (directLink is List<*>) {
                    for (item in directLink.filterIsInstance<Map<String, Any?>>()) {
                        val file = (item["file"] ?: item["src"] ?: item["url"] ?: item["link"])?.toString() ?: continue
                        val decrypted = if (file.startsWith("http")) file else Crypto.decryptToUrl(file) ?: continue
                        if (emitStream(decrypted, epUrl, cb)) return true
                    }
                }

                val htmlStr = json1["html"]?.toString() ?: ""
                if (htmlStr.isBlank()) continue

                val doc = Jsoup.parse(htmlStr)
                val buttons = doc.select("a.btn3dsv, a[data-href], a[data-play], a[data-link], .server-item a, .btn-server, li[data-id] a, .episodes-btn a, button[data-href]")

                if (buttons.isEmpty()) {
                    if (handleDirectResponse(htmlStr, epUrl, cb)) return true
                    continue
                }

                for (btn in buttons) {
                    val hash = btn.attr("data-href").trim()
                        .ifBlank { btn.attr("data-link").trim() }
                        .ifBlank { btn.attr("data-url").trim() }

                    if (hash.isBlank() || hash == "#") {
                        val href = btn.attr("href").trim()
                        if (href.startsWith("http") && emitStream(href, epUrl, cb)) return true
                        continue
                    }

                    if (hash.startsWith("http")) {
                        if (emitStream(hash, epUrl, cb)) return true
                        continue
                    }

                    val play = btn.attr("data-play").trim()
                    val serverId = btn.attr("data-id").trim()

                    val payloads = listOf(
                        mapOf("link" to hash, "id" to filmId, "play" to play, "backup" to "1"),
                        mapOf("link" to hash, "id" to filmId, "backup" to "1"),
                        mapOf("link" to hash, "play" to play, "id" to serverId, "backup" to "1"),
                        mapOf("link" to hash, "id" to epId, "backup" to "1"),
                        mapOf("hash" to hash, "id" to filmId, "backup" to "1"),
                        mapOf("link" to hash, "id" to filmId)
                    )

                    for (payload in payloads) {
                        try {
                            val r2 = app.post(
                                "$mainUrl/ajax/player",
                                data = payload,
                                headers = aH,
                                cookies = cookies,
                                interceptor = cf
                            )
                            cookies.putAll(r2.cookies)
                            val body2 = r2.text ?: continue

                            if (handlePlayerResponse(body2, epUrl, cb)) return true
                        } catch (e: Exception) {
                            continue
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return false
    }

    private suspend fun handlePlayerResponse(body: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (body.isBlank()) return false

        val json = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(body, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            null
        }

        if (json != null) {
            for (key in listOf("link", "url", "stream", "src", "file", "m3u8", "video")) {
                val value = json[key] ?: continue

                when (value) {
                    is String -> {
                        val url = if (value.startsWith("http")) value else Crypto.decryptToUrl(value)
                        if (url != null && emitStream(url, ref, cb)) return true
                    }
                    is List<*> -> {
                        for (item in value.filterIsInstance<Map<String, Any?>>()) {
                            val file = (item["file"] ?: item["src"] ?: item["url"] ?: item["link"] ?: item["m3u8"])?.toString() ?: continue
                            val url = if (file.startsWith("http")) file else Crypto.decryptToUrl(file) ?: continue
                            if (emitStream(url, ref, cb)) return true
                        }
                    }
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = value as Map<String, Any?>
                        val file = (map["file"] ?: map["src"] ?: map["url"] ?: map["link"] ?: map["m3u8"])?.toString() ?: continue
                        val url = if (file.startsWith("http")) file else Crypto.decryptToUrl(file) ?: continue
                        if (emitStream(url, ref, cb)) return true
                    }
                }
            }
        }

        return handleDirectResponse(body, ref, cb)
    }

    private suspend fun handleDirectResponse(body: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        Regex("""https?://storage\.googleapiscdn\.com/[^\s"'<>]+""").findAll(body).forEach { m ->
            if (emitStream(m.value, ref, cb)) return true
        }

        Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""").findAll(body).forEach { m ->
            if (emitStream(m.groupValues[1], ref, cb)) return true
        }

        Regex("""["']([A-Za-z0-9+/=]{100,})["']""").findAll(body).forEach { m ->
            val decrypted = Crypto.decryptToUrl(m.groupValues[1])
            if (decrypted != null && emitStream(decrypted, ref, cb)) return true
        }

        return false
    }

    private suspend fun cdnProbeStrategy(body: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        val hexIds = linkedSetOf<String>()

        Jsoup.parse(body).select("script").forEach { script ->
            Regex("""[0-9a-f]{24}""").findAll(script.html()).forEach { hexIds.add(it.value) }
        }

        Regex("""[0-9a-f]{24}""").findAll(body).forEach { hexIds.add(it.value) }

        for (hexId in hexIds.take(10)) {
            for (suffix in listOf("index.m3u8", "playlist.m3u8", "master.m3u8", "video.m3u8")) {
                val url = "https://$CDN/chunks/$hexId/original/$suffix"
                try {
                    val r = app.get(url, headers = cdnH, interceptor = cf, timeout = 8000)
                    val text = r.text ?: continue

                    if (r.code == 200 && text.contains("#EXTM3U")) {
                        if (emitVerifiedM3u8(url, ref, cb)) return true
                    }
                } catch (_: Exception) {}
            }
        }

        return false
    }

    private suspend fun directMineStrategy(body: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        val patterns = listOf(
            Regex("""(?:file|link|source|url|src)\s*[:=]\s*["']([A-Za-z0-9+/=]{40,})["']"""),
            Regex("""(?:var|let|const)\s+\w+\s*=\s*["']([A-Za-z0-9+/=]{40,})["']"""),
            Regex("""atob\(["']([A-Za-z0-9+/=]{40,})["']"""),
            Regex("""decodeURIComponent\(["']([A-Za-z0-9+/=]{40,})["']""")
        )

        for (pattern in patterns) {
            pattern.findAll(body).forEach { m ->
                val decrypted = Crypto.decryptToUrl(m.groupValues[1])
                if (decrypted != null && emitStream(decrypted, ref, cb)) return true
            }
        }

        return false
    }

    private suspend fun emitStream(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) {
            val decrypted = Crypto.decryptToUrl(url) ?: return false
            return emitStream(decrypted, ref, cb)
        }

        return when {
            url.contains(".m3u8") || url.contains("/hls/") -> emitVerifiedM3u8(url, ref, cb)
            url.contains(CDN) -> {
                val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
                if (hexId != null) {
                    val m3u8 = "https://$CDN/chunks/$hexId/original/index.m3u8"
                    emitVerifiedM3u8(m3u8, ref, cb)
                } else false
            }
            url.contains(".mp4") || url.contains(".mkv") || url.contains(".webm") -> emitVideo(url, ref, cb)
            else -> emitVerifiedM3u8(url, ref, cb)
        }
    }

    private suspend fun emitVerifiedM3u8(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        return try {
            val r = app.get(url, headers = cdnH, interceptor = cf, timeout = 10000)
            val text = r.text

            if (r.code != 200 || text == null || !text.contains("#EXTM3U")) {
                return false
            }

            cb(newExtractorLink(name, "AnimeVietSub", url) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = cdnH
            })
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun emitVideo(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        return try {
            cb(newExtractorLink(name, "AnimeVietSub", url) {
                this.referer = ref
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO
                this.headers = cdnH
            })
            true
        } catch (e: Exception) {
            false
        }
    }
}
