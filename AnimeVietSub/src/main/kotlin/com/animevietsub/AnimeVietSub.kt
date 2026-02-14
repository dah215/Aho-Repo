package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeVietSub())
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name   = "AnimeVietSub"
    override var lang   = "vi"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val DECODE_PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"
    }

    private val cfKiller = CloudflareKiller()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to ua,
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Priority"   to "u=0, i",
        "Sec-Ch-Ua"  to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/"                           to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.ifBlank { mainUrl }
        val url = if (page == 1) data else "${data.removeSuffix("/")}/trang-$page.html"

        val fixedUrl = fixUrl(url) ?: return newHomePageResponse(request.name, emptyList(), false)

        val res = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders)
        val doc = res.document

        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href")) ?: return null
        val title = selectFirst(".Title, h3, h2, .title, .name")?.text()?.trim()
            ?: a.attr("title").trim()
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article, .TPostMv, .item, .list-film li").mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw Exception("Invalid URL")
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document

        var episodesNodes = doc.select("ul.list-episode li a")
        if (episodesNodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-see, a[href*='/tap-'], .btn-watch a, a.watch_button")?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = defaultHeaders).document
                episodesNodes = doc.select("ul.list-episode li a")
            }
        }

        val filmId = Regex("[/-]a(\\d+)").find(fixedUrl)?.groupValues?.get(1) ?: ""
        val episodes = episodesNodes.mapNotNull { ep ->
            val id = ep.attr("data-id").trim()
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim().ifEmpty { ep.attr("title") }
            newEpisode("$href@@$filmId@@$id") {
                this.name = name
                this.episode = Regex("\\d+").find(name ?: "")?.value?.toIntOrNull()
            }
        }

        val title = doc.selectFirst("h1.Title, .Title, h1")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img, .InfoImg img, img[itemprop=image]")?.let {
            fixUrl(it.attr("data-src").ifEmpty { it.attr("src") })
        }

        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".Description, .InfoDesc, #film-content")?.text()?.trim()
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
        if (parts.size < 3) return false
        val (epUrl, filmId, episodeId) = parts

        // Khởi tạo session (lấy cookie)
        val pageReq = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
        var cookies = pageReq.cookies

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer"          to epUrl,
            "Origin"           to mainUrl,
            "User-Agent"       to ua,
            "Accept"           to "application/json, text/javascript, */*; q=0.01"
        )

        // Bước 1: lấy server/html chứa data-href hoặc hash
        val step1Req = app.post("$mainUrl/ajax/player", data = mapOf("episodeId" to episodeId, "backup" to "1"),
            headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
        cookies = cookies + step1Req.cookies

        // Cố gắng parse JSON html field hoặc fallback parse text
        val step1Parsed = runCatching { step1Req.parsedSafe<ServerSelectionResp>() }.getOrNull()
        val serverHtml = step1Parsed?.html ?: step1Req.text().takeIf { it.isNotBlank() } ?: ""
        val serverDoc = Jsoup.parse(serverHtml)

        // Cố gắng tìm nút bằng nhiều selector
        val btn = serverDoc.selectFirst("a.btn3dsv[data-play=api], a.btn3dsv[data-href], a.btn3dsv, a[data-play], a[data-href]")

        if (btn == null) {
            // Fallback: tìm trực tiếp url trong html bằng regex
            val hrefMatch = Regex("data-href=[\"']([^\"']+)[\"']").find(serverHtml)?.groupValues?.get(1)
                ?: Regex("href=[\"']([^\"']+)[\"']").find(serverHtml)?.groupValues?.get(1)
            if (hrefMatch == null) {
                return false
            } else {
                // nếu hrefMatch là link trực tiếp (http...), load extractor
                val candidate = hrefMatch
                if (candidate.startsWith("http")) {
                    loadExtractor(candidate, epUrl, subtitleCallback, callback)
                    return true
                }
            }
        }

        val hash = btn?.attr("data-href") ?: ""
        val play = btn?.attr("data-play") ?: ""
        val btnId = btn?.attr("data-id") ?: ""

        // Kích hoạt session (get_episode)
        val activeReq = app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId",
            headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
        cookies = cookies + activeReq.cookies

        // Thử nhiều id và nhiều param fallback
        val idsToTry = listOf(filmId, episodeId)
        var finalDecrypted: String? = null
        var finalDirect: String? = null

        for (id in idsToTry) {
            val params = if (play == "api" || hash.isNotBlank()) {
                mapOf("link" to hash, "id" to id)
            } else {
                mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
            }

            val step2Req = app.post("$mainUrl/ajax/player", data = params, headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
            cookies = cookies + step2Req.cookies

            // Nhiều dạng trả về: có thể là PlayerResp, hoặc JSON khác, hoặc plain text containing link
            val parsed = runCatching { step2Req.parsedSafe<PlayerResp>() }.getOrNull()
            // 1) nếu linkString là URL trực tiếp
            val directCandidate = parsed?.linkString ?: run {
                // fallback: parse text body for http or m3u8
                Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").find(step2Req.text())?.groupValues?.get(1)
                    ?: Regex("(https?://[^\"'\\s<>]+)").find(step2Req.text())?.groupValues?.get(1)
            }

            if (!directCandidate.isNullOrBlank()) {
                if (directCandidate.startsWith("http")) {
                    finalDirect = directCandidate
                    break
                }
            }

            // 2) nếu có array link -> file có thể là encrypt string
            val enc = parsed?.linkArray?.firstOrNull()?.file ?: run {
                // parsed.linkRaw có thể là Map hoặc List<Map>
                val raw = parsed?.linkRaw
                when (raw) {
                    is List<*> -> {
                        (raw.firstOrNull() as? Map<*, *>)?.get("file") as? String
                    }
                    is Map<*, *> -> {
                        (raw["file"] as? String)
                    }
                    is String -> raw
                    else -> null
                }
            }

            if (!enc.isNullOrBlank()) {
                val dec = decryptLink(enc)
                if (!dec.isNullOrBlank()) {
                    finalDecrypted = dec
                    break
                }
            }
        }

        val decrypted = finalDecrypted ?: finalDirect ?: return false

        // Tách redirect / follow để lấy .m3u8 nếu cần thiết
        val videoHeaders = mapOf(
            "User-Agent" to ua,
            "Referer"    to epUrl,
            "Origin"     to mainUrl,
            "Accept"     to "*/*"
        )

        var realUrl = decrypted
        if (realUrl.startsWith("http") && !realUrl.contains(".m3u8")) {
            runCatching {
                var currentUrl = realUrl
                for (i in 1..8) {
                    val res = app.get(currentUrl, headers = videoHeaders, cookies = cookies, interceptor = cfKiller)
                    // nếu hit tới m3u8, dùng nó
                    if (res.url.contains(".m3u8")) {
                        realUrl = res.url
                        break
                    }
                    // nếu body chứa m3u8 link, lấy link
                    val body = res.text()
                    val m3u8InBody = Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)").find(body)?.groupValues?.get(1)
                    if (!m3u8InBody.isNullOrBlank()) {
                        realUrl = m3u8InBody
                        break
                    }
                    if (res.url == currentUrl) break
                    currentUrl = res.url
                }
            }
        }

        if (realUrl.contains(".m3u8")) {
            callback(newExtractorLink(name, name, realUrl) {
                this.headers = videoHeaders
                this.type = ExtractorLinkType.M3U8
            })
            runCatching {
                M3u8Helper.generateM3u8(name, realUrl, epUrl, headers = videoHeaders).forEach {
                    it.headers = videoHeaders
                    callback(it)
                }
            }
        } else if (realUrl.startsWith("http")) {
            callback(newExtractorLink(name, name, realUrl) { this.headers = videoHeaders })
        } else {
            return false
        }

        return true
    }

    private fun decryptLink(aes: String): String? {
        return try {
            if (aes.startsWith("http")) return aes

            // Chuỗi base64 có thể được nén bằng zlib hoặc không. Thử các cách:
            val key = MessageDigest.getInstance("SHA-256").digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val cleaned = aes.replace("\\s".toRegex(), "")
            val decoded = try {
                Base64.decode(cleaned, Base64.DEFAULT)
            } catch (e: Exception) {
                null
            } ?: return null

            if (decoded.size < 16) return null

            // Nếu first 16 bytes là IV (thường đúng), tách iv và ct
            val iv = decoded.copyOfRange(0, 16)
            val ct = decoded.copyOfRange(16, decoded.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = try { cipher.doFinal(ct) } catch (e: Exception) { null } ?: return null

            // Thử giải nén zlib; nếu fail, treat plain là string chứa url
            val asString = try {
                val inflater = Inflater(true).apply { setInput(plain) }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) break
                    out.write(buf, 0, n)
                }
                inflater.end()
                String(out.toByteArray(), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                String(plain, StandardCharsets.UTF_8)
            }

            val cleanedResult = asString.replace("\"", "").trim()
            // Nếu decoded result vẫn là base64 (double-encoded), thử decode lần nữa
            if (cleanedResult.matches(Regex("^[A-Za-z0-9+/=\\s]+$")) && cleanedResult.length > 32) {
                val secondDecoded = try { Base64.decode(cleanedResult.replace("\\s".toRegex(), ""), Base64.DEFAULT) } catch (e: Exception) { null }
                if (secondDecoded != null && secondDecoded.isNotEmpty()) {
                    val s = String(secondDecoded, StandardCharsets.UTF_8)
                    if (s.startsWith("http")) return s.trim()
                }
            }

            cleanedResult
        } catch (e: Exception) {
            null
        }
    }

    data class ServerSelectionResp(@JsonProperty("html") val html: String? = null)

    data class PlayerResp(
        @JsonProperty("link") val linkRaw: Any? = null,
        @JsonProperty("success") val success: Int? = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkFile>?
            get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map {
                LinkFile(it["file"] as? String)
            }
        val linkString: String? get() = linkRaw as? String
    }

    data class LinkFile(@JsonProperty("file") val file: String? = null)
}
