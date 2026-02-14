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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer"    to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            null
        }
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
        } finally { inflater.end() }
        return out.toByteArray()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title  = selectFirst(".Title, h3")?.text()?.trim() ?: return null
        val href   = selectFirst("a")?.attr("href")            ?: return null
        val poster = selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("data-original").ifEmpty { img.attr("src") } }
        }
        val epText = selectFirst(".mli-eps i, .mli-eps, .Epnum, .ep-count")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = if (poster?.startsWith("//") == true) "https:$poster" else poster
            Regex("\\d+").find(epText)?.value?.toIntOrNull()?.let { addSub(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}trang-$page.html"
        val items = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
            .select("article.TPostMv, article.TPost")
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id], .list-episode a[data-id]")

        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()?.also { doc2 ->
                    episodesNodes = doc2.select("ul.list-episode li a[data-id], .list-episode a[data-id]")
                }
            }
        }

        val episodesList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (episodeId.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            newEpisode(epUrl) {
                name    = epName
                episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
            }
        }

        val title = document.selectFirst("h1.Title, .TPost h1")?.text()?.trim() ?: "Anime"
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = document.selectFirst(".Image img, .InfoImg img")
                ?.let { img -> img.attr("data-src").ifEmpty { img.attr("src") } }
            plot = document.selectFirst(".Description, .InfoDesc, .TPost .Description")
                ?.text()?.trim()
            episodes = if (episodesList.isNotEmpty())
                mutableMapOf(DubStatus.Subbed to episodesList)
            else mutableMapOf()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data

        val pageResponse = runCatching {
            app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders)
        }.getOrNull() ?: return false

        val html = pageResponse.text
        val doc = pageResponse.document

        val filmId = Regex("""filmInfo\.filmID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""")
            .find(html)?.groupValues?.get(1) ?: ""
        
        val episodeId = Regex("""filmInfo\.episodeID\s*=\s*parseInt\s*\(\s*['"](\d+)['"]\s*\)""")
            .find(html)?.groupValues?.get(1) ?: ""

        val jsHash = Regex("""AnimeVsub\s*\(\s*['"]([^'"]+)['"]\s*,\s*filmInfo\.filmID\s*\)""")
            .find(html)?.groupValues?.get(1) ?: ""

        val activeEpHash = doc.selectFirst("a.episode.active, a.episode.playing, a.btn3dsv.active")?.attr("data-hash")?.trim() ?: ""
        val epHash = doc.selectFirst("a[data-id=$episodeId]")?.attr("data-hash")?.trim() ?: ""

        val hashList = mutableListOf<String>()
        if (jsHash.isNotEmpty()) hashList.add(jsHash)
        if (activeEpHash.isNotEmpty()) hashList.add(activeEpHash)
        if (epHash.isNotEmpty()) hashList.add(epHash)

        val allHashes = doc.select("a[data-hash]").mapNotNull { 
            it.attr("data-hash").takeIf { it.isNotEmpty() } 
        }.distinct()
        hashList.addAll(allHashes)

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
            "Origin"           to mainUrl,
            "Referer"          to epUrl
        )

        for (hash in hashList.distinct()) {
            if (callPlayerApi(hash, filmId, epUrl, ajaxHeaders, callback, subtitleCallback)) return true
            if (callPlayerApi(hash, filmId, epUrl, ajaxHeaders, callback, subtitleCallback, backup = true)) return true
            if (callPlayerApiEmbed(hash, epUrl, ajaxHeaders, callback, subtitleCallback)) return true
        }

        if (callAllApi(episodeId, epUrl, ajaxHeaders, callback, subtitleCallback)) return true

        return false
    }

    private suspend fun callPlayerApi(
        hash: String,
        filmId: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        backup: Boolean = false
    ): Boolean {
        val params = if (backup) {
            mapOf("link" to hash, "id" to filmId, "backup" to "1")
        } else {
            mapOf("link" to hash, "id" to filmId)
        }

        val response = runCatching {
            app.post("$mainUrl/ajax/player", data = params, interceptor = cfKiller, referer = referer, headers = headers)
        }.getOrNull() ?: return false

        val respText = response.text.trim()
        if (respText.isEmpty() || respText.contains("Không tải được") || respText.startsWith("<") || !respText.startsWith("{")) {
            return false
        }

        val parsed = runCatching { response.parsedSafe<PlayerResponse>() }.getOrNull() ?: return false

        when (parsed.playTech) {
            "api" -> {
                val encryptedFile = parsed.linkArray?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) } ?: return false
                val decrypted = decryptLink(encryptedFile) ?: return false
                return handleDecryptedLink(decrypted, referer, callback, subtitleCallback, "DU")
            }
            "embed" -> {
                val directUrl = parsed.linkString?.takeIf { it.startsWith("http") } ?: return false
                return handleEmbedLink(directUrl, referer, callback, subtitleCallback, "HDX")
            }
        }
        return false
    }

    private suspend fun callPlayerApiEmbed(
        hash: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val params = mapOf("link" to hash, "play" to "embed", "id" to "3", "backuplinks" to "1")

        val response = runCatching {
            app.post("$mainUrl/ajax/player", data = params, interceptor = cfKiller, referer = referer, headers = headers)
        }.getOrNull() ?: return false

        val respText = response.text.trim()
        if (respText.isEmpty() || respText.contains("Không tải được") || respText.startsWith("<") || !respText.startsWith("{")) {
            return false
        }

        val parsed = runCatching { response.parsedSafe<PlayerResponse>() }.getOrNull() ?: return false
        val directUrl = parsed.linkString?.takeIf { it.startsWith("http") } ?: return false
        return handleEmbedLink(directUrl, referer, callback, subtitleCallback, "HDX")
    }

    private suspend fun callAllApi(
        episodeId: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val response = runCatching {
            app.post("$mainUrl/ajax/all", data = mapOf("EpisodeMess" to "1", "EpisodeID" to episodeId), interceptor = cfKiller, referer = referer, headers = headers)
        }.getOrNull() ?: return false

        val respText = response.text.trim()
        if (respText.isEmpty() || respText.contains("Không tải được")) return false

        val doc = Jsoup.parse(respText)
        val links = doc.select("a[data-href], a[data-link], source[src], video[src]")
        
        for (link in links) {
            val videoUrl = link.attr("data-href").ifEmpty { link.attr("data-link") }.ifEmpty { link.attr("src") }.ifEmpty { link.attr("href") }
            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http") && videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, videoUrl, referer).forEach(callback)
                return true
            }
        }
        return false
    }

    private suspend fun handleDecryptedLink(
        decrypted: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        serverName: String = "DU"
    ): Boolean {
        val link = decrypted.trim()
        when {
            link.contains(".m3u8") && link.startsWith("http") -> {
                M3u8Helper.generateM3u8("$name - $serverName", link, referer).forEach(callback)
                return true
            }
            link.startsWith("#EXTM3U") -> {
                parseM3u8Content(link, referer, callback, serverName)
                return true
            }
            link.startsWith("http") -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                return true
            }
            link.contains("\n") -> {
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    if (url.contains(".m3u8")) {
                        M3u8Helper.generateM3u8("$name - $serverName", url.trim(), referer).forEach(callback)
                    } else {
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                    }
                }
                return true
            }
        }
        return false
    }

    private fun parseM3u8Content(content: String, referer: String, callback: (ExtractorLink) -> Unit, serverName: String = "DU") {
        val lines = content.lines()
        if (content.contains("#EXT-X-STREAM-INF")) {
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val q  = when {
                        bw == null      -> Qualities.Unknown.value
                        bw >= 4_000_000 -> Qualities.P1080.value
                        bw >= 2_000_000 -> Qualities.P720.value
                        bw >= 1_000_000 -> Qualities.P480.value
                        else            -> Qualities.P360.value
                    }
                    val urlLine = lines.getOrNull(i + 1)?.trim() ?: return@forEachIndexed
                    if (urlLine.startsWith("http")) {
                        M3u8Helper.generateM3u8("$name - $serverName", urlLine, referer, q).forEach(callback)
                    }
                }
            }
        }
    }

    private suspend fun handleEmbedLink(
        shortUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        serverName: String = "HDX"
    ): Boolean {
        return try {
            var currentUrl = shortUrl
            var redirectCount = 0
            val maxRedirects = 10
            
            while (redirectCount < maxRedirects) {
                val response = app.get(
                    currentUrl,
                    allowRedirects = false,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
                
                val location = response.headers["location"] ?: response.headers["Location"]
                
                if (!location.isNullOrEmpty()) {
                    currentUrl = location
                    
                    if (location.contains("storage.googleapis.com") || location.contains(".m3u8") || location.contains(".mp4") || location.contains("googlevideo.com")) {
                        if (location.contains(".m3u8")) {
                            M3u8Helper.generateM3u8("$name - $serverName", location, "https://abysscdn.com/").forEach(callback)
                        } else {
                            // Use loadExtractor for mp4 links
                            loadExtractor(location, "https://abysscdn.com/", subtitleCallback, callback)
                        }
                        return true
                    }
                    redirectCount++
                } else {
                    val doc = response.document
                    val videoUrl = extractVideoFromPage(doc, currentUrl)
                    if (!videoUrl.isNullOrEmpty()) {
                        if (videoUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8("$name - $serverName", videoUrl, currentUrl).forEach(callback)
                        } else {
                            loadExtractor(videoUrl, currentUrl, subtitleCallback, callback)
                        }
                        return true
                    }
                    break
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractVideoFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        doc.selectFirst("video source")?.attr("src")?.let { return fixUrl(it, baseUrl) }
        doc.selectFirst("video")?.attr("src")?.let { return fixUrl(it, baseUrl) }
        doc.selectFirst("iframe")?.attr("src")?.let { return fixUrl(it, baseUrl) }
        doc.selectFirst("[src*='.m3u8']")?.attr("src")?.let { return fixUrl(it, baseUrl) }
        doc.selectFirst("[src*='.mp4']")?.attr("src")?.let { return fixUrl(it, baseUrl) }
        
        val scripts = doc.select("script")
        for (script in scripts) {
            val content = script.html()
            Regex("""['"]([^'"]*\.m3u8[^'"]*)['"]""").find(content)?.groupValues?.get(1)?.let { return it }
            Regex("""['"](https?://storage\.googleapis\.com[^'"]+)['"]""").find(content)?.groupValues?.get(1)?.let { return it }
            Regex("""['"](https?://[^'"]*googlevideo\.com[^'"]+)['"]""").find(content)?.groupValues?.get(1)?.let { return it }
            Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(content)?.groupValues?.get(1)?.let { 
                if (it.startsWith("http") || it.contains(".m3u8")) return it 
            }
        }
        return null
    }
    
    private fun fixUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = Regex("""https?://[^/]+""").find(baseUrl)?.value ?: ""
                "$base$url"
            }
            else -> url
        }
    }

    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        @JsonProperty("link") private val linkRaw: Any?  = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkItem>?
            get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { LinkItem(it["file"] as? String) }

        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkItem(val file: String? = null)
}
