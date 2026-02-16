package com.animevietsub

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class AnimeVietSubPlugin : Plugin() {
    override fun load() { registerMainAPI(AnimeVietSub()) }
}

object Crypto {
    private const val AES_KEY = "dm_thang_suc_vat_get_link_an_dbt"
    private const val AES_IV = "dm_thang_suc_vat"

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        return try {
            val safeEnc = enc.replace("-", "+").replace("_", "/")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(StandardCharsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(safeEnc, Base64.DEFAULT)
            String(cipher.doFinal(decoded), StandardCharsets.UTF_8).trim()
        } catch (e: Exception) { null }
    }
}

class AnimeVietSub : MainAPI() {
    override var mainUrl = "https://animevietsub.ee"
    override var name = "AnimeVietSub"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        // Giả lập trình duyệt mạnh nhất
        private const val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    
    private val defaultHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "max-age=0",
        "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http")) u else "$mainUrl/${u.removePrefix("/")}"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/anime-moi/" to "Đang Chiếu",
        "$mainUrl/anime-hoan-thanh/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val res = app.get(url, interceptor = cf, headers = defaultHeaders)
        val doc = res.document
        val items = doc.select("div.item, article.TPostMv").mapNotNull { it.toSR() }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = selectFirst(".title, h3, h2")?.text()?.trim() ?: a.attr("title") ?: ""
        val img = selectFirst("figure.Objf img, img")
        val poster = fix(img?.attr("src")?.ifBlank { img.attr("data-src") })
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/"
        return app.get(url, interceptor = cf, headers = defaultHeaders).document
            .select("div.item").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url, interceptor = cf, headers = defaultHeaders)
        val doc = res.document
        
        val title = doc.selectFirst("h1.Title, meta[property='og:title']")?.let { 
            if (it.tagName() == "meta") it.attr("content") else it.text() 
        }?.trim() ?: ""
        
        val poster = fix(doc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: doc.selectFirst("figure.Objf img")?.attr("src"))

        // BIỆN PHÁP MẠNH: Lấy Film ID trực tiếp từ mã nguồn Script
        val filmId = Regex("""filmID\s*=\s*parseInt\('(\d+)'\)""").find(res.text)?.groupValues?.get(1)
            ?: Regex("""-a(\d+)\.html""").find(url)?.groupValues?.get(1) 
            ?: ""

        // Tìm danh sách tập phim - Quét tất cả các tab server
        val episodes = doc.select("ul.list-episode li a").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim()
            val hash = ep.attr("data-hash")
            
            // Đóng gói dữ liệu để loadLinks sử dụng
            newEpisode("$href|$filmId|$hash") {
                this.name = "Tập $name"
                this.episode = Regex("""\d+""").find(name)?.value?.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
            plot = doc.selectFirst(".Description, meta[name='description']")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3) return false
        
        val epUrl = parts[0]
        val filmId = parts[1]
        val dataHash = parts[2]

        // Giả lập AJAX request với đầy đủ Header bảo mật
        val response = app.post(
            "$mainUrl/ajax/player",
            data = mapOf("id" to filmId, "link" to dataHash),
            headers = mapOf(
                "User-Agent" to ua,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to epUrl,
                "Origin" to mainUrl,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            ),
            interceptor = cf
        ).text

        if (!response.isNullOrBlank()) {
            try {
                val json = mapper.readTree(response)
                val linkArray = json.get("link")
                
                linkArray?.forEach { item ->
                    val encryptedFile = item.get("file").asText()
                    
                    // Giải mã lớp 1 (Chuỗi file trong JSON)
                    val decryptedFileJson = Crypto.decrypt(encryptedFile)
                    
                    if (!decryptedFileJson.isNullOrBlank()) {
                        // Giải mã lớp 2 (Parse JSON nội bộ)
                        val sources = mapper.readTree(decryptedFileJson)
                        sources.forEach { src ->
                            val fileUrl = src.get("file").asText()
                            val label = src.get("label")?.asText() ?: "FHD"
                            
                            callback(newExtractorLink(
                                source = "AnimeVietSub",
                                name = "Server DU - $label",
                                url = fileUrl
                            ) {
                                this.referer = mainUrl
                                this.quality = when {
                                    label.contains("1080") -> 1080
                                    label.contains("720") -> 720
                                    else -> 480
                                }
                                this.type = if (fileUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            })
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        return true
    }
}
