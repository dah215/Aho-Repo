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
            // Xử lý chuỗi Base64 URL Safe
            val safeEnc = enc.replace("-", "+").replace("_", "/")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(StandardCharsets.UTF_8))
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(safeEnc, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, StandardCharsets.UTF_8).trim()
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
        private const val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    private val cf = CloudflareKiller()
    private val mapper = jacksonObjectMapper()
    private val pageH = mapOf("User-Agent" to ua)

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http")) u else "$mainUrl/${u.removePrefix("/")}"
    }

    // Cập nhật danh mục theo đúng thực tế trang web
    override val mainPage = mainPageOf(
        "$mainUrl/anime-moi/" to "Anime Mới",
        "$mainUrl/anime-bo/" to "Anime Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-hoan-thanh/" to "Hoàn Thành",
        "$mainUrl/anime-sap-chieu/" to "Sắp Chiếu"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(url, interceptor = cf, headers = pageH).document
        
        // Selector chuẩn cho các item phim
        val items = doc.select("div.item article, div.TPostMv").mapNotNull { it.toSR() }
        return newHomePageResponse(req.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = selectFirst("h2, h3, .Title")?.text()?.trim() ?: a.attr("title") ?: ""
        val poster = fix(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/"
        return app.get(url, interceptor = cf, headers = pageH).document
            .select("div.item article").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf, headers = pageH).document
        val title = doc.selectFirst("h1.Title")?.text()?.trim() ?: ""
        val poster = fix(doc.selectFirst(".Image img")?.attr("src"))
        
        // Lấy Film ID từ URL (ví dụ: phim-a1234.html)
        val filmId = Regex("""-a(\d+)\.html""").find(url)?.groupValues?.get(1) ?: ""

        // Tìm link xem phim để lấy danh sách tập
        val watchUrl = fix(doc.selectFirst("a.btn-see")?.attr("href")) ?: url
        val watchDoc = if (watchUrl != url) app.get(watchUrl, interceptor = cf, headers = pageH).document else doc

        val episodes = watchDoc.select("ul.list-episode li a").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim()
            // Truyền cả URL tập và Film ID vào data
            newEpisode("$href?id=$filmId") {
                this.name = "Tập $name"
                this.episode = name.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
            plot = doc.selectFirst(".Description")?.text()?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = data.substringBefore("?id=")
        val filmId = data.substringAfter("?id=", "")

        // Load trang xem phim để lấy danh sách server
        val doc = app.get(epUrl, interceptor = cf, headers = pageH).document
        val servers = doc.select("a.btn3dsv[data-id]")
        
        servers.forEach { sv ->
            val id = sv.attr("data-id")
            val href = sv.attr("data-href") // Chuỗi mã hóa link

            val response = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("id" to id, "link" to href),
                headers = mapOf(
                    "User-Agent" to ua,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to epUrl,
                    "Origin" to mainUrl
                ),
                interceptor = cf
            ).text

            if (!response.isNullOrBlank()) {
                try {
                    val json = mapper.readTree(response)
                    val linkArray = json.get("link")
                    
                    if (linkArray != null && linkArray.isArray) {
                        linkArray.forEach { item ->
                            val encryptedFile = item.get("file").asText()
                            val decrypted = Crypto.decrypt(encryptedFile)
                            
                            if (!decrypted.isNullOrBlank()) {
                                // decrypted: [{"file":"...","type":"hls","label":"Auto"}]
                                val sources = mapper.readTree(decrypted)
                                sources.forEach { src ->
                                    val fileUrl = src.get("file").asText()
                                    val label = src.get("label")?.asText() ?: "Auto"
                                    
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
                    }
                } catch (e: Exception) { }
            }
        }
        return true
    }
}
