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

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank() || u == "#") return null
        return when {
            u.startsWith("http") -> u.trim()
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$mainUrl$u"
            else -> "$mainUrl/$u"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/" to "Anime Mới",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(fix(url) ?: mainUrl, interceptor = cf, headers = pageH).document
        val items = doc.select("article,.TPostMv,.item,.list-film li,.TPost").mapNotNull { it.toSR() }
        return newHomePageResponse(req.name, items)
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val title = selectFirst(".Title,h3,h2,.title")?.text()?.trim()
            ?.ifBlank { a.attr("title").trim() } ?: return null
        val img = selectFirst("img")
        return newAnimeSearchResponse(title, url, TvType.Anime) { 
            posterUrl = fix(img?.attr("data-src") ?: img?.attr("src"))
        }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/", 
            interceptor = cf, headers = pageH).document
        return doc.select("article,.TPostMv,.item,.list-film li").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fUrl = fix(url) ?: throw ErrorLoadingException("Invalid URL")
        var doc = app.get(fUrl, interceptor = cf, headers = pageH).document
        
        var filmId = Regex("""[/-]a(\d+)""").find(fUrl)?.groupValues?.get(1) ?: ""
        
        val epSelector = ".btn-episode,.episode-link,a[data-hash],ul.list-episode li a,.list-eps a,.episodes a"
        var epNodes = doc.select(epSelector)
        
        if (epNodes.isEmpty()) {
            val watchLink = doc.selectFirst("a.btn-see,a[href*='/xem/'],.btn-watch a")
                ?.attr("href")?.let { fix(it) }
            if (watchLink != null) {
                doc = app.get(watchLink, interceptor = cf, headers = pageH).document
                epNodes = doc.select(epSelector)
            }
        }
        
        if (filmId.isBlank()) {
            filmId = doc.selectFirst("[data-filmid]")?.attr("data-filmid") 
                ?: doc.selectFirst("[data-movie]")?.attr("data-movie")?.filter { it.isDigit() }
                ?: ""
        }
        
        val eps = epNodes.mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val epId = ep.attr("data-id").ifBlank { ep.attr("data-episodeid") }
            val name = ep.text().trim().ifBlank { ep.attr("title") }
            val hash = ep.attr("data-hash")
            newEpisode("$href@@$filmId@@$epId@@$hash") { 
                this.name = name 
                episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            }
        }
        
        val title = doc.selectFirst("h1.Title,h1,.Title")?.text()?.trim() ?: "Anime"
        val poster = doc.selectFirst(".Image img,.InfoImg img,img[itemprop=image]")?.let {
            fix(it.attr("data-src").ifBlank { it.attr("src") })
        }
        val plot = doc.selectFirst(".Description,.InfoDesc,#film-content")?.text()?.trim()
        
        return newAnimeLoadResponse(title, fUrl, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            episodes = mutableMapOf(DubStatus.Subbed to eps)
        }
    }

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        val epUrl = parts[0]
        val filmId = parts.getOrNull(1) ?: ""
        val epId = parts.getOrNull(2) ?: ""
        val savedHash = parts.getOrNull(3) ?: ""
        
        val res = app.get(epUrl, interceptor = cf, headers = pageH)
        val body = res.text ?: return false
        val cookies = res.cookies.toMutableMap()
        val aH = ajaxH(epUrl)
        
        // Nếu đã có hash, dùng luôn
        if (savedHash.isNotBlank() && savedHash != "null") {
            val result = tryDecrypt(savedHash, filmId.ifBlank { epId }, aH, cookies, epUrl, callback)
            if (result) return true
        }
        
        val ids = mutableSetOf<String>()
        if (epId.isNotBlank() && epId.any { it.isDigit() }) {
            ids.add(epId.filter { it.isDigit() })
        }
        
        val doc = Jsoup.parse(body)
        doc.select("[data-id],[data-episodeid],.btn-episode,.episode-link").forEach {
            val id = it.attr("data-id").filter { c -> c.isDigit() }
            if (id.isNotEmpty()) ids.add(id)
        }
        
        // Tìm hash từ page
        doc.select("a[data-hash],.btn-episode,.episode-link").forEach { btn ->
            val hash = btn.attr("data-hash").trim()
            if (hash.isNotBlank() && hash != "null") {
                val result = tryDecrypt(hash, filmId.ifBlank { epId }, aH, cookies, epUrl, callback)
                if (result) return true
            }
        }
        
        // Thử với episodeId
        for (id in ids.take(5)) {
            val result = tryWithEpisodeId(id, filmId, aH, cookies, epUrl, callback)
            if (result) return true
        }
        
        // Fallback iframe
        for (ifr in doc.select("iframe[src],iframe[data-src]")) {
            val src = fix(ifr.attr("src").ifBlank { ifr.attr("data-src") }) ?: continue
            try { 
                loadExtractor(src, epUrl, subtitleCallback, callback)
                return true 
            } catch (_: Exception) {}
        }
        
        return false
    }
    
    private suspend fun tryDecrypt(
        hash: String, 
        id: String, 
        headers: Map<String, String>, 
        cookies: MutableMap<String, String>,
        ref: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val r = app.post("$mainUrl/ajax/player", 
                data = mapOf("link" to hash, "id" to id), 
                headers = headers, 
                cookies = cookies, 
                interceptor = cf)
            
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(r.text, Map::class.java) as Map<String, Any?>
            val links = json["link"] as? List<Map<String, Any?>> ?: return false
            
            for (item in links) {
                val enc = item["file"]?.toString() ?: continue
                val dec = AVSDecrypt.decrypt(enc) ?: continue
                if (emitStream(dec, ref, callback)) return true
            }
            false
        } catch (_: Exception) { 
            false 
        }
    }
    
    private suspend fun tryWithEpisodeId(
        epId: String,
        filmId: String,
        headers: Map<String, String>,
        cookies: MutableMap<String, String>,
        ref: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val r1 = app.post("$mainUrl/ajax/player", 
                data = mapOf("episodeId" to epId, "backup" to "1"), 
                headers = headers, 
                cookies = cookies, 
                interceptor = cf)
            
            val html = try {
                @Suppress("UNCHECKED_CAST")
                val json = mapper.readValue(r1.text, Map::class.java) as Map<String, Any?>
                json["html"]?.toString()
            } catch (_: Exception) { 
                r1.text 
            } ?: return false
            
            for (btn in Jsoup.parse(html).select("a[data-href],a[data-hash],.btn3dsv")) {
                val hash = btn.attr("data-hash").ifBlank { btn.attr("data-href") }.trim()
                if (hash.isBlank() || hash == "#") continue
                if (hash.startsWith("http") && emitStream(hash, ref, callback)) return true
                
                val result = tryDecrypt(hash, filmId.ifBlank { epId }, headers, cookies, ref, callback)
                if (result) return true
            }
            false
        } catch (_: Exception) { 
            false 
        }
    }

    private suspend fun emitStream(url: String, ref: String, cb: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http")) return false
        
        // Google URLs
        if (url.contains("googleusercontent.com") || url.contains("drive.google") || url.contains("docs.google")) {
            return try { 
                loadExtractor(url, ref, { }, cb)
                true 
            } catch (_: Exception) { 
                false 
            }
        }
        
        // M3U8
        if (url.contains(".m3u8") || url.contains("/hls/")) {
            cb(newExtractorLink(name, name, url) { 
                referer = ref
                type = ExtractorLinkType.M3U8
                headers = cdnH
            })
            return true
        }
        
        // MP4
        if (url.contains(".mp4")) {
            cb(newExtractorLink(name, name, url) { 
                referer = ref
                type = ExtractorLinkType.VIDEO
                headers = cdnH
            })
            return true
        }
        
        // CDN
        if (url.contains("googleapiscdn.com")) {
            val hex = Regex("""/chunks/([0-9a-f]{24})/""").find(url)?.groupValues?.get(1)
            val m3u8 = if (hex != null) "https://storage.googleapiscdn.com/chunks/$hex/original/index.m3u8" else url
            cb(newExtractorLink(name, name, m3u8) { 
                referer = ref
                type = ExtractorLinkType.M3U8
                headers = cdnH
            })
            return true
        }
        
        // Try loadExtractor for other URLs
        return try { 
            loadExtractor(url, ref, { }, cb)
            true 
        } catch (_: Exception) { 
            false 
        }
    }
}
