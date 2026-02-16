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
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

object AVSCrypto {
    private const val PASSWORD = "dm_thang_suc_vat_get_link_an_dbt"

    fun decrypt(encryptedBase64: String): String? {
        if (encryptedBase64.isBlank()) return null
        if (encryptedBase64.startsWith("http") || encryptedBase64.startsWith("#EXTM3U")) return encryptedBase64

        return try {
            val rawData = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            if (rawData.size < 17) return null

            val iv = rawData.copyOfRange(0, 16)
            val ciphertext = rawData.copyOfRange(16, rawData.size)

            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(PASSWORD.toByteArray(StandardCharsets.UTF_8))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)

            zlibDecompress(decrypted)
        } catch (e: Exception) {
            println("[AVS-Crypto] Decrypt failed: ${e.message}")
            null
        }
    }

    private fun zlibDecompress(data: ByteArray): String? {
        return try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                output.write(buffer, 0, count)
            }
            inflater.end()
            String(output.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            println("[AVS-Crypto] Zlib failed: ${e.message}")
            null
        }
    }

    fun extractHexId(content: String): String? {
        return Regex("""/chunks/([0-9a-f]{24})/""").find(content)?.groupValues?.get(1)
    }
}

private const val CDN = "storage.googleapiscdn.com"

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()

    private val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val pageHeaders = mapOf("User-Agent" to ua, "Accept" to "text/html", "Accept-Language" to "vi-VN")
    
    private fun ajaxHeaders(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref,
        "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*"
    )

    private fun fix(u: String?): String? = when {
        u.isNullOrBlank() || u == "#" -> null
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
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
        val doc = app.get(fix(url)!!, interceptor = cf, headers = pageHeaders).document
        val items = doc.select("article, .TPostMv, .item, .list-film li, .TPost")
            .mapNotNull { it.toSR() }.distinctBy { it.url }
        return newHomePageResponse(req.name, items, items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = (selectFirst(".Title, h2, h3")?.text() ?: a.attr("title")).trim().ifBlank { return null }
        val img = selectFirst("img")
        val poster = fix(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(query, "UTF-8")}/", interceptor = cf, headers = pageHeaders).document
        return doc.select("article, .TPostMv, .item").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageHeaders).document
        
        val sel = "ul.list-episode li a, .list-eps a, .server-list a, .episodes a, #list_episodes a"
        var epNodes = doc.select(sel)
        
        if (epNodes.isEmpty()) {
            doc.selectFirst("a.btn-see, a[href*='/tap-']")?.attr("href")?.let { fix(it) }?.let {
                doc = app.get(it, interceptor = cf, headers = pageHeaders).document
                epNodes = doc.select(sel)
            }
        }

        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        val eps = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = listOf(ep.attr("data-id"), ep.attr("data-episodeid")).firstOrNull { it.matches(Regex("\\d+")) } ?: ""
            val hash = ep.attr("data-hash").ifBlank { "" }
            val epName = ep.text().trim().ifBlank { ep.attr("title") }
            newEpisode("$href@@$filmId@@$epId@@$hash") {
                name = epName
                episode = Regex("\\d+").find(epName.orEmpty())?.value?.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(doc.selectFirst("h1")?.text() ?: "Anime", fUrl, TvType.Anime) {
            posterUrl = doc.selectFirst(".Image img, img[itemprop=image]")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
            plot = doc.selectFirst(".Description, #film-content")?.text()
            this.episodes = mutableMapOf(DubStatus.Subbed to eps)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts[1]
        val epId = parts[2]
        val hash = parts[3]

        println("[AVS] === START ===")
        println("[AVS] epUrl=$epUrl filmId=$filmId epId=$epId hash=${hash.take(30)}")

        val page = try { app.get(epUrl, interceptor = cf, headers = pageHeaders) }
            catch (e: Exception) { println("[AVS] GET page failed: ${e.message}"); return false }
        val cookies = page.cookies.toMutableMap()
        val html = page.text ?: return false

        if (hash.isNotBlank()) {
            println("[AVS] Trying direct hash...")
            if (fetchAndEmit(hash, filmId, epUrl, cookies, callback)) return true
        }

        val doc = Jsoup.parse(html)
        for (el in doc.select("a[data-hash], a[data-href]")) {
            val h = el.attr("data-hash").ifBlank { el.attr("data-href") }.trim()
            if (h.isNotBlank() && h != "#" && !h.startsWith("http")) {
                println("[AVS] Found hash: ${h.take(30)}")
                if (fetchAndEmit(h, filmId, epUrl, cookies, callback)) return true
            }
        }

        if (epId.isNotBlank()) {
            println("[AVS] Trying AJAX with epId=$epId")
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("episodeId" to epId, "backup" to "1"),
                    headers = ajaxHeaders(epUrl), cookies = cookies, interceptor = cf)
                
                val body = r.text ?: return false
                println("[AVS] AJAX response: ${body.take(200)}")

                @Suppress("UNCHECKED_CAST")
                val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                
                (json["link"] as? List<*>)?.let { links ->
                    for (item in links) {
                        (item as? Map<*, *>)?.get("file")?.toString()?.let { file ->
                            println("[AVS] Found file in JSON")
                            if (processEncrypted(file, epUrl, callback)) return true
                        }
                    }
                }

                json["html"]?.toString()?.let { htmlResp ->
                    val btns = Jsoup.parse(htmlResp).select("a[data-href], a.btn3dsv")
                    for (btn in btns) {
                        btn.attr("data-href").trim().takeIf { it.isNotBlank() && it != "#" }?.let { h ->
                            println("[AVS] Button hash: ${h.take(30)}")
                            if (fetchAndEmit(h, filmId, epUrl, cookies, callback)) return true
                        }
                    }
                }
            } catch (e: Exception) { println("[AVS] AJAX error: ${e.message}") }
        }

        println("[AVS] === FAILED ===")
        return false
    }

    private suspend fun fetchAndEmit(
        hash: String, filmId: String, referer: String,
        cookies: MutableMap<String, String>, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ids = mutableListOf<String>().apply { if (filmId.isNotBlank()) add(filmId); add("0") }

        for (id in ids) {
            println("[AVS] POST /ajax/player link=${hash.take(20)}... id=$id")
            try {
                val r = app.post("$mainUrl/ajax/player",
                    data = mapOf("link" to hash, "id" to id),
                    headers = ajaxHeaders(referer), cookies = cookies, interceptor = cf)
                
                println("[AVS] Response: code=${r.code}, len=${r.text?.length ?: 0}")
                
                r.text?.let { body ->
                    @Suppress("UNCHECKED_CAST")
                    val json = mapper.readValue(body, Map::class.java) as Map<String, Any?>
                    
                    (json["link"] as? List<*>)?.let { links ->
                        for (item in links) {
                            (item as? Map<*, *>)?.get("file")?.toString()?.let { file ->
                                println("[AVS] Got encrypted file")
                                if (processEncrypted(file, referer, callback)) return true
                            }
                        }
                    }
                }
            } catch (e: Exception) { println("[AVS] POST error: ${e.message}") }
        }
        return false
    }

    private suspend fun processEncrypted(enc: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (enc.isBlank()) return false
        if (enc.startsWith("http")) return emit(enc, referer, callback)

        println("[AVS] Decrypting...")
        val dec = AVSCrypto.decrypt(enc) ?: run { println("[AVS] Decrypt failed"); return false }
        println("[AVS] Decrypted: ${dec.take(100)}...")

        return emitFromContent(dec, referer, callback)
    }

    private suspend fun emitFromContent(content: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (content.startsWith("http")) return emit(content, referer, callback)

        if (content.contains("#EXTM3U")) {
            val hexId = AVSCrypto.extractHexId(content)
            if (hexId != null) {
                val url = "https://$CDN/chunks/$hexId/original/index.m3u8"
                println("[AVS] CDN URL: $url")
                return emit(url, referer, callback)
            }
            Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(content)?.value?.let {
                return emit(it, referer, callback)
            }
        }

        if (content.startsWith("{") || content.startsWith("[")) {
            try {
                @Suppress("UNCHECKED_CAST")
                val json = mapper.readValue(content, Map::class.java) as Map<String, Any?>
                (json["link"] as? List<*>)?.let { links ->
                    for (item in links) {
                        (item as? Map<*, *>)?.get("file")?.toString()?.let { file ->
                            if (file.startsWith("http")) return emit(file, referer, callback)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return false
    }

    private suspend fun emit(url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false

        val finalUrl = if (url.contains(CDN)) {
            val hexId = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
            if (hexId != null) "https://$CDN/chunks/$hexId/original/index.m3u8" else url
        } else url

        println("[AVS] Emitting: $finalUrl")

        callback(newExtractorLink(name, name, finalUrl) {
            this.referer = referer
            quality = Qualities.Unknown.value
            type = ExtractorLinkType.M3U8
            headers = mapOf("User-Agent" to ua)
        })
        return true
    }
}
