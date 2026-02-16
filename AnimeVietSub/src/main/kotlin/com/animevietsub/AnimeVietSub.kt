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
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

object AVSDecrypt {
    private val KEY = Base64.decode("ZG1fdGhhbmdfc3VjX3ZhdF9nZXRfbGlua19hbl9kYnQ=", Base64.DEFAULT)
    
    fun decrypt(enc: String): String? {
        if (enc.isBlank() || enc.startsWith("http")) return enc
        return try {
            var b64 = enc.trim().replace("-", "+").replace("_", "/")
            while (b64.length % 4 != 0) b64 += "="
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 17) return null
            
            val iv = raw.copyOfRange(0, 16)
            val cipher = raw.copyOfRange(16, raw.size)
            
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(iv))
            val decrypted = c.doFinal(cipher)
            
            val inf = Inflater(true)
            inf.setInput(decrypted)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inf.end()
            out.toString("UTF-8")
        } catch (_: Exception) { null }
    }
}

private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

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
    private val pageH = mapOf("User-Agent" to UA, "Accept-Language" to "vi-VN,vi;q=0.9")
    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to UA, "X-Requested-With" to "XMLHttpRequest",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to ref, "Origin" to mainUrl,
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )
    private val cdnH = mapOf("User-Agent" to UA)

    private fun fix(u: String?) = when {
        u.isNullOrBlank() || u == "#" -> null
        u.startsWith("http") -> u.trim()
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> "$mainUrl$u"
        else -> "$mainUrl/$u"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        return newHomePageResponse(req.name, doc.select("article,.TPostMv,.item,.list-film li").mapNotNull { it.toSR() })
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = selectFirst(".Title,h3,h2")?.text()?.trim()?.ifBlank { a.attr("title").trim() } ?: return null
        val img = selectFirst("img")
        return newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = fix(img?.attr("data-src") ?: img?.attr("src")) }
    }

    override suspend fun search(q: String) = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/", interceptor = cf, headers = pageH).document
        .select("article,.TPostMv,.item").mapNotNull { it.toSR() }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        val doc = app.get(fUrl, interceptor = cf, headers = pageH).document
        val filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        val eps = doc.select("ul.list-episode li a,.list-eps a,.episodes a").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").ifBlank { ep.attr("data-episodeid") }
            newEpisode("$href@@$filmId@@$epId") { name = ep.text().trim() }
        }
        return newAnimeLoadResponse(doc.selectFirst("h1.Title,h1")?.text() ?: "Anime", fUrl, TvType.Anime) {
            posterUrl = doc.selectFirst(".Image img")?.let { fix(it.attr("data-src").ifBlank { it.attr("src") }) }
            plot = doc.selectFirst(".Description")?.text()
            episodes = mutableMapOf(DubStatus.Subbed to eps)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts.getOrNull(1) ?: ""
        val epId = parts.getOrNull(2) ?: ""
        
        val res = app.get(epUrl, interceptor = cf, headers = pageH)
        val body = res.text ?: return false
        val cookies = res.cookies.toMutableMap()
        
        val ids = mutableSetOf<String>()
        if (epId.isNotBlank()) ids.add(epId)
        Jsoup.parse(body).select("[data-id]").forEach { it.attr("data-id").filter { c -> c.isDigit() }.takeIf { it.isNotEmpty() }?.let { ids.add(it) } }
        
        val aH = ajaxH(epUrl)
        for (id in ids.take(5)) {
            try {
                val r1 = app.post("$mainUrl/ajax/player", data = mapOf("episodeId" to id, "backup" to "1"), headers = aH, cookies = cookies, interceptor = cf)
                cookies.putAll(r1.cookies)
                val html = (try { (mapper.readValue(r1.text ?: continue, Map::class.java) as Map<*, *>)["html"]?.toString() } catch (_: Exception) { null }) ?: r1.text
                
                for (btn in Jsoup.parse(html).select("a[data-href]")) {
                    val hash = btn.attr("data-href").trim()
                    if (hash.isBlank()) continue
                    if (hash.startsWith("http") && emit(hash, epUrl, callback)) return true
                    
                    val params = mapOf("link" to hash, "id" to (filmId.ifBlank { id }))
                    val r2 = app.post("$mainUrl/ajax/player", data = params, headers = aH, cookies = cookies, interceptor = cf)
                    val json = try { mapper.readValue(r2.text ?: continue, Map::class.java) as Map<*, *> } catch (_: Exception) { continue }
                    
                    for (item in (json["link"] as? List<*> ?: continue).filterIsInstance<Map<*, *>>()) {
                        val enc = item["file"]?.toString() ?: continue
                        val dec = AVSDecrypt.decrypt(enc) ?: continue
                        if (emit(dec, epUrl, callback)) return true
                    }
                }
            } catch (_: Exception) {}
        }
        
        for (ifr in Jsoup.parse(body).select("iframe[src],iframe[data-src]")) {
            val src = fix(ifr.attr("src").ifBlank { ifr.attr("data-src") }) ?: continue
            try { loadExtractor(src, epUrl, subtitleCallback, callback); return true } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun emit(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false
        
        if (url.contains("googleusercontent.com") || url.contains("drive.google") || url.contains("docs.google")) {
            return try { loadExtractor(url, ref, { }, cb); true } catch (_: Exception) { false }
        }
        
        if (url.contains(".m3u8") || url.contains("/hls/")) {
            cb(newExtractorLink(name, name, url) { referer = ref; type = ExtractorLinkType.M3U8; headers = cdnH })
            return true
        }
        
        if (url.contains(".mp4")) {
            cb(newExtractorLink(name, name, url) { referer = ref; type = ExtractorLinkType.VIDEO; headers = cdnH })
            return true
        }
        
        if (url.contains("googleapiscdn.com")) {
            val hex = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
            val m3u8 = if (hex != null) "https://storage.googleapiscdn.com/chunks/$hex/original/index.m3u8" else url
            cb(newExtractorLink(name, name, m3u8) { referer = ref; type = ExtractorLinkType.M3U8; headers = cdnH })
            return true
        }
        
        return try { loadExtractor(url, ref, { }, cb); true } catch (_: Exception) { false }
    }
}
