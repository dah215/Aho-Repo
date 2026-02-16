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
    // Key và IV cố định như bạn đã cung cấp
    private const val AES_KEY = "dm_thang_suc_vat_get_link_an_dbt"
    private const val AES_IV = "dm_thang_suc_vat" // 16 ký tự đầu của Key

    fun decrypt(enc: String?): String? {
        if (enc.isNullOrBlank()) return null
        return try {
            // Fix lỗi Base64 nếu web dùng định dạng URL Safe
            val safeEnc = enc.replace("-", "+").replace("_", "/")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(StandardCharsets.UTF_8))
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(safeEnc, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

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
    private const val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val pageH = mapOf("User-Agent" to ua)
    
    private fun ajaxH(ref: String) = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to ref,
        "Origin" to mainUrl
    )

    private fun fix(u: String?): String? {
        if (u.isNullOrBlank()) return null
        if (u.startsWith("http")) return u
        return "$mainUrl/${u.removePrefix("/")}"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trang Chủ",
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/" to "Trọn Bộ",
        "$mainUrl/anime-le/" to "Anime Lẻ",
        "$mainUrl/anime-bo/" to "Anime Bộ"
    )

    override suspend fun getMainPage(page: Int, req: MainPageRequest): HomePageResponse {
        val url = if (page == 1) req.data else "${req.data.removeSuffix("/")}/trang-$page.html"
        val doc = app.get(url, interceptor = cf, headers = pageH).document
        val items = doc.select("article.TPostMv, .list-film li").mapNotNull { it.toSR() }
        return newHomePageResponse(req.name, items)
    }

    private fun Element.toSR(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val url = fix(a.attr("href")) ?: return null
        val ttl = selectFirst("h2, h3, .title")?.text() ?: a.attr("title")
        val poster = fix(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        return newAnimeSearchResponse(ttl, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(q: String): List<SearchResponse> {
        val url = "$mainUrl/tim-kiem/${URLEncoder.encode(q, "utf-8")}/"
        return app.get(url, interceptor = cf, headers = pageH).document
            .select("article.TPostMv").mapNotNull { it.toSR() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, interceptor = cf, headers = pageH).document
        val title = doc.selectFirst("h1.Title")?.text()?.trim() ?: ""
        val poster = fix(doc.selectFirst(".Image img")?.attr("src"))
        
        // Tìm link tập phim (thường là nút "Xem ngay" hoặc danh sách tập)
        val watchUrl = fix(doc.selectFirst("a.btn-see")?.attr("href")) ?: url
        val watchDoc = if (watchUrl != url) app.get(watchUrl, interceptor = cf, headers = pageH).document else doc

        val episodes = watchDoc.select(".list-episode li a").mapNotNull { ep ->
            val href = fix(ep.attr("href")) ?: return@mapNotNull null
            val name = ep.text().trim()
            // Lưu thông tin cần thiết để load link
            newEpisode(href) {
                this.name = "Tập $name"
                this.episode = name.toIntOrNull()
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
            plot = doc.selectFirst(".Description")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Lấy trang xem phim để tìm data-id và data-href
        val doc = app.get(data, interceptor = cf, headers = pageH).document
        
        // Tìm tất cả các server có data-play="api"
        val servers = doc.select("a.btn3dsv[data-play=api], a.btn3dsv[data-id]")
        
        servers.forEach { sv ->
            val id = sv.attr("data-id")
            val href = sv.attr("data-href") // Đây là chuỗi đã mã hóa lần 1

            // 2. Gửi request AJAX để lấy chuỗi mã hóa lần 2 (chứa link file)
            val response = app.post(
                "$mainUrl/ajax/player",
                data = mapOf("id" to id, "link" to href),
                headers = ajaxH(data),
                interceptor = cf
            ).text

            if (!response.isNullOrBlank()) {
                try {
                    val json = mapper.readTree(response)
                    val linkArray = json.get("link")
                    
                    if (linkArray != null && linkArray.isArray) {
                        linkArray.forEach { item ->
                            val encryptedFile = item.get("file").asText()
                            
                            // 3. Giải mã chuỗi "file" bằng Key/IV bạn đã cung cấp
                            val decrypted = Crypto.decrypt(encryptedFile)
                            
                            if (!decrypted.isNullOrBlank()) {
                                // decrypted lúc này là JSON: [{"file":"...","type":"hls","label":"Auto"}]
                                val sources = mapper.readTree(decrypted)
                                sources.forEach { src ->
                                    val fileUrl = src.get("file").asText()
                                    val label = src.get("label")?.asText() ?: "Auto"
                                    
                                    callback(newExtractorLink(
                                        "AnimeVietSub",
                                        "Server DU - $label",
                                        fileUrl,
                                        referer = mainUrl,
                                        quality = getQuality(label),
                                        isM3u8 = fileUrl.contains("m3u8")
                                    ))
                                }
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        return true
    }

    private fun getQuality(label: String): Int {
        return when {
            label.contains("1080") -> 1080
            label.contains("720") -> 720
            label.contains("480") -> 480
            label.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }
}
