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
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none"
    )

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "$mainUrl/${trimmed.removePrefix("/")}"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.ifBlank { return newHomePageResponse(request.name, emptyList(), false) }
        val url = if (page == 1) data else "${data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fixUrl(url)!!, interceptor = cfKiller, headers = defaultHeaders).document
        val items = doc.select("article.TPostMv, article.TPost, .TPostMv").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst(".Title, h3, h2")?.text()?.trim() ?: return null
        val href  = fixUrl(selectFirst("a")?.attr("href")) ?: return null
        val poster = selectFirst("img")?.let { fixUrl(it.attr("data-src").ifEmpty { it.attr("src") }) }
        return newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)!!
        var doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document
        
        var episodesNodes = doc.select("ul.list-episode li a")
        if (episodesNodes.isEmpty()) {
            val watchUrl = doc.selectFirst("a.btn-see, a[href*='/tap-'], .btn-watch a")?.attr("href")?.let { fixUrl(it) }
            if (watchUrl != null) {
                doc = app.get(watchUrl, interceptor = cfKiller, headers = defaultHeaders).document
                episodesNodes = doc.select("ul.list-episode li a")
            }
        }

        val filmId = Regex("[/-]a(\\d+)").find(fixedUrl)?.groupValues?.get(1) ?: ""
        val episodes = episodesNodes.mapNotNull { ep ->
            val id = ep.attr("data-id").trim()
            val href = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            newEpisode("$href@@$filmId@@$id") {
                this.name = ep.text().trim()
                this.episode = Regex("\\d+").find(this.name ?: "")?.value?.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(doc.selectFirst("h1.Title")?.text() ?: "Anime", fixedUrl, TvType.Anime) {
            this.posterUrl = doc.selectFirst(".Image img")?.let { fixUrl(it.attr("data-src")) }
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (epUrl, filmId, episodeId) = data.split("@@")

        // 1. Khởi tạo phiên làm việc
        val pageReq = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
        val cookies = pageReq.cookies

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer"          to epUrl,
            "Origin"           to mainUrl,
            "User-Agent"       to ua
        )

        // 2. Bước 1: Lấy Hash
        val step1 = app.post("$mainUrl/ajax/player", data = mapOf("episodeId" to episodeId, "backup" to "1"), 
                             headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller).parsedSafe<ServerSelectionResp>() ?: return false

        val serverDoc = Jsoup.parse(step1.html ?: "")
        val btn = serverDoc.selectFirst("a.btn3dsv[data-play=api]") ?: serverDoc.selectFirst("a.btn3dsv") ?: return false
        val hash = btn.attr("data-href")
        val play = btn.attr("data-play")

        // 3. Kích hoạt tập phim (Dựa trên log get_episode của bạn)
        app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId", 
                headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)

        // 4. Bước 2: Lấy Link mã hóa
        val params = if (play == "api") mapOf("link" to hash, "id" to filmId)
                     else mapOf("link" to hash, "play" to play, "id" to btn.attr("data-id"), "backuplinks" to "1")
        
        val step2 = app.post("$mainUrl/ajax/player", data = params, headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)
        val parsed = step2.parsedSafe<PlayerResp>() ?: return false

        val videoHeaders = mapOf("User-Agent" to ua, "Referer" to epUrl, "Origin" to mainUrl)

        if (play == "api") {
            val enc = parsed.linkArray?.firstOrNull()?.file ?: return false
            val dec = decryptLink(enc) ?: return false
            
            // 5. GIẢI QUYẾT REDIRECT (Quan trọng nhất để sửa lỗi 2001/1002)
            // Nếu link là video0.html, video1.html... chúng ta phải lấy link thật sau khi redirect
            val finalUrl = if (dec.startsWith("http") && (dec.contains(".html") || !dec.contains(".m3u8"))) {
                runCatching {
                    app.get(dec, headers = videoHeaders, cookies = cookies, interceptor = cfKiller).url
                }.getOrDefault(dec)
            } else dec

            if (finalUrl.contains(".m3u8")) {
                callback(newExtractorLink(name, name, finalUrl) {
                    this.headers = videoHeaders
                    this.type = ExtractorLinkType.M3U8
                })
                runCatching { M3u8Helper.generateM3u8(name, finalUrl, epUrl, headers = videoHeaders).forEach(callback) }
            } else if (finalUrl.startsWith("http")) {
                callback(newExtractorLink(name, name, finalUrl) { this.headers = videoHeaders })
            }
        } else {
            parsed.linkString?.let { if (it.startsWith("http")) loadExtractor(it, epUrl, subtitleCallback, callback) }
        }
        return true
    }

    private fun decryptLink(aes: String): String? {
        return try {
            val key = MessageDigest.getInstance("SHA-256").digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(aes.replace("\\s".toRegex(), ""), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(decoded.copyOfRange(0, 16)))
            val plain = cipher.doFinal(decoded.copyOfRange(16, decoded.size))
            val inflater = Inflater(true).apply { setInput(plain) }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            String(out.toByteArray(), StandardCharsets.UTF_8).replace("\"", "").trim()
        } catch (e: Exception) { null }
    }

    data class ServerSelectionResp(@JsonProperty("html") val html: String? = null)
    data class PlayerResp(@JsonProperty("link") val linkRaw: Any? = null) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkFile>? get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { LinkFile(it["file"] as? String) }
        val linkString: String? get() = linkRaw as? String
    }
    data class LinkFile(@JsonProperty("file") val file: String? = null)
}
