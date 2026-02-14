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
        val fixedUrl = fixUrl(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        
        val doc = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document
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

        // 1. Khởi tạo Session và lấy Cookie (Bắt buộc)
        val pageReq = app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
        val cookies = pageReq.cookies

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer"          to epUrl,
            "Origin"           to mainUrl,
            "User-Agent"       to ua,
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Sec-Fetch-Mode"   to "cors",
            "Sec-Fetch-Site"   to "same-origin"
        )

        // 2. Bước 1: Lấy Hash (player request đầu tiên)
        val step1 = app.post(
            "$mainUrl/ajax/player",
            data = mapOf("episodeId" to episodeId, "backup" to "1"),
            interceptor = cfKiller,
            headers = ajaxHeaders,
            cookies = cookies
        ).parsedSafe<ServerSelectionResp>() ?: return false

        val serverDoc = Jsoup.parse(step1.html ?: "")
        val btn = serverDoc.selectFirst("a.btn3dsv[data-play=api]") ?: serverDoc.selectFirst("a.btn3dsv") ?: return false
        val hash = btn.attr("data-href")
        val play = btn.attr("data-play")
        val btnId = btn.attr("data-id")

        // 3. Kích hoạt tập phim (Dựa trên log get_episode của bạn - Cực kỳ quan trọng)
        app.get("$mainUrl/ajax/get_episode?filmId=$filmId&episodeId=$episodeId", 
                headers = ajaxHeaders, cookies = cookies, interceptor = cfKiller)

        // 4. Bước 2: Lấy Link mã hóa (player request thứ hai)
        val params = if (play == "api") mapOf("link" to hash, "id" to filmId)
                     else mapOf("link" to hash, "play" to play, "id" to btnId, "backuplinks" to "1")
        
        val step2 = app.post("$mainUrl/ajax/player", data = params, interceptor = cfKiller, headers = ajaxHeaders, cookies = cookies)
        val parsed = step2.parsedSafe<PlayerResp>() ?: return false

        val videoHeaders = mapOf(
            "User-Agent" to ua, 
            "Referer"    to epUrl, 
            "Origin"     to mainUrl,
            "Accept"     to "*/*",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        if (play == "api") {
            val enc = parsed.linkArray?.firstOrNull()?.file ?: return false
            val dec = decryptLink(enc) ?: return false
            
            // 5. XỬ LÝ REDIRECT THỦ CÔNG (Sửa lỗi 2001/1002 triệt để)
            // Plugin sẽ tự mình đi qua video0.html, video1.html... để lấy link m3u8 cuối cùng
            var finalUrl = dec
            if (dec.startsWith("http") && !dec.contains(".m3u8")) {
                runCatching {
                    val res = app.get(dec, headers = videoHeaders, cookies = cookies, interceptor = cfKiller)
                    finalUrl = res.url
                }
            }

            if (finalUrl.contains(".m3u8")) {
                // Gửi link cuối cùng cho trình phát
                callback(newExtractorLink(name, name, finalUrl) {
                    this.headers = videoHeaders
                    this.type = ExtractorLinkType.M3U8
                })
                // Thử lấy các chất lượng khác nếu có
                runCatching {
                    M3u8Helper.generateM3u8(name, finalUrl, epUrl, headers = videoHeaders).forEach { 
                        it.headers = videoHeaders
                        callback(it)
                    }
                }
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
            if (aes.startsWith("http")) return aes
            val key = MessageDigest.getInstance("SHA-256").digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(aes.replace("\\s".toRegex(), ""), Base64.DEFAULT)
            if (decoded.size < 17) return null
            
            val iv = decoded.copyOfRange(0, 16)
            val ct = decoded.copyOfRange(16, decoded.size)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = cipher.doFinal(ct)
            
            try {
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
            } catch (e: Exception) {
                String(plain, StandardCharsets.UTF_8).replace("\"", "").trim()
            }
        } catch (e: Exception) { null }
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
