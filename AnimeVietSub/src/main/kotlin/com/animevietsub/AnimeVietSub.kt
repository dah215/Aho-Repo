package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent"      to ua,
        "Referer"         to "$mainUrl/",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    // Hàm sửa URL cực kỳ an toàn
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("javascript") || url == "#") return null
        return try {
            if (url.startsWith("http")) url 
            else if (url.startsWith("//")) "https:$url"
            else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
        } catch (e: Exception) { null }
    }

    // ── Search / MainPage ─────────────────────────────────────────────────────
    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = fixUrl(selectFirst("a")?.attr("href")) ?: return null // Bỏ qua nếu URL lỗi
        
        val poster = selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
            fixUrl(src)
        }
        val epText = selectFirst(".mli-eps i, .mli-eps, .Epnum, .ep-count")?.text()?.trim() ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
            Regex("\\d+").find(epText)?.value?.toIntOrNull()?.let { addSub(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.ifBlank { return newHomePageResponse(request.name, emptyList(), false) }
        val url = if (page == 1) baseUrl else "${baseUrl.removeSuffix("/")}/trang-$page.html"
        
        val doc = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        val items = doc.select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
            
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(query, "utf-8")}/"
        return app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost")
            .distinctBy { it.selectFirst("a")?.attr("href") ?: it.text() }
            .mapNotNull { it.toSearchResponse() }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url) ?: throw Exception("URL không hợp lệ")
        var document = app.get(fixedUrl, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        if (episodesNodes.isEmpty()) {
            document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { fixUrl(it) }
                ?.let { firstEp ->
                    runCatching {
                        app.get(firstEp, interceptor = cfKiller, headers = defaultHeaders).document
                    }.getOrNull()?.also { doc2 ->
                        episodesNodes = doc2.select("ul.list-episode li a[data-id]")
                        if (episodesNodes.isNotEmpty()) document = doc2
                    }
                }
        }

        val filmId = Regex("[/-]a(\\d+)").find(fixedUrl)?.groupValues?.get(1) ?: ""

        val epList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = fixUrl(ep.attr("href")) ?: return@mapNotNull null
            newEpisode("$epUrl@@$filmId@@$episodeId") {
                name    = epName
                episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, fixedUrl, TvType.Anime) {
            posterUrl = document.selectFirst(".Image img, .InfoImg img")
                ?.let { fixUrl(it.attr("data-src").ifEmpty { it.attr("src") }) }
            plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")?.text()?.trim()
            episodes = if (epList.isNotEmpty()) mutableMapOf(DubStatus.Subbed to epList) else mutableMapOf()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false
        val epUrl     = parts[0]
        val filmId    = parts[1]
        val episodeId = parts[2]

        if (fixUrl(epUrl) == null) return false

        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Origin"           to mainUrl,
            "User-Agent"       to ua
        )

        val step1 = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = mapOf("episodeId" to episodeId, "backup" to "1"),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            ).parsedSafe<ServerSelectionResp>()
        }.getOrNull()

        val serverHtml = step1?.html ?: return false
        val serverDoc  = Jsoup.parse(serverHtml)
        val prefServer = serverDoc.selectFirst("a.btn3dsv[data-play=api]")
            ?: serverDoc.selectFirst("a.btn3dsv")
            ?: return false

        val freshHash  = prefServer.attr("data-href").trim().ifEmpty { return false }
        val serverPlay = prefServer.attr("data-play").trim().ifEmpty { "api" }
        val serverBtnId = prefServer.attr("data-id").trim()

        suspend fun fetchLink(idToUse: String): PlayerResp? {
            val params = when (serverPlay) {
                "api"  -> mapOf("link" to freshHash, "id" to idToUse)
                else   -> mapOf("link" to freshHash, "play" to serverPlay, "id" to serverBtnId, "backuplinks" to "1")
            }
            return runCatching {
                app.post("$mainUrl/ajax/player", data = params, interceptor = cfKiller, referer = epUrl, headers = ajaxHeaders)
                    .parsedSafe<PlayerResp>()
            }.getOrNull()
        }

        var parsed = fetchLink(filmId)
        if (parsed == null || (parsed.fxStatus != 1 && parsed.success != 1)) {
            parsed = fetchLink(episodeId)
        }

        if (parsed == null || (parsed.fxStatus != 1 && parsed.success != 1)) return false

        return when (serverPlay) {
            "api" -> {
                val enc = parsed.linkArray?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                    ?: return false
                val dec = decryptLink(enc) ?: return false
                handleDecrypted(dec, epUrl, callback, subtitleCallback)
            }
            else -> {
                val direct = parsed.linkString?.takeIf { it.startsWith("http") } ?: return false
                loadExtractor(direct, epUrl, subtitleCallback, callback)
                true
            }
        }
    }

    private fun decryptLink(aes: String): String? {
        return try {
            if (aes.startsWith("http")) return aes
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            if (decoded.size < 17) return null
            val iv     = decoded.copyOfRange(0, 16)
            val ct     = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain  = cipher.doFinal(ct)
            String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
                .replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) { null }
    }

    private fun pakoInflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data)
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        } catch (e: Exception) { } finally { inflater.end() }
        return out.toByteArray()
    }

    private suspend fun handleDecrypted(
        dec: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = dec.trim()
        val videoHeaders = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl)

        return when {
            link.startsWith("http") && link.contains(".m3u8") -> {
                val links = M3u8Helper.generateM3u8(name, link, "$mainUrl/")
                if (links.isNotEmpty()) {
                    links.forEach { it.headers = videoHeaders; callback(it) }
                } else {
                    callback(newExtractorLink(name, name, link) {
                        this.headers = videoHeaders
                        this.type = ExtractorLinkType.M3U8
                    })
                }
                true
            }
            link.startsWith("#EXTM3U") -> {
                link.lines().forEach { line ->
                    if (line.startsWith("http")) {
                        callback(newExtractorLink(name, name, line.trim()) {
                            this.headers = videoHeaders
                            this.type = ExtractorLinkType.M3U8
                        })
                    }
                }
                true
            }
            link.startsWith("http") -> { loadExtractor(link, referer, subtitleCallback, callback); true }
            else -> false
        }
    }

    data class ServerSelectionResp(
        @JsonProperty("success") val success: Int?  = null,
        @JsonProperty("html")    val html: String?  = null
    )

    data class PlayerResp(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("link") private val linkRaw: Any?  = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkFile>?
            get() = (linkRaw as? List<*>)
                ?.filterIsInstance<Map<String, Any?>>()
                ?.map { LinkFile(it["file"] as? String) }
        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkFile(val file: String? = null)
}
