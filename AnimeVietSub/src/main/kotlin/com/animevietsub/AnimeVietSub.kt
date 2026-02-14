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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer"    to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/danh-sach/list-dang-chieu/" to "Đang Chiếu",
        "$mainUrl/danh-sach/list-tron-bo/"    to "Trọn Bộ",
        "$mainUrl/anime-le/"                  to "Anime Lẻ",
        "$mainUrl/anime-bo/"                  to "Anime Bộ"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // DECRYPT: AES-256-CBC + pako.inflateRaw
    //
    // ✅ XÁC NHẬN từ network Player1 response:
    //    file = "Q/x+SJ6lo..." → có ký tự + và / → STANDARD base64 (DEFAULT)
    //    KHÔNG phải URL_SAFE (đó là lý do decrypt thất bại tất cả lần trước!)
    // ─────────────────────────────────────────────────────────────────────────
    private fun decryptLink(aes: String): String? {
        return try {
            val cleaned = aes.replace(Regex("\\s"), "")
            val key     = MessageDigest.getInstance("SHA-256")
                            .digest(DECODE_PASSWORD.toByteArray(StandardCharsets.UTF_8))
            // *** FIX QUAN TRỌNG: Base64.DEFAULT, KHÔNG phải URL_SAFE ***
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            val iv      = decoded.copyOfRange(0, 16)
            val ct      = decoded.copyOfRange(16, decoded.size)
            val cipher  = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain   = cipher.doFinal(ct)
            val result  = String(pakoInflateRaw(plain), StandardCharsets.UTF_8)
            result.replace("\\n", "\n").replace("\"", "").trim()
        } catch (e: Exception) {
            e.printStackTrace()
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

    // ── Search response ───────────────────────────────────────────────────────
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

    // ── Load ──────────────────────────────────────────────────────────────────
    // Data format: "epUrl@@filmId@@episodeId"
    // epUrl     = URL trang xem tập (Referer)
    // filmId    = ID phim từ URL (a5820 → 5820), dùng trong POST /ajax/player
    // episodeId = data-id của tập (111047), dùng để lấy server list
    override suspend fun load(url: String): LoadResponse {
        var document      = app.get(url, interceptor = cfKiller, headers = defaultHeaders).document
        var episodesNodes = document.select("ul.list-episode li a[data-id]")

        if (episodesNodes.isEmpty()) {
            val firstEpHref = document.selectFirst("a[href*='/tap-']")
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            if (firstEpHref != null) {
                runCatching {
                    app.get(firstEpHref, interceptor = cfKiller, headers = defaultHeaders).document
                }.getOrNull()?.also { doc2 ->
                    episodesNodes = doc2.select("ul.list-episode li a[data-id]")
                    if (episodesNodes.isNotEmpty()) document = doc2
                }
            }
        }

        // filmId từ URL: ".../a5820/..." → "5820"
        val filmId = Regex("[/-]a(\\d+)").find(url)?.groupValues?.get(1) ?: ""

        val episodesList = episodesNodes.mapNotNull { ep ->
            val episodeId = ep.attr("data-id").trim()
            val epName    = ep.attr("title").ifEmpty { ep.text().trim() }
            val epUrl     = ep.attr("href").let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (episodeId.isEmpty() || epUrl.isEmpty()) return@mapNotNull null

            newEpisode("$epUrl@@$filmId@@$episodeId") {
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

    // ── loadLinks ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("@@")
        if (parts.size < 3) return false

        val epUrl     = parts[0]  // URL trang tập (Referer)
        val filmId    = parts[1]  // Film ID (5820)
        val episodeId = parts[2]  // Episode ID (111047)

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        // Warm CF cookies
        runCatching { app.get(epUrl, interceptor = cfKiller, headers = defaultHeaders) }

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 1: POST /ajax/player với episodeId để lấy server selection HTML
        //
        // ✅ Xác nhận từ Player2: episodeId=111047&backup=1
        //    Response: {"html": "<a data-href='7tEB...' data-play='api'>DU</a>..."}
        // ═══════════════════════════════════════════════════════════════════════
        val step1 = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = mapOf("episodeId" to episodeId, "backup" to "1"),
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            ).parsedSafe<ServerSelectionResponse>()
        }.getOrNull()

        val htmlContent = step1?.html ?: return false
        val serverDoc   = Jsoup.parse(htmlContent)

        // Ưu tiên server api (DU), bỏ qua embed (HDX/ADS có quảng cáo)
        val server = serverDoc.selectFirst("a.btn3dsv[data-play=api]")
            ?: serverDoc.selectFirst("a.btn3dsv")
            ?: return false

        val serverHash = server.attr("data-href").trim()
        val serverPlay = server.attr("data-play").trim()
        val serverId   = server.attr("data-id").trim()   // data-id của SERVER button (không phải episode)

        if (serverHash.isEmpty()) return false

        // ═══════════════════════════════════════════════════════════════════════
        // BƯỚC 2: POST /ajax/player với server hash để lấy encrypted/direct link
        //
        // ✅ Xác nhận từ Player1 (DU/api):
        //    Payload: link=7tEB...&id=5820
        //    Response: {"link":[{"file":"Q/x+SJ6lo..."}], "playTech":"api"}
        //    → file dùng STANDARD BASE64 (có + và /)
        //
        // ✅ Xác nhận từ Player3 (HDX/embed):
        //    Payload: link=PP_g8...&play=embed&id=3&backuplinks=1
        //    Response: {"link":"https://short.icu/...","playTech":"embed"}
        //    → link là URL trực tiếp (có quảng cáo)
        // ═══════════════════════════════════════════════════════════════════════
        val step2Params = when (serverPlay) {
            "api"   -> mapOf("link" to serverHash, "id" to filmId)
            else    -> mapOf("link" to serverHash, "play" to serverPlay, "id" to serverId, "backuplinks" to "1")
        }

        val step2Resp = runCatching {
            app.post(
                "$mainUrl/ajax/player",
                data        = step2Params,
                interceptor = cfKiller,
                referer     = epUrl,
                headers     = ajaxHeaders
            )
        }.getOrNull() ?: return false

        val respText = step2Resp.text.trim()
        if (respText.isBlank() || respText.startsWith("<")) return false

        val parsed = step2Resp.parsedSafe<PlayerResponse>() ?: return false

        return when (parsed.playTech) {
            // ── API server (DU): link là array [{file: "<standard_base64_encrypted>"}]
            "api" -> {
                val encryptedFile = parsed.linkArray
                    ?.firstNotNullOfOrNull { it.file?.takeIf(String::isNotBlank) }
                    ?: return false
                val decrypted = decryptLink(encryptedFile) ?: return false
                handleDecryptedLink(decrypted, epUrl, callback, subtitleCallback)
            }
            // ── Embed server (HDX): link là string URL trực tiếp
            "embed" -> {
                val directUrl = parsed.linkString?.takeIf { it.startsWith("http") }
                    ?: return false
                loadExtractor(directUrl, epUrl, subtitleCallback, callback)
                true
            }
            else -> false
        }
    }

    // ── Handle decrypted m3u8 URL hoặc content ────────────────────────────────
    private suspend fun handleDecryptedLink(
        decrypted: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val link = decrypted.trim()
        return when {
            link.contains(".m3u8") && link.startsWith("http") -> {
                M3u8Helper.generateM3u8(name, link, referer).forEach(callback)
                true
            }
            link.startsWith("#EXTM3U") -> {
                parseM3u8Content(link, referer, callback)
                true
            }
            link.startsWith("http") -> {
                loadExtractor(link, referer, subtitleCallback, callback)
                true
            }
            link.contains("\n") -> {
                link.lines().filter { it.trim().startsWith("http") }.forEach { url ->
                    if (url.contains(".m3u8"))
                        M3u8Helper.generateM3u8(name, url.trim(), referer).forEach(callback)
                    else
                        loadExtractor(url.trim(), referer, subtitleCallback, callback)
                }
                true
            }
            else -> false
        }
    }

    private suspend fun parseM3u8Content(content: String, referer: String, callback: (ExtractorLink) -> Unit) {
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
                        callback.invoke(newExtractorLink(name, name, urlLine) {
                            this.referer = referer
                            this.quality = q
                            this.type    = ExtractorLinkType.M3U8
                        })
                    }
                }
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class ServerSelectionResponse(
        @JsonProperty("success") val success: Int?  = null,
        @JsonProperty("html")    val html: String?  = null
    )

    // PlayerResponse xử lý cả 2 format:
    //   api server:   "link": [{"file": "base64..."}]
    //   embed server: "link": "https://..."
    data class PlayerResponse(
        @JsonProperty("_fxStatus") val fxStatus: Int?    = null,
        @JsonProperty("success")   val success: Int?     = null,
        @JsonProperty("playTech")  val playTech: String? = null,
        // Dùng Any? để xử lý cả Array lẫn String
        @JsonProperty("link") private val linkRaw: Any?  = null
    ) {
        @Suppress("UNCHECKED_CAST")
        val linkArray: List<LinkItem>?
            get() = (linkRaw as? List<*>)?.filterIsInstance<Map<String, Any?>>()
                ?.map { LinkItem(it["file"] as? String) }

        val linkString: String?
            get() = linkRaw as? String
    }

    data class LinkItem(
        val file: String? = null
    )
}
